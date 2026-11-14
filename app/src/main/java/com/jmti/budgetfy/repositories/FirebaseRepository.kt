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
        /** How long to wait for Firestore before using the phone's copy (offline). */
        private const val QUICK_MS = 1500L
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

    /**
     * @param preferCache use the phone's copy if it has one (instant, works
     *   offline). Used while saving a receipt; screens ask the server so a
     *   change made on another phone shows up.
     */
    suspend fun getOverallBudget(userId: String, preferCache: Boolean = false, cacheOnly: Boolean = false): Double {
        return try {
            val ref = firestore.collection("users").document(userId)
            if (cacheOnly) {
                val doc = ref.get(com.google.firebase.firestore.Source.CACHE).await()
                val amount = doc.getDouble("overallBudget") ?: 0.0
                return if (doc.getString("overallBudgetMonth") == getCurrentMonth()) amount else 0.0
            }
            val cached = if (preferCache) {
                runCatching { ref.get(com.google.firebase.firestore.Source.CACHE).await() }.getOrNull()
            } else null
            val doc = if (cached != null && cached.exists()) cached else ref.get().await()
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

    /** [source] CACHE reads the phone's copy instantly (Home shows it before the server answers). */
    suspend fun getBudgets(userId: String, source: com.google.firebase.firestore.Source = com.google.firebase.firestore.Source.DEFAULT): List<BudgetCategory> {
        val month = getCurrentMonth()
        val snapshot = firestore.collection("budgets")
            .whereEqualTo("userId", userId)
            .whereEqualTo("month", month)
            .get(source).await()

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

    /**
     * Adds [amount] to this month's budget for [category], creating the budget
     * if there is none. Works offline: the change is kept on the phone and
     * sent to Firestore when the connection is back.
     */
    suspend fun updateBudgetSpent(userId: String, category: String, amount: Double) {
        val month = getCurrentMonth()
        val query = firestore.collection("budgets")
            .whereEqualTo("userId", userId)
            .whereEqualTo("category", category)
            .whereEqualTo("month", month)
        val snapshot = quickGet(query)

        if (snapshot != null && snapshot.documents.isNotEmpty()) {
            // Increment (not read-then-write), so receipts saved offline all add up after syncing.
            awaitBriefly(
                firestore.collection("budgets").document(snapshot.documents.first().id)
                    .update("spent", com.google.firebase.firestore.FieldValue.increment(amount))
            )
        } else {
            val overall = kotlinx.coroutines.withTimeoutOrNull(QUICK_MS) { getOverallBudget(userId, preferCache = true) } ?: 0.0
            val defaultLimits = categorizationEngine.getDefaultBudgetLimits()
            val desiredLimit = defaultLimits[category] ?: 100.0
            val actualLimit = if (overall > 0) minOf(desiredLimit, overall) else desiredLimit

            val budget = Budget(
                userId = userId, category = category,
                limit = actualLimit, spent = amount, month = month
            )
            awaitBriefly(firestore.collection("budgets").document().set(budget))
        }
    }

    /**
     * A query that doesn't hang offline: the copy Firestore keeps on the
     * phone first (Home has already loaded it), the server only if the phone
     * has nothing, and then for at most [QUICK_MS].
     */
    private suspend fun quickGet(query: com.google.firebase.firestore.Query): com.google.firebase.firestore.QuerySnapshot? {
        val cached = runCatching { query.get(com.google.firebase.firestore.Source.CACHE).await() }.getOrNull()
        if (cached != null && !cached.isEmpty) return cached
        return kotlinx.coroutines.withTimeoutOrNull(QUICK_MS) { runCatching { query.get().await() }.getOrNull() }
            ?: cached
    }

    /**
     * Firestore saves a write on the phone straight away and only reports
     * "done" once the server confirms, which offline never happens. So don't
     * wait: the write is sent in the background (even after the app closes,
     * on its next start). Failures, e.g. a security rule, are logged.
     */
    private fun awaitBriefly(task: com.google.android.gms.tasks.Task<*>) {
        task.addOnFailureListener { Log.e("FirebaseRepo", "Write failed: ${it.message}") }
    }

    // ==================== RECEIPTS ====================

    /**
     * Human-readable fields on a receipt photo, so the receipt_images list in
     * the Firebase console says whose receipt it is and from where/when.
     * The app itself only reads "image", "userId" and "createdAt".
     */
    private fun photoLabels(receipt: Receipt, ownerName: String?, savedAtMs: Long): Map<String, Any> {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return buildMap {
            put("storeName", receipt.storeName)
            put("totalAmount", receipt.totalAmount)
            put("receiptDate", fmt.format(receipt.date.toDate()))
            putAll(ownerLabels(ownerName, savedAtMs))
        }
    }

    /** Who saved it and when, readable: "Kotohaaa", email, "2026-11-13 20:42". */
    private fun ownerLabels(ownerName: String?, savedAtMs: Long): Map<String, Any> = buildMap {
        put("savedAt", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(savedAtMs)))
        ownerName?.takeIf { it.isNotBlank() }?.let { put("displayName", it) }
        auth.currentUser?.email?.let { put("email", it) }
    }

    /**
     * One-time tidy of receipts saved before: adds the readable labels and
     * removes fields that were always empty ("id", "imageUrl", items' "id").
     * @return how many were tidied, or -1 if any failed (tried again later).
     */
    suspend fun tidyOldReceipts(userId: String, ownerName: String?): Int {
        var count = 0
        var failed = false
        for (r in getReceipts(userId)) {
            try {
                firestore.collection("receipts").document(r.id).update(
                    ownerLabels(ownerName, r.createdAt) + mapOf(
                        "id" to com.google.firebase.firestore.FieldValue.delete(),
                        "imageUrl" to com.google.firebase.firestore.FieldValue.delete(),
                        "items" to r.items // rewritten without the empty "id"
                    )
                ).await()
                count++
            } catch (e: Exception) {
                failed = true
                Log.e("FirebaseRepo", "tidyOldReceipts ${r.id}: ${e.message}")
            }
        }
        return if (failed) -1 else count
    }

    /**
     * Adds the readable fields to photos saved before they existed. Runs once
     * per account (the caller remembers it); returns how many were labelled.
     */
    /** @return how many photos were labelled, or -1 if any failed (so it's tried again later). */
    suspend fun labelOldReceiptImages(userId: String, ownerName: String?): Int {
        var count = 0
        var failed = false
        for (r in getReceipts(userId).filter { it.cloudImage }) {
            try {
                firestore.collection(RECEIPT_IMAGES).document(r.id)
                    .update(photoLabels(r, ownerName, r.createdAt)).await()
                count++
            } catch (e: Exception) {
                failed = true
                Log.e("FirebaseRepo", "labelOldReceiptImages ${r.id}: ${e.message}")
            }
        }
        return if (failed) -1 else count
    }

    /**
     * Saves the receipt. When [imageJpeg] is given, the photo is stored in
     * Firestore too (receipt_images/{receiptId}) so it survives uninstalling
     * the app. If the photo upload fails, the receipt is still saved.
     */
    suspend fun saveReceipt(
        userId: String, receipt: Receipt, imageJpeg: ByteArray? = null, ownerName: String? = null
    ) {
        val ref = firestore.collection("receipts").document()
        var toSave = receipt
        if (imageJpeg != null) {
            try {
                // Queued on the phone and uploaded in the background (also offline).
                awaitBriefly(
                    firestore.collection(RECEIPT_IMAGES).document(ref.id).set(
                        mapOf(
                            "userId" to userId,
                            "image" to Blob.fromBytes(imageJpeg),
                            "createdAt" to System.currentTimeMillis()
                        ) + photoLabels(receipt, ownerName, System.currentTimeMillis())
                    )
                )
                toSave = receipt.copy(cloudImage = true)
            } catch (e: Exception) {
                Log.e("FirebaseRepo", "Receipt photo upload failed: ${e.message}")
            }
        }
        awaitBriefly(ref.set(toSave))
        // Readable labels for the Firebase console (not part of the app's model).
        awaitBriefly(ref.set(ownerLabels(ownerName, System.currentTimeMillis()), com.google.firebase.firestore.SetOptions.merge()))
        // One budget update per category, not per item.
        val share = receipt.chargeShare()
        receipt.items.groupBy { it.category }.forEach { (category, items) ->
            updateBudgetSpent(userId, category, items.sumOf { it.price } * share)
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

    suspend fun getReceipts(userId: String, source: com.google.firebase.firestore.Source = com.google.firebase.firestore.Source.DEFAULT): List<Receipt> {
        return try {
            val snapshot = firestore.collection("receipts")
                .whereEqualTo("userId", userId).get(source).await()
            snapshot.documents.mapNotNull { doc ->
                doc.toObject(Receipt::class.java)?.copy(id = doc.id)
            }.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "Error getting receipts: ${e.message}")
            emptyList()
        }
    }

    suspend fun getReceiptsForCurrentMonth(userId: String, source: com.google.firebase.firestore.Source = com.google.firebase.firestore.Source.DEFAULT): List<Receipt> {
        val allReceipts = getReceipts(userId, source)
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