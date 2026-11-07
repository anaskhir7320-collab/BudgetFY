package com.jmti.budgetfy

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jmti.budgetfy.adapters.ExpenseAdapter
import com.jmti.budgetfy.models.Receipt

class TransactionHistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transaction_history)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val monthTitleTextView = findViewById<android.widget.TextView>(R.id.monthTitleTextView)
        val totalSpentTextView = findViewById<android.widget.TextView>(R.id.totalSpentTextView)
        val itemCountTextView = findViewById<android.widget.TextView>(R.id.itemCountTextView)
        val recyclerView = findViewById<RecyclerView>(R.id.receiptsRecyclerView)

        // Get data passed from HistoryActivity
        val monthYear = intent.getStringExtra("MONTH_YEAR") ?: "History"
        val receiptsJson = intent.getStringExtra("RECEIPTS_LIST")

        monthTitleTextView.text = monthYear

        // Parse the JSON list back to a Kotlin List
        val listType = object : TypeToken<List<Receipt>>() {}.type
        val receipts: List<Receipt> = Gson().fromJson(receiptsJson, listType) ?: emptyList()

        // ✅ NEW: Calculate total spent and count
        val totalSpent = receipts.sumOf { it.totalAmount }
        totalSpentTextView.text = "RM ${String.format("%.2f", totalSpent)}"
        itemCountTextView.text = "${receipts.size} receipts scanned"

        // Set up the adapter
        val adapter = ExpenseAdapter { receipt ->
            val detailIntent = android.content.Intent(this, ReceiptDetailActivity::class.java)
            detailIntent.putExtra("RECEIPT_JSON", Gson().toJson(receipt))
            startActivity(detailIntent)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        adapter.updateData(receipts)
    }
}