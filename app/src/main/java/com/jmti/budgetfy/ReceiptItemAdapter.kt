package com.jmti.budgetfy.adapters

import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        holder.bind(item, isLast = position == items.lastIndex)

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
        MaterialAlertDialogBuilder(context)
            .setTitle("Choose a category")
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
        val form = com.jmti.budgetfy.utils.DialogForm(context)
        val nameInput = form.field("Item name", item.name)
        val qtyInput = form.field("Quantity", item.quantity, com.jmti.budgetfy.utils.DialogForm.NUMBER)
        val priceInput = form.field(
            "Price", String.format("%.2f", item.price),
            com.jmti.budgetfy.utils.DialogForm.DECIMAL, prefix = "RM "
        )
        val layout = form.view

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle("Edit item")
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
                MaterialAlertDialogBuilder(context)
                    .setTitle("Delete item?")
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
        private val categoryChevron: android.widget.ImageView = itemView.findViewById(R.id.categoryChevron)
        private val quantityText: TextView = itemView.findViewById(R.id.itemQuantityTextView)
        private val rowDivider: View = itemView.findViewById(R.id.rowDivider)

        fun bind(item: ReceiptItem, isLast: Boolean) {
            itemNameView.text = item.name
            itemPriceView.text = "RM ${String.format("%.2f", item.price)}"
            quantityText.text = "×${item.quantity}"

            // Chip: blue = read automatically, green with ✓ = you corrected it
            val (chipBg, chipText) = if (item.isManuallyCorrected) {
                androidx.core.content.ContextCompat.getColor(itemView.context, R.color.chip_corrected_bg) to androidx.core.content.ContextCompat.getColor(itemView.context, R.color.chip_corrected_text)
            } else {
                androidx.core.content.ContextCompat.getColor(itemView.context, R.color.accent_soft) to
                        androidx.core.content.ContextCompat.getColor(itemView.context, R.color.accent_text)
            }
            categoryText.text = if (item.isManuallyCorrected) "✓ ${item.category}" else item.category
            categoryText.setTextColor(chipText)
            categoryChevron.setColorFilter(chipText)
            categoryBar.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 100f * itemView.resources.displayMetrics.density
                setColor(chipBg)
            }

            rowDivider.visibility = if (isLast) View.GONE else View.VISIBLE
        }
    }
}