package com.jmti.budgetfy

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.jmti.budgetfy.adapters.CalendarAdapter
import com.jmti.budgetfy.models.Receipt
import com.jmti.budgetfy.repositories.FirebaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var calendarRecyclerView: RecyclerView
    private lateinit var yearTextView: TextView
    private lateinit var yearSpentLabel: TextView
    private lateinit var yearTotalTextView: TextView
    private lateinit var yearReceiptsValue: TextView
    private lateinit var yearActiveDaysValue: TextView
    private lateinit var yearMonthlyAvgValue: TextView
    private lateinit var prevYearButton: ImageButton
    private lateinit var nextYearButton: ImageButton
    private lateinit var progressBar: ProgressBar

    private val firebaseRepo = FirebaseRepository()
    private var allReceipts = listOf<Receipt>()
    private val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    private var shownYear = currentYear

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<Toolbar>(R.id.historyToolbar).setNavigationOnClickListener { finish() }

        calendarRecyclerView = findViewById(R.id.calendarRecyclerView)
        yearTextView = findViewById(R.id.yearTextView)
        yearSpentLabel = findViewById(R.id.yearSpentLabel)
        yearTotalTextView = findViewById(R.id.yearTotalTextView)
        yearReceiptsValue = findViewById(R.id.yearReceiptsValue)
        yearActiveDaysValue = findViewById(R.id.yearActiveDaysValue)
        yearMonthlyAvgValue = findViewById(R.id.yearMonthlyAvgValue)
        prevYearButton = findViewById(R.id.prevYearButton)
        nextYearButton = findViewById(R.id.nextYearButton)
        progressBar = findViewById(R.id.historyProgressBar)

        calendarRecyclerView.layoutManager = GridLayoutManager(this, 3)

        prevYearButton.setOnClickListener { showYear(shownYear - 1) }
        nextYearButton.setOnClickListener { showYear(shownYear + 1) }

        updateYearButtons()
        yearTextView.text = shownYear.toString()
        loadHistoryData()
    }

    /** Receipts are fetched once; switching years only re-renders. */
    private fun loadHistoryData() {
        val userId = firebaseRepo.getCurrentUser()?.uid
        if (userId == null) {
            finish()
            return
        }

        progressBar.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            try {
                allReceipts = firebaseRepo.getAllReceipts(userId)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showYear(shownYear)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@HistoryActivity, "Error loading history: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showYear(year: Int) {
        if (year > currentYear) return
        shownYear = year
        yearTextView.text = year.toString()
        updateYearButtons()

        val cal = Calendar.getInstance()
        val yearReceipts = allReceipts.filter {
            cal.time = it.date.toDate()
            cal.get(Calendar.YEAR) == year
        }
        val total = yearReceipts.sumOf { it.totalAmount }
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val activeDays = yearReceipts.map { dayFormat.format(it.date.toDate()) }.toSet().size

        // Average over months elapsed so far (this year) or all 12 (past years).
        val monthsElapsed = if (year == currentYear) Calendar.getInstance().get(Calendar.MONTH) + 1 else 12

        yearSpentLabel.text = "Spent in $year"
        yearTotalTextView.text = "RM %.2f".format(total)
        yearReceiptsValue.text = yearReceipts.size.toString()
        yearActiveDaysValue.text = activeDays.toString()
        yearMonthlyAvgValue.text = "RM %.2f".format(total / monthsElapsed)

        calendarRecyclerView.adapter = CalendarAdapter(year, allReceipts) { day -> openDay(day) }
    }

    private fun updateYearButtons() {
        val canGoNext = shownYear < currentYear
        nextYearButton.isEnabled = canGoNext
        nextYearButton.alpha = if (canGoNext) 1f else 0.35f
    }

    /** Opens the receipts of a tapped day in the transaction list. */
    private fun openDay(day: Calendar) {
        val cal = Calendar.getInstance()
        val onDay = allReceipts.filter {
            cal.time = it.date.toDate()
            cal.get(Calendar.YEAR) == day.get(Calendar.YEAR) &&
                    cal.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
        }
        if (onDay.isEmpty()) return

        val intent = Intent(this, TransactionHistoryActivity::class.java)
        intent.putExtra("MONTH_YEAR", SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(day.time))
        intent.putExtra("RECEIPTS_LIST", Gson().toJson(onDay))
        startActivity(intent)
    }
}
