package com.jmti.budgetfy.utils

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jmti.budgetfy.R
import com.jmti.budgetfy.models.ReceiptCharge
import com.jmti.budgetfy.models.ReceiptItem

/**
 * Fills layout/view_receipt_totals: items subtotal, one row per tax/charge
 * printed on the receipt, rounding & adjustments, and total paid.
 */
object ReceiptTotals {

    /**
     * [printedTotal] is the total read from the receipt; when it's missing
     * (older receipts, on-device reading) [fallbackTotal] is shown instead.
     */
    fun bind(
        root: View,
        items: List<ReceiptItem>,
        charges: List<ReceiptCharge>,
        printedTotal: Double?,
        fallbackTotal: Double
    ) {
        val context = root.context
        val subtotal = items.sumOf { it.price }
        val added = charges.filter { !it.inclusive }.sumOf { it.amount }
        val totalPaid = printedTotal?.takeIf { it > 0.0 } ?: fallbackTotal
        // What's left after items and added taxes is rounding/discount.
        // Rounded to sen so float noise shows as RM 0.00.
        val diff = Math.round((totalPaid - subtotal - added) * 100) / 100.0
        val count = items.size

        root.findViewById<TextView>(R.id.totalsSubtotalLabel).text =
            if (count == 1) context.getString(R.string.items_subtotal_one)
            else context.getString(R.string.items_subtotal_n, count)
        root.findViewById<TextView>(R.id.totalsSubtotal).text = rm(subtotal)

        // Tax rows, only for taxes printed on this receipt.
        val container = root.findViewById<ViewGroup>(R.id.totalsChargesContainer)
        container.removeAllViews()
        val dp = context.resources.displayMetrics.density
        val secondary = ContextCompat.getColor(context, R.color.text_secondary)
        val primary = ContextCompat.getColor(context, R.color.text_primary)
        charges.forEach { c ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * dp).toInt(), 0, 0)
            }
            row.addView(TextView(context).apply {
                val label = chargeLabel(context, c)
                text = if (c.inclusive) context.getString(R.string.charge_included, label) else label
                textSize = 14f
                setTextColor(secondary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Included taxes are already in the item prices, so no "+".
            row.addView(TextView(context).apply {
                text = if (c.inclusive) rm(c.amount) else "+ ${rm(c.amount)}"
                textSize = 14f
                setTextColor(if (c.inclusive) secondary else primary)
            })
            container.addView(row)
        }

        root.findViewById<TextView>(R.id.totalsRounding).text = when {
            diff > 0.0 -> "+ ${rm(diff)}"
            diff < 0.0 -> "− ${rm(-diff)}"
            else -> "RM 0.00"
        }
        root.findViewById<TextView>(R.id.totalsTotal).text = rm(totalPaid)
    }

    private fun rm(v: Double) = "RM ${String.format("%.2f", v)}"

    /** "SST 6%", "Cukai Perkhidmatan 8%": the tax name in the app language plus its rate. */
    private fun chargeLabel(context: android.content.Context, c: ReceiptCharge): String {
        val name = when (c.type) {
            "SST" -> R.string.charge_sst
            "Sales Tax" -> R.string.charge_sales_tax
            "Service Tax" -> R.string.charge_service_tax
            "GST" -> R.string.charge_gst
            "Service Charge" -> R.string.charge_service_charge
            "Tourism Tax" -> R.string.charge_tourism_tax
            "Other" -> R.string.charge_other
            else -> R.string.charge_tax
        }
        val pct = c.rate?.let { r -> if (r % 1.0 == 0.0) "${r.toLong()}%" else "$r%" }
        return listOfNotNull(context.getString(name), pct).joinToString(" ")
    }
}
