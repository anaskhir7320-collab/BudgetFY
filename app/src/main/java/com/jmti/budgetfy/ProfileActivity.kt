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

        // Language: English or Bahasa Melayu; the app redraws in the new language.
        val malay = isMalay()
        findViewById<TextView>(R.id.languageValueTextView).text =
            getString(if (malay) R.string.language_malay else R.string.language_english)
        findViewById<View>(R.id.languageRow).setOnClickListener { chooseLanguage() }

        rateRow.setOnClickListener {
            // TODO: Open Play Store link later
        }
        contactRow.setOnClickListener {
            // TODO: Open email intent later
        }
        termsRow.setOnClickListener {
            Toast.makeText(this, getString(R.string.coming_soon), Toast.LENGTH_SHORT).show()
        }
        privacyRow.setOnClickListener {
            Toast.makeText(this, getString(R.string.coming_soon), Toast.LENGTH_SHORT).show()
        }
    }

    private fun isMalay(): Boolean =
        androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
            .toLanguageTags().startsWith("ms")

    /** English / Bahasa Melayu picker. Android remembers the choice for the app. */
    private fun chooseLanguage() {
        val options = arrayOf(getString(R.string.language_english), getString(R.string.language_malay))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.language_title))
            .setSingleChoiceItems(options, if (isMalay()) 1 else 0) { dialog, which ->
                dialog.dismiss()
                val tag = if (which == 1) "ms" else "en"
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                )
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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
                Toast.makeText(this, getString(R.string.token_not_ready), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // QR of the code so the owner can scan it with their phone camera.
            val dp = resources.displayMetrics.density
            val qr = AppCheckSetup.tokenQr(token, (220 * dp).toInt())
            val qrView = qr?.let { bitmap ->
                android.widget.FrameLayout(this).apply {
                    setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
                    addView(android.widget.ImageView(this@ProfileActivity).apply {
                        setImageBitmap(bitmap)
                        contentDescription = getString(R.string.app_check_token)
                        // White frame keeps the QR scannable in Night mode too.
                        setBackgroundColor(android.graphics.Color.WHITE)
                        val pad = (8 * dp).toInt()
                        setPadding(pad, pad, pad, pad)
                    }, android.widget.FrameLayout.LayoutParams(
                        (236 * dp).toInt(), (236 * dp).toInt(), android.view.Gravity.CENTER_HORIZONTAL
                    ))
                }
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.app_check_token))
                .setMessage(getString(R.string.token_send_message, token))
                .apply { if (qrView != null) setView(qrView) }
                .setPositiveButton(getString(R.string.copy)) { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("App Check token", token))
                    Toast.makeText(this, getString(R.string.token_copied), Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton(getString(R.string.share)) { _, _ ->
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, getString(R.string.token_share_text, token)),
                        getString(R.string.send_token)
                    ))
                }
                .setNegativeButton(getString(R.string.close), null)
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
            emailTextView.text = user.email ?: getString(R.string.no_email)

            // ✅ Prefer custom display name from prefs, fallback to Firebase display name
            val customName = prefs.getString("display_name", null)
            nameTextView.text = customName
                ?: user.displayName
                        ?: getString(R.string.user)
        }
    }

    private fun confirmResetOverall() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.reset_overall_title))
            .setMessage(getString(R.string.reset_overall_message))
            .setPositiveButton(getString(R.string.reset)) { _, _ -> performResetOverall() }
            .setNegativeButton(getString(R.string.cancel), null)
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
                    getString(if (success) R.string.overall_reset_done else R.string.failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun confirmFullReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.full_reset_title))
            .setMessage(getString(R.string.full_reset_message))
            .setPositiveButton(getString(R.string.delete_all)) { _, _ -> performFullReset() }
            .setNegativeButton(getString(R.string.cancel), null)
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
                    getString(if (success) R.string.full_reset_done else R.string.failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun logoutUser() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.logout_title))
            .setMessage(getString(R.string.logout_message))
            .setPositiveButton(getString(R.string.logout)) { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    firebaseRepo.logoutUser()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ProfileActivity, getString(R.string.logged_out), Toast.LENGTH_SHORT).show()
                        navigateToLogin()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
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