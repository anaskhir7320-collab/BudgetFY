package com.jmti.budgetfy.models

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Exclude

data class Receipt(
    /** The Firestore document's own ID; filled in after reading, never stored as a field. */
    @get:Exclude
    val id: String = "",
    val userId: String = "",
    /** The photo's file on this phone (the cloud copy is receipt_images/{id}). */
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
