package com.jmti.budgetfy.utils

import android.util.Log
import com.jmti.budgetfy.models.ReceiptItem
import kotlin.math.abs
import kotlin.math.round

/**
 * Offline reading (no Gemini): parse more than one OCR reading of the same
 * receipt and keep the one that agrees with the printed total, then use the
 * total to repair a single misread price.
 */
object OfflineReceiptReader {

    private const val TOLERANCE = 0.06 // 5-sen rounding

    data class Reading(val ocr: OCRHelper.OcrOutput, val result: ReceiptParser.ParseResult)

    /** Row-based reading of one OCR pass, in the same shape as the old parser's result. */
    fun rowReading(ocr: OCRHelper.OcrOutput, rows: RowReceiptParser): Reading {
        val r = rows.parse(ocr.layoutText)
        return Reading(
            ocr,
            ReceiptParser.ParseResult(
                items = r.items,
                receiptTotal = r.total ?: r.items.sumOf { it.price },
                totalFound = r.total != null,
                extras = r.extras,
                charges = r.charges
            )
        )
    }

    /**
     * Best reading first: one with items whose items (+ charges) match a
     * printed total, then the smallest gap to that total. On a tie the
     * earlier reading in [readings] wins, so callers list the most trusted
     * reader first.
     */
    fun pickBest(readings: List<Reading>): Reading {
        fun gap(r: Reading): Double =
            if (r.result.totalFound) abs(r.result.items.sumOf { it.price } + r.result.extras - r.result.receiptTotal)
            else Double.MAX_VALUE
        val best = readings.sortedWith( // stable: ties keep the caller's order
            compareBy<Reading> { if (it.result.items.isEmpty()) 1 else 0 }
                .thenBy { if (gap(it) <= TOLERANCE) 0 else 1 }
                .thenBy { gap(it) }
        ).first()
        readings.forEachIndexed { i, r ->
            Log.d("OFFLINE", "reading $i: ${r.result.items.size} items, sum=${r.result.items.sumOf { it.price }}, total=${r.result.receiptTotal} found=${r.result.totalFound}")
        }
        Log.d("OFFLINE", "kept reading ${readings.indexOf(best)}")
        return best
    }

    /**
     * When the items miss the printed total and exactly one price can be
     * changed by a single digit to close the gap (e.g. 8.90 read for 6.90),
     * that price is corrected. Anything less certain is left for the user.
     */
    fun repairToTotal(result: ReceiptParser.ParseResult): List<ReceiptItem> {
        val items = result.items
        if (!result.totalFound || items.isEmpty()) return items
        val sum = items.sumOf { it.price }
        val itemsTotal = result.receiptTotal - result.extras // what the items should add up to
        if (abs(sum - itemsTotal) <= TOLERANCE) return items

        val fixes = items.indices.mapNotNull { i ->
            val wanted = round((itemsTotal - (sum - items[i].price)) * 100) / 100
            if (wanted > 0.0 && oneDigitApart(items[i].price, wanted)) i to wanted else null
        }
        if (fixes.size != 1) return items
        val (index, price) = fixes.first()
        Log.d("OFFLINE", "repaired '${items[index].name}': ${items[index].price} -> $price")
        return items.toMutableList().also { it[index] = it[index].copy(price = price) }
    }

    /** "8.90" vs "6.90": same length, exactly one digit different. */
    private fun oneDigitApart(a: Double, b: Double): Boolean {
        val x = String.format(java.util.Locale.US, "%.2f", a)
        val y = String.format(java.util.Locale.US, "%.2f", b)
        if (x.length != y.length) return false
        return x.indices.count { x[it] != y[it] } == 1
    }
}
