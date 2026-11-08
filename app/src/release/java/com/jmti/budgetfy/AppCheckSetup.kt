package com.jmti.budgetfy

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** Release builds: App Check backed by Google Play Integrity. */
object AppCheckSetup {
    fun install() {
        Firebase.appCheck.installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance()
        )
    }

    /** Release builds have no debug token. */
    @Suppress("UNUSED_PARAMETER")
    fun debugToken(context: Context): String? = null
}
