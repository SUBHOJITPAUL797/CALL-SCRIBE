package com.example.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
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
        private const val BASE_URL = "https://api.nvidia.com/v1"
        private const val ASR_MODEL = "nvidia/canary-1b"
        private const val CHAT_MODEL = "meta/llama-3.1-70b-instruct"
        const val MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024 // 25 MB
    }

    fun isApiKeyConfigured(): Boolean {
        val key = apiKeyProvider().trim()
        return key.isNotBlank() && key.length > 10
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Transcribe audio bytes → plain text transcript using NVIDIA Canary ASR */
    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        fileName: String,
        mimeType: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (!isApiKeyConfigured()) {
            return@withContext Result.failure(Exception("NVIDIA API key not configured."))
        }

        val safeFileName = fileName.ifBlank { "recording.mp3" }
        val mediaType = mimeType.toMediaTypeOrNull() ?: "audio/mp3".toMediaTypeOrNull()
        val fileBody = audioBytes.toRequestBody(mediaType)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", ASR_MODEL)
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
            if (!response.isSuccessful) {
                return@withContext when (response.code) {
                    401, 403 -> Result.failure(Exception("Invalid NVIDIA API key (HTTP ${response.code})."))
                    429 -> Result.failure(Exception("NVIDIA API rate limit exceeded."))
                    else -> Result.failure(Exception("NVIDIA transcription error: ${response.code} — $body"))
                }
            }
            val json = JSONObject(body ?: "{}")
            val transcript = json.optString("text", "").trim()
            if (transcript.isBlank()) {
                Result.failure(Exception("NVIDIA returned empty transcript."))
            } else {
                Result.success(transcript)
            }
        } catch (e: Exception) {
            Result.failure(Exception("NVIDIA transcription failed: ${e.localizedMessage}", e))
        }
    }

    /** Summarize an existing transcript text using NVIDIA Llama 3.1 70B */
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

        val body = JSONObject().apply {
            put("model", CHAT_MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
            })
            put("temperature", 0.2)
            put("max_tokens", 1500)
        }

        return@withContext callChatApi(apiKey, body)
    }

    /** Answer a user question about a call using Llama 3.1 */
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

        val body = JSONObject().apply {
            put("model", CHAT_MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", "$context\n\nUser Question: $question") })
            })
            put("temperature", 0.3)
            put("max_tokens", 600)
        }

        return@withContext callChatApi(apiKey, body)
    }

    /** Validate an NVIDIA API key */
    suspend fun testApiKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank() || trimmed.length < 10) {
            return@withContext Result.failure(Exception("Key is too short or empty."))
        }

        val body = JSONObject().apply {
            put("model", CHAT_MODEL)
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
            return@withContext when {
                response.isSuccessful -> Result.success("✅ NVIDIA API key is valid and working!")
                response.code == 401 || response.code == 403 ->
                    Result.failure(Exception("Invalid NVIDIA key (HTTP ${response.code}). Verify at build.nvidia.com."))
                response.code == 429 -> Result.success("✅ Key is valid — rate limit reached. Try again soon.")
                else -> Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.localizedMessage ?: "Connection error", e))
        }
    }

    // Shared helper for chat/completions endpoint
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
                    401, 403 -> Result.failure(Exception("Invalid NVIDIA API key (HTTP ${response.code})."))
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
