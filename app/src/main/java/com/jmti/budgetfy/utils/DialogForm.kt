package com.jmti.budgetfy.utils

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jmti.budgetfy.R

/**
 * Builds the content of an input dialog with labeled, rounded text fields
 * (the same style as the rest of the app) instead of bare underlined
 * EditTexts.
 *
 *     val form = DialogForm(context)
 *     val name = form.field("Item name", item.name)
 *     val price = form.field("Price", "8.40", DialogForm.DECIMAL, prefix = "RM ")
 *     MaterialAlertDialogBuilder(context).setView(form.view)...
 */
class DialogForm(private val context: Context) {

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val d = context.resources.displayMetrics.density
        setPadding((24 * d).toInt(), (4 * d).toInt(), (24 * d).toInt(), (4 * d).toInt())
    }

    fun field(
        label: String,
        value: String = "",
        inputType: Int = TEXT,
        prefix: String? = null,
        helper: String? = null
    ): TextInputEditText {
        val layout = LayoutInflater.from(context)
            .inflate(R.layout.dialog_field, view, false) as TextInputLayout
        layout.hint = label
        if (prefix != null) layout.prefixText = prefix
        if (helper != null) layout.helperText = helper
        if (view.childCount == 0) {
            (layout.layoutParams as LinearLayout.LayoutParams).topMargin = 0
        }

        val input = layout.findViewById<TextInputEditText>(R.id.dialogFieldInput)
        input.inputType = inputType
        input.setText(value)
        input.setSelection(input.text?.length ?: 0)

        view.addView(layout)
        return input
    }

    companion object {
        const val TEXT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        const val NUMBER = InputType.TYPE_CLASS_NUMBER
        const val DECIMAL = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
}
