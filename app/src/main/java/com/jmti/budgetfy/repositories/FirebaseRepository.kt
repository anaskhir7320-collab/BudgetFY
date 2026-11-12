package com.jmti.budgetfy.repositories

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.jmti.budgetfy.models.Budget
import com.jmti.budgetfy.models.BudgetCategory
import com.jmti.budgetfy.models.LearnedKeyword
import com.jmti.budgetfy.models.Receipt
import com.jmti.budgetfy.models.User
import com.jmti.budgetfy.utils.CategorizationEngine
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class FirebaseRepository {

    companion object {
        /** Receipt photos, one document per receipt, keyed by receipt id. */
        private const val RECEIPT_IMAGES = "receipt_images"
    }

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val categorizationEngine = CategorizationEngine()

    // ==================== AUTH ====================

    fun getCurrentUser(): FirebaseUser? = auth.currentUser

    suspend fun loginUser(email: String, password: String): FirebaseUser? {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            result.user
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "Login failed: ${e.message}")
            null
        }
    }

    suspend fun registerUser(email: String, password: String, displayName: String): FirebaseUser? {
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user
            user?.updateProfile(
                UserProfileChangeRequest.Builder().setDisplayName(displayName).build()
            )?.await()
            user?.let { saveUserToFirestore(it, displayName) }
            user
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "Registration failed: ${e.message}")
            null
        }
    }

    suspend fun logoutUser() {
        auth.signOut()
    }

    private suspend fun saveUserToFirestore(user: FirebaseUser, displayName: String) {
        val userData = User(
            id = user.uid,
            email = user.email ?: "",
            displayName = displayName
        )
        firestore.collection("users").document(user.uid)
            .set(userData, SetOptions.merge()).await()
    }

    // ==================== OVERALL BUDGET ====================

    suspend fun setOverallBudget(userId: String, amount: Double): Boolean {
        return try {
            val month = getCurrentMonth()
            firestore.collection("users").document(userId)
                .update(
                    "overallBudget", amount,
                    "overallBudgetMonth", month
                ).await()
            Log.d("FirebaseRepo", "✅ Overall budget set: $amount for $month")
            true
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "setOverallBudget error: ${e.message}")
            false
        }
    }

    suspend fun getOverallBudget(userId: String): Double {
        return try {
            val doc = firestore.collection("users").document(userId).get().await()
            val amount = doc.getDouble("overallBudget") ?: 0.0
            val month = doc.getString("overallBudgetMonth") ?: ""
            val currentMonth = getCurrentMonth()
            if (month == currentMonth) amount else 0.0
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "getOverallBudget error: ${e.message}")
            0.0
        }
    }

    suspend fun resetOverallBudget(userId: String): Boolean {
        return try {
            firestore.collection("users").document(userId)
                .update(
                    "overallBudget", 0.0,
                    "overallBudgetMonth", ""
                ).await()

            val month = getCurrentMonth()
            val snapshot = firestore.collection("budgets")
                .whereEqualTo("userId", userId)
                .whereEqualTo("month", month)
                .get().await()
            for (doc in snapshot.documents) {
                firestore.collection("budgets").document(doc.id).delete().await()
            }
            Log.d("FirebaseRepo", "✅ Overall budget reset + ${snapshot.documents.size} category budgets cleared")
            true
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "resetOverallBudget error: ${e.message}")
            false
        }
    }

    // ==================== CATEGORY BUDGETS ====================

    suspend fun addOrUpdateBudget(userId: String, category: String, limit: Double): Boolean {
        return try {
            val overall = getOverallBudget(userId)
            if (overall <= 0) {
                Log.e("FirebaseRepo", "addOrUpdateBudget: overall budget not set")
                return false
            }
            if (limit > overall) {
                Log.e("FirebaseRepo", "addOrUpdateBudget: limit $limit > overall $overall")
                return false
            }

            val month = getCurrentMonth()
            val snapshot = firestore.collection("budgets")
                .whereEqualTo("userId", userId)
                .whereEqualTo("category", category)
                .whereEqualTo("month", month)
                .get().await()

            if (snapshot.documents.isNotEmpty()) {
                val doc = snapshot.documents.first()
                firestore.collection("budgets").document(doc.id)
                    .update("limit", limit).await()
                Log.d("FirebaseRepo", "Updated $category limit=$limit")
            } else {
                val budget = Budget(
                    userId = userId,
                    category = category,
                    limit = limit,
                    spent = 0.0,
                    month = month
                )
                firestore.collection("budgets").document().set(budget).await()
                Log.d("FirebaseRepo", "Created $category limit=$limit")
            }
            true
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "addOrUpdateBudget error: ${e.message}")
            false
        }
    }

    suspend fun getBudgets(userId: String): List<BudgetCategory> {
        val month = getCurrentMonth()
        val snapshot = firestore.collection("budgets")
            .whereEqualTo("userId", userId)
            .whereEqualTo("month", month)
            .get().await()

        val uniqueByCategory = snapshot.documents
            .mapNotNull { it.toObject(Budget::class.java) }
            .distinctBy { it.category }

        return uniqueByCategory.map { budget ->
            BudgetCategory(
                name = budget.category,
                limit = budget.limit,
                spent = budget.spent,
                color = getCategoryColor(budget.category)
            )
        }
    }

    suspend fun updateBudgetSpent(userId: String, category: String, amount: Double) {
        val month = getCurrentMonth()
        val snapshot = firestore.collection("budgets")
            .whereEqualTo("userId", userId)
            .whereEqualTo("category", category)
            .whereEqualTo("month", month)
            .get().await()

        if (snapshot.documents.isNotEmpty()) {
            val doc = snapshot.documents.first()
            val current = doc.getDouble("spent") ?: 0.0
            firestore.collection("budgets").document(doc.id)
                .update("spent", current + amount).await()
        } else {
            val overall = getOverallBudget(userId)
            val defaultLimits = categorizationEngine.getDefaultBudgetLimits()
            val desiredLimit = defaultLimits[category] ?: 100.0
            val actualLimit = if (overall > 0) minOf(desiredLimit, overall) else desiredLimit

            val budget = Budget(
                userId = userId, category = category,
                limit = actualLimit, spent = amount, month = month
            )
            firestore.collection("budgets").document().set(budget).await()
        }
    }

    // ==================== RECEIPTS ====================

    /**
     * Saves the receipt. When [imageJpeg] is given, the photo is stored in
     * Firestore too (receipt_images/{receiptId}) so it survives uninstalling
     * the app. If the photo upload fails, the receipt is still saved.
     */
    suspend fun saveReceipt(userId: String, receipt: Receipt, imageJpeg: ByteArray? = null) {
        val ref = firestore.collection("receipts").document()
        var toSave = receipt
        if (imageJpeg != null) {
            try {
                firestore.collection(RECEIPT_IMAGES).document(ref.id).set(
                    mapOf(
                        "userId" to userId,
                        "image" to Blob.fromBytes(imageJpeg),
                        "createdAt" to System.currentTimeMillis()
                    )
                ).await()
                toSave = receipt.copy(cloudImage = true)
            } catch (e: Exception) {
                Log.e("FirebaseRepo", "Receipt photo upload failed: ${e.message}")
            }
        }
        ref.set(toSave).await()
        val share = receipt.chargeShare()
        for (item in receipt.items) {
            updateBudgetSpent(userId, item.category, item.price * share)
        }
    }

    /**
     * Added tax, service charge and rounding are spread over the items'
     * categories in proportion to their prices, so category budgets add up
     * to what was actually paid. 1.0 for receipts without added charges
     * (and for older receipts, where the total is the items sum).
     */
    private fun Receipt.chargeShare(): Double {
        val itemsSum = items.sumOf { it.price }
        return if (itemsSum > 0.0 && totalAmount > 0.0) totalAmount / itemsSum else 1.0
    }

    /** Returns the photo saved in Firestore for this receipt, or null. */
    suspend fun getReceiptImage(receiptId: String): ByteArray? {
        return try {
            firestore.collection(RECEIPT_IMAGES).document(receiptId).get().await()
                .getBlob("image")?.toBytes()
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "getReceiptImage error: ${e.message}")
            null
        }
    }

    private suspend fun deleteReceiptImage(receiptId: String) {
        try {
            firestore.collection(RECEIPT_IMAGES).document(receiptId).delete().await()
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "deleteReceiptImage error: ${e.message}")
        }
    }

    /**
     * Deletes Firestore photos older than [maxAgeDays] to stay inside the free
     * quota. The receipt itself (store, items, amounts) is kept.
     * Returns how many photos were removed.
     */
    suspend fun cleanupOldReceiptImages(userId: String, maxAgeDays: Int = 90): Int {
        return try {
            val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 60 * 60 * 1000
            val old = getReceipts(userId).filter { it.cloudImage && it.createdAt < cutoff }
            for (r in old) {
                deleteReceiptImage(r.id)
                firestore.collection("receipts").document(r.id)
                    .update("cloudImage", false).await()
            }
            old.size
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "cleanupOldReceiptImages error: ${e.message}")
            0
        }
    }

    suspend fun getReceipts(userId: String): List<Receipt> {
        return try {
            val snapshot = firestore.collection("receipts")
                .whereEqualTo("userId", userId).get().await()
            snapshot.documents.mapNotNull { doc ->
                doc.toObject(Receipt::class.java)?.copy(id = doc.id)
            }.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "Error getting receipts: ${e.message}")
            emptyList()
        }
    }

    suspend fun getReceiptsForCurrentMonth(userId: String): List<Receipt> {
        val allReceipts = getReceipts(userId)
        val formatter = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val currentMonth = formatter.format(Date())
        return allReceipts.filter {
            try {
                formatter.format(it.date.toDate()) == currentMonth
            } catch (e: Exception) { false }
        }
    }

    /**
     * ✅ Get ALL receipts for the history calendar.
     */
    suspend fun getAllReceipts(userId: String): List<Receipt> {
        return try {
            val snapshot = firestore.collection("receipts")
                .whereEqualTo("userId", userId)
                .get()
                .await()

            // Sort descending (newest first)
            snapshot.documents.mapNotNull { doc ->
                doc.toObject(Receipt::class.java)?.copy(id = doc.id)
            }.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "Error getting all receipts: ${e.message}")
            emptyList()
        }
    }

    suspend fun deleteReceipt(receipt: Receipt) {
        try {
            val share = receipt.chargeShare()
            for (item in receipt.items) {
                val month = getCurrentMonth()
                val snap = firestore.collection("budgets")
                    .whereEqualTo("userId", receipt.userId)
                    .whereEqualTo("category", item.category)
                    .whereEqualTo("month", month)
                    .get().await()
                if (snap.documents.isNotEmpty()) {
                    val doc = snap.documents.first()
                    val cur = doc.getDouble("spent") ?: 0.0
                    firestore.collection("budgets").document(doc.id)
                        .update("spent", (cur - item.price * share).coerceAtLeast(0.0)).await()
                }
            }
            firestore.collection("receipts").document(receipt.id).delete().await()
            if (receipt.cloudImage) deleteReceiptImage(receipt.id)
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "deleteReceipt error: ${e.message}")
        }
    }

    suspend fun renameReceipt(receiptId: String, newName: String) {
        try {
            firestore.collection("receipts").document(receiptId)
                .update("storeName", newName).await()
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "renameReceipt error: ${e.message}")
        }
    }

    suspend fun updateItemCategory(receipt: Receipt, itemIndex: Int, newCategory: String) {
        try {
            val oldItem = receipt.items[itemIndex]
            val newItem = oldItem.copy(category = newCategory, isManuallyCorrected = true)
            val updatedItems = receipt.items.toMutableList()
            updatedItems[itemIndex] = newItem

            firestore.collection("receipts").document(receipt.id)
                .update("items", updatedItems).await()

            val month = getCurrentMonth()
            suspend fun adjust(cat: String, delta: Double) {
                val snap = firestore.collection("budgets")
                    .whereEqualTo("userId", receipt.userId)
                    .whereEqualTo("category", cat)
                    .whereEqualTo("month", month)
                    .get().await()
                if (snap.documents.isNotEmpty()) {
                    val doc = snap.documents.first()
                    val cur = doc.getDouble("spent") ?: 0.0
                    firestore.collection("budgets").document(doc.id)
                        .update("spent", (cur + delta).coerceAtLeast(0.0)).await()
                }
            }
            val moved = oldItem.price * receipt.chargeShare()
            adjust(oldItem.category, -moved)
            adjust(newCategory, moved)
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "updateItemCategory error: ${e.message}")
        }
    }

    // ==================== SHARED LEARNING (KEYWORDS) ====================

    suspend fun saveLearnedKeyword(
        userId: String,
        original: String,
        correctedName: String? = null,
        correctedCategory: String? = null
    ) {
        try {
            val normalized = original.lowercase().trim()
            if (normalized.isEmpty()) return

            // ✅ SHARED: no userId filter — same correction aggregates globally
            val existing = firestore.collection("learned_keywords")
                .whereEqualTo("original", normalized)
                .get().await()

            if (existing.documents.isNotEmpty()) {
                val doc = existing.documents.first()
                val currentCount = doc.getLong("count") ?: 0L
                val updates = mutableMapOf<String, Any>(
                    "count" to (currentCount + 1),
                    "lastUsed" to System.currentTimeMillis()
                )
                if (!correctedName.isNullOrEmpty()) updates["correctedName"] = correctedName
                if (!correctedCategory.isNullOrEmpty()) updates["correctedCategory"] = correctedCategory

                firestore.collection("learned_keywords").document(doc.id)
                    .update(updates).await()
            } else {
                val learned = LearnedKeyword(
                    userId = userId,
                    original = normalized,
                    correctedName = correctedName ?: "",
                    correctedCategory = correctedCategory ?: "",
                    count = 1,
                    lastUsed = System.currentTimeMillis()
                )
                firestore.collection("learned_keywords").document().set(learned).await()
            }
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "saveLearnedKeyword error: ${e.message}")
        }
    }

    suspend fun getLearnedKeywords(userId: String): List<LearnedKeyword> {
        return try {
            // ✅ SHARED: load all learned keywords from all users
            val snapshot = firestore.collection("learned_keywords")
                .get().await()
            snapshot.documents.mapNotNull { doc ->
                doc.toObject(LearnedKeyword::class.java)?.copy(id = doc.id)
            }
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "getLearnedKeywords error: ${e.message}")
            emptyList()
        }
    }

    // ==================== SHARED LEARNING (AVOID PATTERNS) ====================

    suspend fun saveAvoidKeyword(pattern: String) {
        try {
            val normalized = pattern.lowercase().trim()
            if (normalized.isEmpty()) return

            // 🔒 SAFETY: Don't save if pattern contains a product keyword
            val productKeywords = listOf(
                "rice", "bowl", "noodle", "bread", "cake", "muffin", "cookie",
                "pizza", "burger", "chicken", "beef", "fish", "soup", "salad",
                "lemonade", "tea", "coffee", "juice", "water", "drink", "soda",
                "milk", "matcha", "latte", "chocolate", "egg", "sugar", "salt",
                "flour", "oil", "sauce", "cereal", "yogurt", "cheese", "butter",
                "shampoo", "soap", "toothpaste", "medicine", "pill", "vitamin"
            )
            if (productKeywords.any { normalized.contains(it) }) {
                Log.d("AvoidKeyword", "Skipped (contains product keyword): $normalized")
                return
            }

            val existing = firestore.collection("learned_avoid")
                .whereEqualTo("pattern", normalized)
                .get().await()

            if (existing.documents.isNotEmpty()) {
                val doc = existing.documents.first()
                val currentCount = doc.getLong("count") ?: 0L
                firestore.collection("learned_avoid").document(doc.id)
                    .update(
                        "count", currentCount + 1,
                        "lastUsed", System.currentTimeMillis()
                    ).await()
                Log.d("AvoidKeyword", "Incremented '${normalized}' to count ${currentCount + 1}")
            } else {
                val data = hashMapOf(
                    "pattern" to normalized,
                    "count" to 1L,
                    "lastUsed" to System.currentTimeMillis()
                )
                firestore.collection("learned_avoid").document().set(data).await()
                Log.d("AvoidKeyword", "Created '${normalized}' with count 1")
            }
        } catch (e: Exception) {
            Log.e("AvoidKeyword", "saveAvoidKeyword error: ${e.message}")
        }
    }

    suspend fun getAvoidKeywords(threshold: Long = 2L): List<String> {
        return try {
            val snapshot = firestore.collection("learned_avoid")
                .whereGreaterThanOrEqualTo("count", threshold)
                .get().await()
            snapshot.documents.mapNotNull { it.getString("pattern") }
        } catch (e: Exception) {
            Log.e("AvoidKeyword", "getAvoidKeywords error: ${e.message}")
            emptyList()
        }
    }

    // ==================== FULL RESET ====================

    suspend fun resetCurrentMonthFull(userId: String): Boolean {
        return try {
            val month = getCurrentMonth()

            val allReceipts = getReceipts(userId)
            val formatter = SimpleDateFormat("yyyy-MM", Locale.getDefault())
            for (r in allReceipts) {
                val rMonth = try {
                    formatter.format(r.date.toDate())
                } catch (e: Exception) { "" }
                if (rMonth == month) {
                    firestore.collection("receipts").document(r.id).delete().await()
                    if (r.cloudImage) deleteReceiptImage(r.id)
                }
            }

            val budgetDocs = firestore.collection("budgets")
                .whereEqualTo("userId", userId)
                .whereEqualTo("month", month)
                .get().await()
            for (doc in budgetDocs.documents) {
                firestore.collection("budgets").document(doc.id).delete().await()
            }

            firestore.collection("users").document(userId)
                .update(
                    "overallBudget", 0.0,
                    "overallBudgetMonth", ""
                ).await()

            Log.d("FirebaseRepo", "✅ Full reset done for $month")
            true
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "resetCurrentMonthFull error: ${e.message}")
            false
        }
    }

    // ==================== HELPERS ====================

    private fun getCurrentMonth(): String {
        return SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
    }

    fun getPreviousMonth(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -1)
        return SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(cal.time)
    }

    private fun getCategoryColor(category: String): Int {
        val colors = mapOf(
            "Groceries" to android.graphics.Color.parseColor("#4CAF50"),
            "Personal Care" to android.graphics.Color.parseColor("#9C27B0"),
            "Transportation" to android.graphics.Color.parseColor("#2196F3"),
            "Entertainment" to android.graphics.Color.parseColor("#FF5722"),
            "Food & Dining" to android.graphics.Color.parseColor("#FF9800"),
            "Education" to android.graphics.Color.parseColor("#3F51B5"),
            "Shopping" to android.graphics.Color.parseColor("#E91E63"),
            "Health" to android.graphics.Color.parseColor("#00BCD4"),
            "Utilities" to android.graphics.Color.parseColor("#795548"),
            "Other" to android.graphics.Color.parseColor("#9E9E9E")
        )
        return colors[category] ?: android.graphics.Color.parseColor("#9E9E9E")
    }
}