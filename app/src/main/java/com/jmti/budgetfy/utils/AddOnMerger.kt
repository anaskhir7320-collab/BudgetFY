package com.jmti.budgetfy.utils

import com.jmti.budgetfy.models.AddOn
import com.jmti.budgetfy.models.ReceiptItem
import kotlin.math.round

/**
 * Safety net for Gemini: receipts mark add-ons with ":" or "+" at the start
 * of the line (":AO5 Egg Noodle  1  2.00"). If Gemini still returned such a
 * line as its own item, move it under the item above it and add its price
 * to that item, using the OCR rows to see which lines were marked.
 */
object AddOnMerger {

    private val marker = Regex("^\\s*[:+•*·~>]")

    fun attachMarkedAddOns(items: List<ReceiptItem>, layoutText: String): List<ReceiptItem> {
        // ":AO5 Egg   1   2.00" -> ("ao5egg", 2.00): name part and its price.
        val priceAtEnd = Regex("(?:\\s+\\d{1,2})?\\s+(-?\\d*\\.\\d{2})\\s*$")
        val markedRows = layoutText.lines()
            .filter { marker.containsMatchIn(it) }
            .map { row ->
                val m = priceAtEnd.find(row)
                norm(if (m != null) row.substring(0, m.range.first) else row) to m?.groupValues?.get(1)?.toDoubleOrNull()
            }
            .filter { it.first.length >= 3 }
        if (markedRows.isEmpty() || items.size < 2) return items

        val out = mutableListOf<ReceiptItem>()
        for (item in items) {
            val name = norm(item.name)
            // Same start of name AND (if the line shows a price) the same price,
            // so a real dish like "Egg Noodle Soup" is never pulled in by mistake.
            val isMarked = out.isNotEmpty() && markedRows.any { (row, price) ->
                (name.startsWith(row) || row.startsWith(name)) &&
                        (price == null || kotlin.math.abs(price - item.price) < 0.01)
            }
            if (isMarked) {
                val parent = out.removeAt(out.lastIndex)
                out.add(
                    parent.copy(
                        price = round((parent.price + item.price) * 100) / 100,
                        addOns = parent.addOns + AddOn(withoutCode(item.name), item.price)
                    )
                )
            } else {
                out.add(item)
            }
        }
        return out
    }

    /** Letters and digits only, lower case: ":AO5 Egg" -> "ao5egg". */
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** "AO5 Egg Noodle" -> "Egg Noodle". */
    fun withoutCode(name: String) = name.trim().replace(Regex("^[A-Za-z]{1,3}\\d{1,3}\\s+"), "")
}
