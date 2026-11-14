package com.jmti.budgetfy.models

import com.google.firebase.firestore.Exclude

data class ReceiptItem(
    /** Not used; kept for older code paths but never written to Firestore. */
    @get:Exclude
    val id: String = "",
    val name: String = "",
    val quantity: String = "1",
    val price: Double = 0.0,
    val category: String = "Other",
    val isManuallyCorrected: Boolean = false
)
