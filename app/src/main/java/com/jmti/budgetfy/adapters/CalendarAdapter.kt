package com.jmti.budgetfy.adapters

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.jmti.budgetfy.R
import com.jmti.budgetfy.TransactionHistoryActivity
import com.jmti.budgetfy.models.Receipt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Year grid of 12 month cards. Each card shows the month's total, opens
 * that month's receipts when tapped, and marks days that have receipts.
 */
class CalendarAdapter(
    private val year: Int,
    private val receipts: List<Receipt>,
    private val onDayClick: (Calendar) -> Unit
) : RecyclerView.Adapter<CalendarAdapter.MonthViewHolder>() {

    private val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val fullMonthNames = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    private val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val daysWithReceipts = mutableSetOf<String>()
    private val receiptsByMonth = Array(12) { mutableListOf<Receipt>() }

    private val now = Calendar.getInstance()
    private val todayKey = dateKeyFormat.format(now.time)

    init {
        val cal = Calendar.getInstance()
        receipts.forEach { receipt ->
            try {
                val date = receipt.date.toDate()
                daysWithReceipts.add(dateKeyFormat.format(date))
                cal.time = date
                if (cal.get(Calendar.YEAR) == year) receiptsByMonth[cal.get(Calendar.MONTH)].add(receipt)
            } catch (e: Exception) {
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MonthViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_month, parent, false)
        return MonthViewHolder(view)
    }

    override fun onBindViewHolder(holder: MonthViewHolder, position: Int) {
        val month = position
        val context = holder.itemView.context
        val monthReceipts = receiptsByMonth[month]
        val total = monthReceipts.sumOf { it.totalAmount }

        holder.monthNameTextView.text = context.resources.getStringArray(R.array.month_short)[month]
        holder.monthTotalTextView.text = if (total > 0) compactRm(total) else ""

        val isCurrentMonth = year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH)
        holder.monthCard.setBackgroundResource(
            if (isCurrentMonth) R.drawable.bg_month_card_current else R.drawable.bg_month_card
        )
        holder.monthNameTextView.setTextColor(
            ContextCompat.getColor(context, if (isCurrentMonth) R.color.accent_text else R.color.text_primary)
        )

        // The whole card opens this month's receipts.
        holder.monthCard.setOnClickListener {
            val intent = Intent(context, TransactionHistoryActivity::class.java)
            intent.putExtra("MONTH_YEAR", "${context.resources.getStringArray(R.array.month_full)[month]} $year")
            intent.putExtra("RECEIPTS_LIST", Gson().toJson(monthReceipts))
            context.startActivity(intent)
        }

        holder.daysRecyclerView.layoutManager = GridLayoutManager(context, 7)
        // Taps on days without receipts open the month, like the rest of the card.
        holder.daysRecyclerView.adapter = DayAdapter(generateDaysForMonth(year, month)) {
            holder.monthCard.performClick()
        }
    }

    override fun getItemCount(): Int = 12

    /** "RM 84", "RM 1.2k": fits in the narrow month header. */
    private fun compactRm(amount: Double): String = when {
        amount >= 10_000 -> "RM %.0fk".format(amount / 1000)
        amount >= 1_000 -> "RM %.1fk".format(amount / 1000)
        else -> "RM %.0f".format(amount)
    }

    class MonthViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val monthCard: View = itemView.findViewById(R.id.monthCard)
        val monthNameTextView: TextView = itemView.findViewById(R.id.monthNameTextView)
        val monthTotalTextView: TextView = itemView.findViewById(R.id.monthTotalTextView)
        val daysRecyclerView: RecyclerView = itemView.findViewById(R.id.daysRecyclerView)
    }

    private fun generateDaysForMonth(year: Int, month: Int): List<Calendar?> {
        val days = mutableListOf<Calendar?>()
        val cal = Calendar.getInstance()
        cal.set(year, month, 1)

        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
        val maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        repeat(firstDayOfWeek) { days.add(null) }
        for (i in 1..maxDays) {
            val dayCal = Calendar.getInstance()
            dayCal.set(year, month, i)
            days.add(dayCal)
        }
        return days
    }

    inner class DayAdapter(
        private val days: List<Calendar?>,
        private val onOtherClick: () -> Unit
    ) : RecyclerView.Adapter<DayAdapter.DayViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, itemViewType: Int): DayViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_day, parent, false)
            return DayViewHolder(view)
        }

        override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
            val cal = days[position]
            val context = holder.itemView.context
            val dayText = holder.dayText

            if (cal == null) {
                dayText.text = ""
                dayText.background = null
                holder.itemView.setOnClickListener { onOtherClick() }
                return
            }

            dayText.text = cal.get(Calendar.DAY_OF_MONTH).toString()
            val dateKey = dateKeyFormat.format(cal.time)
            val hasReceipts = daysWithReceipts.contains(dateKey)
            val isToday = dateKey == todayKey
            val isFuture = cal.after(now) && !isToday

            when {
                hasReceipts -> {
                    dayText.setBackgroundResource(R.drawable.bg_calendar_active_day)
                    dayText.setTextColor(Color.WHITE)
                    dayText.setTypeface(null, Typeface.BOLD)
                }
                isToday -> {
                    dayText.setBackgroundResource(R.drawable.bg_calendar_today)
                    dayText.setTextColor(ContextCompat.getColor(context, R.color.accent_text))
                    dayText.setTypeface(null, Typeface.BOLD)
                }
                else -> {
                    dayText.background = null
                    dayText.setTextColor(
                        ContextCompat.getColor(context, if (isFuture) R.color.text_tertiary else R.color.text_primary)
                    )
                    dayText.setTypeface(null, Typeface.NORMAL)
                }
            }

            holder.itemView.setOnClickListener {
                if (hasReceipts) onDayClick(cal) else onOtherClick()
            }
        }

        override fun getItemCount(): Int = days.size

        inner class DayViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val dayText: TextView = itemView.findViewById(R.id.dayNumberTextView)
        }
    }
}
