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
    private val onItemDeleted: ((ReceiptItem) -> Unit)? = null,
    /** The whole list after an add-on change (added, removed, or an item turned into an add-on). */
    private val onItemsReplaced: ((List<ReceiptItem>) -> Unit)? = null
) : RecyclerView.Adapter<ReceiptItemAdapter.ItemViewHolder>() {

    private var items = listOf<ReceiptItem>()
    private val categories = arrayOf(
        "Groceries", "Personal Care", "Transportation", "Entertainment",
        "Food & Dining", "Education", "Shopping", "Health",
        "Utilities", "Other"
    )

    /** Rows whose add-ons are shown. Kept across edits (e.g. changing a category). */
    private val expanded = mutableSetOf<Int>()

    fun updateItems(newItems: List<ReceiptItem>) {
        if (newItems.size != items.size) expanded.clear() // a new reading or a deleted row
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
        holder.bindAddOns(
            item, position in expanded,
            onToggle = {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    if (!expanded.add(pos)) expanded.remove(pos)
                    notifyItemChanged(pos)
                }
            },
            onAddOnTapped = if (onItemsReplaced == null) null else { addOn ->
                confirmRemoveAddOn(holder.itemView.context, holder.bindingAdapterPosition, addOn)
            }
        )

        holder.categoryBar.setOnClickListener {
            showCategoryDialog(holder.itemView.context, item)
        }
        holder.editIcon.setOnClickListener {
            showEditItemDialog(holder.itemView.context, item, holder.bindingAdapterPosition)
        }
        holder.itemNameView.setOnClickListener {
            showEditItemDialog(holder.itemView.context, item, holder.bindingAdapterPosition)
        }
        holder.itemPriceView.setOnClickListener {
            showEditItemDialog(holder.itemView.context, item, holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun showCategoryDialog(context: Context, item: ReceiptItem) {
        val currentIndex = categories.indexOfFirst { it == item.category }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.choose_a_category))
            .setSingleChoiceItems(
                categories.map { com.jmti.budgetfy.utils.CategoryNames.display(context, it) }.toTypedArray(),
                currentIndex
            ) { dialog, which ->
                val newCategory = categories[which]
                val updated = item.copy(
                    category = newCategory,
                    isManuallyCorrected = true
                )
                onItemChanged(item, updated)
                dialog.dismiss()
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }

    private fun replaceItems(newItems: List<ReceiptItem>) {
        onItemsReplaced?.invoke(newItems)
    }

    /** Adds an add-on to the item at [position]; its price is added to the item. */
    private fun showAddAddOnDialog(context: Context, position: Int) {
        val item = items.getOrNull(position) ?: return
        val form = com.jmti.budgetfy.utils.DialogForm(context)
        val nameInput = form.field(context.getString(R.string.add_on_name))
        val priceInput = form.field(
            context.getString(R.string.add_on_price_optional), "",
            com.jmti.budgetfy.utils.DialogForm.DECIMAL, prefix = "RM "
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.add_add_on))
            .setView(form.view)
            .setPositiveButton(context.getString(R.string.add)) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.name_cannot_be_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val price = (priceInput.text.toString().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                val updated = item.copy(
                    addOns = item.addOns + com.jmti.budgetfy.models.AddOn(name, price),
                    price = Math.round((item.price + price) * 100) / 100.0,
                    isManuallyCorrected = true
                )
                expanded.add(position)
                replaceItems(items.toMutableList().also { it[position] = updated })
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }

    /** Moves the item at [position] under the item above it as an add-on. */
    private fun makeAddOnOfItemAbove(position: Int) {
        if (position <= 0 || position >= items.size) return
        val item = items[position]
        val parent = items[position - 1]
        val merged = parent.copy(
            addOns = parent.addOns + com.jmti.budgetfy.models.AddOn(
                com.jmti.budgetfy.utils.AddOnMerger.withoutCode(item.name), item.price
            ) + item.addOns,
            price = Math.round((parent.price + item.price) * 100) / 100.0,
            isManuallyCorrected = true
        )
        expanded.clear()
        expanded.add(position - 1)
        replaceItems(items.toMutableList().also { it[position - 1] = merged; it.removeAt(position) })
    }

    /** Tapped an add-on in the list: remove it (and its price) from the item. */
    private fun confirmRemoveAddOn(context: Context, position: Int, addOn: com.jmti.budgetfy.models.AddOn) {
        val item = items.getOrNull(position) ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.remove_add_on_title))
            .setMessage(context.getString(R.string.remove_add_on_message, addOn.name))
            .setPositiveButton(context.getString(R.string.remove)) { _, _ ->
                val updated = item.copy(
                    addOns = item.addOns - addOn,
                    price = Math.round((item.price - addOn.price) * 100) / 100.0,
                    isManuallyCorrected = true
                )
                replaceItems(items.toMutableList().also { it[position] = updated })
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }

    private fun showEditItemDialog(context: Context, item: ReceiptItem, position: Int) {
        val form = com.jmti.budgetfy.utils.DialogForm(context)
        val nameInput = form.field(context.getString(R.string.item_name), item.name)
        val qtyInput = form.field(context.getString(R.string.quantity), item.quantity, com.jmti.budgetfy.utils.DialogForm.NUMBER)
        val priceInput = form.field(
            context.getString(R.string.price), String.format("%.2f", item.price),
            com.jmti.budgetfy.utils.DialogForm.DECIMAL, prefix = "RM "
        )
        val layout = form.view

        // Add-on tools (only where the screen can save a changed list).
        var dialog: androidx.appcompat.app.AlertDialog? = null
        if (onItemsReplaced != null && position != RecyclerView.NO_POSITION) {
            fun action(text: String, onClick: () -> Unit) = TextView(context).apply {
                this.text = text
                textSize = 14f
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.accent_text))
                val d = context.resources.displayMetrics.density
                setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog?.dismiss(); onClick() }
            }
            layout.addView(action(context.getString(R.string.add_add_on_action)) { showAddAddOnDialog(context, position) })
            if (position > 0) {
                layout.addView(action(context.getString(R.string.make_add_on_of_above)) { makeAddOnOfItemAbove(position) })
            }
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.edit_item))
            .setView(layout)
            .setPositiveButton(context.getString(R.string.save)) { _, _ ->
                val newName = nameInput.text.toString().trim().ifEmpty { item.name }
                val newQty = qtyInput.text.toString().trim().ifEmpty { "1" }
                val newPrice = priceInput.text.toString().toDoubleOrNull() ?: item.price
                if (newPrice <= 0) {
                    Toast.makeText(context, context.getString(R.string.price_must_be_positive), Toast.LENGTH_SHORT).show()
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
            .setNegativeButton(context.getString(R.string.cancel), null)

        // ✅ NEW: Add delete button if callback is provided
        if (onItemDeleted != null) {
            builder.setNeutralButton(context.getString(R.string.delete)) { _, _ ->
                MaterialAlertDialogBuilder(context)
                    .setTitle(context.getString(R.string.delete_item_title))
                    .setMessage(context.getString(R.string.delete_item_message, item.name))
                    .setPositiveButton(context.getString(R.string.delete)) { _, _ ->
                        onItemDeleted.invoke(item)
                    }
                    .setNegativeButton(context.getString(R.string.cancel), null)
                    .show()
            }
        }

        dialog = builder.show()
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
        private val addOnsToggle: TextView = itemView.findViewById(R.id.addOnsToggle)
        private val addOnsList: android.widget.LinearLayout = itemView.findViewById(R.id.addOnsList)

        /**
         * "3 add-ons ▾" under the item; tapping shows its options underneath
         * (Egg Noodle, Level 1, "+ RM 2.00" for priced ones) and hides them again.
         */
        fun bindAddOns(
            item: ReceiptItem, expanded: Boolean, onToggle: () -> Unit,
            onAddOnTapped: ((com.jmti.budgetfy.models.AddOn) -> Unit)? = null
        ) {
            val context = itemView.context
            val count = item.addOns.size
            if (count == 0) {
                addOnsToggle.visibility = View.GONE
                addOnsList.visibility = View.GONE
                return
            }
            addOnsToggle.visibility = View.VISIBLE
            val label = if (count == 1) context.getString(R.string.one_add_on) else context.getString(R.string.n_add_ons, count)
            addOnsToggle.text = "$label ${if (expanded) "▴" else "▾"}"
            addOnsToggle.setOnClickListener { onToggle() }

            addOnsList.removeAllViews()
            addOnsList.visibility = if (expanded) View.VISIBLE else View.GONE
            if (!expanded) return
            val secondary = androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary)
            val dp = context.resources.displayMetrics.density
            item.addOns.forEach { a ->
                val row = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(0, (5 * dp).toInt(), 0, (5 * dp).toInt())
                    if (onAddOnTapped != null) {
                        // Tap an add-on to remove it.
                        val ripple = android.util.TypedValue()
                        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                        setBackgroundResource(ripple.resourceId)
                        setOnClickListener { onAddOnTapped(a) }
                    }
                }
                row.addView(TextView(context).apply {
                    text = "•  ${a.name}"
                    textSize = 13f
                    setTextColor(secondary)
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (a.price > 0.0) row.addView(TextView(context).apply {
                    text = "+ RM ${String.format("%.2f", a.price)}"
                    textSize = 13f
                    setTextColor(secondary)
                })
                addOnsList.addView(row)
            }
        }

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
            val shownCategory = com.jmti.budgetfy.utils.CategoryNames.display(itemView.context, item.category)
            categoryText.text = if (item.isManuallyCorrected) "✓ $shownCategory" else shownCategory
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