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
    val createdAt: Long = System.currentTimeMillis()
)