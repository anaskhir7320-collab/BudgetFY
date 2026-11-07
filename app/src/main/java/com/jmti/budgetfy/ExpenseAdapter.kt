package com.jmti.budgetfy.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jmti.budgetfy.R
import com.jmti.budgetfy.models.Receipt
import java.text.SimpleDateFormat
import java.util.Locale

class ExpenseAdapter(
    private val onItemClick: (Receipt) -> Unit
) : RecyclerView.Adapter<ExpenseAdapter.ExpenseViewHolder>() {

    private var receipts = listOf<Receipt>()

    fun updateData(newReceipts: List<Receipt>) {
        receipts = newReceipts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExpenseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_receipt, parent, false)
        return ExpenseViewHolder(view)
    }

    override fun onBindViewHolder(holder: ExpenseViewHolder, position: Int) {
        val receipt = receipts[position]
        holder.bind(receipt)
        holder.itemView.setOnClickListener { onItemClick(receipt) }
    }

    override fun getItemCount(): Int = receipts.size

    class ExpenseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val storeNameTextView: TextView = itemView.findViewById(R.id.storeNameTextView)
        private val totalAmountTextView: TextView = itemView.findViewById(R.id.totalAmountTextView)
        private val dateTextView: TextView = itemView.findViewById(R.id.dateTextView)
        private val itemCountTextView: TextView = itemView.findViewById(R.id.itemCountTextView)

        fun bind(receipt: Receipt) {
            storeNameTextView.text = receipt.storeName.ifEmpty { "Receipt" }
            totalAmountTextView.text = "RM ${String.format("%.2f", receipt.totalAmount)}"

            val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            dateTextView.text = dateFormat.format(receipt.date.toDate())

            // ✅ Fixed: "1 item" vs "N items"
            val count = receipt.items.size
            itemCountTextView.text = if (count == 1) "1 item" else "$count items"
        }
    }
}