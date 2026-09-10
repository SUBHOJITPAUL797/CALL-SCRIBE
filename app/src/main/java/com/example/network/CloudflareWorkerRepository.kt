package com.example.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudflareWorkerRepository(
    private val urlProvider: () -> String,
    private val tokenProvider: () -> String = { "" }
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun isConfigured(): Boolean {
        val url = getCleanUrl()
        return url.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))
    }

    private fun getCleanUrl(): String {
        val raw = urlProvider().trim().trimEnd('/')
        if (raw.isBlank()) return ""
        return if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else if (raw.contains(".") && !raw.contains(" ") && !raw.contains("\n")) {
            "https://$raw"
        } else {
            raw
        }
    }

    private fun getToken(): String {
        return tokenProvider().trim()
    }

    /**
     * Test connection to the Cloudflare Worker endpoint.
     */
    suspend fun testConnection(
        testUrl: String? = null,
        testToken: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val rawUrl = (testUrl ?: urlProvider()).trim().trimEnd('/')
        val baseUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else if (rawUrl.contains(".") && !rawUrl.contains(" ") && !rawUrl.contains("\n")) {
            "https://$rawUrl"
        } else {
            rawUrl
        }
        val token = (testToken ?: getToken()).trim()

        if (baseUrl.isBlank() || (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://"))) {
            return@withContext Result.failure(Exception("Please enter a valid Worker URL (e.g. https://callscribe-ai.user.workers.dev)"))
        }

        try {
            // Test 1: Check auth / ping endpoint
            val pingRequest = Request.Builder()
                .url("$baseUrl/health")
                .apply {
                    if (token.isNotBlank()) {
                        addHeader("Authorization", "Bearer $token")
                    }
                }
                .get()
                .build()

            client.newCall(pingRequest).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""

                if (code == 401 || code == 403) {
                    return@withContext Result.failure(Exception("Unauthorized (HTTP $code). Check your secret API token."))
                }

                if (response.isSuccessful) {
                    val json = try { JSONObject(body) } catch (_: Exception) { null }
                    val msg = json?.optString("message") ?: "Cloudflare Worker is online and reachable!"
                    return@withContext Result.success("✅ $msg (Whisper AI ready)")
                }

                return@withContext Result.failure(Exception("Worker returned HTTP $code: ${body.take(150)}"))
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(Exception("Failed to reach Worker: ${e.localizedMessage}", e))
        }
    }

    private fun sanitizeHeaderValue(value: String): String {
        val ascii = value.filter { it.code in 32..126 }
        return if (ascii.isNotBlank()) ascii else "Call_Recording"
    }

    private fun urlEncode(value: String): String {
        return try {
            java.net.URLEncoder.encode(value, "UTF-8")
        } catch (_: Exception) {
            sanitizeHeaderValue(value)
        }
    }

    /**
     * Transcribe audio bytes using Cloudflare Workers AI (@cf/openai/whisper).
     */
    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        fileName: String,
        mimeType: String,
        language: String = "auto"
    ): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = getCleanUrl()
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Cloudflare Worker URL is not configured."))
        }

        try {
            val token = getToken()
            val mediaType = (mimeType.ifBlank { "audio/mp3" }).toMediaTypeOrNull()
            val requestBody = audioBytes.toRequestBody(mediaType)

            val encodedTitle = urlEncode(fileName)
            val langParam = if (language.isNotBlank() && language != "auto") "&lang=${urlEncode(language)}" else ""
            val url = "$baseUrl/transcribe?title=$encodedTitle$langParam"

            val request = Request.Builder()
                .url(url)
                .apply {
                    if (token.isNotBlank()) {
                        addHeader("Authorization", "Bearer $token")
                    }
                    addHeader("X-Call-Title-Encoded", encodedTitle)
                    addHeader("X-Call-Title", sanitizeHeaderValue(fileName))
                    addHeader("X-Call-Language", sanitizeHeaderValue(language))
                }
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""

                if (code == 401 || code == 403) {
                    return@withContext Result.failure(Exception("Cloudflare Worker unauthorized. Verify API token."))
                }

                if (!response.isSuccessful) {
                    val errMsg = try { JSONObject(body).optString("error", body) } catch (_: Exception) { body }
                    return@withContext Result.failure(Exception("Cloudflare Worker error (HTTP $code): ${errMsg.take(200)}"))
                }

                val json = JSONObject(body)
                val transcription = json.optString("transcription", json.optString("text", "")).trim()

                if (transcription.isBlank()) {
                    return@withContext Result.failure(Exception("No speech detected by Cloudflare Whisper."))
                }

                Result.success(transcription)
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(Exception("Cloudflare Whisper transcription failed: ${e.localizedMessage}", e))
        }
    }

    /**
     * Summarize transcript using Cloudflare Workers AI (@cf/meta/llama-3.1-8b-instruct).
     */
    suspend fun summarizeTranscript(
        transcript: String,
        callTitle: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = getCleanUrl()
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Cloudflare Worker URL is not configured."))
        }

        try {
            val token = getToken()
            val jsonPayload = JSONObject().apply {
                put("transcript", transcript)
                put("callTitle", callTitle)
            }.toString()

            val requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("$baseUrl/summarize")
                .apply {
                    if (token.isNotBlank()) {
                        addHeader("Authorization", "Bearer $token")
                    }
                }
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errMsg = try { JSONObject(body).optString("error", body) } catch (_: Exception) { body }
                    return@withContext Result.failure(Exception("Cloudflare Worker summary error (HTTP $code): ${errMsg.take(200)}"))
                }

                val json = JSONObject(body)
                val summary = json.optString("summary", "").trim()
                if (summary.isBlank()) {
                    return@withContext Result.failure(Exception("Empty summary returned by Cloudflare Worker."))
                }

                Result.success(summary)
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(Exception("Cloudflare summarization failed: ${e.localizedMessage}", e))
        }
    }

    /**
     * All-in-one transcribe + summarize via Cloudflare Worker.
     */
    suspend fun analyzeAudio(
        audioBytes: ByteArray,
        fileName: String,
        mimeType: String,
        language: String = "auto"
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val baseUrl = getCleanUrl()
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Cloudflare Worker URL is not configured."))
        }

        try {
            val token = getToken()
            val mediaType = (mimeType.ifBlank { "audio/mp3" }).toMediaTypeOrNull()
            val requestBody = audioBytes.toRequestBody(mediaType)

            val encodedTitle = urlEncode(fileName)
            val langParam = if (language.isNotBlank() && language != "auto") "&lang=${urlEncode(language)}" else ""
            val url = "$baseUrl/analyze?title=$encodedTitle$langParam"

            val request = Request.Builder()
                .url(url)
                .apply {
                    if (token.isNotBlank()) {
                        addHeader("Authorization", "Bearer $token")
                    }
                    addHeader("X-Call-Title-Encoded", encodedTitle)
                    addHeader("X-Call-Title", sanitizeHeaderValue(fileName))
                    addHeader("X-Call-Language", sanitizeHeaderValue(language))
                }
                .post(requestBody)
                .build()

            val (code, body) = client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""

                if (code == 401 || code == 403) {
                    return@withContext Result.failure(Exception("Cloudflare Worker unauthorized. Check your secret API token."))
                }

                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val transcription = json.optString("transcription", json.optString("text", "")).trim()
                    val summary = json.optString("summary", "").trim()
                    if (summary.isNotBlank()) {
                        val finalTrans = if (transcription.isNotBlank()) transcription else "(No audible speech detected)"
                        return@withContext Result.success(Pair(finalTrans, summary))
                    }
                }
                Pair(code, body)
            }

            // Fallback to separate endpoints if /analyze endpoint returned 404 (e.g. older worker script)
            if (code == 404) {
                val transResult = transcribeAudio(audioBytes, fileName, mimeType, language)
                if (transResult.isFailure) {
                    return@withContext Result.failure(transResult.exceptionOrNull() ?: Exception("Transcription failed"))
                }
                val trans = transResult.getOrThrow()
                val sumResult = summarizeTranscript(trans, fileName)
                val sum = sumResult.getOrDefault("")
                return@withContext Result.success(Pair(trans, sum))
            }

            val errMsg = try { JSONObject(body).optString("error", body) } catch (_: Exception) { body }
            Result.failure(Exception("Cloudflare analysis failed (HTTP $code): ${errMsg.take(200)}"))
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(Exception("Cloudflare analysis failed: ${e.localizedMessage}", e))
        }
    }

    /**
     * Ask a question about the call using Cloudflare Workers AI Llama 3.1.
     */
    suspend fun chatWithCall(
        transcript: String,
        summary: String,
        question: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = getCleanUrl()
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Cloudflare Worker URL is not configured."))
        }

        try {
            val token = getToken()
            val prompt = """
You are an expert AI assistant answering questions about a specific phone call recording.
Use ONLY the call transcript and summary below to answer the user's question accurately.

CALL TRANSCRIPT:
$transcript

CALL SUMMARY:
$summary

USER QUESTION:
$question

Provide a direct, helpful, and concise answer based strictly on the conversation.
            """.trimIndent()

            val bodyJson = JSONObject().apply {
                put("prompt", prompt)
            }

            val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val url = "$baseUrl/chat"

            val request = Request.Builder()
                .url(url)
                .apply {
                    if (token.isNotBlank()) {
                        addHeader("Authorization", "Bearer $token")
                    }
                }
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errMsg = try { JSONObject(body).optString("error", body) } catch (_: Exception) { body }
                    return@withContext Result.failure(Exception("Chat failed (HTTP $code): ${errMsg.take(200)}"))
                }

                val json = JSONObject(body)
                val reply = json.optString("reply", "").trim()
                if (reply.isBlank()) {
                    return@withContext Result.failure(Exception("Empty reply from Cloudflare Worker."))
                }

                Result.success(reply)
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(Exception("Cloudflare chat failed: ${e.localizedMessage}", e))
        }
    }
}
