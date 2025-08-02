package com.example.deepenglish

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var systemPromptInput: TextInputEditText
    private lateinit var saveSettingsButton: Button
    private lateinit var getApiKeyButton: Button

    private var deepseekApiKey: String = ""
    private var llmSystemPrompt: String = ""

    companion object {
        private const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant that provides sentence examples, phonetics, and detailed translations with parts of speech in a JSON format. Use common abbreviations for parts of speech (e.g., n., v., adj.)."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        apiKeyInput = findViewById(R.id.apiKeyInput)
        systemPromptInput = findViewById(R.id.systemPromptInput)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        getApiKeyButton = findViewById(R.id.getApiKeyButton)

        loadSettings()

        saveSettingsButton.setOnClickListener {
            saveSettings()
        }

        getApiKeyButton.setOnClickListener {
            val url = "https://platform.deepseek.com/sign_in"
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
        }
    }

    private fun loadSettings() {
        deepseekApiKey = sharedPreferences.getString("deepseek_api_key", "") ?: ""
        llmSystemPrompt = sharedPreferences.getString("llm_system_prompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT

        apiKeyInput.setText(deepseekApiKey)
        systemPromptInput.setText(llmSystemPrompt)
    }

    private fun saveSettings() {
        deepseekApiKey = apiKeyInput.text.toString().trim()
        llmSystemPrompt = systemPromptInput.text.toString().trim()
        sharedPreferences.edit()
            .putString("deepseek_api_key", deepseekApiKey)
            .putString("llm_system_prompt", llmSystemPrompt)
            .apply()

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    fun onBackButtonClick(view: android.view.View) {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish() // Optional: close SettingsActivity after navigating back
    }
}
