package com.jmti.budgetfy.adapters

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.jmti.budgetfy.R
import com.jmti.budgetfy.models.ReceiptItem

class ReceiptItemAdapter(
    private val onItemChanged: (ReceiptItem, ReceiptItem) -> Unit,
    private val onItemDeleted: ((ReceiptItem) -> Unit)? = null
) : RecyclerView.Adapter<ReceiptItemAdapter.ItemViewHolder>() {

    private var items = listOf<ReceiptItem>()
    private val categories = arrayOf(
        "Groceries", "Personal Care", "Transportation", "Entertainment",
        "Food & Dining", "Education", "Shopping", "Health",
        "Utilities", "Other"
    )

    fun updateItems(newItems: List<ReceiptItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_receipt_item, parent, false)
        return ItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)

        holder.categoryBar.setOnClickListener {
            showCategoryDialog(holder.itemView.context, item)
        }
        holder.editIcon.setOnClickListener {
            showEditItemDialog(holder.itemView.context, item)
        }
        holder.itemNameView.setOnClickListener {
            showEditItemDialog(holder.itemView.context, item)
        }
        holder.itemPriceView.setOnClickListener {
            showEditItemDialog(holder.itemView.context, item)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun showCategoryDialog(context: Context, item: ReceiptItem) {
        val currentIndex = categories.indexOfFirst { it == item.category }.coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("Category for: ${item.name}")
            .setSingleChoiceItems(categories, currentIndex) { dialog, which ->
                val newCategory = categories[which]
                val updated = item.copy(
                    category = newCategory,
                    isManuallyCorrected = true
                )
                onItemChanged(item, updated)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditItemDialog(context: Context, item: ReceiptItem) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val nameInput = EditText(context).apply {
            hint = "Item name"
            setText(item.name)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        layout.addView(nameInput)
        val qtyInput = EditText(context).apply {
            hint = "Quantity"
            setText(item.quantity)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(qtyInput)
        val priceInput = EditText(context).apply {
            hint = "Price (RM)"
            setText(String.format("%.2f", item.price))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(priceInput)

        val builder = AlertDialog.Builder(context)
            .setTitle("Edit Item")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim().ifEmpty { item.name }
                val newQty = qtyInput.text.toString().trim().ifEmpty { "1" }
                val newPrice = priceInput.text.toString().toDoubleOrNull() ?: item.price
                if (newPrice <= 0) {
                    Toast.makeText(context, "Price must be > 0", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val updated = item.copy(
                    name = newName,
                    quantity = newQty,
                    price = newPrice,
                    isManuallyCorrected = true
                )
                onItemChanged(item, updated)
            }
            .setNegativeButton("Cancel", null)

        // ✅ NEW: Add delete button if callback is provided
        if (onItemDeleted != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                AlertDialog.Builder(context)
                    .setTitle("Delete Item?")
                    .setMessage("Remove \"${item.name}\" from this receipt?")
                    .setPositiveButton("Delete") { _, _ ->
                        onItemDeleted.invoke(item)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        builder.show()
    }

    class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val categoryBar: View = itemView.findViewById(R.id.categoryBar)
        val editIcon: View = itemView.findViewById(R.id.editIcon)
        val itemNameView: TextView = itemView.findViewById(R.id.itemNameTextView)
        val itemPriceView: TextView = itemView.findViewById(R.id.itemPriceTextView)

        private val categoryText: TextView = itemView.findViewById(R.id.itemCategoryTextView)
        private val quantityText: TextView = itemView.findViewById(R.id.itemQuantityTextView)

        fun bind(item: ReceiptItem) {
            itemNameView.text = item.name
            itemPriceView.text = "RM ${String.format("%.2f", item.price)}"
            quantityText.text = "×${item.quantity}"
            categoryText.text = item.category

            if (item.isManuallyCorrected) {
                categoryBar.setBackgroundColor(
                    android.graphics.Color.parseColor("#C8E6C9")
                )
                categoryText.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
            } else {
                categoryBar.setBackgroundColor(
                    android.graphics.Color.parseColor("#F3E5F5")
                )
                categoryText.setTextColor(android.graphics.Color.parseColor("#6200EE"))
            }
        }
    }
}