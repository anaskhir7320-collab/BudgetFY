package com.jmti.budgetfy.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jmti.budgetfy.R
import com.jmti.budgetfy.models.BudgetCategory
import com.jmti.budgetfy.utils.CategoryStyle

/** Legend rows under the Home budget donut. */
class CategoryLegendAdapter : RecyclerView.Adapter<CategoryLegendAdapter.Row>() {

    private var categories = listOf<BudgetCategory>()

    fun updateData(newCategories: List<BudgetCategory>) {
        categories = newCategories
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
        Row(LayoutInflater.from(parent.context).inflate(R.layout.item_category_budget_compact, parent, false))

    override fun onBindViewHolder(holder: Row, position: Int) = holder.bind(categories[position])

    override fun getItemCount(): Int = categories.size

    class Row(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dot: View = itemView.findViewById(R.id.legendDot)
        private val name: TextView = itemView.findViewById(R.id.legendName)
        private val amount: TextView = itemView.findViewById(R.id.legendAmount)
        private val percent: TextView = itemView.findViewById(R.id.legendPercent)

        fun bind(c: BudgetCategory) {
            val color = CategoryStyle.color(itemView.context, c.name)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            name.text = c.name
            amount.text = "${String.format("%.2f", c.spent)} / ${formatLimit(c.limit)}"

            val pct = if (c.limit > 0) ((c.spent / c.limit) * 100).toInt() else 0
            percent.text = "$pct%"
            percent.setTextColor(
                when {
                    pct >= 100 -> androidx.core.content.ContextCompat.getColor(itemView.context, R.color.pct_over)
                    pct >= 80 -> androidx.core.content.ContextCompat.getColor(itemView.context, R.color.pct_warning)
                    else -> color
                }
            )
        }

        /** 300 → "300", 99.5 → "99.50" */
        private fun formatLimit(v: Double): String =
            if (v % 1.0 == 0.0) v.toLong().toString() else String.format("%.2f", v)
    }
}
