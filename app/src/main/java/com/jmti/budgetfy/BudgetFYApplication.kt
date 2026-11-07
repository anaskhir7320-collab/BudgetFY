package com.jmti.budgetfy

import android.app.Application
import com.google.firebase.FirebaseApp

class BudgetFYApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}