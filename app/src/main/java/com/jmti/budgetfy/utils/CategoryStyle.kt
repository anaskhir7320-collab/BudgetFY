package com.jmti.budgetfy.utils

import android.graphics.Color
import com.jmti.budgetfy.models.Receipt

/** Category colours for receipt tiles, spend bars and chips. */
object CategoryStyle {

    /** Main colour: bar segments, chip dot and chip text. */
    private val main = mapOf(
        "Food & Dining" to "#F57C00",
        "Groceries" to "#388E3C",
        "Shopping" to "#C2185B",
        "Transportation" to "#1976D2",
        "Entertainment" to "#E64A19",
        "Personal Care" to "#7B1FA2",
        "Health" to "#0097A7",
        "Education" to "#303F9F",
        "Utilities" to "#5D4037",
        "Other" to "#757575"
    )

    /** Light tint: chip background. */
    private val tint = mapOf(
        "Food & Dining" to "#FFF0D9",
        "Groceries" to "#E4F3E5",
        "Shopping" to "#FCDDE8",
        "Transportation" to "#DEEFFD",
        "Entertainment" to "#FFE6DE",
        "Personal Care" to "#F0DFF3",
        "Health" to "#D9F5F9",
        "Education" to "#E2E5F4",
        "Utilities" to "#EBE6E4",
        "Other" to "#F0F0F0"
    )

    fun color(category: String): Int = Color.parseColor(main[category] ?: main.getValue("Other"))
    fun tint(category: String): Int = Color.parseColor(tint[category] ?: tint.getValue("Other"))

    /**
     * RM spent per category, biggest first. If no item has a price
     * (e.g. totals-only receipts), item counts are used instead.
     */
    fun spendByCategory(receipt: Receipt): List<Pair<String, Double>> {
        val items = receipt.items
        if (items.isEmpty()) return emptyList()
        val bySpend = items.groupBy { it.category.ifBlank { "Other" } }
            .mapValues { (_, list) -> list.sumOf { it.price.coerceAtLeast(0.0) } }
        val totals = if (bySpend.values.sum() > 0.0) bySpend
        else items.groupingBy { it.category.ifBlank { "Other" } }.eachCount()
            .mapValues { it.value.toDouble() }
        return totals.filterValues { it > 0.0 }.toList().sortedByDescending { it.second }
    }
}
