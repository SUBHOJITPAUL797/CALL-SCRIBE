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

    /**
     * Parses and cleans configured keys, automatically stitching together accidental line wraps.
     */
    fun parseConfiguredKeys(raw: String): List<String> {
        val clean = raw.trim().replace("\"", "").replace("'", "").replace("`", "")
        if (clean.isBlank()) return emptyList()

        val lines = clean.split('\n', '\r').map { it.trim() }.filter { it.isNotBlank() }
        val unifiedLines = mutableListOf<String>()
        for (line in lines) {
            if (unifiedLines.isEmpty()) {
                unifiedLines.add(line)
            } else {
                val last = unifiedLines.last()
                if (!line.startsWith("AQ.") && !line.startsWith("AIza") && !line.contains(",") && last.length < 50) {
                    unifiedLines[unifiedLines.size - 1] = last + line
                } else {
                    unifiedLines.add(line)
                }
            }
        }

        return unifiedLines.flatMap { it.split(',', ';') }
            .map { it.trim().replace(" ", "") }
            .filter { it.length > 10 && !it.equals("MY_GEMINI_API_KEY", ignoreCase = true) && !it.equals("YOUR_API_KEY", ignoreCase = true) }
            .distinct()
    }

    /**
     * Returns all configured Gemini API keys (supports comma, semicolon, newline, or whitespace separation).
     * If user configured multiple keys, returns all of them for automatic failover rotation.
     */
    fun getApiKeys(): List<String> {
        val userRaw = prefs.getString(KEY_GEMINI_API_KEY, "")?.trim() ?: ""
        if (userRaw.isNotBlank()) {
            val parsed = parseConfiguredKeys(userRaw)
            if (parsed.isNotEmpty()) return parsed
        }
        val buildKey = BuildConfig.GEMINI_API_KEY.trim().replace("\"", "").replace("'", "").replace("`", "")
        if (buildKey.isNotBlank() &&
            !buildKey.equals("MY_GEMINI_API_KEY", ignoreCase = true) &&
            !buildKey.equals("YOUR_API_KEY", ignoreCase = true) &&
            buildKey.length > 10
        ) {
            return listOf(buildKey)
        }
        return emptyList()
    }

    fun getApiKey(): String {
        return getApiKeys().firstOrNull() ?: ""
    }

    fun setApiKey(apiKey: String) {
        val keys = parseConfiguredKeys(apiKey)
        val clean = keys.joinToString(",")
        prefs.edit().putString(KEY_GEMINI_API_KEY, clean).apply()
    }

    /**
     * Rotates failedKey to the end of the key pool so subsequent requests
     * automatically try the next active backup key first.
     */
    fun rotateGeminiKey(failedKey: String) {
        val keys = getApiKeys().toMutableList()
        val matchIdx = keys.indexOfFirst { it.equals(failedKey.trim(), ignoreCase = true) }
        if (matchIdx != -1 && keys.size > 1) {
            val failed = keys.removeAt(matchIdx)
            keys.add(failed)
            prefs.edit().putString(KEY_GEMINI_API_KEY, keys.joinToString(",")).apply()
        }
    }

    fun isConfigured(): Boolean {
        return getApiKeys().isNotEmpty()
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
        val saved = prefs.getString(KEY_CLOUDFLARE_WORKER_URL, "")?.trim() ?: ""
        if (saved.isNotBlank()) return saved
        return DEFAULT_CLOUDFLARE_WORKER_URL
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
