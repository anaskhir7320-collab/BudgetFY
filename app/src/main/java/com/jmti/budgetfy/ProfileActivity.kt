package com.jmti.budgetfy

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.jmti.budgetfy.repositories.FirebaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileActivity : AppCompatActivity() {

    private lateinit var profileAvatarImage: ShapeableImageView
    private lateinit var emailTextView: TextView
    private lateinit var nameTextView: TextView
    private lateinit var editProfileCard: MaterialCardView
    private lateinit var budgetSettingsHeaderRow: View
    private lateinit var budgetCollapseArrow: ImageView
    private lateinit var resetBudgetCard: MaterialCardView
    private lateinit var fullResetCard: MaterialCardView
    private lateinit var themeDayButton: View
    private lateinit var themeNightButton: View
    private lateinit var dayIndicator: View
    private lateinit var nightIndicator: View
    private lateinit var rateRow: View
    private lateinit var contactRow: View
    private lateinit var termsRow: View
    private lateinit var privacyRow: View
    private lateinit var logoutCard: MaterialCardView
    private lateinit var toolbar: Toolbar
    private lateinit var progressBar: ProgressBar

    private val firebaseRepo = FirebaseRepository()

    private lateinit var prefs: SharedPreferences

    private var isBudgetSettingsExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        prefs = getSharedPreferences("budgetfy_prefs", MODE_PRIVATE)

        initViews()
        setupListeners()
        loadUserData()
        loadAvatarImage()
    }

    private fun initViews() {
        profileAvatarImage = findViewById(R.id.profileAvatarImage)
        emailTextView = findViewById(R.id.profileEmailTextView)
        nameTextView = findViewById(R.id.profileNameTextView)
        editProfileCard = findViewById(R.id.editProfileCard)
        budgetSettingsHeaderRow = findViewById(R.id.budgetSettingsHeaderRow)
        budgetCollapseArrow = findViewById(R.id.budgetCollapseArrow)
        resetBudgetCard = findViewById(R.id.resetBudgetCard)
        fullResetCard = findViewById(R.id.fullResetCard)
        themeDayButton = findViewById(R.id.themeDayButton)
        themeNightButton = findViewById(R.id.themeNightButton)
        dayIndicator = findViewById(R.id.dayIndicator)
        nightIndicator = findViewById(R.id.nightIndicator)
        rateRow = findViewById(R.id.rateRow)
        contactRow = findViewById(R.id.contactRow)
        termsRow = findViewById(R.id.termsRow)
        privacyRow = findViewById(R.id.privacyRow)
        logoutCard = findViewById(R.id.logoutCard)
        toolbar = findViewById(R.id.toolbar)

        progressBar = ProgressBar(this).apply { visibility = View.GONE }
        val root = findViewById<View>(android.R.id.content)
        (root as? android.widget.FrameLayout)?.addView(progressBar)

        toolbar.setNavigationOnClickListener { finish() }

        applyBudgetSettingsState()
    }

    private fun setupListeners() {
        // ✅ NEW: Open Edit Profile screen
        editProfileCard.setOnClickListener {
            val intent = Intent(this, EditProfileActivity::class.java)
            startActivity(intent)
        }

        budgetSettingsHeaderRow.setOnClickListener {
            isBudgetSettingsExpanded = !isBudgetSettingsExpanded
            applyBudgetSettingsState()
        }

        resetBudgetCard.setOnClickListener { confirmResetOverall() }
        fullResetCard.setOnClickListener { confirmFullReset() }
        logoutCard.setOnClickListener { logoutUser() }
        setupDebugTokenRow()

        // Day / Night: saved, and every open screen redraws in the new mode.
        themeDayButton.setOnClickListener { com.jmti.budgetfy.utils.ThemePrefs.setNight(this, false) }
        themeNightButton.setOnClickListener { com.jmti.budgetfy.utils.ThemePrefs.setNight(this, true) }
        showThemeSelection(com.jmti.budgetfy.utils.ThemePrefs.isNight(this))

        rateRow.setOnClickListener {
            // TODO: Open Play Store link later
        }
        contactRow.setOnClickListener {
            // TODO: Open email intent later
        }
        termsRow.setOnClickListener {
            Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show()
        }
        privacyRow.setOnClickListener {
            Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    /** Highlights the selected Day / Night option. */
    private fun showThemeSelection(night: Boolean) {
        val selected = androidx.core.content.ContextCompat.getColor(this, R.color.brand_blue_dark)
        val normal = androidx.core.content.ContextCompat.getColor(this, R.color.text_primary)
        themeDayButton.setBackgroundResource(if (night) R.drawable.bg_theme_option else R.drawable.bg_theme_option_selected)
        themeNightButton.setBackgroundResource(if (night) R.drawable.bg_theme_option_selected else R.drawable.bg_theme_option)
        dayIndicator.setBackgroundResource(if (night) R.drawable.bg_dot_unselected else R.drawable.bg_dot_selected)
        nightIndicator.setBackgroundResource(if (night) R.drawable.bg_dot_selected else R.drawable.bg_dot_unselected)
        findViewById<TextView>(R.id.dayLabel).setTextColor(if (night) normal else selected)
        findViewById<TextView>(R.id.nightLabel).setTextColor(if (night) selected else normal)
    }

    private fun applyBudgetSettingsState() {
        if (isBudgetSettingsExpanded) {
            resetBudgetCard.visibility = View.VISIBLE
            fullResetCard.visibility = View.VISIBLE
            rotateArrow(0f, 180f)
        } else {
            resetBudgetCard.visibility = View.GONE
            fullResetCard.visibility = View.GONE
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
        budgetCollapseArrow.startAnimation(anim)
    }

    /**
     * Debug builds only: lets a tester see and send their App Check debug
     * token, which the owner adds in Firebase so Gemini reading works for them.
     */
    private fun setupDebugTokenRow() {
        val card = findViewById<View>(R.id.debugTokenCard)
        if (AppCheckSetup.debugToken(this) == null && !BuildConfig.DEBUG) return
        card.visibility = View.VISIBLE
        card.setOnClickListener {
            val token = AppCheckSetup.debugToken(this)
            if (token == null) {
                Toast.makeText(this, "Token not ready yet. Scan a receipt once, then try again.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("App Check token")
                .setMessage("Send this code to the app owner. Keep it private.\n\n$token")
                .setPositiveButton("Copy") { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("App Check token", token))
                    Toast.makeText(this, "Token copied", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Share") { _, _ ->
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "BudgetFY App Check token: $token"),
                        "Send token"
                    ))
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun loadAvatarImage() {
        val saved = prefs.getString("profile_avatar_uri", null) ?: return
        try {
            profileAvatarImage.setImageURI(Uri.parse(saved))
        } catch (e: Exception) {
            prefs.edit().remove("profile_avatar_uri").apply()
        }
    }

    private fun loadUserData() {
        val user = firebaseRepo.getCurrentUser()
        if (user != null) {
            emailTextView.text = user.email ?: "No email"

            // ✅ Prefer custom display name from prefs, fallback to Firebase display name
            val customName = prefs.getString("display_name", null)
            nameTextView.text = customName
                ?: user.displayName
                        ?: "User"
        }
    }

    private fun confirmResetOverall() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset overall budget?")
            .setMessage(
                "This will:\n\n" +
                        "• Set overall budget to RM 0.00\n" +
                        "• Delete all category budgets for this month\n" +
                        "• Keep receipts\n\n" +
                        "You'll need to setup budgets again.\n\n" +
                        "Continue?"
            )
            .setPositiveButton("Reset") { _, _ -> performResetOverall() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performResetOverall() {
        val userId = firebaseRepo.getCurrentUser()?.uid ?: return
        showProgress(true)
        CoroutineScope(Dispatchers.IO).launch {
            val success = firebaseRepo.resetOverallBudget(userId)
            withContext(Dispatchers.Main) {
                showProgress(false)
                Toast.makeText(
                    this@ProfileActivity,
                    if (success) "✅ Overall budget reset to RM 0.00" else "❌ Failed",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun confirmFullReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Full reset?")
            .setMessage(
                "This will DELETE:\n\n" +
                        "• All receipts for this month\n" +
                        "• All category budgets for this month\n" +
                        "• Overall budget reset to RM 0.00\n\n" +
                        "⚠️ This CANNOT be undone."
            )
            .setPositiveButton("Delete All") { _, _ -> performFullReset() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performFullReset() {
        val userId = firebaseRepo.getCurrentUser()?.uid ?: return
        showProgress(true)
        CoroutineScope(Dispatchers.IO).launch {
            val success = firebaseRepo.resetCurrentMonthFull(userId)
            withContext(Dispatchers.Main) {
                showProgress(false)
                Toast.makeText(
                    this@ProfileActivity,
                    if (success) "✅ Full reset complete" else "❌ Failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun logoutUser() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Log out?")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Logout") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    firebaseRepo.logoutUser()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ProfileActivity, "Logged out", Toast.LENGTH_SHORT).show()
                        navigateToLogin()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun showProgress(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ✅ Reload when coming back from EditProfileActivity
    override fun onResume() {
        super.onResume()
        loadUserData()
        loadAvatarImage()
    }
}