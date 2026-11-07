package com.jmti.budgetfy.adapters

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.jmti.budgetfy.R
import com.jmti.budgetfy.TransactionHistoryActivity
import com.jmti.budgetfy.models.Receipt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CalendarAdapter(
    private val year: Int,
    private val receipts: List<Receipt>,
    private val onDayClick: (Calendar) -> Unit
) : RecyclerView.Adapter<CalendarAdapter.MonthViewHolder>() {

    private val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val fullMonthNames = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    private val daysWithReceipts = mutableSetOf<String>()

    init {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        receipts.forEach { receipt ->
            try {
                daysWithReceipts.add(dateFormat.format(receipt.date.toDate()))
            } catch (e: Exception) {}
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MonthViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_month, parent, false)
        return MonthViewHolder(view)
    }

    override fun onBindViewHolder(holder: MonthViewHolder, position: Int) {
        val month = position
        holder.monthNameTextView.text = monthNames[month]

        // ✅ NEW: Make the month name clickable
        holder.monthNameTextView.setOnClickListener {
            val intent = Intent(holder.itemView.context, TransactionHistoryActivity::class.java)

            // Filter receipts for this specific month
            val receiptsInMonth = receipts.filter {
                try {
                    val cal = Calendar.getInstance()
                    cal.time = it.date.toDate()
                    cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) == month
                } catch (e: Exception) { false }
            }

            // Pass month name and receipts to next screen
            intent.putExtra("MONTH_YEAR", "${fullMonthNames[month]} $year")
            intent.putExtra("RECEIPTS_LIST", Gson().toJson(receiptsInMonth))
            holder.itemView.context.startActivity(intent)
        }

        val days = generateDaysForMonth(year, month)
        val dayAdapter = DayAdapter(days, daysWithReceipts, onDayClick)

        holder.daysRecyclerView.layoutManager = GridLayoutManager(holder.itemView.context, 7)
        holder.daysRecyclerView.adapter = dayAdapter
    }

    override fun getItemCount(): Int = 12

    class MonthViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val monthNameTextView: TextView = itemView.findViewById(R.id.monthNameTextView)
        val daysRecyclerView: RecyclerView = itemView.findViewById(R.id.daysRecyclerView)
    }

    private fun generateDaysForMonth(year: Int, month: Int): List<Calendar?> {
        val days = mutableListOf<Calendar?>()
        val cal = Calendar.getInstance()
        cal.set(year, month, 1)

        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
        val maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        for (i in 0 until firstDayOfWeek) {
            days.add(null)
        }

        for (i in 1..maxDays) {
            val dayCal = Calendar.getInstance()
            dayCal.set(year, month, i)
            days.add(dayCal)
        }

        return days
    }

    inner class DayAdapter(
        private val days: List<Calendar?>,
        private val activeDays: Set<String>,
        private val onDayClick: (Calendar) -> Unit
    ) : RecyclerView.Adapter<DayAdapter.DayViewHolder>() {

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        override fun onCreateViewHolder(parent: ViewGroup, itemViewType: Int): DayViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_day, parent, false)
            return DayViewHolder(view)
        }

        override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
            val cal = days[position]

            if (cal == null) {
                holder.dayText.text = ""
                holder.dayText.setTextColor(Color.TRANSPARENT)
                holder.itemView.isClickable = false
                holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            } else {
                val dayStr = cal.get(Calendar.DAY_OF_MONTH).toString()
                holder.dayText.text = dayStr
                val dateKey = dateFormat.format(cal.time)

                if (activeDays.contains(dateKey)) {
                    holder.dayText.setTextColor(Color.WHITE)
                    holder.dayText.setTypeface(null, Typeface.BOLD)
                    holder.itemView.setBackgroundResource(R.drawable.bg_calendar_active_day)
                } else {
                    holder.dayText.setTextColor(Color.BLACK)
                    holder.dayText.setTypeface(null, Typeface.NORMAL)
                    holder.itemView.setBackgroundColor(Color.TRANSPARENT)
                }

                holder.itemView.setOnClickListener {
                    onDayClick(cal)
                }
            }
        }

        override fun getItemCount(): Int = days.size

        inner class DayViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val dayText: TextView = itemView.findViewById(R.id.dayNumberTextView)
        }
    }
}