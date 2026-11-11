package com.jmti.budgetfy.models

import com.google.firebase.Timestamp

data class Receipt(
    val id: String = "",
    val userId: String = "",
    val imageUrl: String = "",
    val imageUri: String = "",
    val totalAmount: Double = 0.0,
    val storeName: String = "",
    val date: Timestamp = Timestamp.now(),
    val items: List<ReceiptItem> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** True while a copy of the photo is kept in Firestore (receipt_images/{id}). */
    val cloudImage: Boolean = false,
    /** Tax and charge lines printed on the receipt (empty when there are none). */
    val charges: List<ReceiptCharge> = emptyList(),
    /** Final amount printed on the receipt, or null when it wasn't read. */
    val printedTotal: Double? = null
)