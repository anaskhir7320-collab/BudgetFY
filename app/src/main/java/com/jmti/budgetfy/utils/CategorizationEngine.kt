package com.jmti.budgetfy.utils

import com.jmti.budgetfy.models.LearnedKeyword

class CategorizationEngine {

    private val categoryKeywords = mapOf(
        "Groceries" to listOf(
            "rice", "bread", "milk", "egg", "sugar", "flour", "oil", "salt",
            "vegetable", "fruit", "meat", "chicken", "fish", "noodle", "pasta",
            "sauce", "spice", "snack", "beverage", "drink", "water", "juice",
            "cereal", "butter", "cheese", "yogurt", "coffee", "tea"
        ),
        "Personal Care" to listOf(
            "shampoo", "soap", "toothpaste", "lotion", "cream", "perfume",
            "deodorant", "razor", "shaving", "conditioner", "body wash",
            "face wash", "sunscreen", "moisturizer", "tissue", "towel"
        ),
        "Transportation" to listOf(
            "petrol", "diesel", "gas", "bus", "grab", "taxi", "train",
            "lrt", "mrt", "parking", "toll", "fuel", "e-hailing", "ride"
        ),
        "Entertainment" to listOf(
            "movie", "netflix", "spotify", "game", "ticket", "cinema",
            "theatre", "concert", "streaming", "youtube", "music"
        ),
        "Food & Dining" to listOf(
            "restaurant", "cafe", "food", "meal", "lunch", "dinner",
            "breakfast", "mcdonald", "kfc", "pizza", "burger", "sushi",
            "mamak", "hawker", "takeaway", "delivery", "grab food",
            "lemonade", "muffin", "chocolate", "chicken", "rice bowl",
            "menu", "drinking water", "ice cream", "tea", "milk tea"
        ),
        "Education" to listOf(
            "book", "stationery", "pen", "paper", "notebook", "printer",
            "ink", "course", "tuition", "class", "library", "study"
        ),
        "Shopping" to listOf(
            "clothes", "shirt", "pants", "dress", "shoe", "bag", "watch",
            "accessory", "fashion", "department", "mall"
        ),
        "Health" to listOf(
            "clinic", "pharmacy", "medicine", "pill", "vitamin", "doctor",
            "hospital", "medical", "checkup", "vaccine"
        ),
        "Utilities" to listOf(
            "electric", "water", "internet", "phone", "bill", "subscription",
            "wifi", "broadband", "mobile"
        )
    )

    fun categorizeItem(itemName: String): String {
        return categorizeItem(itemName, emptyList())
    }

    fun categorizeItem(itemName: String, learnedKeywords: List<LearnedKeyword>): String {
        val lowerName = itemName.lowercase().trim()

        val exactMatch = learnedKeywords.firstOrNull {
            it.original.equals(lowerName, ignoreCase = true) &&
                    it.correctedCategory.isNotEmpty()
        }
        if (exactMatch != null) return exactMatch.correctedCategory

        val fuzzyMatch = learnedKeywords.firstOrNull {
            it.correctedCategory.isNotEmpty() &&
                    (lowerName.contains(it.original) || it.original.contains(lowerName)) &&
                    it.original.length >= 4
        }
        if (fuzzyMatch != null) return fuzzyMatch.correctedCategory

        for ((category, keywords) in categoryKeywords) {
            for (keyword in keywords) {
                if (lowerName.contains(keyword)) {
                    return category
                }
            }
        }
        return "Other"
    }

    fun applyLearnedName(itemName: String, learnedKeywords: List<LearnedKeyword>): String {
        val lowerName = itemName.lowercase().trim()

        val exactMatch = learnedKeywords.firstOrNull {
            it.original.equals(lowerName, ignoreCase = true) &&
                    it.correctedName.isNotEmpty()
        }
        if (exactMatch != null) return exactMatch.correctedName

        val fuzzyMatch = learnedKeywords.firstOrNull {
            it.correctedName.isNotEmpty() &&
                    it.original.length >= 8 &&
                    (lowerName.contains(it.original) || it.original.contains(lowerName))
        }
        if (fuzzyMatch != null) return fuzzyMatch.correctedName

        return itemName
    }

    // ✅ NEW: apply learned store names
    fun applyLearnedStoreName(detectedName: String, learnedKeywords: List<LearnedKeyword>): String {
        val key = "__store__:${detectedName.lowercase()}"
        val match = learnedKeywords.firstOrNull {
            it.original.equals(key, ignoreCase = true) && it.correctedName.isNotEmpty()
        }
        return match?.correctedName ?: detectedName
    }

    fun getCategories(): List<String> {
        return categoryKeywords.keys.sorted()
    }

    fun getDefaultBudgetLimits(): Map<String, Double> {
        return mapOf(
            "Groceries" to 300.0,
            "Personal Care" to 100.0,
            "Transportation" to 200.0,
            "Entertainment" to 100.0,
            "Food & Dining" to 250.0,
            "Education" to 50.0,
            "Shopping" to 100.0,
            "Health" to 50.0,
            "Utilities" to 150.0,
            "Other" to 100.0
        )
    }
}