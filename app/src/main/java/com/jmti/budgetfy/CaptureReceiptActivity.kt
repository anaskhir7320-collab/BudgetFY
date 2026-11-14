package com.jmti.budgetfy

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.jmti.budgetfy.adapters.ReceiptItemAdapter
import com.jmti.budgetfy.models.LearnedKeyword
import com.jmti.budgetfy.models.Receipt
import com.jmti.budgetfy.models.ReceiptCharge
import com.jmti.budgetfy.models.ReceiptItem
import com.jmti.budgetfy.repositories.FirebaseRepository
import com.jmti.budgetfy.utils.CategorizationEngine
import com.jmti.budgetfy.utils.DialogForm
import com.jmti.budgetfy.utils.GeminiReceiptExtractor
import com.jmti.budgetfy.utils.OfflineReceiptReader
import com.jmti.budgetfy.utils.ImageCompress
import com.jmti.budgetfy.utils.ImageLoader
import com.jmti.budgetfy.utils.OCRHelper
import com.jmti.budgetfy.utils.ReceiptParser
import com.jmti.budgetfy.utils.ReceiptValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CaptureReceiptActivity : AppCompatActivity() {

    companion object {
        /** Longest wait for Gemini before showing the offline reading. Normal replies take 3-6 s. */
        private const val GEMINI_LIMIT_MS = 15_000L
    }

    private lateinit var imageView: ShapeableImageView
    private lateinit var recyclerView: RecyclerView
    private lateinit var captureButton: MaterialButton
    private lateinit var galleryButton: MaterialButton
    private lateinit var processButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var addItemButton: MaterialButton
    private lateinit var progressBar: android.widget.ProgressBar

    private lateinit var storeNameTextView: android.widget.TextView
    private lateinit var storeEditIcon: android.widget.TextView
    private var currentStoreName: String = "Receipt"
    /** Tax/charge lines and printed total from the last AI read. */
    private var currentCharges: List<ReceiptCharge> = emptyList()
    private var currentPrintedTotal: Double? = null

    private val firebaseRepo by lazy { FirebaseRepository() }
    private val ocrHelper = OCRHelper()
    private val receiptParser = ReceiptParser()
    private val categorizationEngine = CategorizationEngine()
    private val geminiExtractor = GeminiReceiptExtractor()

    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var analysisJob: Deferred<Analysis>? = null

    // Same scanner Receiptly uses: finds the receipt edges, crops, flattens
    // perspective and cleans shadows before OCR ever sees the image.
    private val documentScanner by lazy {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setGalleryImportAllowed(true)
                .setPageLimit(1)
                .build()
        )
    }

    private var capturedBitmap: Bitmap? = null
    private val extractedItems = mutableListOf<ReceiptItem>()
    private lateinit var itemAdapter: ReceiptItemAdapter

    private var photoUri: Uri? = null
    private var savedImagePath: String = ""
    private var learnedKeywords: List<LearnedKeyword> = emptyList()
    private var avoidPatterns: List<String> = emptyList()

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openCamera()
        else Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
    }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pageUri = scan?.pages?.firstOrNull()?.imageUri
            if (pageUri != null) loadReceiptImage(pageUri, getString(R.string.receipt_scanned_tap_read))
        }
    }

    // Fallback when the scanner is unavailable (no Google Play services).
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) photoUri?.let { loadReceiptImage(it, getString(R.string.photo_captured_tap_read)) }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadReceiptImage(it, getString(R.string.image_loaded_tap_read)) }
    }

    private fun loadReceiptImage(uri: Uri, message: String) {
        val started = System.currentTimeMillis()
        progressBar.visibility = View.VISIBLE
        // Decoding a full-size photo takes a moment: do it off the main thread
        // so the screen doesn't freeze after the scanner closes.
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // EXIF-aware: phone photos otherwise reach OCR rotated sideways.
                val bitmap = withContext(Dispatchers.IO) { ImageLoader.load(this@CaptureReceiptActivity, uri) }
                progressBar.visibility = View.GONE
                if (bitmap == null) {
                    Toast.makeText(this@CaptureReceiptActivity, getString(R.string.could_not_read_image), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                Log.d("CAPTURE", "Image loaded in ${System.currentTimeMillis() - started} ms")
                capturedBitmap = bitmap
                // Placeholder uses fitCenter with room for its caption; real photos fill the frame.
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                val edge = (1f * resources.displayMetrics.density).toInt()
                imageView.setPadding(edge, edge, edge, edge)
                findViewById<View>(R.id.noReceiptText).visibility = View.GONE
                // New photo: the last reading's status no longer applies.
                findViewById<View>(R.id.readStatusText).visibility = View.GONE
                findViewById<View>(R.id.readWarningText).visibility = View.GONE
                imageView.setImageBitmap(bitmap)
                processButton.isEnabled = true
                startAnalysis(bitmap)
                Toast.makeText(this@CaptureReceiptActivity, message, Toast.LENGTH_SHORT).show()
                // Keep a copy for the receipt page; the user doesn't wait for this.
                savedImagePath = withContext(Dispatchers.IO) { saveImageToInternal(bitmap) }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                Toast.makeText(this@CaptureReceiptActivity, getString(R.string.error_with_message, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_receipt)
        initViews()
        setupListeners()
        loadLearnedKeywords()
    }

    private fun initViews() {
        imageView = findViewById(R.id.receiptImageView)
        recyclerView = findViewById(R.id.itemsRecyclerView)
        captureButton = findViewById(R.id.captureButton)
        galleryButton = findViewById(R.id.galleryButton)
        processButton = findViewById(R.id.processButton)
        saveButton = findViewById(R.id.saveButton)
        addItemButton = findViewById(R.id.addItemButton)
        progressBar = findViewById(R.id.progressBar)
        storeNameTextView = findViewById(R.id.storeNameTextView)
        // Default name in the app language until the receipt is read.
        currentStoreName = getString(R.string.receipt_default)
        storeNameTextView.text = currentStoreName
        storeEditIcon = findViewById(R.id.storeEditIcon)

        itemAdapter = ReceiptItemAdapter(
            onItemChanged = { oldItem, newItem ->
                val idx = extractedItems.indexOfFirst {
                    it.name == oldItem.name && it.price == oldItem.price
                }
                if (idx != -1) {
                    extractedItems[idx] = newItem
                    saveLearnedCorrection(oldItem, newItem)
                } else {
                    val idx2 = extractedItems.indexOfFirst { it.name == oldItem.name }
                    if (idx2 != -1) {
                        extractedItems[idx2] = newItem
                        saveLearnedCorrection(oldItem, newItem)
                    }
                }
                itemAdapter.updateItems(extractedItems.toList())
                refreshTotals()
            },
            onItemsReplaced = { newItems ->
                // Add-on added/removed, or an item turned into an add-on.
                extractedItems.clear()
                extractedItems.addAll(newItems)
                itemAdapter.updateItems(extractedItems.toList())
                refreshTotals()
            },
            onItemDeleted = { item ->
                val idx = extractedItems.indexOfFirst {
                    it.name == item.name && it.price == item.price
                }
                if (idx != -1) {
                    extractedItems.removeAt(idx)
                    itemAdapter.updateItems(extractedItems.toList())
                    refreshTotals()
                    saveButton.isEnabled = extractedItems.isNotEmpty()

                    // ✅ Save avoid pattern to shared Firestore
                    CoroutineScope(Dispatchers.IO).launch {
                        firebaseRepo.saveAvoidKeyword(item.name)
                        avoidPatterns = firebaseRepo.getAvoidKeywords(threshold = 2L)
                        Log.d("CAPTURE", "Avoid patterns now: ${avoidPatterns.size}")
                    }

                    Toast.makeText(
                        this,
                        getString(R.string.removed_item, item.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = itemAdapter

        processButton.isEnabled = false
        saveButton.isEnabled = false
    }

    private fun setupListeners() {
        captureButton.setOnClickListener { startDocumentScanner() }
        galleryButton.setOnClickListener { galleryLauncher.launch("image/*") }
        processButton.setOnClickListener { processReceipt() }
        saveButton.setOnClickListener { saveReceipt() }
        addItemButton.setOnClickListener { showAddItemDialog() }
        storeEditIcon.setOnClickListener { showEditStoreDialog() }
        storeNameTextView.setOnClickListener { showEditStoreDialog() }
    }

    private fun loadLearnedKeywords() {
        val userId = firebaseRepo.getCurrentUser()?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            learnedKeywords = firebaseRepo.getLearnedKeywords(userId)
            avoidPatterns = firebaseRepo.getAvoidKeywords(threshold = 2L)
            Log.d("CAPTURE", "Loaded ${learnedKeywords.size} keywords, ${avoidPatterns.size} avoid patterns")
        }
    }

    private fun saveLearnedCorrection(oldItem: ReceiptItem, newItem: ReceiptItem) {
        val userId = firebaseRepo.getCurrentUser()?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val oldName = oldItem.name.lowercase().trim()

            if (oldItem.name != newItem.name && oldName.isNotEmpty()) {
                firebaseRepo.saveLearnedKeyword(
                    userId = userId,
                    original = oldName,
                    correctedName = newItem.name
                )
            }

            if (oldItem.category != newItem.category && oldName.isNotEmpty()) {
                firebaseRepo.saveLearnedKeyword(
                    userId = userId,
                    original = oldName,
                    correctedCategory = newItem.category
                )
            }

            learnedKeywords = firebaseRepo.getLearnedKeywords(userId)
        }
    }

    private fun showEditStoreDialog() {
        val form = DialogForm(this)
        val input = form.field(
            getString(R.string.store_name), currentStoreName,
            helper = getString(R.string.store_name_helper)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.store_name))
            .setView(form.view)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, getString(R.string.name_cannot_be_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val oldName = currentStoreName
                currentStoreName = newName
                storeNameTextView.text = newName
                // Learn "Thong EMPIRE -> Thong Bowl", but not from the default
                // "Receipt", which would rename every receipt without a name.
                if (oldName != newName && !categorizationEngine.isDefaultStoreName(oldName)) {
                    saveStoreCorrection(oldName, newName)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun saveStoreCorrection(oldName: String, newName: String) {
        val userId = firebaseRepo.getCurrentUser()?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            firebaseRepo.saveLearnedKeyword(
                userId = userId,
                original = "__store__:$oldName".lowercase(),
                correctedName = newName
            )
            learnedKeywords = firebaseRepo.getLearnedKeywords(userId)
        }
    }

    private fun startDocumentScanner() {
        documentScanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                Log.w("CAPTURE", "Document scanner unavailable, using camera: ${e.message}")
                checkCameraPermission()
            }
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> openCamera()
            else -> cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        try {
            val file = File(cacheDir, "receipt_${System.currentTimeMillis()}.jpg")
            photoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            photoUri?.let { cameraLauncher.launch(it) }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.camera_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImageToInternal(bitmap: Bitmap): String {
        return try {
            val file = File(filesDir, "receipt_${System.currentTimeMillis()}.jpg")
            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush()
            out.close()
            file.absolutePath
        } catch (e: Exception) { "" }
    }

    /** OCR + Gemini result for one image, computed once and reused by Process. */
    private class Analysis(
        val ocr: OCRHelper.OcrOutput,
        val ai: GeminiReceiptExtractor.Result?,
        val aiError: String?,
        /** True when the fast read didn't add up and the accurate model re-read it. */
        val rechecked: Boolean = false,
        /** Second OCR reading on an enhanced copy, made in advance when Gemini failed. */
        val enhancedOcr: OCRHelper.OcrOutput? = null,
        /** How long this background reading took, and when it finished. */
        val durationMs: Long = 0L,
        val finishedAt: Long = System.currentTimeMillis()
    )

    /**
     * Starts reading the receipt in the background as soon as an image is
     * loaded, so most of the work is already done when the user taps Process.
     */
    private fun startAnalysis(bitmap: Bitmap) {
        analysisJob?.cancel()
        analysisJob = workScope.async { analyzeReceipt(bitmap) }
    }

    private suspend fun analyzeReceipt(bitmap: Bitmap): Analysis {
        val started = System.currentTimeMillis()
        val ocr = ocrHelper.recognize(bitmap)
        Log.d("CAPTURE", "OCR took ${System.currentTimeMillis() - started} ms")

        // 1) Gemini reads the image (OCR text as a hint).
        // 2) If there's no key, no network or no items, Process falls back to the on-device parser.
        if (!geminiExtractor.isConfigured) {
            val enhanced = ocrHelper.recognizeEnhanced(bitmap)
            return Analysis(ocr, null, null, enhancedOcr = enhanced,
                durationMs = System.currentTimeMillis() - started)
        }
        // A stuck connection can leave Gemini silent for ~2 minutes. Stop
        // waiting after GEMINI_LIMIT_MS and use the offline reader instead.
        val outcome = kotlinx.coroutines.withTimeoutOrNull(GEMINI_LIMIT_MS) {
            kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                geminiExtractor.extract(bitmap, ocr.layoutText, categorizationEngine.getCategories())
            }
        } ?: GeminiReceiptExtractor.Outcome(null, "Gemini took longer than ${GEMINI_LIMIT_MS / 1000} s")
        Log.d("CAPTURE", "Analysis took ${System.currentTimeMillis() - started} ms")
        val error = when {
            outcome.result == null -> outcome.error
            outcome.result.items.isEmpty() -> "no items found"
            else -> null
        }
        // Gemini didn't help (offline, quota, error): prepare the offline
        // reader's second reading now, often before the user taps Read.
        val enhanced = if (error != null) ocrHelper.recognizeEnhanced(bitmap) else null
        return Analysis(ocr, outcome.result, error, outcome.rechecked, enhanced,
            durationMs = System.currentTimeMillis() - started)
    }

    override fun onDestroy() {
        workScope.cancel()
        super.onDestroy()
    }

    private fun processReceipt() {
        val bitmap = capturedBitmap ?: return
        val readTapped = System.currentTimeMillis()
        processButton.isEnabled = false
        progressBar.visibility = View.VISIBLE

        // Usually already running (started when the image loaded).
        val job = analysisJob?.takeUnless { it.isCancelled }
            ?: workScope.async { analyzeReceipt(bitmap) }.also { analysisJob = it }

        workScope.launch {
            try {
                val analysis = job.await()
                val ocr = analysis.ocr
                val ai = analysis.ai
                val categories = categorizationEngine.getCategories()

                val usedAi = ai != null && ai.items.isNotEmpty()

                // Avoid patterns only matter for the on-device parser, so the
                // Firestore round trip is skipped when Gemini succeeded.
                // The avoid words were loaded when the screen opened; use that
                // copy straight away (offline, Firestore can take seconds to
                // give up) and refresh it quietly for the next receipt.
                val freshAvoids = avoidPatterns
                if (!usedAi) workScope.launch {
                    kotlinx.coroutines.withTimeoutOrNull(10_000) {
                        firebaseRepo.getAvoidKeywords(threshold = 2L)
                    }?.let { avoidPatterns = it }
                }
                var rawItems: List<ReceiptItem>
                val detectedStore: String
                val receiptTotal: Double?
                val extras: Double
                var offlineCharges: List<ReceiptCharge> = emptyList()
                if (usedAi) {
                    rawItems = ai!!.items.map {
                        ReceiptItem(
                            name = it.name,
                            quantity = it.quantity.toString(),
                            price = it.lineTotal,
                            category = it.category ?: "Other",
                            addOns = it.addOns.map { a -> com.jmti.budgetfy.models.AddOn(a.name, a.price) }
                        )
                    }
                    // Lines marked ":" / "+" on the receipt are add-ons, even when priced.
                    rawItems = com.jmti.budgetfy.utils.AddOnMerger.attachMarkedAddOns(rawItems, ocr.layoutText)
                    detectedStore = ai.storeName ?: receiptParser.detectStoreName(ocr.text)
                    receiptTotal = ai.total
                    extras = ai.extras
                } else {
                    // Offline: read the receipt again on an enhanced copy, parse
                    // both readings row by row (name on the left, price on the
                    // right), keep the one that matches the printed total, then
                    // let the total fix a single misread price. The old
                    // block-based parser is only a last resort.
                    val enhancedOcr = analysis.enhancedOcr ?: ocrHelper.recognizeEnhanced(bitmap)
                    val passes = listOf(ocr, enhancedOcr).filter { it.text.isNotBlank() }.ifEmpty { listOf(ocr) }
                    val rowParser = com.jmti.budgetfy.utils.RowReceiptParser(receiptParser)
                    val best = OfflineReceiptReader.pickBest(
                        passes.map { OfflineReceiptReader.rowReading(it, rowParser) } +
                                passes.map { OfflineReceiptReader.Reading(it, receiptParser.parseReceipt(it.text, freshAvoids)) }
                    )
                    rawItems = OfflineReceiptReader.repairToTotal(best.result)
                    detectedStore = receiptParser.detectStoreName(best.ocr.text)
                    receiptTotal = best.result.receiptTotal.takeIf { best.result.totalFound }
                    extras = best.result.extras
                    offlineCharges = best.result.charges
                }

                // Learned corrections always win; Gemini's category fills in
                // where the keyword rules only know "Other".
                val correctedItems = rawItems.map { item ->
                    val fixedName = categorizationEngine.applyLearnedName(item.name, learnedKeywords)
                    val ruleCategory = categorizationEngine.categorizeItem(fixedName, learnedKeywords)
                    val category = if (ruleCategory == "Other" && item.category in categories) {
                        item.category
                    } else ruleCategory
                    item.copy(name = fixedName, category = category)
                }

                val check = ReceiptValidator.check(correctedItems, receiptTotal, extras)
                val aiError = analysis.aiError

                withContext(Dispatchers.Main) {
                    currentCharges = if (usedAi) {
                        val read = ai!!.charges.map {
                            ReceiptCharge(type = it.type, rate = it.rate, amount = it.amount, inclusive = it.inclusive)
                        }
                        // Whole-bill discount ("BUY 3 FOR RM 18 -RM2.94"): a minus
                        // line, so what was paid = items + charges - discount.
                        val discount = ai.discount?.takeIf { it > 0.0 }
                        if (discount != null) read + ReceiptCharge(type = "Discount", amount = -discount) else read
                    } else offlineCharges
                    currentPrintedTotal = receiptTotal

                    extractedItems.clear()
                    extractedItems.addAll(correctedItems)
                    itemAdapter.updateItems(extractedItems.toList())
                    refreshTotals()
                    Log.d("CAPTURE", "Read tapped -> items shown in ${System.currentTimeMillis() - readTapped} ms" +
                            if (usedAi) " (Gemini)" else " (offline)")

                    val learnedStore = categorizationEngine.applyLearnedStoreName(detectedStore, learnedKeywords)
                    currentStoreName = learnedStore
                    storeNameTextView.text = learnedStore

                    saveButton.isEnabled = extractedItems.isNotEmpty()
                    progressBar.visibility = View.GONE
                    processButton.isEnabled = true

                    if (aiError != null) {
                        Log.w("CAPTURE", "AI reading failed: $aiError")
                    }

                    // Status line under "Items" instead of a pop-up:
                    // "Found 2 items. Read in 1.1 s" (+ " · offline mode").
                    // Time = the reading work itself (from the photo appearing),
                    // not the time spent before tapping Read.
                    val now = System.currentTimeMillis()
                    val workMs = analysis.durationMs + (now - maxOf(readTapped, analysis.finishedAt))
                    val count = extractedItems.size
                    val status = findViewById<TextView>(R.id.readStatusText)
                    val warning = findViewById<TextView>(R.id.readWarningText)
                    status.visibility = View.VISIBLE
                    status.text = if (count == 0) {
                        getString(R.string.no_items_found_add)
                    } else {
                        val found = if (count == 1) getString(R.string.one_item) else getString(R.string.n_items, count)
                        getString(R.string.read_status, found, String.format("%.1f", workMs / 1000.0)) +
                                if (usedAi) "" else getString(R.string.read_status_offline)
                    }
                    if (count > 0 && check.matches == false) {
                        warning.visibility = View.VISIBLE
                        warning.text = getString(
                            R.string.read_warning_mismatch,
                            "%.2f".format(check.itemsSum + extras), "%.2f".format(check.receiptTotal)
                        )
                    } else {
                        warning.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    processButton.isEnabled = true
                    Toast.makeText(this@CaptureReceiptActivity,
                        getString(R.string.error_with_message, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * What the receipt actually cost: items plus any tax or charge added on
     * top. The printed total is used when it agrees with that (it then also
     * carries the 5-sen rounding); if the items were edited or the total was
     * misread, items + added charges is used instead.
     */
    private fun amountPaid(items: List<ReceiptItem>): Double {
        val expected = items.sumOf { it.price } +
                currentCharges.filter { !it.inclusive }.sumOf { it.amount }
        val printed = currentPrintedTotal
        return if (printed != null && printed > 0.0 && kotlin.math.abs(printed - expected) <= 0.10) printed
        else expected
    }

    /** Updates the subtotal / tax / total card under the items list. */
    private fun refreshTotals() {
        val card = findViewById<View>(R.id.captureTotalsCard)
        val items = extractedItems.filter { it.price > 0 }
        if (items.isEmpty()) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        com.jmti.budgetfy.utils.ReceiptTotals.bind(
            card, items, currentCharges, null, amountPaid(items)
        )
    }

    private fun showAddItemDialog() {
        val form = DialogForm(this)
        val nameInput = form.field(getString(R.string.item_name))
        val qtyInput = form.field(getString(R.string.quantity), "1", DialogForm.NUMBER)
        val priceInput = form.field(getString(R.string.price), inputType = DialogForm.DECIMAL, prefix = "RM ")

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.add_item_title))
            .setView(form.view)
            .setPositiveButton(getString(R.string.add)) { _, _ ->
                val name = nameInput.text.toString().trim()
                val qty = qtyInput.text.toString().trim().ifEmpty { "1" }
                val price = priceInput.text.toString().toDoubleOrNull() ?: 0.0

                if (name.isEmpty() || price <= 0) {
                    Toast.makeText(this, getString(R.string.enter_valid_name_price), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newItem = ReceiptItem(
                    name = name,
                    quantity = qty,
                    price = price,
                    category = categorizationEngine.categorizeItem(name, learnedKeywords),
                    isManuallyCorrected = true
                )
                extractedItems.add(newItem)
                itemAdapter.updateItems(extractedItems.toList())
                refreshTotals()
                saveButton.isEnabled = true
                Toast.makeText(this, getString(R.string.added_item, name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun saveReceipt() {
        val userId = firebaseRepo.getCurrentUser()?.uid ?: return
        val items = extractedItems.filter { it.price > 0 }
        if (items.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_valid_items), Toast.LENGTH_SHORT).show()
            return
        }
        saveButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        val total = amountPaid(items)
        val receipt = Receipt(
            userId = userId,
            totalAmount = total,
            storeName = currentStoreName,
            items = items,
            imageUri = savedImagePath,
            charges = currentCharges,
            printedTotal = currentPrintedTotal
        )

        val photo = capturedBitmap
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Receipt text stays readable at ~1600px.
                val saveStarted = System.currentTimeMillis()
                val jpeg = photo?.let { ImageCompress.toJpeg(it, maxSide = 1600, maxBytes = 700_000) }
                // Shown on the photo in the Firebase console ("displayName").
                val ownerName = getSharedPreferences("budgetfy_prefs", MODE_PRIVATE).getString("display_name", null)
                    ?: firebaseRepo.getCurrentUser()?.displayName
                firebaseRepo.saveReceipt(userId, receipt, jpeg, ownerName)
                Log.d("CAPTURE", "Saved in ${System.currentTimeMillis() - saveStarted} ms")
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@CaptureReceiptActivity,
                        getString(R.string.saved_amount, String.format("%.2f", total)),
                        Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    saveButton.isEnabled = true
                    Toast.makeText(this@CaptureReceiptActivity,
                        getString(R.string.error_with_message, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}