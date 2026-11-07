package com.jmti.budgetfy.models

data class LearnedKeyword(
    val id: String = "",
    val userId: String = "",
    val original: String = "",         // What OCR produced (lowercase)
    val correctedName: String = "",    // What user wanted the name to be (empty if only category fixed)
    val correctedCategory: String = "", // What user wanted category to be (empty if only name fixed)
    val count: Int = 1,                 // How many times this correction was made
    val lastUsed: Long = System.currentTimeMillis()
)