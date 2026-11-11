package com.jmti.budgetfy.models

/**
 * A tax or charge line printed on a receipt, e.g. "SST 6%" or "Service Charge 10%".
 * [inclusive] is true when it's already inside the item prices (shown, not added).
 */
data class ReceiptCharge(
    /** SST, Sales Tax, Service Tax, GST, Service Charge, Tourism Tax or Other. */
    val type: String = "",
    /** Percentage as printed (6.0 for 6%), or null when not shown. */
    val rate: Double? = null,
    val amount: Double = 0.0,
    val inclusive: Boolean = false
) {
    /** "SST 6%", "Service Charge 10%", "Tourism Tax" (not stored in Firestore) */
    @get:com.google.firebase.firestore.Exclude
    val label: String
        get() {
            val pct = rate?.let { r -> if (r % 1.0 == 0.0) "${r.toLong()}%" else "$r%" }
            return listOfNotNull(type.ifBlank { "Tax" }, pct).joinToString(" ")
        }
}
