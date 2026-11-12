package com.jmti.budgetfy

import android.app.Application
import com.google.firebase.FirebaseApp

class BudgetFYApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Day / Night choice from the Profile page.
        com.jmti.budgetfy.utils.ThemePrefs.apply(this)
        FirebaseApp.initializeApp(this)
        // Proves requests come from the real app, so nobody else can use our
        // Firebase AI Logic (Gemini) quota. Debug builds use a debug token;
        // release builds use Play Integrity. See src/debug and src/release.
        AppCheckSetup.install()
    }
}