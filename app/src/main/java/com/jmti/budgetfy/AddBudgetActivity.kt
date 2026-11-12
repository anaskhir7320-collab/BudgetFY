package com.jmti.budgetfy

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jmti.budgetfy.adapters.CategoryBudgetAdapter
import com.jmti.budgetfy.repositories.FirebaseRepository
import com.jmti.budgetfy.utils.CategorizationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddBudgetActivity : AppCompatActivity() {

    private lateinit var overallBudgetEditText: TextInputEditText
    private lateinit var saveOverallButton: MaterialButton
    private lateinit var categoryDropdown: AutoCompleteTextView
    private lateinit var limitEditText: TextInputEditText
    private lateinit var saveButton: MaterialButton
    private lateinit var maxHintTextView: TextView
    private lateinit var toolbar: Toolbar
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var budgetsRecyclerView: RecyclerView
    private lateinit var budgetsEmptyText: TextView
    private val budgetsAdapter = CategoryBudgetAdapter()

    private val firebaseRepo = FirebaseRepository()
    private val categorizationEngine = CategorizationEngine()

    private var currentOverallBudget: Double = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_budget)

        initViews()
        setupListeners()
        loadExistingOverall()
        loadCategoryBudgets()
    }

    /** Fills "Your category budgets" (spent vs limit for each category). */
    private fun loadCategoryBudgets() {
        val userId = firebaseRepo.getCurrentUser()?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val budgets = try {
                firebaseRepo.getBudgets(userId)
            } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                budgetsAdapter.updateData(budgets)
                budgetsEmptyText.visibility = if (budgets.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun initViews() {
        overallBudgetEditText = findViewById(R.id.overallBudgetEditText)
        saveOverallButton = findViewById(R.id.saveOverallButton)
        categoryDropdown = findViewById(R.id.categoryDropdown)
        limitEditText = findViewById(R.id.limitEditText)
        saveButton = findViewById(R.id.saveButton)
        maxHintTextView = findViewById(R.id.maxHintTextView)
        toolbar = findViewById(R.id.toolbar)
        progressBar = findViewById(R.id.progressBar)
        budgetsRecyclerView = findViewById(R.id.budgetsRecyclerView)
        budgetsEmptyText = findViewById(R.id.budgetsEmptyText)
        budgetsRecyclerView.layoutManager = LinearLayoutManager(this)
        budgetsRecyclerView.adapter = budgetsAdapter

        toolbar.setNavigationOnClickListener { finish() }

        // Populate category dropdown
        val categories = categorizationEngine.getCategories().toMutableList()
        categories.add("Other")
        val adapter = ArrayAdapter(
            this,
            R.layout.item_dropdown,
            categories.distinct().sorted().map { com.jmti.budgetfy.utils.CategoryNames.display(this, it) }
        )
        categoryDropdown.setAdapter(adapter)
    }

    private fun setupListeners() {
        saveOverallButton.setOnClickListener { saveOverall() }
        saveButton.setOnClickListener { saveBudget() }
    }

    private fun loadExistingOverall() {
        val userId = firebaseRepo.getCurrentUser()?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val overall = firebaseRepo.getOverallBudget(userId)
            withContext(Dispatchers.Main) {
                currentOverallBudget = overall
                if (overall > 0) {
                    overallBudgetEditText.setText(String.format("%.2f", overall))
                    maxHintTextView.text = getString(R.string.max_per_category, String.format("%.2f", overall))
                } else {
                    maxHintTextView.text = getString(R.string.set_overall_first)
                }
            }
        }
    }

    private fun saveOverall() {
        val text = overallBudgetEditText.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, getString(R.string.enter_an_amount), Toast.LENGTH_SHORT).show()
            return
        }
        val amount = text.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            Toast.makeText(this, getString(R.string.enter_valid_amount), Toast.LENGTH_SHORT).show()
            return
        }

        val userId = firebaseRepo.getCurrentUser()?.uid ?: return
        saveOverallButton.isEnabled = false
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val success = firebaseRepo.setOverallBudget(userId, amount)
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                saveOverallButton.isEnabled = true
                if (success) {
                    currentOverallBudget = amount
                    maxHintTextView.text = getString(R.string.max_per_category, String.format("%.2f", amount))
                    Toast.makeText(
                        this@AddBudgetActivity,
                        getString(R.string.overall_budget_set, String.format("%.2f", amount)),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this@AddBudgetActivity, getString(R.string.failed_to_save), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveBudget() {
        val category = com.jmti.budgetfy.utils.CategoryNames.toEnglish(this, categoryDropdown.text.toString().trim())
        val limitText = limitEditText.text.toString().trim()

        if (currentOverallBudget <= 0) {
            Toast.makeText(
                this,
                getString(R.string.setup_overall_first),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (category.isEmpty() || limitText.isEmpty()) {
            Toast.makeText(this, getString(R.string.fill_all_fields), Toast.LENGTH_SHORT).show()
            return
        }

        val limit = limitText.toDoubleOrNull()
        if (limit == null || limit <= 0) {
            Toast.makeText(this, getString(R.string.enter_valid_budget), Toast.LENGTH_SHORT).show()
            return
        }

        if (limit > currentOverallBudget) {
            Toast.makeText(
                this,
                getString(R.string.category_limit_exceeds, String.format("%.2f", limit), String.format("%.2f", currentOverallBudget)),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val userId = firebaseRepo.getCurrentUser()?.uid ?: return
        saveButton.isEnabled = false
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val success = firebaseRepo.addOrUpdateBudget(userId, category, limit)
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                saveButton.isEnabled = true
                if (success) {
                    Toast.makeText(
                        this@AddBudgetActivity,
                        getString(R.string.saved_category_budget, com.jmti.budgetfy.utils.CategoryNames.display(this@AddBudgetActivity, category), String.format("%.2f", limit)),
                        Toast.LENGTH_LONG
                    ).show()
                    limitEditText.setText("")
                    categoryDropdown.setText("", false)
                    loadCategoryBudgets()
                } else {
                    Toast.makeText(this@AddBudgetActivity, getString(R.string.failed_to_save), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}