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
import com.jmti.budgetfy.models.ReceiptItem
import com.jmti.budgetfy.repositories.FirebaseRepository
import com.jmti.budgetfy.utils.CategorizationEngine
import com.jmti.budgetfy.utils.DialogForm
import com.jmti.budgetfy.utils.GeminiReceiptExtractor
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
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
    }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pageUri = scan?.pages?.firstOrNull()?.imageUri
            if (pageUri != null) loadReceiptImage(pageUri, "Receipt scanned! Tap Read.")
        }
    }

    // Fallback when the scanner is unavailable (no Google Play services).
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) photoUri?.let { loadReceiptImage(it, "Photo captured! Tap Read.") }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadReceiptImage(it, "Image loaded! Tap Read.") }
    }

    private fun loadReceiptImage(uri: Uri, message: String) {
        try {
            // EXIF-aware: phone photos otherwise reach OCR rotated sideways.
            val bitmap = ImageLoader.load(this, uri)
            if (bitmap == null) {
                Toast.makeText(this, "Could not read that image", Toast.LENGTH_SHORT).show()
                return
            }
            capturedBitmap = bitmap
            // Placeholder uses fitCenter; real photos fill the frame.
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView.setImageBitmap(bitmap)
            processButton.isEnabled = true
            startAnalysis(bitmap)
            savedImagePath = saveImageToInternal(bitmap)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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
            },
            onItemDeleted = { item ->
                val idx = extractedItems.indexOfFirst {
                    it.name == item.name && it.price == item.price
                }
                if (idx != -1) {
                    extractedItems.removeAt(idx)
                    itemAdapter.updateItems(extractedItems.toList())
                    saveButton.isEnabled = extractedItems.isNotEmpty()

                    // ✅ Save avoid pattern to shared Firestore
                    CoroutineScope(Dispatchers.IO).launch {
                        firebaseRepo.saveAvoidKeyword(item.name)
                        avoidPatterns = firebaseRepo.getAvoidKeywords(threshold = 2L)
                        Log.d("CAPTURE", "Avoid patterns now: ${avoidPatterns.size}")
                    }

                    Toast.makeText(
                        this,
                        "Removed: ${item.name}",
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
            "Store name", currentStoreName,
            helper = "Fix it if it was read wrong. It will be remembered next time."
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Store name")
            .setView(form.view)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val oldName = currentStoreName
                currentStoreName = newName
                storeNameTextView.text = newName
                if (oldName != newName) {
                    saveStoreCorrection(oldName, newName)
                }
            }
            .setNegativeButton("Cancel", null)
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
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
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
        val rechecked: Boolean = false
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
        if (!geminiExtractor.isConfigured) return Analysis(ocr, null, null)
        val outcome = geminiExtractor.extract(bitmap, ocr.layoutText, categorizationEngine.getCategories())
        Log.d("CAPTURE", "Analysis took ${System.currentTimeMillis() - started} ms")
        val error = when {
            outcome.result == null -> outcome.error
            outcome.result.items.isEmpty() -> "no items found"
            else -> null
        }
        return Analysis(ocr, outcome.result, error, outcome.rechecked)
    }

    override fun onDestroy() {
        workScope.cancel()
        super.onDestroy()
    }

    private fun processReceipt() {
        val bitmap = capturedBitmap ?: return
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
                val freshAvoids = if (usedAi) avoidPatterns else {
                    firebaseRepo.getAvoidKeywords(threshold = 2L).also { avoidPatterns = it }
                }
                val rawItems: List<ReceiptItem>
                val detectedStore: String
                val receiptTotal: Double?
                val extras: Double
                if (usedAi) {
                    rawItems = ai!!.items.map {
                        ReceiptItem(
                            name = it.name,
                            quantity = it.quantity.toString(),
                            price = it.lineTotal,
                            category = it.category ?: "Other"
                        )
                    }
                    detectedStore = ai.storeName ?: receiptParser.detectStoreName(ocr.text)
                    receiptTotal = ai.total
                    extras = ai.extras
                } else {
                    val result = receiptParser.parseReceipt(ocr.text, freshAvoids)
                    rawItems = result.items
                    detectedStore = receiptParser.detectStoreName(ocr.text)
                    receiptTotal = result.receiptTotal.takeIf { it > 0.0 }
                    extras = 0.0
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
                    extractedItems.clear()
                    extractedItems.addAll(correctedItems)
                    itemAdapter.updateItems(extractedItems.toList())

                    val learnedStore = categorizationEngine.applyLearnedStoreName(detectedStore, learnedKeywords)
                    currentStoreName = learnedStore
                    storeNameTextView.text = learnedStore

                    saveButton.isEnabled = extractedItems.isNotEmpty()
                    progressBar.visibility = View.GONE
                    processButton.isEnabled = true

                    if (aiError != null) {
                        Log.w("CAPTURE", "AI reading failed: $aiError")
                    }

                    val message = when {
                        extractedItems.isEmpty() ->
                            "No items found. Tap + ADD ITEM."
                        check.matches == false ->
                            "Found ${extractedItems.size} items, but they add up to " +
                                    "RM ${"%.2f".format(check.itemsSum)} while the receipt total is " +
                                    "RM ${"%.2f".format(check.receiptTotal)}. Please check the items."
                        else ->
                            "Found ${extractedItems.size} items. Tap any to edit or delete."
                    }
                    val source = when {
                        usedAi && analysis.rechecked -> "\n(Double-checked with the accurate model.)"
                        usedAi -> ""
                        aiError != null -> "\n(AI unavailable: $aiError. Used on-device reading.)"
                        else -> ""
                    }
                    Toast.makeText(this@CaptureReceiptActivity, message + source,
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    processButton.isEnabled = true
                    Toast.makeText(this@CaptureReceiptActivity,
                        "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showAddItemDialog() {
        val form = DialogForm(this)
        val nameInput = form.field("Item name")
        val qtyInput = form.field("Quantity", "1", DialogForm.NUMBER)
        val priceInput = form.field("Price", inputType = DialogForm.DECIMAL, prefix = "RM ")

        MaterialAlertDialogBuilder(this)
            .setTitle("Add item")
            .setView(form.view)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim()
                val qty = qtyInput.text.toString().trim().ifEmpty { "1" }
                val price = priceInput.text.toString().toDoubleOrNull() ?: 0.0

                if (name.isEmpty() || price <= 0) {
                    Toast.makeText(this, "Enter valid name and price", Toast.LENGTH_SHORT).show()
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
                saveButton.isEnabled = true
                Toast.makeText(this, "Added: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveReceipt() {
        val userId = firebaseRepo.getCurrentUser()?.uid ?: return
        val items = extractedItems.filter { it.price > 0 }
        if (items.isEmpty()) {
            Toast.makeText(this, "No valid items", Toast.LENGTH_SHORT).show()
            return
        }
        saveButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        val total = items.sumOf { it.price }
        val receipt = Receipt(
            userId = userId,
            totalAmount = total,
            storeName = currentStoreName,
            items = items,
            imageUri = savedImagePath
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                firebaseRepo.saveReceipt(userId, receipt)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@CaptureReceiptActivity,
                        "Saved: RM ${String.format("%.2f", total)}",
                        Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    saveButton.isEnabled = true
                    Toast.makeText(this@CaptureReceiptActivity,
                        "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}