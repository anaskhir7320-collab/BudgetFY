package com.jmti.budgetfy

import com.google.firebase.Firebase
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
}
