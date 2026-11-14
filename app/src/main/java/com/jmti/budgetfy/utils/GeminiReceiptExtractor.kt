package com.jmti.budgetfy.utils

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FirebaseAIException
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.RequestTimeoutException
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ServerException
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jmti.budgetfy.BuildConfig
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/**
 * Reads a receipt with Gemini: the image is the source of truth and ML Kit's
 * text is sent along as a hint. Gemini returns strict JSON (fixed schema), so
 * no regex rules or per-store typo lists are needed.
 *
 * Gemini is called through Firebase AI Logic, so no Gemini API key ships in
 * the app: the key is held by Firebase on Google's servers.
 *
 * Two models are used:
 * - [fastModel] (Flash-Lite) reads every receipt. It's quick, and its free
 *   quota is larger.
 * - [accurateModel] (Flash) re-reads a receipt only when the fast result's
 *   items don't add up to the printed total, or it found no items. That
 *   saves Flash's small free quota for receipts that need it.
 *
 * [extract] blocks on the network, so call it from Dispatchers.IO. When
 * reading fails, [Outcome.result] is null and the caller falls back to the
 * on-device ReceiptParser.
 */
class GeminiReceiptExtractor(
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

    data class Charge(val type: String, val rate: Double?, val amount: Double, val inclusive: Boolean)

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
        /** Every tax/charge line printed, inclusive or added on top. */
        val charges: List<Charge> = emptyList(),
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

    val isConfigured: Boolean get() = fastModel.isNotBlank() || accurateModel.isNotBlank()

    fun extract(bitmap: Bitmap, ocrText: String, categories: List<String>): Outcome {
        if (!isConfigured) return Outcome(null, "No Gemini model configured")
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
    private fun readWithFirstAvailable(candidates: List<String>, request: Request): Outcome {
        return try {
            val now = System.currentTimeMillis()
            val models = candidates.filter { it.isNotBlank() }.distinct()
            val usable = models.filter { (blockedUntil[it] ?: 0L) <= now }.ifEmpty { models }

            var lastFailure: CallError? = null
            for (m in usable) {
                try {
                    val started = System.currentTimeMillis()
                    val result = parseResult(send(m, request)).copy(model = m)
                    Log.d(TAG, "$m answered in ${System.currentTimeMillis() - started} ms")
                    return Outcome(result, null)
                } catch (e: CallError) {
                    lastFailure = e
                    e.quotaRetryDelayMs?.let { blockedUntil[m] = System.currentTimeMillis() + it }
                    if (!e.isBusy) throw e
                    Log.w(TAG, "$m unavailable (${e.message}), trying next model")
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
     * Sends with the model's thinking setting. The fast model thinks as little
     * as possible (quickest); the accurate model a bit more, since it only runs
     * on receipts that were hard to read. A model that rejects the setting is
     * remembered and sent without it.
     */
    private fun send(model: String, request: Request): String {
        val name = if (model == accurateModel) accurateThinkingLevel else fastThinkingLevel
        val level = thinkingLevelFor(name)
        if (level != null && model !in thinkingUnsupported) {
            try {
                return generateWithRetry(model, request, level)
            } catch (e: CallError) {
                if (!e.isBadRequest) throw e
                Log.w(TAG, "$model rejected thinkingLevel=$name, sending without it")
                thinkingUnsupported.add(model)
            }
        }
        return generateWithRetry(model, request, null)
    }

    private fun thinkingLevelFor(name: String): ThinkingLevel? = when (name.trim().lowercase()) {
        "minimal" -> ThinkingLevel.MINIMAL
        "low" -> ThinkingLevel.LOW
        "medium" -> ThinkingLevel.MEDIUM
        "high" -> ThinkingLevel.HIGH
        else -> null
    }

    // ── Request ────────────────────────────────────────────────────────────

    private class Request(val content: Content, val schema: Schema)

    private fun buildRequest(bitmap: Bitmap, ocrText: String, categories: List<String>): Request {
        // Smaller upload = faster and steadier on mobile data. Capped by total
        // pixels (not the long side), so tall narrow receipts keep their width
        // and small print stays readable.
        val scaled = scaleToPixels(bitmap, MAX_IMAGE_PIXELS)
        Log.d(TAG, "Sending ${scaled.width}x${scaled.height} (from ${bitmap.width}x${bitmap.height})")
        val prompt = buildPrompt(ocrText, categories)
        return Request(
            content = content {
                image(scaled)
                text(prompt)
            },
            schema = responseSchema(categories)
        )
    }

    /** Shrinks [src] so width x height <= [maxPixels]; never enlarges. */
    private fun scaleToPixels(src: Bitmap, maxPixels: Int): Bitmap {
        val pixels = src.width.toLong() * src.height
        if (pixels <= maxPixels) return src
        val ratio = kotlin.math.sqrt(maxPixels.toDouble() / pixels)
        return Bitmap.createScaledBitmap(
            src, (src.width * ratio).toInt().coerceAtLeast(1), (src.height * ratio).toInt().coerceAtLeast(1), true
        )
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
        - charges: every tax or charge line printed on the receipt, whether it is ADDED to the bill
          or already INCLUDED in the prices (e.g. "SST (Inclu 6%) 2.30" is included). For each:
          type = SST, Sales Tax, Service Tax, GST, Service Charge, Tourism Tax or Other
          ("SST" when the receipt just says SST; "Service Tax" when it says Service Tax);
          rate = the percentage as a number (6 for 6%) or null; amount = the amount printed;
          inclusive = true if it is already inside the item prices, false if added on top.
          Use an empty list when the receipt shows no tax or charge. Never invent one.
        - discount: a whole-bill discount as a positive number, else null.
        - rounding: the rounding adjustment as printed (can be negative), else null.
        - total = the final amount payable (Grand Total / Total / Jumlah).
        - category: the closest match from: ${categories.joinToString(", ")}.
        - Use null for anything not on the receipt. Never invent items or prices.

        OCR text (rows reconstructed from positions, may contain errors):
        $ocrText
    """.trimIndent()

    private fun responseSchema(categories: List<String>): Schema {
        val itemSchema = Schema.obj(
            mapOf(
                "name" to Schema.string(),
                "quantity" to Schema.integer(),
                "unitPrice" to Schema.double(null, true),
                "lineTotal" to Schema.double(),
                "category" to Schema.enumeration(categories)
            ),
            listOf("unitPrice")
        )
        val chargeSchema = Schema.obj(
            mapOf(
                "type" to Schema.enumeration(CHARGE_TYPES),
                "rate" to Schema.double(null, true),
                "amount" to Schema.double(),
                "inclusive" to Schema.boolean()
            ),
            listOf("rate")
        )
        return Schema.obj(
            mapOf(
                "storeName" to Schema.string(null, true),
                "date" to Schema.string(null, true),
                "items" to Schema.array(itemSchema),
                "subtotal" to Schema.double(null, true),
                "tax" to Schema.double(null, true),
                "serviceCharge" to Schema.double(null, true),
                "discount" to Schema.double(null, true),
                "rounding" to Schema.double(null, true),
                "total" to Schema.double(null, true),
                "charges" to Schema.array(chargeSchema)
            ),
            listOf(
                "storeName", "date", "subtotal", "tax", "serviceCharge", "discount", "rounding"
            )
        )
    }

    // ── Firebase AI call ───────────────────────────────────────────────────

    private class CallError(
        message: String,
        val isBusy: Boolean,
        /** For "quota exceeded", how long until the quota resets. Retrying sooner is pointless. */
        val quotaRetryDelayMs: Long?,
        /** The request itself was rejected (e.g. an unsupported setting). */
        val isBadRequest: Boolean,
        cause: Throwable?
    ) : IllegalStateException(message, cause)

    /**
     * Gemini sometimes answers "high demand" or a short rate limit for a few
     * seconds; those are worth retrying. Other errors fail immediately.
     */
    private fun generateWithRetry(model: String, request: Request, thinkingLevel: ThinkingLevel?): String {
        val delaysMs = listOf(1_000L, 3_000L)
        var attempt = 0
        while (true) {
            try {
                return generate(model, request, thinkingLevel)
            } catch (e: CallError) {
                if (!e.isBusy || e.quotaRetryDelayMs != null || attempt >= delaysMs.size) throw e
                Log.w(TAG, "Gemini busy (${e.message}), retrying in ${delaysMs[attempt]} ms")
                Thread.sleep(delaysMs[attempt])
                attempt++
            }
        }
    }

    private fun generate(model: String, request: Request, thinkingLevel: ThinkingLevel?): String = runBlocking {
        val generativeModel = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = model,
            generationConfig = generationConfig {
                temperature = 0f
                responseMimeType = "application/json"
                responseSchema = request.schema
                if (thinkingLevel != null) {
                    thinkingConfig = thinkingConfig { this.thinkingLevel = thinkingLevel }
                }
            }
        )
        try {
            generativeModel.generateContent(request.content).text
                ?: throw IllegalStateException("Gemini returned no content")
        } catch (e: FirebaseAIException) {
            throw classify(e)
        }
    }

    private fun classify(e: FirebaseAIException): CallError {
        val message = e.message ?: e.javaClass.simpleName
        val quotaHit = e is QuotaExceededException ||
                message.contains("quota", ignoreCase = true) ||
                message.contains("RESOURCE_EXHAUSTED")
        val busy = quotaHit || e is RequestTimeoutException ||
                (e is ServerException && BUSY_PATTERN.containsMatchIn(message))
        val badRequest = !busy && (message.contains("INVALID_ARGUMENT") ||
                message.contains("invalid argument", ignoreCase = true) ||
                message.contains("thinking", ignoreCase = true) ||
                Regex("\\b400\\b").containsMatchIn(message))
        return CallError(
            message = "Gemini: $message",
            isBusy = busy,
            quotaRetryDelayMs = if (quotaHit) quotaDelayMs(message) else null,
            isBadRequest = badRequest,
            cause = e
        )
    }

    /** Parses "Please retry in 5m14.49s"; at least a minute. */
    private fun quotaDelayMs(message: String): Long {
        val match = Regex("retry in (?:(\\d+)h)?(?:(\\d+)m)?(?:([\\d.]+)s)?").find(message)
        val seconds = match?.let {
            (it.groupValues[1].toLongOrNull() ?: 0L) * 3600 +
                    (it.groupValues[2].toLongOrNull() ?: 0L) * 60 +
                    (it.groupValues[3].toDoubleOrNull() ?: 0.0).toLong()
        } ?: 0L
        return maxOf(seconds, 60L) * 1000
    }

    // ── Response ───────────────────────────────────────────────────────────

    private fun parseResult(json: String): Result {
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
            total = obj.number("total"),
            charges = obj.getAsJsonArray("charges")?.mapNotNull { element ->
                val c = element.asJsonObject
                val amount = c.number("amount")?.takeIf { it > 0.0 } ?: return@mapNotNull null
                Charge(
                    type = c.string("type")?.takeIf { it in CHARGE_TYPES } ?: "Other",
                    rate = c.number("rate")?.takeIf { it > 0.0 },
                    amount = amount,
                    inclusive = c.field("inclusive")?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                )
            }.orEmpty()
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
        /** ~1.8 MP, e.g. 1160 x 1550 for a normal receipt (was up to 2048 px long side, ~3 MP). */
        private const val MAX_IMAGE_PIXELS = 1_800_000

        private val CHARGE_TYPES = listOf(
            "SST", "Sales Tax", "Service Tax", "GST", "Service Charge", "Tourism Tax", "Other"
        )

        /** Allows for 5-sen rounding on Malaysian receipts. */
        private const val TOLERANCE = 0.06

        private val BUSY_PATTERN =
            Regex("\\b(429|500|503)\\b|UNAVAILABLE|overloaded|high demand", RegexOption.IGNORE_CASE)

        // Shared across screens so a used-up quota is remembered for the app session.
        private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val thinkingUnsupported: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}
