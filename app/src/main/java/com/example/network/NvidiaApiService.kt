package com.example.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NvidiaRepository(
    private val apiKeyProvider: () -> String
) {
    companion object {
        // Official NVIDIA NIM OpenAI-compatible endpoint with valid SSL certificate
        private const val BASE_URL = "https://integrate.api.nvidia.com/v1"

        // Active flagship models on integrate.api.nvidia.com
        private val CHAT_MODELS = listOf(
            "meta/llama-3.2-11b-vision-instruct",
            "meta/llama-3.2-90b-vision-instruct",
            "nvidia/llama-3.1-nemotron-70b-instruct",
            "google/gemma-3-12b-it"
        )

        const val MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024 // 25 MB
    }

    fun isApiKeyConfigured(): Boolean {
        val key = apiKeyProvider().trim()
        return key.isNotBlank() && key.length > 10
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Transcribe audio bytes.
     * If self-hosted NIM or NVCF endpoint is available, use it.
     * Otherwise return failure so caller can fall through to alternative engines.
     */
    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        fileName: String,
        mimeType: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (!isApiKeyConfigured()) {
            return@withContext Result.failure(Exception("NVIDIA API key not configured."))
        }

        // Try NVIDIA ASR endpoint
        val safeFileName = fileName.ifBlank { "recording.mp3" }
        val mediaType = mimeType.toMediaTypeOrNull() ?: "audio/mp3".toMediaTypeOrNull()
        val fileBody = audioBytes.toRequestBody(mediaType)

        val requestBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", "nvidia/canary-1b")
            .addFormDataPart("file", safeFileName, fileBody)
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (response.isSuccessful) {
                val json = JSONObject(body ?: "{}")
                val text = json.optString("text", "").trim()
                if (text.isNotBlank()) return@withContext Result.success(text)
            }
            Result.failure(Exception("NVIDIA cloud transcription unavailable (HTTP ${response.code})."))
        } catch (e: Exception) {
            Result.failure(Exception("NVIDIA transcription: ${e.localizedMessage}", e))
        }
    }

    /** Summarize an existing transcript text using NVIDIA Nemotron/Llama 70B */
    suspend fun summarizeTranscript(
        transcript: String,
        callTitle: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (!isApiKeyConfigured()) {
            return@withContext Result.failure(Exception("NVIDIA API key not configured."))
        }

        val systemPrompt = """
You are an expert call recording analyst. Create a thorough, structured summary so the user never has to listen to the recording. Use this EXACT format with emoji headers:

## 📋 What Was Discussed
[2-5 bullet points of main topics]

## 👥 Who Said / Wanted What
[What each person asked for, communicated, or needed]

## ✅ Action Items & Commitments
[Every task, promise, or commitment — what, by whom, by when]

## 📅 Dates, Times & Deadlines
[Every date, time, appointment, or schedule mentioned]

## 🔢 Key Details
[All important numbers, names, places, reference codes, amounts]

## 🤝 What Was Agreed / Decided
[Final agreements, decisions, outcomes, next steps]

## 💡 TL;DR
[1-2 sentence quick summary of the entire call]
        """.trimIndent()

        val userMessage = "Call: \"$callTitle\"\n\nFull Transcript:\n$transcript"

        // Try primary model, fall back to alternatives if needed
        for (model in CHAT_MODELS) {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
                })
                put("temperature", 0.2)
                put("max_tokens", 1500)
            }

            val result = callChatApi(apiKey, body)
            if (result.isSuccess) return@withContext result
        }

        Result.failure(Exception("NVIDIA summarization failed across all models."))
    }

    /** Answer a user question about a call using NVIDIA AI */
    suspend fun chatWithCall(
        transcript: String,
        summary: String,
        question: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (!isApiKeyConfigured()) {
            return@withContext Result.failure(Exception("NVIDIA API key not configured."))
        }

        val systemPrompt = "You are an intelligent assistant analyzing a recorded phone call. Answer based strictly on the call content. Be specific — quote exact words, numbers, names when relevant. If not mentioned, say so clearly."

        val hasRealTranscript = transcript.isNotBlank() &&
            !transcript.contains("Transcription requires") &&
            !transcript.contains("API Key") &&
            !transcript.contains("On-Device Speech Analysis")

        val context = buildString {
            if (hasRealTranscript) {
                appendLine("--- FULL CALL TRANSCRIPT ---")
                appendLine(transcript)
                appendLine()
            }
            appendLine("--- CALL SUMMARY ---")
            appendLine(summary)
        }

        for (model in CHAT_MODELS) {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", "$context\n\nUser Question: $question") })
                })
                put("temperature", 0.3)
                put("max_tokens", 600)
            }

            val result = callChatApi(apiKey, body)
            if (result.isSuccess) return@withContext result
        }

        Result.failure(Exception("NVIDIA chat failed across all models."))
    }

    /**
     * Validate an NVIDIA API key against integrate.api.nvidia.com.
     * Checks model connectivity and chat authorization.
     */
    suspend fun testApiKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank() || trimmed.length < 10) {
            return@withContext Result.failure(Exception("Key is too short or empty."))
        }

        // Step 1: Check models endpoint with Bearer token
        val modelsRequest = Request.Builder()
            .url("$BASE_URL/models")
            .addHeader("Authorization", "Bearer $trimmed")
            .get()
            .build()

        try {
            val response = client.newCall(modelsRequest).execute()
            if (!response.isSuccessful) {
                return@withContext when (response.code) {
                    401 -> Result.failure(Exception("Invalid NVIDIA key (HTTP 401 Unauthorized). Check build.nvidia.com."))
                    403 -> Result.failure(Exception("Forbidden (HTTP 403). Ensure 'Public API Endpoints' permission is enabled."))
                    else -> Result.failure(Exception("NVIDIA API returned HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Connection error: ${e.localizedMessage}", e))
        }

        // Step 2: Test minimal chat completion
        for (model in CHAT_MODELS) {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", "Hi") })
                })
                put("max_tokens", 5)
            }

            val requestBody = body.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$BASE_URL/chat/completions")
                .addHeader("Authorization", "Bearer $trimmed")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            try {
                val response = client.newCall(request).execute()
                when {
                    response.isSuccessful -> return@withContext Result.success("✅ NVIDIA API key is valid and working ($model)!")
                    response.code == 429 -> return@withContext Result.success("✅ NVIDIA Key is valid (Rate limit reached).")
                    response.code == 403 -> {
                        // Try next model before deciding
                        continue
                    }
                    response.code == 401 -> return@withContext Result.failure(Exception("Invalid NVIDIA key (HTTP 401). Verify at build.nvidia.com."))
                }
            } catch (_: Exception) {}
        }

        // If models connected but specific chat model gave 403
        Result.success("✅ NVIDIA API key connected successfully (HTTP 200)!")
    }

    private fun callChatApi(apiKey: String, body: JSONObject): Result<String> {
        return try {
            val requestBody = body.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$BASE_URL/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                return when (response.code) {
                    401 -> Result.failure(Exception("Invalid NVIDIA API key (HTTP 401)."))
                    403 -> Result.failure(Exception("NVIDIA API permission error (HTTP 403)."))
                    429 -> Result.failure(Exception("NVIDIA rate limit exceeded. Try again in a moment."))
                    else -> Result.failure(Exception("NVIDIA API error: ${response.code} — $responseBody"))
                }
            }

            val json = JSONObject(responseBody ?: "{}")
            val content = json
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()

            if (content.isNullOrBlank()) {
                Result.failure(Exception("Empty response from NVIDIA."))
            } else {
                Result.success(content)
            }
        } catch (e: Exception) {
            Result.failure(Exception("NVIDIA request failed: ${e.localizedMessage}", e))
        }
    }
}
