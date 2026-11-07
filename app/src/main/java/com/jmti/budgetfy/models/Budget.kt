package com.jmti.budgetfy.models

data class Budget(
    val id: String = "",
    val userId: String = "",
    val category: String = "",
    val limit: Double = 0.0,
    val spent: Double = 0.0,
    val month: String = "" // Format: "YYYY-MM"
)

data class BudgetCategory(
    val name: String,
    val limit: Double,
    val spent: Double,
    val color: Int
)