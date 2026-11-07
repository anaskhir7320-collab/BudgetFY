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
import kotlin.math.abs

/**
 * Reads a receipt with Gemini: the image is the source of truth and ML Kit's
 * text is sent along as a hint. Gemini returns strict JSON (fixed schema), so
 * no regex rules or per-store typo lists are needed.
 *
 * Two models are used:
 * - [fastModel] (Flash-Lite) reads every receipt. It's quick, and its free
 *   quota is larger.
 * - [accurateModel] (Flash) re-reads a receipt only when the fast result's
 *   items don't add up to the printed total, or it found no items. That
 *   saves Flash's small free quota (20/day) for receipts that need it.
 *
 * [extract] blocks on the network, so call it from Dispatchers.IO. When
 * reading fails, [Outcome.result] is null and the caller falls back to the
 * on-device ReceiptParser.
 */
class GeminiReceiptExtractor(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val fastModel: String = BuildConfig.GEMINI_MODEL,
    private val accurateModel: String = BuildConfig.GEMINI_ACCURATE_MODEL,
    private val fastThinkingLevel: String = BuildConfig.GEMINI_THINKING_LEVEL,
    private val accurateThinkingLevel: String = BuildConfig.GEMINI_ACCURATE_THINKING_LEVEL
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
        val total: Double?,
        /** Which Gemini model produced this result. */
        val model: String = ""
    ) {
        /** Charges added on top of the item lines; 0 when the receipt is tax-inclusive. */
        val extras: Double
            get() = (tax ?: 0.0) + (serviceCharge ?: 0.0) + (rounding ?: 0.0) - (discount ?: 0.0)

        val itemsSum: Double get() = items.sumOf { it.lineTotal }

        /**
         * false when the items don't add up to the printed total (a missed,
         * doubled or misread line); null when there's no total to compare.
         */
        val addsUp: Boolean?
            get() {
                val printed = total?.takeIf { it > 0.0 } ?: return null
                return abs(itemsSum + extras - printed) <= TOLERANCE ||
                        abs(itemsSum - printed) <= TOLERANCE
            }

        /** How far the items are from the printed total (0 when they add up). */
        val mismatch: Double
            get() {
                val printed = total ?: return 0.0
                return minOf(abs(itemsSum + extras - printed), abs(itemsSum - printed))
            }
    }

    /**
     * [result] is null when reading failed; [error] then says why.
     * [rechecked] is true when the accurate model re-read the receipt.
     */
    data class Outcome(val result: Result?, val error: String?, val rechecked: Boolean = false)

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    fun extract(bitmap: Bitmap, ocrText: String, categories: List<String>): Outcome {
        if (!isConfigured) return Outcome(null, "No GEMINI_API_KEY in local.properties")
        val request = try {
            buildRequest(bitmap, ocrText, categories)
        } catch (e: Exception) {
            return Outcome(null, e.message ?: e.javaClass.simpleName)
        }

        // Step 1: fast read. If the fast model is unavailable (busy or out of
        // quota), the accurate model is the backup.
        val first = readWithFirstAvailable(listOf(fastModel, accurateModel), request)
        val firstResult = first.result ?: return first

        // Step 2: re-read with the accurate model only when the fast result
        // looks wrong, and only if it didn't already come from that model.
        val looksWrong = firstResult.items.isEmpty() || firstResult.addsUp == false
        if (!looksWrong || firstResult.model == accurateModel || accurateModel.isBlank()) return first

        Log.d(
            TAG, "${firstResult.model}: items RM %.2f vs total RM %s, re-checking with $accurateModel"
                .format(firstResult.itemsSum, firstResult.total)
        )
        val second = readWithFirstAvailable(listOf(accurateModel), request)
        val secondResult = second.result
        if (secondResult == null || secondResult.items.isEmpty()) {
            Log.d(TAG, "Re-check unavailable (${second.error}), keeping the fast result")
            return first
        }

        // Keep whichever result is closer to the printed total; Flash wins ties.
        val better = when {
            firstResult.items.isEmpty() -> secondResult
            secondResult.addsUp != false -> secondResult
            secondResult.mismatch <= firstResult.mismatch -> secondResult
            else -> firstResult
        }
        Log.d(TAG, "Re-check: kept ${better.model}")
        return Outcome(better, null, rechecked = true)
    }

    /** Tries each model in order, skipping ones whose quota is known to be used up. */
    private fun readWithFirstAvailable(candidates: List<String>, request: JsonObject): Outcome {
        return try {
            val now = System.currentTimeMillis()
            val models = candidates.filter { it.isNotBlank() }.distinct()
            val usable = models.filter { (blockedUntil[it] ?: 0L) <= now }.ifEmpty { models }

            var lastFailure: HttpError? = null
            for (m in usable) {
                try {
                    val started = System.currentTimeMillis()
                    val result = parseResult(send(m, request)).copy(model = m)
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
     * Sends with the model's thinking level. The fast model uses "minimal"
     * (quickest); the accurate model uses a bit more, since it only runs on
     * receipts that were hard to read. A model that rejects the setting is
     * remembered and sent without it.
     */
    private fun send(model: String, request: JsonObject): String {
        val thinkingLevel = if (model == accurateModel) accurateThinkingLevel else fastThinkingLevel
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

        /** Allows for 5-sen rounding on Malaysian receipts. */
        private const val TOLERANCE = 0.06

        // Shared across screens so a used-up quota is remembered for the app session.
        private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val thinkingUnsupported: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}
