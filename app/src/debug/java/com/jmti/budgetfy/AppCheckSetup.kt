package com.jmti.budgetfy

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds: App Check debug provider. On first launch it prints a debug
 * token to Logcat ("DebugAppCheckProvider"); add that token in the Firebase
 * console under App Check → Apps → BudgetFY → Manage debug tokens.
 */
object AppCheckSetup {
    fun install() {
        Firebase.appCheck.installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )
    }

    /**
     * This install's debug token, so testers can send it to the app owner
     * without Logcat. Null until App Check has created it.
     */
    fun debugToken(context: Context): String? {
        val key = FirebaseApp.getInstance().persistenceKey
        return context.getSharedPreferences(
            "com.google.firebase.appcheck.debug.store.$key", Context.MODE_PRIVATE
        ).getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null)
    }
}
