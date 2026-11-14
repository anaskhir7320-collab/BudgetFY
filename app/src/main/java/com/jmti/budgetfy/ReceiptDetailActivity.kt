package com.jmti.budgetfy

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.jmti.budgetfy.adapters.ReceiptItemAdapter
import com.jmti.budgetfy.models.Receipt
import com.jmti.budgetfy.repositories.FirebaseRepository
import com.jmti.budgetfy.utils.CategoryStyle
import com.jmti.budgetfy.views.CategorySpendBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class ReceiptDetailActivity : AppCompatActivity() {

    private lateinit var receiptImage: ImageView
    private lateinit var totalTextView: TextView
    private lateinit var dateTextView: TextView
    private lateinit var storeNameTextView: TextView
    private lateinit var itemsRecyclerView: RecyclerView
    private lateinit var toolbar: Toolbar
    private lateinit var spendBar: CategorySpendBar
    private lateinit var categoryChips: ViewGroup
    private lateinit var imageExpandHint: TextView
    private lateinit var receiptImageCard: android.view.View
    private var imageExpanded = false
    private var hasPhoto = false

    private val firebaseRepo = FirebaseRepository()
    private var receipt: Receipt? = null
    private lateinit var itemAdapter: ReceiptItemAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receipt_detail)

        val receiptJson = intent.getStringExtra("RECEIPT_JSON")
        receipt = try {
            Gson().fromJson(receiptJson, Receipt::class.java)
        } catch (e: Exception) { null }

        if (receipt == null) {
            Toast.makeText(this, getString(R.string.receipt_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupToolbar()
        bindData(receipt!!)
    }

    private fun initViews() {
        receiptImage = findViewById(R.id.receiptImageDetail)
        totalTextView = findViewById(R.id.detailTotalTextView)
        dateTextView = findViewById(R.id.detailDateTextView)
        storeNameTextView = findViewById(R.id.detailStoreNameTextView)
        itemsRecyclerView = findViewById(R.id.detailItemsRecyclerView)
        toolbar = findViewById(R.id.detailToolbar)
        spendBar = findViewById(R.id.detailSpendBar)
        categoryChips = findViewById(R.id.detailCategoryChips)
        imageExpandHint = findViewById(R.id.imageExpandHint)
        receiptImageCard = findViewById(R.id.receiptImageCard)

        // Tap the photo to see the whole receipt; tap again to collapse.
        receiptImageCard.setOnClickListener {
            if (!hasPhoto) return@setOnClickListener
            imageExpanded = !imageExpanded
            val lp = receiptImage.layoutParams
            if (imageExpanded) {
                lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                receiptImage.adjustViewBounds = true
                receiptImage.scaleType = ImageView.ScaleType.FIT_CENTER
                imageExpandHint.text = getString(R.string.tap_to_collapse)
            } else {
                lp.height = (200 * resources.displayMetrics.density).toInt()
                receiptImage.adjustViewBounds = false
                receiptImage.scaleType = ImageView.ScaleType.CENTER_CROP
                imageExpandHint.text = getString(R.string.tap_to_expand)
            }
            receiptImage.layoutParams = lp
        }

        itemAdapter = ReceiptItemAdapter(
            // Add-ons added/removed or an item turned into an add-on: save the whole list.
            onItemsReplaced = { newItems -> saveItems(newItems) },
            onItemChanged = { oldItem, newItem ->
                val idx = receipt?.items?.indexOf(oldItem) ?: -1
                val onlyCategory = oldItem.copy(category = newItem.category, isManuallyCorrected = newItem.isManuallyCorrected) == newItem
                if (idx >= 0 && !onlyCategory) {
                    // Name, quantity or price edited: save the list so the total and budgets follow.
                    saveItems(receipt!!.items.toMutableList().also { it[idx] = newItem })
                } else if (idx >= 0) {
                    val r = receipt!!
                    CoroutineScope(Dispatchers.IO).launch {
                        firebaseRepo.updateItemCategory(r, idx, newItem.category)
                        val updatedItems = r.items.toMutableList()
                        updatedItems[idx] = newItem
                        receipt = r.copy(items = updatedItems)

                        withContext(Dispatchers.Main) {
                            itemAdapter.updateItems(receipt!!.items)
                            bindCategories(receipt!!)
                            bindTotals(receipt!!)
                            Toast.makeText(
                                this@ReceiptDetailActivity,
                                getString(R.string.category_updated_to, com.jmti.budgetfy.utils.CategoryNames.display(this@ReceiptDetailActivity, newItem.category)),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        )

        itemsRecyclerView.layoutManager = LinearLayoutManager(this)
        itemsRecyclerView.adapter = itemAdapter
    }

    /** The name shown in the Firebase "Summary" (the one set on the Profile). */
    private fun ownerName(): String? =
        getSharedPreferences("budgetfy_prefs", MODE_PRIVATE).getString("display_name", null)

    /** Saves an edited item list; the receipt total and category budgets are updated too. */
    private fun saveItems(newItems: List<com.jmti.budgetfy.models.ReceiptItem>) {
        val r = receipt ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val updated = firebaseRepo.updateReceiptItems(r, newItems, ownerName())
            receipt = updated
            withContext(Dispatchers.Main) {
                itemAdapter.updateItems(updated.items)
                totalTextView.text = "RM ${String.format("%.2f", updated.totalAmount)}"
                bindCategories(updated)
                bindTotals(updated)
            }
        }
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.receipt_detail_menu)
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_rename -> { showRenameDialog(); true }
                R.id.action_delete -> { confirmDelete(); true }
                else -> false
            }
        }
    }

    private fun bindData(r: Receipt) {
        totalTextView.text = "RM ${String.format("%.2f", r.totalAmount)}"

        val dateFormat = SimpleDateFormat("d MMM yyyy · h:mm a", Locale.getDefault())
        val count = r.items.size
        val countText = if (count == 1) getString(R.string.one_item) else getString(R.string.n_items, count)
        dateTextView.text = "${dateFormat.format(r.date.toDate())} · $countText"

        bindCategories(r)
        bindTotals(r)

        setStoreName(r.storeName)

        hasPhoto = try {
            r.imageUri.isNotEmpty() && File(r.imageUri).exists()
        } catch (e: Exception) { false }

        if (hasPhoto) {
            receiptImage.setImageURI(Uri.fromFile(File(r.imageUri)))
            showPhotoHint()
        } else {
            showNoPhoto()
            // Local copy is gone (e.g. app reinstalled) — fetch the Firestore copy.
            if (r.cloudImage) loadCloudPhoto(r.id)
        }

        itemAdapter.updateItems(r.items)
    }

    /** Photo deleted after 90 days, or never saved: show the "No Receipt Found" art. */
    private fun showNoPhoto() {
        receiptImage.setImageResource(R.drawable.receipt_placeholder)
        receiptImage.scaleType = ImageView.ScaleType.FIT_CENTER
        // Transparent picture on the card colour, with room for the caption below.
        val dp = resources.displayMetrics.density
        receiptImage.setPadding(0, (16 * dp).toInt(), 0, (44 * dp).toInt())
        findViewById<View>(R.id.noReceiptText).visibility = View.VISIBLE
        imageExpandHint.visibility = View.GONE
    }

    private fun showPhotoHint() {
        receiptImage.setPadding(0, 0, 0, 0)
        findViewById<View>(R.id.noReceiptText).visibility = View.GONE
        imageExpandHint.visibility = View.VISIBLE
        imageExpandHint.text = getString(R.string.tap_to_expand)
    }

    private fun loadCloudPhoto(receiptId: String) {
        imageExpandHint.visibility = View.VISIBLE
        imageExpandHint.text = getString(R.string.loading_photo)
        CoroutineScope(Dispatchers.IO).launch {
            val bytes = firebaseRepo.getReceiptImage(receiptId)
            val bitmap = bytes?.let {
                android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (bitmap != null) {
                    hasPhoto = true
                    receiptImage.setImageBitmap(bitmap)
                    receiptImage.scaleType = ImageView.ScaleType.CENTER_CROP
                    showPhotoHint()
                } else {
                    showNoPhoto()
                }
            }
        }
    }

    private fun setStoreName(name: String) {
        val shown = name.ifEmpty { getString(R.string.receipt_default) }
        storeNameTextView.text = shown
        toolbar.title = shown
    }

    /** Footer under the items: subtotal, tax rows, rounding and total paid. */
    private fun bindTotals(r: Receipt) {
        com.jmti.budgetfy.utils.ReceiptTotals.bind(
            findViewById(R.id.receiptTotals), r.items, r.charges, null, r.totalAmount
        )
    }

    /** Spend bar plus one colour chip per category ("● Groceries  RM 110.00"). */
    private fun bindCategories(r: Receipt) {
        val spend = CategoryStyle.spendByCategory(r)
        spendBar.setSpend(spend)
        spendBar.visibility = if (spend.isEmpty()) View.GONE else View.VISIBLE

        categoryChips.removeAllViews()
        val usePrices = r.items.any { it.price > 0.0 }
        val dp = resources.displayMetrics.density
        spend.forEach { (category, amount) ->
            val color = CategoryStyle.color(this, category)
            val dot = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
                setSize((7 * dp).toInt(), (7 * dp).toInt())
            }
            val chip = TextView(this).apply {
                val shownName = com.jmti.budgetfy.utils.CategoryNames.display(this@ReceiptDetailActivity, category)
                text = if (usePrices) "$shownName  RM ${String.format("%.2f", amount)}" else shownName
                textSize = 12f
                setTextColor(color)
                setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
                compoundDrawablePadding = (6 * dp).toInt()
                setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(CategoryStyle.tint(this@ReceiptDetailActivity, category))
                }
            }
            categoryChips.addView(chip)
        }
        categoryChips.visibility = if (spend.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showRenameDialog() {
        val form = com.jmti.budgetfy.utils.DialogForm(this)
        val input = form.field(getString(R.string.store_name), receipt?.storeName ?: "")

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.rename_receipt))
            .setView(form.view)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, getString(R.string.name_cannot_be_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                renameReceipt(newName)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun renameReceipt(newName: String) {
        val r = receipt ?: return
        CoroutineScope(Dispatchers.IO).launch {
            firebaseRepo.renameReceipt(r, newName, ownerName())
            receipt = r.copy(storeName = newName)
            withContext(Dispatchers.Main) {
                setStoreName(newName)
                Toast.makeText(
                    this@ReceiptDetailActivity,
                    getString(R.string.renamed_to, newName),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_receipt_title))
            .setMessage(getString(R.string.delete_receipt_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteReceipt() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteReceipt() {
        val r = receipt ?: return
        CoroutineScope(Dispatchers.IO).launch {
            firebaseRepo.deleteReceipt(r)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@ReceiptDetailActivity,
                    getString(R.string.receipt_deleted),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
}