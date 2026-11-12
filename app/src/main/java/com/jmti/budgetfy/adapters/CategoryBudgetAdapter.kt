package com.jmti.budgetfy.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.jmti.budgetfy.R
import com.jmti.budgetfy.models.BudgetCategory

class CategoryBudgetAdapter : RecyclerView.Adapter<CategoryBudgetAdapter.CategoryViewHolder>() {

    private var categories = listOf<BudgetCategory>()

    fun updateData(newCategories: List<BudgetCategory>) {
        categories = newCategories
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_budget, parent, false)
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(categories[position])
    }

    override fun getItemCount(): Int = categories.size

    class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val colorView: View = itemView.findViewById(R.id.categoryColorView)
        private val nameTextView: TextView = itemView.findViewById(R.id.categoryNameTextView)
        private val amountTextView: TextView = itemView.findViewById(R.id.categoryAmountTextView)
        private val progressBar: LinearProgressIndicator = itemView.findViewById(R.id.categoryProgressBar)
        private val statusTextView: TextView = itemView.findViewById(R.id.categoryStatusTextView)

        fun bind(category: BudgetCategory) {
            nameTextView.text = com.jmti.budgetfy.utils.CategoryNames.display(itemView.context, category.name)
            amountTextView.text = "RM ${String.format("%.2f", category.spent)} / RM ${String.format("%.2f", category.limit)}"

            // Calculate percentage
            val percentage = if (category.limit > 0) {
                ((category.spent / category.limit) * 100).toInt()
            } else 0

            progressBar.progress = percentage.coerceAtMost(100)

            // Set color based on usage
            val color = when {
                percentage >= 100 -> Color.parseColor("#F44336")   // Red - exceeded
                percentage >= 80 -> Color.parseColor("#FF9800")    // Orange - warning
                else -> category.color                              // Normal color
            }
            colorView.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            progressBar.setIndicatorColor(color)

            // Status text
            statusTextView.text = when {
                percentage >= 100 -> itemView.context.getString(R.string.over_budget_by, String.format("%.2f", category.spent - category.limit))
                percentage >= 80 -> itemView.context.getString(R.string.pct_used_almost, percentage)
                else -> itemView.context.getString(R.string.pct_used, percentage)
            }

            statusTextView.setTextColor(color)
        }
    }
}