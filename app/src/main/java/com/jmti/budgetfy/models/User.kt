package com.jmti.budgetfy.models

data class User(
    val id: String = "",
    val email: String = "",
    val displayName: String = "",
    val overallBudget: Double = 0.0,
    val overallBudgetMonth: String = "",
    val createdAt: Long = System.currentTimeMillis()
)