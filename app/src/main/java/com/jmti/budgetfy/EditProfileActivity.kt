package com.jmti.budgetfy

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jmti.budgetfy.repositories.FirebaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EditProfileActivity : AppCompatActivity() {

    private lateinit var cancelButton: TextView
    private lateinit var saveButton: TextView
    private lateinit var editProfileAvatar: ShapeableImageView
    private lateinit var profilePicContainer: FrameLayout
    private lateinit var displayNameCard: MaterialCardView
    private lateinit var displayNameValueText: TextView
    private lateinit var switchStandard: SwitchMaterial
    private lateinit var switchExpanded: SwitchMaterial
    private lateinit var cardPreviewBox: FrameLayout
    private lateinit var cardPreviewImage: ImageView
    private lateinit var cardPreviewOverlay: View

    private val firebaseRepo = FirebaseRepository()
    private lateinit var prefs: SharedPreferences

    private var currentDisplayName: String = ""
    private var currentAvatarUri: String? = null
    private var currentCardUri: String? = null
    private var cardMode: String = "standard"
    private var hasChanges = false

    // Flag to suppress listener triggers during initial load
    private var isLoadingState = false

    private val avatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            currentAvatarUri = it.toString()
            editProfileAvatar.setImageURI(it)
            markChanged()
        }
    }

    private val cardPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            currentCardUri = it.toString()
            cardPreviewImage.setImageURI(it)
            cardPreviewImage.visibility = View.VISIBLE
            cardPreviewOverlay.visibility = View.VISIBLE
            markChanged()
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
        displayNameCard = findViewById(R.id.displayNameCard)
        displayNameValueText = findViewById(R.id.displayNameValueText)
        switchStandard = findViewById(R.id.switchStandard)
        switchExpanded = findViewById(R.id.switchExpanded)
        cardPreviewBox = findViewById(R.id.cardPreviewBox)
        cardPreviewImage = findViewById(R.id.cardPreviewImage)
        cardPreviewOverlay = findViewById(R.id.cardPreviewOverlay)
    }

    private fun loadCurrentState() {
        isLoadingState = true

        // Display name
        val savedName = prefs.getString("display_name", null)
        val firebaseName = firebaseRepo.getCurrentUser()?.displayName
        currentDisplayName = savedName ?: firebaseName ?: "Your name"
        displayNameValueText.text = currentDisplayName

        // Avatar
        currentAvatarUri = prefs.getString("profile_avatar_uri", null)
        if (!currentAvatarUri.isNullOrEmpty()) {
            try {
                editProfileAvatar.setImageURI(Uri.parse(currentAvatarUri))
            } catch (e: Exception) { }
        } else {
            editProfileAvatar.setImageDrawable(null)
            editProfileAvatar.setBackgroundColor(android.graphics.Color.WHITE)
        }

        // Card mode
        cardMode = prefs.getString("card_mode", "standard") ?: "standard"
        currentCardUri = prefs.getString("card_bg_uri", null)

        if (cardMode == "expanded") {
            switchStandard.isChecked = false
            switchExpanded.isChecked = true
            cardPreviewBox.layoutParams.height = dpToPx(220)
        } else {
            switchStandard.isChecked = true
            switchExpanded.isChecked = false
            cardPreviewBox.layoutParams.height = dpToPx(120)
        }
        cardPreviewBox.requestLayout()

        // Card image
        if (!currentCardUri.isNullOrEmpty()) {
            try {
                cardPreviewImage.setImageURI(Uri.parse(currentCardUri))
                cardPreviewImage.visibility = View.VISIBLE
                cardPreviewOverlay.visibility = View.VISIBLE
            } catch (e: Exception) { }
        }

        isLoadingState = false
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

        displayNameCard.setOnClickListener { showEditNameDialog() }

        // ✅ Standard toggle
        switchStandard.setOnCheckedChangeListener { _, isChecked ->
            if (isLoadingState) return@setOnCheckedChangeListener
            if (isChecked) {
                if (switchExpanded.isChecked) switchExpanded.isChecked = false
                cardMode = "standard"
                cardPreviewBox.layoutParams.height = dpToPx(120)
                cardPreviewBox.requestLayout()
                markChanged()
            } else {
                // Prevent both off
                if (!switchExpanded.isChecked) switchStandard.isChecked = true
            }
        }

        // ✅ Expanded toggle
        switchExpanded.setOnCheckedChangeListener { _, isChecked ->
            if (isLoadingState) return@setOnCheckedChangeListener
            if (isChecked) {
                if (switchStandard.isChecked) switchStandard.isChecked = false
                cardMode = "expanded"
                cardPreviewBox.layoutParams.height = dpToPx(220)
                cardPreviewBox.requestLayout()
                markChanged()
            } else {
                if (!switchStandard.isChecked) switchExpanded.isChecked = true
            }
        }

        // ✅ Card preview box opens bottom sheet in BOTH modes
        cardPreviewBox.setOnClickListener {
            showCardPicBottomSheet()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  BOTTOM SHEETS
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
            editProfileAvatar.setBackgroundColor(android.graphics.Color.WHITE)
            markChanged()
        }
        view.findViewById<View>(R.id.optionCancel).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showCardPicBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_card_pic, null)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.optionCamera).setOnClickListener {
            dialog.dismiss()
            Toast.makeText(this, "Camera option coming soon", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.optionLibrary).setOnClickListener {
            dialog.dismiss()
            cardPickerLauncher.launch("image/*")
        }
        view.findViewById<View>(R.id.optionRemove).setOnClickListener {
            dialog.dismiss()
            currentCardUri = null
            cardPreviewImage.setImageDrawable(null)
            cardPreviewImage.visibility = View.GONE
            cardPreviewOverlay.visibility = View.GONE
            markChanged()
        }
        view.findViewById<View>(R.id.optionCancel).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    // ═══════════════════════════════════════════════════════════
    //  DISPLAY NAME DIALOG
    // ═══════════════════════════════════════════════════════════
    private fun showEditNameDialog() {
        val form = com.jmti.budgetfy.utils.DialogForm(this)
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
        saveButton.setTextColor(android.graphics.Color.parseColor("#0096FF"))
        saveButton.isEnabled = true
    }

    private fun saveChanges() {
        // ✅ COMPUTE MODE FROM ACTUAL TOGGLE STATE — not stale variable
        val finalMode = if (switchExpanded.isChecked) "expanded" else "standard"

        prefs.edit().apply {
            putString("display_name", currentDisplayName)
            if (currentAvatarUri != null) {
                putString("profile_avatar_uri", currentAvatarUri)
            } else {
                remove("profile_avatar_uri")
            }
            putString("card_mode", finalMode)
            if (currentCardUri != null) {
                putString("card_bg_uri", currentCardUri)
            } else {
                remove("card_bg_uri")
            }
            apply()
        }

        // Sync display name to Firebase
        val userId = firebaseRepo.getCurrentUser()?.uid
        if (userId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val updates = mapOf("displayName" to currentDisplayName)
                    com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(userId)
                        .update(updates)
                } catch (_: Exception) { }
            }
        }

        Toast.makeText(this, "✅ Profile updated", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ═══════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}