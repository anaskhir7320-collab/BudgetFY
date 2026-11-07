package com.jmti.budgetfy

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jmti.budgetfy.adapters.CalendarAdapter
import com.jmti.budgetfy.repositories.FirebaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class HistoryActivity : AppCompatActivity() {

    private lateinit var calendarRecyclerView: RecyclerView
    private lateinit var yearTextView: android.widget.TextView
    private lateinit var progressBar: android.widget.ProgressBar

    private val firebaseRepo = FirebaseRepository()
    private var allReceipts = listOf<com.jmti.budgetfy.models.Receipt>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val toolbar = findViewById<Toolbar>(R.id.historyToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        calendarRecyclerView = findViewById(R.id.calendarRecyclerView)
        yearTextView = findViewById(R.id.yearTextView)

        // Add a loading spinner programmatically
        progressBar = android.widget.ProgressBar(this).apply {
            visibility = View.VISIBLE
        }
        val root = findViewById<android.widget.FrameLayout>(android.R.id.content)
        root.addView(progressBar)

        // Set current year
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        yearTextView.text = currentYear.toString()

        loadHistoryData(currentYear)
    }

    private fun loadHistoryData(year: Int) {
        val userId = firebaseRepo.getCurrentUser()?.uid
        if (userId == null) {
            finish()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                allReceipts = firebaseRepo.getAllReceipts(userId)

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE

                    // Initialize Calendar with the fetched data
                    val calendarAdapter = CalendarAdapter(year, allReceipts) { clickedDate ->
                        // Show a toast when a user taps a day
                        val dateFormat = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
                        val dateStr = dateFormat.format(clickedDate.time)

                        // Check if there are receipts on this day
                        val receiptsOnDay = allReceipts.filter {
                            val receiptCal = Calendar.getInstance()
                            receiptCal.time = it.date.toDate()
                            receiptCal.get(Calendar.YEAR) == clickedDate.get(Calendar.YEAR) &&
                                    receiptCal.get(Calendar.MONTH) == clickedDate.get(Calendar.MONTH) &&
                                    receiptCal.get(Calendar.DAY_OF_MONTH) == clickedDate.get(Calendar.DAY_OF_MONTH)
                        }

                        if (receiptsOnDay.isNotEmpty()) {
                            // For Phase 2, we just show a summary Toast.
                            // In Phase 3, we can open a bottom sheet or new activity with the list.
                            Toast.makeText(this@HistoryActivity, "Found ${receiptsOnDay.size} receipt(s) on $dateStr", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@HistoryActivity, "No receipts on $dateStr", Toast.LENGTH_SHORT).show()
                        }
                    }

                    // ✅ Grid with 3 columns for Jan, Feb, Mar layout
                    calendarRecyclerView.layoutManager = GridLayoutManager(this@HistoryActivity, 3)
                    calendarRecyclerView.adapter = calendarAdapter
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@HistoryActivity, "Error loading history: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}