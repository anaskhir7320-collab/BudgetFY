package com.jmti.budgetfy.utils

import com.jmti.budgetfy.models.ReceiptItem
import kotlin.math.abs

/**
 * Checks whether the extracted items add up to the total printed on the receipt.
 * A mismatch usually means a missed item, a duplicated item or a misread price,
 * so the user is told to check before saving.
 */
object ReceiptValidator {

    /** Allows for 5-sen rounding on Malaysian receipts. */
    private const val TOLERANCE = 0.06

    data class Check(
        val itemsSum: Double,
        val receiptTotal: Double?,
        /** null when the receipt total is unknown, so nothing could be checked. */
        val matches: Boolean?
    ) {
        val difference: Double get() = (receiptTotal ?: itemsSum) - itemsSum
    }

    /**
     * @param extras tax + service charge + rounding − discount, when those are
     *   charged as separate lines. Receipts that say "inclusive of tax" have none.
     */
    fun check(items: List<ReceiptItem>, receiptTotal: Double?, extras: Double = 0.0): Check {
        val itemsSum = items.sumOf { it.price }
        if (receiptTotal == null || receiptTotal <= 0.0) {
            return Check(itemsSum, null, null)
        }
        val matches = abs(itemsSum + extras - receiptTotal) <= TOLERANCE ||
                abs(itemsSum - receiptTotal) <= TOLERANCE
        return Check(itemsSum, receiptTotal, matches)
    }
}
