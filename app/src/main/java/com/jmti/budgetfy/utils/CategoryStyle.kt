package com.jmti.budgetfy.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.jmti.budgetfy.models.Receipt

/** Category colours for receipt tiles, spend bars, chips and the budget donut. */
object CategoryStyle {

    /** Day: main colour for bar segments, chip dots and chip text. */
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

    /** Day: light tint for chip backgrounds. */
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

    /** Night: lighter shades of the same colours, readable on dark cards. */
    private val mainNight = mapOf(
        "Food & Dining" to "#FFA94D",
        "Groceries" to "#66BB6A",
        "Shopping" to "#F06292",
        "Transportation" to "#64B5F6",
        "Entertainment" to "#FF8A65",
        "Personal Care" to "#BA68C8",
        "Health" to "#4DD0E1",
        "Education" to "#7986CB",
        "Utilities" to "#A1887F",
        "Other" to "#9E9E9E"
    )

    /** Night: dark tints for chip backgrounds. */
    private val tintNight = mapOf(
        "Food & Dining" to "#3A2A17",
        "Groceries" to "#1E2F20",
        "Shopping" to "#3A1F2B",
        "Transportation" to "#1A2C3E",
        "Entertainment" to "#3A231C",
        "Personal Care" to "#2E1F35",
        "Health" to "#163238",
        "Education" to "#1F2438",
        "Utilities" to "#2A2523",
        "Other" to "#2A2D33"
    )

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    fun color(context: Context, category: String): Int {
        val map = if (isNight(context)) mainNight else main
        return Color.parseColor(map[category] ?: map.getValue("Other"))
    }

    fun tint(context: Context, category: String): Int {
        val map = if (isNight(context)) tintNight else tint
        return Color.parseColor(map[category] ?: map.getValue("Other"))
    }

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
