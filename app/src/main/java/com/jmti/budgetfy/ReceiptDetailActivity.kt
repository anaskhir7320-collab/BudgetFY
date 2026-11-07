package com.jmti.budgetfy

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.text.InputType
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
    private lateinit var itemCountTextView: TextView
    private lateinit var storeNameTextView: TextView
    private lateinit var itemsRecyclerView: RecyclerView
    private lateinit var toolbar: Toolbar

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
            Toast.makeText(this, "Receipt not found", Toast.LENGTH_SHORT).show()
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
        itemCountTextView = findViewById(R.id.detailItemCountTextView)
        storeNameTextView = findViewById(R.id.detailStoreNameTextView)
        itemsRecyclerView = findViewById(R.id.detailItemsRecyclerView)
        toolbar = findViewById(R.id.detailToolbar)

        itemAdapter = ReceiptItemAdapter(
            onItemChanged = { oldItem, newItem ->
                val idx = receipt?.items?.indexOf(oldItem) ?: -1
                if (idx >= 0) {
                    val r = receipt!!
                    CoroutineScope(Dispatchers.IO).launch {
                        firebaseRepo.updateItemCategory(r, idx, newItem.category)
                        val updatedItems = r.items.toMutableList()
                        updatedItems[idx] = newItem
                        receipt = r.copy(items = updatedItems)

                        withContext(Dispatchers.Main) {
                            itemAdapter.updateItems(receipt!!.items)
                            Toast.makeText(
                                this@ReceiptDetailActivity,
                                "Category updated to ${newItem.category}",
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

        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        dateTextView.text = dateFormat.format(r.date.toDate())

        val count = r.items.size
        itemCountTextView.text = if (count == 1) "1 item" else "$count items"

        storeNameTextView.text = r.storeName.ifEmpty { "Receipt" }
        toolbar.title = r.storeName.ifEmpty { "Receipt Details" }

        if (r.imageUri.isNotEmpty()) {
            try {
                val file = File(r.imageUri)
                if (file.exists()) {
                    receiptImage.setImageURI(Uri.fromFile(file))
                } else {
                    receiptImage.setImageResource(R.mipmap.ic_launcher)
                }
            } catch (e: Exception) {
                receiptImage.setImageResource(R.mipmap.ic_launcher)
            }
        } else {
            receiptImage.setImageResource(R.mipmap.ic_launcher)
        }

        itemAdapter.updateItems(r.items)
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setText(receipt?.storeName ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "Enter new name"
        }

        AlertDialog.Builder(this)
            .setTitle("Rename Receipt")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                renameReceipt(newName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameReceipt(newName: String) {
        val r = receipt ?: return
        CoroutineScope(Dispatchers.IO).launch {
            firebaseRepo.renameReceipt(r.id, newName)
            receipt = r.copy(storeName = newName)
            withContext(Dispatchers.Main) {
                storeNameTextView.text = newName
                toolbar.title = newName
                Toast.makeText(
                    this@ReceiptDetailActivity,
                    "Renamed to \"$newName\"",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete Receipt?")
            .setMessage("This will remove the receipt and reverse its budget impact. Cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteReceipt() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteReceipt() {
        val r = receipt ?: return
        CoroutineScope(Dispatchers.IO).launch {
            firebaseRepo.deleteReceipt(r)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@ReceiptDetailActivity,
                    "Receipt deleted",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
}