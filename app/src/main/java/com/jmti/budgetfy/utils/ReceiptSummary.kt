package com.jmti.budgetfy.utils

import com.jmti.budgetfy.models.Receipt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * A read-only "Summary" map stored on each receipt so the Firebase console
 * shows it in a fixed, readable order ("Summary" sorts before lowercase
 * fields; keys are numbered 01, 02... so they keep their order). The app
 * never reads it back.
 */
object ReceiptSummary {

    fun build(receipt: Receipt, displayName: String?, email: String?): Map<String, String> {
        val day = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val withSeconds = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val rows = mutableListOf<Pair<String, String>>()
        rows += "Display name" to (displayName?.takeIf { it.isNotBlank() } ?: "-")
        rows += "User ID" to receipt.userId
        rows += "Date" to day.format(receipt.date.toDate())
        rows += "Email" to (email ?: "-")
        rows += "Created at" to withSeconds.format(Date(receipt.createdAt))
        rows += "Store" to receipt.storeName

        receipt.items.forEachIndexed { i, item ->
            val addOns = if (item.addOns.isEmpty()) "" else " (+ " + item.addOns.joinToString(", ") { a ->
                if (a.price > 0.0) "${a.name} ${rm(a.price)}" else a.name
            } + ")"
            rows += "Item ${i + 1}" to "${item.name} ×${item.quantity} ${rm(item.price)}$addOns"
        }

        val charges = receipt.charges.map { c ->
            val name = when (c.type) {
                "Other", "" -> "Tax"
                else -> c.type
            } + (c.rate?.let { r -> if (r % 1.0 == 0.0) " ${r.toLong()}%" else " $r%" } ?: "")
            when {
                c.inclusive -> "$name ${rm(c.amount)} (included)"
                c.amount < 0.0 -> "$name −${rm(-c.amount)}"
                else -> "$name ${rm(c.amount)}"
            }
        }.toMutableList()
        val added = receipt.charges.filter { !it.inclusive }.sumOf { it.amount }
        val rounding = Math.round((receipt.totalAmount - receipt.items.sumOf { it.price } - added) * 100) / 100.0
        if (abs(rounding) >= 0.005) charges += if (rounding < 0) "Rounding −${rm(-rounding)}" else "Rounding ${rm(rounding)}"
        rows += "Tax" to (charges.joinToString(", ").ifEmpty { "-" })
        rows += "Total paid" to rm(receipt.totalAmount)

        return rows.mapIndexed { i, (label, value) -> "%02d %s".format(i + 1, label) to value }.toMap()
    }

    private fun rm(v: Double) = "RM %.2f".format(Locale.US, v)
}
