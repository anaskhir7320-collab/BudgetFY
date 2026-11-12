package com.jmti.budgetfy.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Remembers Day / Night choice from the Profile page and applies it app-wide. */
object ThemePrefs {
    private const val PREFS = "budgetfy_prefs"
    private const val KEY_NIGHT = "night_mode"

    fun isNight(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NIGHT, false)

    /** Call once at app start, before any screen is shown. */
    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (isNight(context)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    /** Saves the choice and switches; open screens redraw in the new mode. */
    fun setNight(context: Context, night: Boolean) {
        if (isNight(context) == night) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_NIGHT, night).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
