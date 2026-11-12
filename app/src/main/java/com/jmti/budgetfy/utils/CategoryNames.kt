package com.jmti.budgetfy.utils

import android.content.Context
import com.jmti.budgetfy.R

/**
 * Categories are stored in English (Firestore, budgets, learned keywords),
 * so data stays the same whatever the app language. These helpers only
 * change how a name is shown.
 */
object CategoryNames {

    private val names = mapOf(
        "Food & Dining" to R.string.cat_food_and_dining,
        "Groceries" to R.string.cat_groceries_name,
        "Shopping" to R.string.cat_shopping,
        "Transportation" to R.string.cat_transportation_name,
        "Entertainment" to R.string.cat_entertainment_name,
        "Personal Care" to R.string.cat_personal_care_name,
        "Health" to R.string.cat_health_name,
        "Education" to R.string.cat_education_name,
        "Utilities" to R.string.cat_utilities_name,
        "Other" to R.string.cat_other_name
    )

    /** "Groceries" → "Barangan Runcit" in Malay; unknown names are shown as they are. */
    fun display(context: Context, english: String): String =
        names[english]?.let { context.getString(it) } ?: english

    /** Reverse of [display], for values picked from a translated list. */
    fun toEnglish(context: Context, shown: String): String =
        names.entries.firstOrNull { context.getString(it.value) == shown }?.key ?: shown
}
