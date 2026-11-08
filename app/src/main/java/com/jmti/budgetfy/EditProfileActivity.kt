package com.jmti.budgetfy

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.jmti.budgetfy.repositories.FirebaseRepository
import com.jmti.budgetfy.utils.DialogForm
import com.jmti.budgetfy.utils.ProfileSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditProfileActivity : AppCompatActivity() {

    private lateinit var cancelButton: TextView
    private lateinit var saveButton: TextView
    private lateinit var editProfileAvatar: ShapeableImageView
    private lateinit var profilePicContainer: FrameLayout
    private lateinit var changePhotoText: TextView
    private lateinit var displayNameCard: MaterialCardView
    private lateinit var displayNameValueText: TextView
    private lateinit var sizeStandardPill: TextView
    private lateinit var sizeExpandedPill: TextView
    private lateinit var cardPreviewBox: FrameLayout
    private lateinit var cardPreviewImage: ImageView
    private lateinit var cardPreviewOverlay: View
    private lateinit var previewTitle: TextView
    private lateinit var previewAmount: TextView
    private lateinit var previewBudget: TextView
    private lateinit var previewProgress: LinearProgressIndicator
    private lateinit var uploadCardButton: MaterialButton
    private lateinit var removeCardButton: MaterialButton

    /** Ready-made backgrounds, in grid order: top-left, top-right, bottom-left, bottom-right. */
    private val presetNames = listOf("card_preset_1", "card_preset_2", "card_preset_3", "card_preset_4")
    private lateinit var presetFrames: List<View>
    private lateinit var presetImages: List<ShapeableImageView>
    private lateinit var presetChecks: List<View>

    private val firebaseRepo = FirebaseRepository()
    private lateinit var prefs: SharedPreferences

    private var currentDisplayName: String = ""
    private var currentAvatarUri: String? = null
    private var currentCardUri: String? = null
    private var cardMode: String = "standard"
    private var hasChanges = false
    private var avatarChanged = false
    private var cardChanged = false

    private val avatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            currentAvatarUri = it.toString()
            editProfileAvatar.setImageURI(it)
            avatarChanged = true
            markChanged()
        }
    }

    private val cardPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            setCardBackground(it.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_profile)

        prefs = getSharedPreferences("budgetfy_prefs", MODE_PRIVATE)

        initViews()
        loadCurrentState()
        setupListeners()
    }

    private fun initViews() {
        cancelButton = findViewById(R.id.cancelButton)
        saveButton = findViewById(R.id.saveButton)
        editProfileAvatar = findViewById(R.id.editProfileAvatar)
        profilePicContainer = findViewById(R.id.profilePicContainer)
        changePhotoText = findViewById(R.id.changePhotoText)
        displayNameCard = findViewById(R.id.displayNameCard)
        displayNameValueText = findViewById(R.id.displayNameValueText)
        sizeStandardPill = findViewById(R.id.sizeStandardPill)
        sizeExpandedPill = findViewById(R.id.sizeExpandedPill)
        cardPreviewBox = findViewById(R.id.cardPreviewBox)
        cardPreviewImage = findViewById(R.id.cardPreviewImage)
        cardPreviewOverlay = findViewById(R.id.cardPreviewOverlay)
        previewTitle = findViewById(R.id.previewTitle)
        previewAmount = findViewById(R.id.previewAmount)
        previewBudget = findViewById(R.id.previewBudget)
        previewProgress = findViewById(R.id.previewProgress)
        uploadCardButton = findViewById(R.id.uploadCardButton)
        removeCardButton = findViewById(R.id.removeCardButton)

        presetFrames = listOf(R.id.preset1, R.id.preset2, R.id.preset3, R.id.preset4).map { findViewById(it) }
        presetImages = listOf(R.id.preset1Image, R.id.preset2Image, R.id.preset3Image, R.id.preset4Image).map { findViewById(it) }
        presetChecks = listOf(R.id.preset1Check, R.id.preset2Check, R.id.preset3Check, R.id.preset4Check).map { findViewById(it) }
    }

    private fun loadCurrentState() {
        val savedName = prefs.getString("display_name", null)
        val firebaseName = firebaseRepo.getCurrentUser()?.displayName
        currentDisplayName = savedName ?: firebaseName ?: "Your name"
        displayNameValueText.text = currentDisplayName

        currentAvatarUri = prefs.getString("profile_avatar_uri", null)
        if (!currentAvatarUri.isNullOrEmpty()) {
            try {
                editProfileAvatar.setImageURI(Uri.parse(currentAvatarUri))
            } catch (e: Exception) { }
        } else {
            editProfileAvatar.setImageDrawable(null)
        }

        cardMode = prefs.getString("card_mode", "standard") ?: "standard"
        currentCardUri = prefs.getString("card_bg_uri", null)

        applyCardMode()
        updateCardPreview()
    }

    private fun setupListeners() {
        cancelButton.setOnClickListener {
            if (hasChanges) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Discard changes?")
                    .setMessage("Your edits will not be saved.")
                    .setPositiveButton("Discard") { _, _ -> finish() }
                    .setNegativeButton("Keep editing", null)
                    .show()
            } else {
                finish()
            }
        }

        saveButton.setOnClickListener { saveChanges() }

        profilePicContainer.setOnClickListener { showProfilePicBottomSheet() }
        changePhotoText.setOnClickListener { showProfilePicBottomSheet() }

        displayNameCard.setOnClickListener { showEditNameDialog() }

        sizeStandardPill.setOnClickListener { setCardMode("standard") }
        sizeExpandedPill.setOnClickListener { setCardMode("expanded") }

        cardPreviewBox.setOnClickListener { cardPickerLauncher.launch("image/*") }
        uploadCardButton.setOnClickListener { cardPickerLauncher.launch("image/*") }
        removeCardButton.setOnClickListener {
            if (currentCardUri != null) setCardBackground(null)
        }

        presetFrames.forEachIndexed { index, frame ->
            frame.setOnClickListener { setCardBackground(presetUri(presetNames[index])) }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  SPENDING CARD
    // ═══════════════════════════════════════════════════════════

    /**
     * Ready-made backgrounds are stored by resource name, so the saved value
     * stays valid across app updates. Home loads it with setImageURI.
     */
    private fun presetUri(name: String) = "android.resource://$packageName/drawable/$name"

    private fun setCardMode(mode: String) {
        if (mode == cardMode) return
        cardMode = mode
        applyCardMode()
        markChanged()
    }

    private fun applyCardMode() {
        val expanded = cardMode == "expanded"
        stylePill(sizeStandardPill, selected = !expanded)
        stylePill(sizeExpandedPill, selected = expanded)
        // Same heights as the real card on the home screen
        cardPreviewBox.layoutParams.height = dpToPx(if (expanded) 220 else 176)
        cardPreviewBox.requestLayout()
    }

    private fun stylePill(pill: TextView, selected: Boolean) {
        pill.setBackgroundResource(if (selected) R.drawable.bg_pill_selected else R.drawable.bg_pill_plain)
        pill.setTextColor(if (selected) Color.WHITE else ContextCompat.getColor(this, R.color.text_secondary))
    }

    private fun setCardBackground(uri: String?) {
        if (uri == currentCardUri) return
        currentCardUri = uri
        cardChanged = true
        updateCardPreview()
        markChanged()
    }

    /** Shows the card as it will look on Home, and marks the selected preset. */
    private fun updateCardPreview() {
        val uri = currentCardUri
        var hasImage = false
        if (!uri.isNullOrEmpty()) {
            try {
                cardPreviewImage.setImageURI(Uri.parse(uri))
                hasImage = cardPreviewImage.drawable != null
            } catch (e: Exception) { }
        }
        if (!hasImage) cardPreviewImage.setImageDrawable(null)
        cardPreviewImage.visibility = if (hasImage) View.VISIBLE else View.GONE
        cardPreviewOverlay.visibility = if (hasImage) View.VISIBLE else View.GONE

        val secondary = if (hasImage) Color.parseColor("#E6FFFFFF") else ContextCompat.getColor(this, R.color.text_secondary)
        previewTitle.setTextColor(secondary)
        previewBudget.setTextColor(secondary)
        previewAmount.setTextColor(if (hasImage) Color.WHITE else ContextCompat.getColor(this, R.color.brand_blue))
        previewProgress.setIndicatorColor(if (hasImage) Color.WHITE else ContextCompat.getColor(this, R.color.brand_blue))
        previewProgress.trackColor = ContextCompat.getColor(
            this, if (hasImage) R.color.progress_track_on_image else R.color.progress_track
        )

        removeCardButton.isEnabled = !uri.isNullOrEmpty()
        removeCardButton.alpha = if (uri.isNullOrEmpty()) 0.4f else 1f

        // Selected preset: blue border + ✓
        presetNames.forEachIndexed { i, name ->
            val selected = uri == presetUri(name)
            presetChecks[i].visibility = if (selected) View.VISIBLE else View.GONE
            presetImages[i].strokeColor = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, if (selected) R.color.brand_blue else R.color.stroke)
            )
            presetImages[i].strokeWidth = dpToPx(if (selected) 2 else 1).toFloat()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  PROFILE PHOTO
    // ═══════════════════════════════════════════════════════════
    private fun showProfilePicBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_profile_pic, null)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.optionCamera).setOnClickListener {
            dialog.dismiss()
            Toast.makeText(this, "Camera option coming soon", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.optionLibrary).setOnClickListener {
            dialog.dismiss()
            avatarPickerLauncher.launch("image/*")
        }
        view.findViewById<View>(R.id.optionRemove).setOnClickListener {
            dialog.dismiss()
            currentAvatarUri = null
            editProfileAvatar.setImageDrawable(null)
            avatarChanged = true
            markChanged()
        }
        view.findViewById<View>(R.id.optionCancel).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    // ═══════════════════════════════════════════════════════════
    //  DISPLAY NAME
    // ═══════════════════════════════════════════════════════════
    private fun showEditNameDialog() {
        val form = DialogForm(this)
        val input = form.field("Your name", currentDisplayName)

        MaterialAlertDialogBuilder(this)
            .setTitle("Display name")
            .setView(form.view)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                currentDisplayName = newName
                displayNameValueText.text = newName
                markChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════
    //  SAVE / CANCEL
    // ═══════════════════════════════════════════════════════════
    private fun markChanged() {
        hasChanges = true
        saveButton.setTextColor(ContextCompat.getColor(this, R.color.accent_text))
        saveButton.isEnabled = true
    }

    private fun saveChanges() {
        saveButton.isEnabled = false
        val userId = firebaseRepo.getCurrentUser()?.uid
        CoroutineScope(Dispatchers.IO).launch {
            // Copies picked images into app storage and backs everything up to
            // Firestore, so the photo and card survive reinstalling the app.
            val (avatar, card) = ProfileSync.saveProfile(
                this@EditProfileActivity, userId,
                displayName = currentDisplayName,
                avatarUri = currentAvatarUri, avatarChanged = avatarChanged,
                cardMode = cardMode,
                cardUri = currentCardUri, cardChanged = cardChanged
            )
            prefs.edit().apply {
                putString(ProfileSync.KEY_NAME, currentDisplayName)
                if (avatar != null) putString(ProfileSync.KEY_AVATAR, avatar)
                else remove(ProfileSync.KEY_AVATAR)
                putString(ProfileSync.KEY_CARD_MODE, cardMode)
                if (card != null) putString(ProfileSync.KEY_CARD_BG, card)
                else remove(ProfileSync.KEY_CARD_BG)
                apply()
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                Toast.makeText(this@EditProfileActivity, "Profile updated", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
