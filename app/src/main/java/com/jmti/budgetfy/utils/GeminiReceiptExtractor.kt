package com.jmti.budgetfy.utils

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jmti.budgetfy.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads a receipt with Gemini: the image is the source of truth and ML Kit's
 * text is sent along as a hint. Gemini returns strict JSON (fixed schema), so
 * no regex rules or per-store typo lists are needed.
 *
 * [extract] blocks on the network, so call it from Dispatchers.IO. It returns
 * null when no API key is configured or the request fails ([lastError] says
 * why), and the caller falls back to the on-device ReceiptParser.
 */
class GeminiReceiptExtractor(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val model: String = BuildConfig.GEMINI_MODEL,
    private val fallbackModel: String = BuildConfig.GEMINI_FALLBACK_MODEL,
    private val thinkingLevel: String = BuildConfig.GEMINI_THINKING_LEVEL
) {

    data class Item(
        val name: String,
        val quantity: Int,
        val unitPrice: Double?,
        /** Amount charged for the whole line (quantity × unit price, after line discount). */
        val lineTotal: Double,
        val category: String?
    )

    data class Result(
        val storeName: String?,
        val date: String?,
        val items: List<Item>,
        val subtotal: Double?,
        val tax: Double?,
        val serviceCharge: Double?,
        val discount: Double?,
        val rounding: Double?,
        val total: Double?
    ) {
        /** Charges added on top of the item lines; 0 when the receipt is tax-inclusive. */
        val extras: Double
            get() = (tax ?: 0.0) + (serviceCharge ?: 0.0) + (rounding ?: 0.0) - (discount ?: 0.0)
    }

    /** [result] is null when reading failed; [error] then says why. */
    data class Outcome(val result: Result?, val error: String?)

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    fun extract(bitmap: Bitmap, ocrText: String, categories: List<String>): Outcome {
        if (!isConfigured) return Outcome(null, "No GEMINI_API_KEY in local.properties")
        return try {
            val request = buildRequest(bitmap, ocrText, categories)

            // Skip a model whose daily/minute quota is known to be used up, so
            // the user doesn't wait for a request that will certainly fail.
            val now = System.currentTimeMillis()
            val models = listOf(model, fallbackModel)
                .filter { it.isNotBlank() }
                .distinct()
            val usable = models.filter { (blockedUntil[it] ?: 0L) <= now }.ifEmpty { models }

            var lastFailure: HttpError? = null
            for (m in usable) {
                try {
                    val started = System.currentTimeMillis()
                    val result = parseResult(send(m, request))
                    Log.d(TAG, "$m answered in ${System.currentTimeMillis() - started} ms")
                    return Outcome(result, null)
                } catch (e: HttpError) {
                    lastFailure = e
                    e.quotaRetryDelayMs?.let { blockedUntil[m] = System.currentTimeMillis() + it }
                    if (!e.isBusy) throw e
                    Log.w(TAG, "$m unavailable (HTTP ${e.code}), trying next model")
                }
            }
            throw lastFailure ?: IllegalStateException("No Gemini model configured")
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "Gemini extraction failed: $message")
            Outcome(null, message)
        }
    }

    /**
     * Sends with the configured thinking level. Receipts don't need deep
     * reasoning, and "minimal" thinking roughly halves the response time.
     * A model that rejects the setting is remembered and sent without it.
     */
    private fun send(model: String, request: JsonObject): String {
        if (thinkingLevel.isNotBlank() && model !in thinkingUnsupported) {
            val withThinking = request.deepCopy().apply {
                getAsJsonObject("generationConfig").add("thinkingConfig", JsonObject().apply {
                    addProperty("thinkingLevel", thinkingLevel)
                })
            }
            try {
                return postWithRetry(model, withThinking)
            } catch (e: HttpError) {
                if (e.code != 400) throw e
                Log.w(TAG, "$model rejected thinkingLevel=$thinkingLevel, sending without it")
                thinkingUnsupported.add(model)
            }
        }
        return postWithRetry(model, request)
    }

    // ── Request ────────────────────────────────────────────────────────────

    private fun buildRequest(bitmap: Bitmap, ocrText: String, categories: List<String>): JsonObject {
        val imagePart = JsonObject().apply {
            add("inline_data", JsonObject().apply {
                addProperty("mime_type", "image/jpeg")
                addProperty("data", encodeJpeg(bitmap))
            })
        }
        val textPart = JsonObject().apply {
            addProperty("text", buildPrompt(ocrText, categories))
        }
        val content = JsonObject().apply {
            addProperty("role", "user")
            add("parts", JsonArray().apply { add(imagePart); add(textPart) })
        }
        val generationConfig = JsonObject().apply {
            addProperty("temperature", 0)
            addProperty("responseMimeType", "application/json")
            add("responseSchema", responseSchema(categories))
        }
        return JsonObject().apply {
            add("contents", JsonArray().apply { add(content) })
            add("generationConfig", generationConfig)
        }
    }

    private fun buildPrompt(ocrText: String, categories: List<String>): String = """
        You are reading a photo of a shop or restaurant receipt, usually from Malaysia (currency RM).
        Extract the purchase exactly as printed.

        Rules:
        - The IMAGE is the source of truth. The OCR text below is only a hint and may contain
          mistakes (0/O, 1/l/I, 5/S, 8/B, missing letters). Fix those using the image.
        - One entry per purchased product line. Do NOT include subtotal, total, tax, service charge,
          rounding, cash tendered, change, payment method, card details, addresses, phone numbers,
          order numbers or thank-you lines.
        - Modifiers with no price of their own (e.g. "Normal Ice", "Less Sugar", "Take Away") are not
          items. If a modifier or add-on has its own price, list it as its own item.
        - lineTotal = the amount charged for that line (quantity x unit price, after any discount shown
          on that line). quantity defaults to 1. Prices are plain numbers without "RM".
        - Keep item names as printed, but fix obvious OCR spelling errors and use normal capitalisation.
        - storeName = the brand or shop name customers know (e.g. "Texas Chicken", "Mixue"), not the
          registered company name with "Sdn Bhd".
        - date = purchase date as YYYY-MM-DD, or null if not visible.
        - tax and serviceCharge: only when they are separate lines ADDED to the bill. If the receipt
          says prices are inclusive of tax/service, use null.
        - discount: a whole-bill discount as a positive number, else null.
        - rounding: the rounding adjustment as printed (can be negative), else null.
        - total = the final amount payable (Grand Total / Total / Jumlah).
        - category: the closest match from: ${categories.joinToString(", ")}.
        - Use null for anything not on the receipt. Never invent items or prices.

        OCR text (rows reconstructed from positions, may contain errors):
        $ocrText
    """.trimIndent()

    private fun responseSchema(categories: List<String>): JsonObject {
        fun prop(type: String, nullable: Boolean = false) = JsonObject().apply {
            addProperty("type", type)
            if (nullable) addProperty("nullable", true)
        }

        val itemSchema = JsonObject().apply {
            addProperty("type", "OBJECT")
            add("properties", JsonObject().apply {
                add("name", prop("STRING"))
                add("quantity", prop("INTEGER"))
                add("unitPrice", prop("NUMBER", nullable = true))
                add("lineTotal", prop("NUMBER"))
                add("category", prop("STRING").apply {
                    add("enum", JsonArray().apply { categories.forEach { add(it) } })
                })
            })
            add("required", stringArray("name", "quantity", "lineTotal", "category"))
        }

        return JsonObject().apply {
            addProperty("type", "OBJECT")
            add("properties", JsonObject().apply {
                add("storeName", prop("STRING", nullable = true))
                add("date", prop("STRING", nullable = true))
                add("items", JsonObject().apply {
                    addProperty("type", "ARRAY")
                    add("items", itemSchema)
                })
                add("subtotal", prop("NUMBER", nullable = true))
                add("tax", prop("NUMBER", nullable = true))
                add("serviceCharge", prop("NUMBER", nullable = true))
                add("discount", prop("NUMBER", nullable = true))
                add("rounding", prop("NUMBER", nullable = true))
                add("total", prop("NUMBER", nullable = true))
            })
            add("required", stringArray("items", "total"))
        }
    }

    private fun stringArray(vararg values: String) = JsonArray().apply { values.forEach { add(it) } }

    private fun encodeJpeg(bitmap: Bitmap): String {
        // Gemini reads receipts well at ~2000px; larger only adds upload time.
        val scaled = ImageLoader.scaleDown(bitmap, MAX_IMAGE_SIDE)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ── HTTP ───────────────────────────────────────────────────────────────

    private class HttpError(val code: Int, message: String) : IllegalStateException(message) {
        val isBusy: Boolean get() = code == 429 || code == 500 || code == 503

        /**
         * For "quota exceeded" (429), how long until the quota resets, parsed
         * from "Please retry in 5m14.49s". Retrying sooner is pointless.
         */
        val quotaRetryDelayMs: Long?
            get() {
                if (code != 429 || message?.contains("quota", ignoreCase = true) != true) return null
                val match = Regex("retry in (?:(\\d+)h)?(?:(\\d+)m)?(?:([\\d.]+)s)?")
                    .find(message.orEmpty())
                val seconds = match?.let {
                    (it.groupValues[1].toLongOrNull() ?: 0L) * 3600 +
                            (it.groupValues[2].toLongOrNull() ?: 0L) * 60 +
                            (it.groupValues[3].toDoubleOrNull() ?: 0.0).toLong()
                } ?: 0L
                return maxOf(seconds, 60L) * 1000
            }
    }

    /**
     * Gemini sometimes answers 503 ("high demand") or 429 (rate limit) for a
     * few seconds; those are worth retrying. Other errors fail immediately.
     */
    private fun postWithRetry(model: String, body: JsonObject): String {
        val delaysMs = listOf(1_000L, 3_000L)
        var attempt = 0
        while (true) {
            try {
                return post(model, body)
            } catch (e: HttpError) {
                if (!e.isBusy || e.quotaRetryDelayMs != null || attempt >= delaysMs.size) throw e
                Log.w(TAG, "Gemini busy (HTTP ${e.code}), retrying in ${delaysMs[attempt]} ms")
                Thread.sleep(delaysMs[attempt])
                attempt++
            }
        }
    }

    private fun post(model: String, body: JsonObject): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    JsonParser.parseString(text).asJsonObject
                        .getAsJsonObject("error").get("message").asString
                }.getOrNull() ?: text.take(200)
                throw HttpError(code, "Gemini HTTP $code: $message")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    // ── Response ───────────────────────────────────────────────────────────

    private fun parseResult(responseText: String): Result {
        val root = JsonParser.parseString(responseText).asJsonObject
        val candidate = root.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
            ?: throw IllegalStateException("Gemini returned no answer")
        val parts = candidate.getAsJsonObject("content")?.getAsJsonArray("parts")
            ?: throw IllegalStateException(
                "Gemini returned no content (${candidate.get("finishReason")?.asString})"
            )
        val json = parts.joinToString("") { it.asJsonObject.get("text")?.asString.orEmpty() }
        Log.d(TAG, "Gemini JSON: $json")

        val obj = JsonParser.parseString(json).asJsonObject
        val items = obj.getAsJsonArray("items")?.mapNotNull { element ->
            val item = element.asJsonObject
            val name = item.string("name")?.trim().orEmpty()
            val lineTotal = item.number("lineTotal") ?: return@mapNotNull null
            if (name.isEmpty() || lineTotal <= 0.0) return@mapNotNull null
            Item(
                name = name,
                quantity = item.number("quantity")?.toInt()?.coerceAtLeast(1) ?: 1,
                unitPrice = item.number("unitPrice"),
                lineTotal = lineTotal,
                category = item.string("category")
            )
        }.orEmpty()

        return Result(
            storeName = obj.string("storeName")?.trim()?.takeIf { it.isNotEmpty() },
            date = obj.string("date"),
            items = items,
            subtotal = obj.number("subtotal"),
            tax = obj.number("tax"),
            serviceCharge = obj.number("serviceCharge"),
            discount = obj.number("discount"),
            rounding = obj.number("rounding"),
            total = obj.number("total")
        )
    }

    private fun JsonObject.field(name: String): JsonElement? =
        get(name)?.takeUnless { it.isJsonNull }

    private fun JsonObject.string(name: String): String? =
        field(name)?.asString

    private fun JsonObject.number(name: String): Double? =
        field(name)?.let { runCatching { it.asDouble }.getOrNull() }

    companion object {
        private const val TAG = "GEMINI"
        private const val MAX_IMAGE_SIDE = 2048

        // Shared across screens so a used-up quota is remembered for the app session.
        private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val thinkingUnsupported: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}
