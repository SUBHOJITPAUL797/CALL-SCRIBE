package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

    // ── Cloudflare Worker AI ──────────────────────────────────────────────────

    fun getCloudflareWorkerUrl(): String {
        if (!prefs.contains(KEY_CLOUDFLARE_WORKER_URL)) {
            return DEFAULT_CLOUDFLARE_WORKER_URL
        }
        return prefs.getString(KEY_CLOUDFLARE_WORKER_URL, "")?.trim() ?: ""
    }

    fun setCloudflareWorkerUrl(url: String) {
        val raw = url.trim().trimEnd('/')
        if (raw.isBlank()) {
            clearCloudflareConfig()
            return
        }
        val cleanUrl = if (!raw.startsWith("http://") && !raw.startsWith("https://") && raw.contains(".") && !raw.contains(" ")) {
            "https://$raw"
        } else {
            raw
        }
        prefs.edit().putString(KEY_CLOUDFLARE_WORKER_URL, cleanUrl).apply()
    }

    fun getCloudflareWorkerToken(): String {
        return prefs.getString(KEY_CLOUDFLARE_WORKER_TOKEN, "")?.trim() ?: ""
    }

    fun setCloudflareWorkerToken(token: String) {
        prefs.edit().putString(KEY_CLOUDFLARE_WORKER_TOKEN, token.trim()).apply()
    }

    fun isCloudflareConfigured(): Boolean {
        val url = getCloudflareWorkerUrl()
        if (url.isBlank()) return false
        val httpUrl = try { url.toHttpUrlOrNull() } catch (_: Exception) { null }
        return httpUrl != null && (httpUrl.scheme == "http" || httpUrl.scheme == "https") && httpUrl.host.isNotBlank()
    }

    fun clearCloudflareConfig() {
        prefs.edit().remove(KEY_CLOUDFLARE_WORKER_URL).remove(KEY_CLOUDFLARE_WORKER_TOKEN).apply()
    }

    companion object {
        const val DEFAULT_CLOUDFLARE_WORKER_URL = "https://callscribe-ai.subhojit.workers.dev"
        private const val KEY_GEMINI_API_KEY = "user_gemini_api_key"
        private const val KEY_NVIDIA_API_KEY = "user_nvidia_api_key"
        private const val KEY_CLOUDFLARE_WORKER_URL = "user_cloudflare_worker_url"
        private const val KEY_CLOUDFLARE_WORKER_TOKEN = "user_cloudflare_worker_token"
    }
}
