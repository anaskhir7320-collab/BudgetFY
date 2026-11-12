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
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.gson.Gson
import com.jmti.budgetfy.adapters.CategoryLegendAdapter
import com.jmti.budgetfy.adapters.ExpenseAdapter
import com.jmti.budgetfy.models.BudgetCategory
import com.jmti.budgetfy.models.Receipt
import com.jmti.budgetfy.repositories.FirebaseRepository
import com.jmti.budgetfy.views.SpendingChartView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var categoryRecyclerView: RecyclerView
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var fabCapture: ExtendedFloatingActionButton
    private lateinit var totalSpentTextView: TextView
    private lateinit var totalBudgetTextView: TextView
    private lateinit var budgetProgressBar: LinearProgressIndicator
    private lateinit var budgetRemainingTextView: TextView
    private lateinit var greetingTextView: TextView
    private lateinit var monthPill: TextView
    private lateinit var statReceiptsValue: TextView
    private lateinit var statDailyAvgValue: TextView
    private lateinit var statLeftPerDayValue: TextView
    private lateinit var spendingChart: SpendingChartView
    private lateinit var chartSummaryTextView: TextView
    private lateinit var chartStartLabel: TextView
    private lateinit var chartEndLabel: TextView
    private lateinit var chartModeCumulative: TextView
    private lateinit var chartModeDaily: TextView
    private lateinit var seeAllTextView: TextView
    private lateinit var emptyTransactionsText: TextView
    private var hasBackgroundImage = false
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
    private lateinit var categoryAdapter: CategoryLegendAdapter
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
        budgetRemainingTextView = findViewById(R.id.budgetRemainingTextView)
        greetingTextView = findViewById(R.id.greetingTextView)
        monthPill = findViewById(R.id.monthPill)
        statReceiptsValue = findViewById(R.id.statReceiptsValue)
        statDailyAvgValue = findViewById(R.id.statDailyAvgValue)
        statLeftPerDayValue = findViewById(R.id.statLeftPerDayValue)
        spendingChart = findViewById(R.id.spendingChart)
        chartSummaryTextView = findViewById(R.id.chartSummaryTextView)
        chartStartLabel = findViewById(R.id.chartStartLabel)
        chartEndLabel = findViewById(R.id.chartEndLabel)
        chartModeCumulative = findViewById(R.id.chartModeCumulative)
        chartModeDaily = findViewById(R.id.chartModeDaily)
        seeAllTextView = findViewById(R.id.seeAllTextView)
        emptyTransactionsText = findViewById(R.id.emptyTransactionsText)

        greetingTextView.text = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> getString(R.string.good_morning)
            in 12..17 -> getString(R.string.good_afternoon)
            else -> getString(R.string.good_evening)
        }
        monthPill.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Calendar.getInstance().time)

        expenseAdapter = ExpenseAdapter { receipt ->
            val intent = Intent(this, ReceiptDetailActivity::class.java)
            intent.putExtra("RECEIPT_JSON", Gson().toJson(receipt))
            startActivity(intent)
        }
        categoryAdapter = CategoryLegendAdapter()

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
                    getString(R.string.setup_overall_first),
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

        chartModeCumulative.setOnClickListener { setChartMode(SpendingChartView.Mode.CUMULATIVE) }
        chartModeDaily.setOnClickListener { setChartMode(SpendingChartView.Mode.DAILY) }

        monthPill.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        seeAllTextView.setOnClickListener { openThisMonthTransactions() }

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

    /** Selected pill = solid blue with white text; the other = plain blue text. */
    private fun setChartMode(mode: SpendingChartView.Mode) {
        spendingChart.mode = mode
        val cumulative = mode == SpendingChartView.Mode.CUMULATIVE
        styleModePill(chartModeCumulative, selected = cumulative)
        styleModePill(chartModeDaily, selected = !cumulative)
    }

    private fun styleModePill(pill: TextView, selected: Boolean) {
        pill.setBackgroundResource(if (selected) R.drawable.bg_pill_selected else R.drawable.bg_pill_plain)
        pill.setTextColor(
            if (selected) Color.WHITE else ContextCompat.getColor(this, R.color.accent_text)
        )
    }

    private fun openThisMonthTransactions() {
        val intent = Intent(this, TransactionHistoryActivity::class.java)
        intent.putExtra("MONTH_YEAR", monthPill.text.toString())
        intent.putExtra("RECEIPTS_LIST", Gson().toJson(receipts))
        startActivity(intent)
    }

    private fun applyCollapseState() {
        val content = findViewById<View>(R.id.categoryContent)
        if (isCategoryExpanded) {
            content.visibility = View.VISIBLE
            rotateArrow(0f, 180f)
        } else {
            content.visibility = View.GONE
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

        val targetHeightPx = if (mode == "expanded") dpToPx(220) else dpToPx(176)
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
        hasBackgroundImage = false
        val secondaryText = ContextCompat.getColor(this, R.color.text_secondary)
        summaryTitleTextView.setTextColor(secondaryText)
        totalSpentTextView.setTextColor(ContextCompat.getColor(this, R.color.brand_blue))
        totalBudgetTextView.setTextColor(secondaryText)
        budgetStatusTextView.setTextColor(secondaryText)
        budgetRemainingTextView.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        budgetProgressBar.trackColor = ContextCompat.getColor(this, R.color.progress_track)
    }

    private fun setTextColorsForImage() {
        hasBackgroundImage = true
        val white = Color.WHITE
        val softWhite = Color.parseColor("#E6FFFFFF")
        summaryTitleTextView.setTextColor(softWhite)
        totalSpentTextView.setTextColor(white)
        totalBudgetTextView.setTextColor(softWhite)
        budgetStatusTextView.setTextColor(softWhite)
        budgetRemainingTextView.setTextColor(white)
        budgetProgressBar.trackColor = ContextCompat.getColor(this, R.color.progress_track_on_image)
    }

    private fun loadData() {
        val userId = firebaseRepo.getCurrentUser()?.uid
        if (userId == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        cleanupOldPhotosDaily(userId)
        restoreProfileOnce(userId)

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
                        getString(R.string.error_loading_data, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Receipt photos are kept in Firestore for 90 days (free-plan friendly).
     * Runs at most once a day so it doesn't cost reads on every launch.
     */
    private fun cleanupOldPhotosDaily(userId: String) {
        val prefs = getSharedPreferences("receipt_photo_cleanup", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("lastRun_$userId", 0L) < 24L * 60 * 60 * 1000) return
        CoroutineScope(Dispatchers.IO).launch {
            firebaseRepo.cleanupOldReceiptImages(userId, maxAgeDays = 90)
            prefs.edit().putLong("lastRun_$userId", now).apply()
        }
    }

    /**
     * After a reinstall or on a new phone, brings back the profile picture and
     * spending card from Firestore (once per account per install).
     */
    private fun restoreProfileOnce(userId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val changed = com.jmti.budgetfy.utils.ProfileSync.syncOnLaunch(this@MainActivity, userId)
            if (changed) withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) loadBackgroundPreference()
            }
        }
    }

    private fun updateDashboard() {
        val totalSpent = receipts.sumOf { it.totalAmount }
        val totalBudget = overallBudget
        val percentage = if (totalBudget > 0) ((totalSpent / totalBudget) * 100).toInt() else 0

        // Home screen widget shows the same numbers and the newest receipt.
        val latest = receipts.maxByOrNull { it.createdAt }
        com.jmti.budgetfy.widget.BudgetWidget.save(
            this, totalSpent, totalBudget,
            latest?.storeName?.ifEmpty { getString(R.string.receipt_default) }, latest?.totalAmount
        )

        totalSpentTextView.text = "RM ${String.format("%.2f", totalSpent)}"
        totalBudgetTextView.text = getString(R.string.of_budget, String.format("%.2f", totalBudget))
        budgetProgressBar.setProgressCompat(percentage.coerceAtMost(100), true)
        budgetStatusTextView.text = getString(R.string.percent_budget_used, percentage)

        // Progress color follows budget health: blue → orange at 80% → red when over.
        val healthColor = ContextCompat.getColor(
            this,
            when {
                percentage >= 100 -> R.color.danger_red
                percentage >= 80 -> R.color.warning_orange
                else -> R.color.brand_blue
            }
        )
        budgetProgressBar.setIndicatorColor(if (hasBackgroundImage && percentage < 80) Color.WHITE else healthColor)

        val remaining = totalBudget - totalSpent
        budgetRemainingTextView.text = when {
            totalBudget <= 0 -> ""
            remaining >= 0 -> getString(R.string.rm_left, String.format("%.2f", remaining))
            else -> getString(R.string.rm_over, String.format("%.2f", -remaining))
        }

        when {
            percentage >= 100 -> {
                alertCard.visibility = View.VISIBLE
                alertTextView.setBackgroundResource(R.drawable.bg_alert_danger)
                alertTextView.setTextColor(ContextCompat.getColor(this, R.color.alert_danger_text))
                alertTextView.text = getString(R.string.alert_over, String.format("%.2f", totalSpent - totalBudget))
            }
            percentage >= 80 -> {
                alertCard.visibility = View.VISIBLE
                alertTextView.setBackgroundResource(R.drawable.bg_alert_warning)
                alertTextView.setTextColor(ContextCompat.getColor(this, R.color.alert_warning_text))
                alertTextView.text = getString(R.string.alert_used, percentage)
            }
            else -> alertCard.visibility = View.GONE
        }

        updateStatsAndChart(totalSpent, totalBudget)

        emptyTransactionsText.visibility = if (receipts.isEmpty()) View.VISIBLE else View.GONE

        updateBudgetDonut(budgets)
    }

    /** Donut + legend for "Budget by category", biggest spend first. */
    private fun updateBudgetDonut(budgets: List<com.jmti.budgetfy.models.BudgetCategory>) {
        val sorted = budgets.sortedByDescending { it.spent }
        categoryAdapter.updateData(sorted)

        val spent = sorted.sumOf { it.spent }
        val limit = sorted.sumOf { it.limit }
        val pct = if (limit > 0) ((spent / limit) * 100).toInt() else 0
        val limitText = if (limit % 1.0 == 0.0) limit.toLong().toString() else String.format("%.2f", limit)
        findViewById<com.jmti.budgetfy.views.BudgetDonutView>(R.id.budgetDonut).setData(
            sorted.map { com.jmti.budgetfy.utils.CategoryStyle.color(this, it.name) to it.spent },
            limit,
            "RM ${String.format("%.2f", spent)}",
            getString(R.string.donut_of, limitText, pct)
        )
    }

    /** Stats row (receipts, daily average, left per day) and the spending chart. */
    private fun updateStatsAndChart(totalSpent: Double, totalBudget: Double) {
        val now = Calendar.getInstance()
        val today = now.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = now.getActualMaximum(Calendar.DAY_OF_MONTH)
        val daysLeft = daysInMonth - today + 1   // including today

        statReceiptsValue.text = receipts.size.toString()
        statDailyAvgValue.text = "RM ${String.format("%.2f", totalSpent / today)}"
        val leftPerDay = if (totalBudget > 0) ((totalBudget - totalSpent) / daysLeft).coerceAtLeast(0.0) else 0.0
        statLeftPerDayValue.text = "RM ${String.format("%.2f", leftPerDay)}"

        val daily = DoubleArray(daysInMonth)
        val cal = Calendar.getInstance()
        for (receipt in receipts) {
            cal.time = receipt.date.toDate()
            val day = cal.get(Calendar.DAY_OF_MONTH)
            if (day in 1..daysInMonth) daily[day - 1] += receipt.totalAmount
        }
        spendingChart.setData(daily, daysInMonth, today, totalBudget)

        val monthShort = SimpleDateFormat("MMM", Locale.getDefault()).format(now.time)
        chartStartLabel.text = "1 $monthShort"
        chartEndLabel.text = "$daysInMonth $monthShort"
        chartSummaryTextView.text = when {
            totalBudget <= 0 -> getString(R.string.this_month)
            totalSpent <= totalBudget * today / daysInMonth -> getString(R.string.on_track)
            else -> getString(R.string.ahead_of_pace)
        }
        chartSummaryTextView.setTextColor(
            ContextCompat.getColor(
                this,
                if (chartSummaryTextView.text == getString(R.string.ahead_of_pace)) R.color.warning_orange else R.color.success_green
            )
        )
        if (totalBudget <= 0) chartSummaryTextView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
    }

    override fun onResume() {
        super.onResume()
        // Coming back from another tab: Home is the screen showing again.
        bottomNav.menu.findItem(R.id.nav_home)?.isChecked = true
        loadBackgroundPreference()
        loadData()
    }
}