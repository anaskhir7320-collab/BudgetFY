package com.jmti.budgetfy.models

data class ReceiptItem(
    val id: String = "",
    val name: String = "",
    val quantity: String = "1",
    val price: Double = 0.0,
    val category: String = "Other",
    val isManuallyCorrected: Boolean = false
)