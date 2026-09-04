package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig

class ApiKeyManager(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        "call_scribe_prefs",
        Context.MODE_PRIVATE
    )

    // ── Gemini ──────────────────────────────────────────────────────────────

    fun getApiKey(): String {
        val userKey = prefs.getString(KEY_GEMINI_API_KEY, "")?.trim() ?: ""
        if (userKey.isNotBlank()) return userKey
        val buildKey = BuildConfig.GEMINI_API_KEY.trim()
        if (buildKey.isNotBlank() &&
            !buildKey.equals("MY_GEMINI_API_KEY", ignoreCase = true) &&
            !buildKey.equals("YOUR_API_KEY", ignoreCase = true)
        ) return buildKey
        return ""
    }

    fun setApiKey(apiKey: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, apiKey.trim()).apply()
    }

    fun isConfigured(): Boolean {
        val key = getApiKey()
        return key.isNotBlank() && key.length > 10
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_GEMINI_API_KEY).apply()
    }

    // ── NVIDIA ───────────────────────────────────────────────────────────────

    fun getNvidiaApiKey(): String {
        return prefs.getString(KEY_NVIDIA_API_KEY, "")?.trim() ?: ""
    }

    fun setNvidiaApiKey(apiKey: String) {
        prefs.edit().putString(KEY_NVIDIA_API_KEY, apiKey.trim()).apply()
    }

    fun isNvidiaConfigured(): Boolean {
        val key = getNvidiaApiKey()
        return key.isNotBlank() && key.length > 10
    }

    fun clearNvidiaApiKey() {
        prefs.edit().remove(KEY_NVIDIA_API_KEY).apply()
    }

    companion object {
        private const val KEY_GEMINI_API_KEY = "user_gemini_api_key"
        private const val KEY_NVIDIA_API_KEY = "user_nvidia_api_key"
    }
}
