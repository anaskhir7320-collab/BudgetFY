package com.jmti.budgetfy

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.jmti.budgetfy.repositories.FirebaseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    // ⬇️⬇️⬇️ THESE ARE THE DECLARATIONS YOU'RE MISSING! ⬇️⬇️⬇️
    private lateinit var emailEditText: TextInputEditText
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var loginButton: MaterialButton
    private lateinit var registerLink: android.widget.TextView
    private lateinit var progressBar: android.widget.ProgressBar
    // ⬆️⬆️⬆️ THESE ARE THE DECLARATIONS YOU'RE MISSING! ⬆️⬆️⬆️

    private val firebaseRepo by lazy { FirebaseRepository() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        if (firebaseRepo.getCurrentUser() != null) {
            navigateToMain()
            return
        }

        initViews()
        setupListeners()
    }

    private fun initViews() {
        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        loginButton = findViewById(R.id.loginButton)
        registerLink = findViewById(R.id.registerLink)
        progressBar = findViewById(R.id.loginProgressBar)
    }

    private fun setupListeners() {
        loginButton.setOnClickListener {
            performLogin()
        }

        registerLink.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun performLogin() {
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, getString(R.string.fill_all_fields), Toast.LENGTH_SHORT).show()
            return
        }

        loginButton.isEnabled = false
        progressBar.visibility = android.view.View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val user = firebaseRepo.loginUser(email, password)

            withContext(Dispatchers.Main) {
                loginButton.isEnabled = true
                progressBar.visibility = android.view.View.GONE

                if (user != null) {
                    Toast.makeText(this@LoginActivity, getString(R.string.login_successful), Toast.LENGTH_SHORT).show()
                    navigateToMain()
                } else {
                    Toast.makeText(this@LoginActivity, getString(R.string.login_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}