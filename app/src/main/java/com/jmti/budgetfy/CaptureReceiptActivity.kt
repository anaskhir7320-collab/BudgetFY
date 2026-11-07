package com.jmti.budgetfy

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
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
import com.jmti.budgetfy.utils.GeminiReceiptExtractor
import com.jmti.budgetfy.utils.ImageLoader
import com.jmti.budgetfy.utils.OCRHelper
import com.jmti.budgetfy.utils.ReceiptParser
import com.jmti.budgetfy.utils.ReceiptValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            if (pageUri != null) loadReceiptImage(pageUri, "Receipt scanned! Tap Process.")
        }
    }

    // Fallback when the scanner is unavailable (no Google Play services).
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) photoUri?.let { loadReceiptImage(it, "Photo captured! Tap Process.") }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadReceiptImage(it, "Image loaded! Tap Process.") }
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
            imageView.setImageBitmap(bitmap)
            processButton.isEnabled = true
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
        val input = EditText(this).apply {
            setText(currentStoreName)
            hint = "Store name (e.g., Mixue)"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(this)
            .setTitle("Store Name")
            .setMessage("Correct the store name if OCR got it wrong.\nIt will be remembered for next time.")
            .setView(input)
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

    private fun processReceipt() {
        val bitmap = capturedBitmap ?: return
        processButton.isEnabled = false
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ocr = ocrHelper.recognize(bitmap)
                val categories = categorizationEngine.getCategories()

                // ✅ Reload avoid patterns from Firestore (in case user deleted items in other sessions)
                val freshAvoids = firebaseRepo.getAvoidKeywords(threshold = 2L)
                avoidPatterns = freshAvoids
                Log.d("CAPTURE", "Applying ${freshAvoids.size} avoid patterns")

                // 1) Gemini reads the image (OCR text as a hint).
                // 2) If there's no key, no network or no items, fall back to the on-device parser.
                val ai = if (geminiExtractor.isConfigured) {
                    geminiExtractor.extract(bitmap, ocr.layoutText, categories)
                } else null

                val usedAi = ai != null && ai.items.isNotEmpty()
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
                val aiError = if (geminiExtractor.isConfigured && !usedAi) {
                    geminiExtractor.lastError ?: "no items found"
                } else null

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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val nameInput = EditText(this).apply {
            hint = "Item name"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        layout.addView(nameInput)
        val qtyInput = EditText(this).apply {
            hint = "Quantity"
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(qtyInput)
        val priceInput = EditText(this).apply {
            hint = "Price (RM)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(priceInput)

        AlertDialog.Builder(this)
            .setTitle("Add Item")
            .setView(layout)
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