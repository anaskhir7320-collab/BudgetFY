package com.jmti.budgetfy.models

import com.google.firebase.firestore.Exclude

data class ReceiptItem(
    /** Not used; kept for older code paths but never written to Firestore. */
    @get:Exclude
    val id: String = "",
    val name: String = "",
    val quantity: String = "1",
    /** Amount charged for this line, including any priced add-ons. */
    val price: Double = 0.0,
    val category: String = "Other",
    val isManuallyCorrected: Boolean = false,
    /** Options printed under the item ("Egg Noodle", "Level 1", "+ Brownie 0.60"). */
    val addOns: List<AddOn> = emptyList()
)

/** An option or extra for an item; [price] is 0 when it was free. */
data class AddOn(
    val name: String = "",
    val price: Double = 0.0
)
