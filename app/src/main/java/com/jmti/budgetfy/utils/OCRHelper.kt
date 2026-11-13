package com.jmti.budgetfy.utils

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

class OCRHelper {

    private val recognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    data class OcrOutput(
        /** Block-by-block text, the format ReceiptParser expects. */
        val text: String,
        /** Lines regrouped into visual rows ("ITEM NAME   1   12.90"), for Gemini. */
        val layoutText: String
    )

    suspend fun extractTextFromImage(bitmap: Bitmap): String = recognize(bitmap).text

    suspend fun recognize(bitmap: Bitmap): OcrOutput = recognize(bitmap, enhanced = false)

    /**
     * Second reading of the same receipt on a cleaned-up copy (grayscale,
     * stretched contrast). Faded thermal print often reads better this way;
     * used by the offline reader, which keeps whichever reading adds up.
     */
    suspend fun recognizeEnhanced(bitmap: Bitmap): OcrOutput = recognize(bitmap, enhanced = true)

    private suspend fun recognize(bitmap: Bitmap, enhanced: Boolean): OcrOutput {
        return try {
            val scaled = preprocess(bitmap)
            val processed = if (enhanced) enhance(scaled) else scaled
            val image = InputImage.fromBitmap(processed, 0)
            val result = recognizer.process(image).await()

            val sb = StringBuilder()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    sb.appendLine(line.text)
                }
            }

            val corrected = correctCommonOCRErrors(sb.toString())
            val layout = correctCommonOCRErrors(buildRows(result))
            android.util.Log.d("OCRTEST", corrected)
            android.util.Log.d("OCRTEST", "ROWS:\n$layout")
            OcrOutput(corrected, layout)
        } catch (e: Exception) {
            android.util.Log.e("OCRTEST", "OCR error: ${e.message}")
            OcrOutput("", "")
        }
    }

    /**
     * ML Kit groups text into blocks, so on a receipt the item names and the
     * price column come out as separate lists. Regrouping lines by vertical
     * position puts each item back on the same row as its price.
     */
    private fun buildRows(result: Text): String {
        val lines = result.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        if (lines.isEmpty()) return ""

        val heights = lines.map { it.boundingBox!!.height() }.sorted()
        val rowTolerance = heights[heights.size / 2] * 0.6f

        // A slightly tilted receipt puts the right-hand column (prices) a bit
        // higher or lower than its label on the left, so rows get paired one
        // off. Measure the tilt from each line's top edge and straighten the
        // positions before grouping.
        val slope = medianSlope(lines)
        android.util.Log.d("OCRTEST", "tilt correction: %.4f px/px (%.1f°)".format(slope, Math.toDegrees(kotlin.math.atan(slope.toDouble()))))
        fun rowY(line: Text.Line): Float {
            val box = line.boundingBox!!
            return box.exactCenterY() - slope * box.exactCenterX()
        }

        val rows = mutableListOf<MutableList<Text.Line>>()
        val rowCenters = mutableListOf<Float>()
        for (line in lines.sortedBy { rowY(it) }) {
            val centerY = rowY(line)
            val last = rows.lastIndex
            if (last >= 0 && abs(centerY - rowCenters[last]) <= rowTolerance) {
                rows[last].add(line)
                rowCenters[last] = rows[last].map { rowY(it) }.average().toFloat()
            } else {
                rows.add(mutableListOf(line))
                rowCenters.add(centerY)
            }
        }

        return rows.joinToString("\n") { row ->
            row.sortedBy { it.boundingBox!!.left }.joinToString("   ") { it.text }
        }
    }

    /**
     * Typical tilt of the text (pixels down per pixel across), from the top
     * edge of every reasonably wide line. 0 when it can't be measured.
     */
    private fun medianSlope(lines: List<Text.Line>): Float {
        val slopes = lines.mapNotNull { line ->
            val p = line.cornerPoints ?: return@mapNotNull null
            if (p.size < 2) return@mapNotNull null
            val dx = (p[1].x - p[0].x).toFloat()
            if (dx < 60f) return@mapNotNull null // too short to measure reliably
            ((p[1].y - p[0].y) / dx).takeIf { abs(it) < 0.2f } // ignore odd ones (> ~11°)
        }.sorted()
        if (slopes.size < 3) return 0f
        return slopes[slopes.size / 2]
    }

    private fun preprocess(src: Bitmap): Bitmap {
        val targetWidth = 2000
        return if (src.width < targetWidth) {
            val ratio = targetWidth.toFloat() / src.width
            Bitmap.createScaledBitmap(
                src,
                targetWidth,
                (src.height * ratio).toInt(),
                true
            )
        } else src
    }

    /**
     * Grayscale + contrast stretch: the darkest 2% of pixels become black and
     * the lightest 2% white, so faint grey print turns into solid dark text.
     */
    private fun enhance(src: Bitmap): Bitmap {
        // Measure brightness on a small copy (cheap), then apply the stretch
        // to the full image with a colour filter (no per-pixel arrays).
        val sampleW = 400
        val sample = Bitmap.createScaledBitmap(
            src, sampleW, (src.height * sampleW.toFloat() / src.width).toInt().coerceAtLeast(1), true
        )
        val px = IntArray(sample.width * sample.height)
        sample.getPixels(px, 0, sample.width, 0, 0, sample.width, sample.height)
        val hist = IntArray(256)
        for (c in px) {
            hist[((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000]++
        }
        fun percentile(p: Double): Int {
            val target = (px.size * p).toLong()
            var acc = 0L
            for (v in 0..255) { acc += hist[v]; if (acc >= target) return v }
            return 255
        }
        val lo = percentile(0.02)
        val hi = percentile(0.98)
        if (hi - lo < 10) return src // flat image, nothing to stretch

        // Grayscale, then map [lo, hi] to [0, 255].
        val scale = 255f / (hi - lo)
        val offset = -lo * scale
        val gray = android.graphics.ColorMatrix().apply { setSaturation(0f) }
        val stretch = android.graphics.ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, offset,
            0f, scale, 0f, 0f, offset,
            0f, 0f, scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        ))
        gray.postConcat(stretch)

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(gray)
            isFilterBitmap = true
        }
        android.graphics.Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun correctCommonOCRErrors(text: String): String {
        var corrected = text
        // Whole-word only: a plain substring replace turned the correct "Muffin"
        // into "MMuffin" (via "uffin") and "APPROVAL" into "APPROVALOVAL" (via "APPR").
        for ((pattern, replacement) in wordReplacements) {
            corrected = pattern.replace(corrected, replacement)
        }

        corrected = corrected.replace(Regex("\\bR[HN]\\s*(\\d)"), "RM $1")
        corrected = corrected.replace(Regex("\\bRM[J1l]\\s*(\\d)"), "RM $1")

        return corrected
    }

    private val wordReplacements: List<Pair<Regex, String>> by lazy {
        replacements.map { (wrong, right) ->
            Regex("(?<![A-Za-z0-9])" + Regex.escape(wrong) + "(?![A-Za-z0-9])") to
                    Regex.escapeReplacement(right)
        }
    }

    private val replacements = listOf(
            // ═══ FamilyMart / Mixue ═══
            "Chocoiate" to "Chocolate",
            "Choco1ate" to "Chocolate",
            "Chocolotee" to "Chocolate",
            "Chocolat " to "Chocolate ",
            "Hini" to "Mini",
            "Hint" to "Mini",
            "uffin" to "Muffin",
            "Muffia" to "Muffin",
            "Muffln" to "Muffin",
            "FanilyMart" to "FamilyMart",
            "Fanily" to "Family",
            "Welcone" to "Welcome",
            "Infornation" to "Information",
            "MOKAMAD" to "MOHAMAD",
            "NOKAMAD" to "MOHAMAD",
            "ASUAD" to "ASWAD",
            "ASIAD" to "ASWAD",
            "0den" to "Oden",
            "RREF" to "REF",
            "TIHE" to "TIME",
            "CARD NUHER" to "CARD NUMBER",
            "APPR" to "APPROVAL",
            "Fr eesh" to "Fresh",
            "Lenmonade" to "Lemonade",
            "ienonade" to "Lemonade",
            "Duitn0w" to "DuitNow",
            "Nomal" to "Normal",
            "Sugat" to "Sugar",

            // ═══ Texas Chicken ═══
            "BUTERMILK" to "BUTTERMILK",
            "BUITERMILK" to "BUTTERMILK",
            "Iotal" to "Total",
            "Recelpt" to "Receipt",
            "Reecelpt" to "Receipt",
            "RECETPI" to "RECEIPT",
            "YoUR" to "YOUR",
            "voUR" to "YOUR",
            "Jal an" to "Jalan",
            "Al ma" to "Alma",

            // Total variants
            "lotal" to "total",
            "lota1" to "total",
            "Lotal" to "Total",
            "tota1" to "total",
            "LOTA1" to "TOTAL",
            "totai" to "total",
            "Sub lotal" to "Sub Total",
            "Grand lotal" to "Grand Total",
            "GRAND IOTAL" to "GRAND TOTAL",
            "Grand T0tal" to "Grand Total",
            "6rand Total" to "Grand Total",

            // Rounding variants
            "Roundin)" to "Rounding",
            "Roundin" to "Rounding",
            "Round1ng" to "Rounding",
            "roundin)" to "rounding",
            "roundin" to "rounding",
            "Roundingg" to "Rounding",

            // Texas Chicken sub-items
            "DRINKING WA|ER" to "DRINKING WATER",
            "DRINKING WATFR" to "DRINKING WATER",
            "Rahmab" to "Rahmah",
            "MENU RAHMA" to "MENU RAHMAH",
            "RAHMA SET" to "RAHMAH SET",

            // Texas Chicken camera variants
            "Restauant No" to "Restaurant No",
            "Restauant N0" to "Restaurant No",
            "Restauant no" to "Restaurant No",
            "Restauant" to "Restaurant",
            "Restautant No" to "Restaurant No",
            "Restautant" to "Restaurant",
            "Restaufant No" to "Restaurant No",
            "Restaufant" to "Restaurant",
            "payment Iype" to "Payment Type",
            "Payment Iype" to "Payment Type",
            "pate & fime" to "Date & Time",
            "Pate & fime" to "Date & Time",
            "Batch Nun" to "Batch Num",
            "Bill inclusl ve" to "Bill inclusive of",
            "Bill inclusi ve" to "Bill inclusive of",
            "Bill inclusl" to "Bill inclusive",
            "Bill inclusi" to "Bill inclusive",
            "Bill inciuslve" to "Bill inclusive",
            "Bill incdusl ve" to "Bill inclusive",
            "Gränd total" to "Grand total",
            "Gränd Total" to "Grand Total",
            "Gränd" to "Grand",
            "Grand Jotal" to "Grand Total",
            "Grand otal" to "Grand Total",
            "Gand Total" to "Grand Total",
            "Gtand Total" to "Grand Total",
            "Card Irasacion" to "Card Transaction",
            "Card Iranaaction" to "Card Transaction",
            "Card Tlanaaction" to "Card Transaction",
            "Irasacion" to "Transaction",
            "Iranaaction" to "Transaction",
            "lntonatio" to "Information",
            "Intomhation" to "Information",
            "Intonation" to "Information",
            "Informationn" to "Information",
            "Credit / Debit Cad" to "Credit / Debit Card",
            "Credit/Debit Cad" to "Credit/Debit Card",
            "it ID:" to "Merchant ID:",
            "Take hway" to "Take Away",
            "Take away" to "Take Away",

            // Camera variants v9
            "paynent Iype" to "Payment Type",
            "paynent iype" to "Payment Type",
            "Paynent" to "Payment",
            "IPaynient" to "Payment",
            "IPaynent" to "Payment",
            "Paynient" to "Payment",
            "Cand Nunbe" to "Card Number",
            "Cand Numbe" to "Card Number",
            "Cand Nae" to "Card Name",
            "Cand Nunber" to "Card Number",
            "Cand" to "Card",
            "Fxp Date." to "Exp Date.",
            "Fxp Date" to "Exp Date",
            "Fxp" to "Exp",
            "Appr Cod" to "Approval Code",
            "Appr" to "Approval",
            "Ierminal" to "Terminal",
            "ierminal" to "terminal",
            "Tlanaaction" to "Transaction",
            "Tranaaction" to "Transaction",
            "Naration" to "Information",
            "Merchant I0" to "Merchant ID",
            "Merchant io" to "Merchant ID",
            "Ser vice" to "Service",
            "lax" to "Tax",
            "RECEIPI" to "RECEIPT",
            "Wi0" to "W10",
            "Wi0-" to "W10-",

            // Camera variants v10
            "pate & Tine." to "Date & Time.",
            "pate & Tine" to "Date & Time",
            "pate & time" to "Date & Time",
            "Pate & Tine" to "Date & Time",
            "Tine." to "Time.",
            "Grarnd Total" to "Grand Total",
            "Grarnd total" to "Grand total",
            "Grarnd" to "Grand",
            "Cad IraIsaction" to "Card Transaction",
            "Cad Iralsaction" to "Card Transaction",
            "IraIsaction" to "Transaction",
            "Iralsaction" to "Transaction",
            "nlonation." to "Information.",
            "nlonation" to "Information",
            "nlomation" to "Information",
            "|Card Nag" to "|Card Name",
            "|Card Nae" to "|Card Name",
            "Card Nag" to "Card Name",
            "Merchant IQ" to "Merchant ID",
            "Ilerminal" to "Terminal",
            "IPaynment Type" to "Payment Type",
            "IPaynment" to "Payment",
            "BUTTERMILK RICE BOWI" to "BUTTERMILK RICE BOWL",
            "RICE BOWI" to "RICE BOWL",
            "RAHMAHH SET" to "RAHMAH SET",
            ":DRINKING WATER" to ": DRINKING WATER",
            "Bill inchusive" to "Bill inclusive",
            "inchusive" to "inclusive",
            "SSTID: W10 1808" to "SSTID: W10-1808",
            "Wi0 1808" to "W10-1808",
            "Wi0-1808" to "W10-1808",

            // V11 additions
            "Paysnent Type" to "Payment Type",
            "Paysnent" to "Payment",
            "|Csedit" to "|Credit",
            "Csedit" to "Credit",
            "Date & lime." to "Date & Time.",
            "Date & lime" to "Date & Time",
            "Batch um:" to "Batch Num:",
            "Batch um" to "Batch Num",
            "ITerninal Ip:" to "Terminal ID:",
            "ITerninal" to "Terminal",
            "Bil incuslve of Ser vlce Tax" to "Bill inclusive of Service Tax",
            "Bil incuslve" to "Bill inclusive",
            "incuslve" to "inclusive",
            "Ser vlce" to "Service",
            "vlce" to "vice",
            "Restaunant No" to "Restaurant No",
            "Restaunant" to "Restaurant",
            "RECHIPI" to "RECEIPT",
            "Nane" to "Name",
            "Halaysin" to "Malaysia",
            "Sdn Bd" to "Sdn Bhd",
            "Groumd" to "Ground",
            "Aeort" to "Aeon",
            "Bkit" to "Bukit",
            "Terngah" to "Tengah",
            "Irasaction" to "Transaction",
            "lntomation" to "Information",
            "Card Irasaction lntomation" to "Card Transaction Information",

            // ✅ NEW (v12): Mixue camera variants
            "Unit pice" to "Unit price",
            "Zuantity" to "Quantity",
            "Sub total" to "Sub Total",
            "Total praducts" to "Total products",
            "praducts" to "products",
            "Featute" to "Feature",
            "Nctal" to "Normal",
            "Nounal" to "Normal",
            "ce," to "Ice,",
            "Noal" to "Normal",
            "lce" to "Ice",
            "nunber" to "number",
            "tinie" to "time",
            "SINCE 39i" to "SINCE 1991",
            "CAEAM" to "CREAM",
            "Oider" to "Order",
            "Mertajan" to "Mertajam",
            "irond" to "Ground",
            "lloot" to "floor",
            "Addres5" to "Address",
            "DuitnoWw" to "DuitNow",
            "Duitno" to "DuitNow",
            "solajatoterprie" to "Tana Bahagia Enterprise",
            "SSMe" to "SSM:"
        )
}