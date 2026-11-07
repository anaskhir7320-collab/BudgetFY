package com.jmti.budgetfy

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.jmti.budgetfy.adapters.CategoryBudgetAdapter
import com.jmti.budgetfy.adapters.ExpenseAdapter
import com.jmti.budgetfy.models.BudgetCategory
import com.jmti.budgetfy.models.Receipt
import com.jmti.budgetfy.repositories.FirebaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var categoryRecyclerView: RecyclerView
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var fabCapture: FloatingActionButton
    private lateinit var totalSpentTextView: TextView
    private lateinit var totalBudgetTextView: TextView
    private lateinit var budgetProgressBar: android.widget.ProgressBar
    private lateinit var budgetStatusTextView: TextView
    private lateinit var summaryTitleTextView: TextView
    private lateinit var alertCard: MaterialCardView
    private lateinit var alertTextView: TextView
    private lateinit var categoryHeaderRow: View
    private lateinit var collapseArrow: ImageView
    private lateinit var summaryBackgroundImage: ImageView
    private lateinit var summaryOverlay: View
    private lateinit var summaryFrame: FrameLayout

    private val firebaseRepo = FirebaseRepository()
    private lateinit var expenseAdapter: ExpenseAdapter
    private lateinit var categoryAdapter: CategoryBudgetAdapter
    private var receipts = listOf<Receipt>()
    private var budgets = listOf<BudgetCategory>()

    private var overallBudget: Double = 0.0
    private var isCategoryExpanded = false

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("budgetfy_prefs", MODE_PRIVATE)

        initViews()
        setupListeners()
        loadBackgroundPreference()
        loadData()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        categoryRecyclerView = findViewById(R.id.categoryRecyclerView)
        bottomNav = findViewById(R.id.bottomNavigationView)
        fabCapture = findViewById(R.id.fabCapture)
        totalSpentTextView = findViewById(R.id.totalSpentTextView)
        totalBudgetTextView = findViewById(R.id.totalBudgetTextView)
        budgetProgressBar = findViewById(R.id.budgetProgressBar)
        budgetStatusTextView = findViewById(R.id.budgetStatusTextView)
        summaryTitleTextView = findViewById(R.id.summaryTitleTextView)
        alertCard = findViewById(R.id.alertCard)
        alertTextView = findViewById(R.id.alertTextView)
        categoryHeaderRow = findViewById(R.id.categoryHeaderRow)
        collapseArrow = findViewById(R.id.collapseArrow)
        summaryBackgroundImage = findViewById(R.id.summaryBackgroundImage)
        summaryOverlay = findViewById(R.id.summaryOverlay)
        summaryFrame = findViewById(R.id.summaryFrame)

        expenseAdapter = ExpenseAdapter { receipt ->
            val intent = Intent(this, ReceiptDetailActivity::class.java)
            intent.putExtra("RECEIPT_JSON", Gson().toJson(receipt))
            startActivity(intent)
        }
        categoryAdapter = CategoryBudgetAdapter()

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = expenseAdapter

        categoryRecyclerView.layoutManager = LinearLayoutManager(this)
        categoryRecyclerView.adapter = categoryAdapter

        applyCollapseState()
    }

    private fun setupListeners() {
        fabCapture.setOnClickListener {
            if (overallBudget <= 0) {
                Toast.makeText(
                    this,
                    "Please setup the overall budget limit first",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, CaptureReceiptActivity::class.java))
        }

        categoryHeaderRow.setOnClickListener {
            isCategoryExpanded = !isCategoryExpanded
            applyCollapseState()
        }

        // ✅ UPDATED: Now includes History tab
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    loadData()
                    true
                }
                R.id.nav_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    true
                }
                R.id.nav_budget -> {
                    startActivity(Intent(this, AddBudgetActivity::class.java))
                    true
                }
                R.id.nav_profile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun applyCollapseState() {
        if (isCategoryExpanded) {
            categoryRecyclerView.visibility = View.VISIBLE
            rotateArrow(0f, 180f)
        } else {
            categoryRecyclerView.visibility = View.GONE
            rotateArrow(180f, 0f)
        }
    }

    private fun rotateArrow(from: Float, to: Float) {
        val anim = RotateAnimation(
            from, to,
            RotateAnimation.RELATIVE_TO_SELF, 0.5f,
            RotateAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 250
            fillAfter = true
        }
        collapseArrow.startAnimation(anim)
    }

    private fun loadBackgroundPreference() {
        val mode = prefs.getString("card_mode", "standard") ?: "standard"
        val savedUri = prefs.getString("card_bg_uri", null)

        val targetHeightPx = if (mode == "expanded") dpToPx(220) else dpToPx(160)
        summaryFrame.layoutParams.height = targetHeightPx
        summaryFrame.requestLayout()

        if (!savedUri.isNullOrEmpty()) {
            try {
                summaryBackgroundImage.setImageURI(Uri.parse(savedUri))
                summaryBackgroundImage.visibility = View.VISIBLE
                summaryOverlay.visibility = View.VISIBLE
                setTextColorsForImage()
            } catch (e: Exception) {
                summaryBackgroundImage.visibility = View.GONE
                summaryOverlay.visibility = View.GONE
                setTextColorsForNoImage()
            }
        } else {
            summaryBackgroundImage.visibility = View.GONE
            summaryOverlay.visibility = View.GONE
            setTextColorsForNoImage()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun setTextColorsForNoImage() {
        val secondaryText = Color.parseColor("#6B6B6B")
        val primaryBlue = Color.parseColor("#0078CC")
        summaryTitleTextView.setTextColor(secondaryText)
        totalSpentTextView.setTextColor(primaryBlue)
        totalBudgetTextView.setTextColor(secondaryText)
        budgetStatusTextView.setTextColor(secondaryText)
    }

    private fun setTextColorsForImage() {
        val white = Color.WHITE
        val softWhite = Color.parseColor("#F0F0F0")
        summaryTitleTextView.setTextColor(softWhite)
        totalSpentTextView.setTextColor(white)
        totalBudgetTextView.setTextColor(softWhite)
        budgetStatusTextView.setTextColor(softWhite)
    }

    private fun loadData() {
        val userId = firebaseRepo.getCurrentUser()?.uid
        if (userId == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                overallBudget = firebaseRepo.getOverallBudget(userId)
                receipts = firebaseRepo.getReceiptsForCurrentMonth(userId)
                budgets = firebaseRepo.getBudgets(userId)

                withContext(Dispatchers.Main) {
                    expenseAdapter.updateData(receipts)
                    updateDashboard()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error loading data: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun updateDashboard() {
        val totalSpent = receipts.sumOf { it.totalAmount }
        val totalBudget = overallBudget
        val percentage = if (totalBudget > 0) ((totalSpent / totalBudget) * 100).toInt() else 0

        totalSpentTextView.text = "RM ${String.format("%.2f", totalSpent)}"
        totalBudgetTextView.text = "of RM ${String.format("%.2f", totalBudget)} budget"
        budgetProgressBar.progress = percentage.coerceAtMost(100)
        budgetStatusTextView.text = "$percentage% of budget used"

        when {
            percentage >= 100 -> {
                alertCard.visibility = View.VISIBLE
                alertCard.setCardBackgroundColor(Color.parseColor("#F8D7DA"))
                alertTextView.setTextColor(Color.parseColor("#721C24"))
                alertTextView.text = "🚨 DANGER! You have exceeded your overall budget by RM ${String.format("%.2f", totalSpent - totalBudget)}"
            }
            percentage >= 80 -> {
                alertCard.visibility = View.VISIBLE
                alertCard.setCardBackgroundColor(Color.parseColor("#FFF3CD"))
                alertTextView.setTextColor(Color.parseColor("#856404"))
                alertTextView.text = "⚠️ WARNING! You have used $percentage% of your overall budget."
            }
            else -> alertCard.visibility = View.GONE
        }

        categoryAdapter.updateData(budgets)
    }

    override fun onResume() {
        super.onResume()
        loadBackgroundPreference()
        loadData()
    }
}