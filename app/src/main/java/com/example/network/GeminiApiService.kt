package com.example.network

import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@JsonClass(generateAdapter = true)
data class ListModelsResponse(
    val models: List<ModelInfo>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class ModelInfo(
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val supportedGenerationMethods: List<String>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    val systemInstruction: Content? = null,
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null
)

@JsonClass(generateAdapter = true)
data class InlineData(
    val mimeType: String,
    val data: String
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    val temperature: Float? = null,
    val topK: Int? = null,
    val topP: Float? = null
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    val candidates: List<Candidate>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val content: Content? = null,
    val finishReason: String? = null
)

interface GeminiApiService {
    @GET("v1beta/models")
    suspend fun listModels(
        @Header("x-goog-api-key") apiKey: String
    ): ListModelsResponse

    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val service: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(GeminiApiService::class.java)
    }
}

object GeminiResponseParser {
    private val transcriptionPattern = Regex(
        """(?i)(?:\*{1,2}|#{1,4}\s*)?(?:TRANSCRIPTION|TRANSCRIPT)(?:\*{1,2})?:?(?:\*{1,2})?\s*"""
    )
    private val summaryPattern = Regex(
        """(?i)(?:\*{1,2}|#{1,4}\s*)?(?:SUMMARY|ACTION ITEMS|KEY TAKEAWAYS|SUMMARY & ACTION ITEMS|STRUCTURED SUMMARY)(?:\*{1,2})?:?(?:\*{1,2})?\s*"""
    )

    fun parseAudioAnalysis(fullText: String): Pair<String, String> {
        val trimmed = fullText.trim()
        if (trimmed.isBlank()) {
            return Pair("No speech detected.", "No summary available.")
        }

        val summaryMatch = summaryPattern.find(trimmed)
        val transcriptionMatch = transcriptionPattern.find(trimmed)

        if (summaryMatch != null && transcriptionMatch != null) {
            val transcriptionStart = transcriptionMatch.range.last + 1
            val summaryHeaderStart = summaryMatch.range.first
            val summaryStart = summaryMatch.range.last + 1

            if (summaryHeaderStart > transcriptionStart) {
                val transcription = trimmed.substring(transcriptionStart, summaryHeaderStart).trim()
                val summary = trimmed.substring(summaryStart).trim()
                return Pair(
                    transcription.ifBlank { "No transcription content." },
                    summary.ifBlank { "No summary generated." }
                )
            } else {
                val summary = trimmed.substring(summaryStart, transcriptionMatch.range.first).trim()
                val transcription = trimmed.substring(transcriptionStart).trim()
                return Pair(
                    transcription.ifBlank { "No transcription content." },
                    summary.ifBlank { "No summary generated." }
                )
            }
        } else if (summaryMatch != null) {
            val summaryHeaderStart = summaryMatch.range.first
            val summaryStart = summaryMatch.range.last + 1
            val transcription = trimmed.substring(0, summaryHeaderStart).trim()
            val summary = trimmed.substring(summaryStart).trim()
            return Pair(
                transcription.ifBlank { "No transcription content." },
                summary.ifBlank { "No summary generated." }
            )
        }

        return Pair(trimmed, "See transcription for complete call details.")
    }
}

class GeminiRepository(
    private val apiKeyProvider: () -> String = { BuildConfig.GEMINI_API_KEY },
    private val keyPoolProvider: (() -> List<String>)? = null,
    private val onKeyRotate: ((String) -> Unit)? = null
) {
    companion object {
        // High-capacity production models in priority order for speech & summarization
        val CANDIDATE_MODELS = listOf(
            "gemini-2.0-flash",
            "gemini-1.5-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-flash-latest",
            "gemini-1.5-flash-8b",
            "gemini-2.0-flash-lite",
            "gemini-1.5-pro"
        )
    }

    @Volatile
    private var cachedWorkingModel: String? = null

    /**
     * Returns all active Gemini keys for automatic failover.
     */
    fun getAllKeys(): List<String> {
        val pool = keyPoolProvider?.invoke()?.filter { it.length > 10 }
        if (!pool.isNullOrEmpty()) return pool
        val raw = apiKeyProvider().trim()
        val split = raw.split(',', ';', '\n', '\r')
            .map { it.trim() }
            .filter { it.length > 10 && !it.equals("MY_GEMINI_API_KEY", ignoreCase = true) && !it.equals("YOUR_API_KEY", ignoreCase = true) }
        return if (split.isNotEmpty()) split else if (raw.length > 10) listOf(raw) else emptyList()
    }

    /**
     * Dynamically discovers available models for the given API key using Google's ModelService.ListModels.
     * Prioritizes high-quota production flash models and excludes low-quota experimental/pro models.
     */
    suspend fun discoverActiveModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.service.listModels(apiKey)
            val available = response.models
                ?.filter { it.supportedGenerationMethods?.contains("generateContent") == true }
                ?.map { it.name.removePrefix("models/") }
                ?.filter { !it.contains("pro-exp") && !it.contains("thinking-exp") && !it.contains("embed") && !it.contains("aqa") }
                ?.distinct()
                ?: emptyList()

            if (available.isNotEmpty()) {
                val sorted = available.sortedWith(
                    compareByDescending<String> { it.contains("3.8-flash") }
                        .thenByDescending { it.contains("3.7-flash") }
                        .thenByDescending { it.contains("3.6-flash") }
                        .thenByDescending { it.contains("3.5-flash") && !it.contains("lite") }
                        .thenByDescending { it.contains("3.5-flash-lite") }
                        .thenByDescending { it == "gemini-flash-latest" }
                        .thenByDescending { it == "gemini-flash-lite-latest" }
                        .thenByDescending { it == "gemini-2.0-flash" }
                        .thenByDescending { it == "gemini-1.5-flash" }
                        .thenByDescending { it == "gemini-1.5-flash-8b" }
                        .thenByDescending { it.contains("flash") && !it.contains("exp") }
                        .thenByDescending { it.contains("flash") }
                )
                return@withContext sorted
            }
        } catch (_: Exception) {
            // If listModels fails, fall back to CANDIDATE_MODELS
        }
        return@withContext CANDIDATE_MODELS
    }

    private suspend fun executeWithModelFallback(
        apiKey: String,
        request: GenerateContentRequest
    ): Pair<String, GenerateContentResponse> {
        val keysToTry = getAllKeys().ifEmpty { listOf(apiKey.trim()) }
        var lastException: retrofit2.HttpException? = null

        fun isTransientError(code: Int): Boolean =
            code == 404 || code == 429 || code == 503 || code == 500 || code == 502 || code == 504

        for (activeKey in keysToTry) {
            // 1. Try cached working model first on current active key
            cachedWorkingModel?.let { model ->
                try {
                    val response = RetrofitClient.service.generateContent(model, activeKey, request)
                    return Pair(model, response)
                } catch (e: retrofit2.HttpException) {
                    if (isTransientError(e.code())) {
                        cachedWorkingModel = null
                        lastException = e
                    } else {
                        throw e
                    }
                }
            }

            // 2. Discover available models from Google AI Studio for this active key
            val discovered = try { discoverActiveModels(activeKey) } catch (_: Exception) { emptyList() }
            val allToTry = (CANDIDATE_MODELS + discovered).distinct()

            for (model in allToTry) {
                try {
                    val response = RetrofitClient.service.generateContent(model, activeKey, request)
                    cachedWorkingModel = model
                    return Pair(model, response)
                } catch (e: retrofit2.HttpException) {
                    if (isTransientError(e.code())) {
                        // 404 (model not found), 429 (quota/rate limit), or 503/500 (model overloaded)
                        // Gemini maintains distinct quota pools per model (1.5-flash vs 1.5-flash-8b vs 2.0-flash)!
                        // Failover to next candidate model immediately without crashing.
                        cachedWorkingModel = null
                        lastException = e
                        continue
                    }
                    throw e
                }
            }

            // If active key was throttled across all models, notify key rotation
            if (lastException?.code() == 429) {
                onKeyRotate?.invoke(activeKey)
            }
        }

        throw (lastException ?: Exception("No available Gemini model or key found supporting generateContent."))
    }

    fun isApiKeyConfigured(): Boolean {
        return getAllKeys().isNotEmpty()
    }

    suspend fun summarizeTranscription(transcription: String): String = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (!isApiKeyConfigured()) {
            return@withContext "Error: Gemini API key is not configured. Please enter your API key in Settings."
        }
        val request = GenerateContentRequest(
            systemInstruction = Content(
                parts = listOf(Part(text = "You are an expert assistant who creates detailed, structured summaries from call transcripts. Be thorough — extract everything important so the user never has to listen to the recording again."))
            ),
            contents = listOf(Content(
                parts = listOf(Part(text = buildString {
                    appendLine("Analyze the following call transcript and give a comprehensive structured summary.")
                    appendLine("Format your response with these sections (use bullet points under each):")
                    appendLine("")
                    appendLine("## 📋 What Was Discussed")
                    appendLine("## 👥 Who Wanted What")
                    appendLine("## ✅ Action Items & Commitments")
                    appendLine("## 📅 Dates, Times & Deadlines")
                    appendLine("## 🔢 Key Details (numbers, names, places, amounts)")
                    appendLine("## 🤝 What Was Agreed Upon")
                    appendLine("")
                    appendLine("TRANSCRIPT:")
                    appendLine(transcription)
                }))
            )),
            generationConfig = GenerationConfig(temperature = 0.2f)
        )
        try {
            val (_, response) = executeWithModelFallback(apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "No summary generated."
        } catch (e: Exception) {
            "Error generating summary: ${e.localizedMessage ?: e.message}"
        }
    }

    suspend fun transcribeAndSummarizeAudio(
        base64Audio: String,
        mimeType: String
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (!isApiKeyConfigured()) {
            return@withContext Result.failure(ApiKeyMissingException())
        }

        val normalizedMime = when {
            mimeType.startsWith("audio/mp3") || mimeType.contains("mpeg") || mimeType.endsWith("mp3") -> "audio/mp3"
            mimeType.startsWith("audio/m4a") || mimeType.startsWith("audio/mp4") || mimeType.endsWith("m4a") -> "audio/mp4"
            mimeType.startsWith("audio/wav") || mimeType.startsWith("audio/x-wav") || mimeType.endsWith("wav") -> "audio/wav"
            mimeType.startsWith("audio/ogg") || mimeType.startsWith("audio/opus") || mimeType.endsWith("ogg") || mimeType.endsWith("opus") -> "audio/ogg"
            mimeType.startsWith("audio/aac") || mimeType.endsWith("aac") -> "audio/aac"
            mimeType.startsWith("audio/flac") || mimeType.endsWith("flac") -> "audio/flac"
            mimeType.startsWith("audio/3gpp") || mimeType.startsWith("video/3gpp") || mimeType.startsWith("audio/amr") || mimeType.endsWith("3gp") || mimeType.endsWith("amr") -> "audio/3gpp"
            else -> "audio/mp3"
        }

        // Rich multilingual system prompt for complete call analysis
        val systemPrompt = """
You are an expert multilingual call recording analyst specializing in Bengali (বাংলা), Hindi (हिंदी), English, and code-mixed conversations (Banglish / Hinglish).
Your job is to make sure the user knows EVERYTHING that was said in this call without having to listen to it. Be thorough, structured, and detailed.

CRITICAL LANGUAGE INSTRUCTIONS:
- The speakers predominantly speak in Bengali, Hindi, English, or a mix of these languages.
- In TRANSCRIPTION: write the exact verbatim words spoken in the correct script.
  * For Bengali speech, write in Bengali script (বাংলা) or natural script if mixed.
  * For Hindi speech, write in Devanagari script (हिंदी) or natural script if mixed.
  * For English speech, write in English.
  * Do NOT translate or transcribe into unrelated languages like Korean, Arabic, Chinese, or Welsh.
- In SUMMARY: Provide the structured summary in clear English so the user can easily understand all points, commitments, and decisions regardless of the language spoken in the call.

Listen to this phone call audio carefully. Then output in EXACTLY this format:

TRANSCRIPTION:
[Write the FULL verbatim transcription of everything spoken. If you can identify speakers, label them as "Speaker 1:", "Speaker 2:" etc. Include every word spoken.]

SUMMARY:
## 📋 What Was Discussed
[2-5 bullet points of the main topics covered in this call]

## 👥 Who Said / Wanted What
[What each person asked for, needed, requested, or communicated]

## ✅ Action Items & Commitments
[Every specific task, promise, or commitment made — what, by whom, by when]

## 📅 Dates, Times & Deadlines
[Every date, time, appointment, deadline, or schedule mentioned]

## 🔢 Key Details
[All important numbers, names, places, reference numbers, amounts, phone numbers, addresses]

## 🤝 What Was Agreed / Decided
[Final agreements, decisions, outcomes, or next steps]

## 💡 Quick Summary (1-2 sentences)
[Ultra-brief TL;DR of the entire call]

Do NOT skip any section. Do NOT summarize too briefly. The user needs to know everything.
        """.trimIndent()

        val request = GenerateContentRequest(
            systemInstruction = Content(parts = listOf(Part(text = systemPrompt))),
            contents = listOf(Content(
                parts = listOf(
                    Part(text = "Transcribe and analyze this call recording completely."),
                    Part(inlineData = InlineData(mimeType = normalizedMime, data = base64Audio))
                )
            )),
            generationConfig = GenerationConfig(temperature = 0.1f)
        )
        try {
            val (_, response) = executeWithModelFallback(apiKey, request)
            val fullText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (fullText.isNullOrBlank()) {
                val finishReason = response.candidates?.firstOrNull()?.finishReason
                return@withContext Result.failure(
                    Exception(if (finishReason != null) "Generation stopped: $finishReason" else "No response from Gemini API.")
                )
            }
            val parsed = GeminiResponseParser.parseAudioAnalysis(fullText)
            Result.success(parsed)
        } catch (e: retrofit2.HttpException) {
            val code = e.code()
            val errorBody = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
            val exception = when (code) {
                400, 401, 403 -> ApiKeyInvalidException("Gemini API Key is invalid (HTTP $code). Please check your key in Settings.")
                429 -> ApiQuotaExceededException("Google AI Studio 1-minute speed limit reached. Your daily 1,500 quota is active! Resets in 30–60s.")
                503 -> Exception("Google Gemini service is temporarily overloaded. Please try again in a few moments.")
                else -> {
                    val message = if (!errorBody.isNullOrBlank()) "API Error ($code): $errorBody" else "HTTP error: $code ${e.message()}"
                    Exception(message, e)
                }
            }
            Result.failure(exception)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(Exception(e.localizedMessage ?: "Unknown error occurred during analysis", e))
        }
    }

    suspend fun testApiKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val keys = apiKey.split(',', ';', '\n', '\r')
            .map { it.trim() }
            .filter { it.length > 10 }

        if (keys.isEmpty()) {
            return@withContext Result.failure(Exception("API Key is too short or empty."))
        }

        val results = mutableListOf<String>()
        var successCount = 0

        for ((idx, key) in keys.withIndex()) {
            val label = if (keys.size > 1) "Key #${idx + 1}" else "Key"
            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = "Hello")))),
                generationConfig = GenerationConfig(temperature = 0.1f)
            )
            try {
                val (workingModel, response) = executeWithModelFallback(key, request)
                if (response.candidates?.isNotEmpty() == true) {
                    results.add("$label: ✅ Valid ($workingModel)")
                    successCount++
                } else {
                    results.add("$label: ✅ Connected ($workingModel)")
                    successCount++
                }
            } catch (e: retrofit2.HttpException) {
                val code = e.code()
                val errorBody = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                val errorMsg = if (!errorBody.isNullOrBlank()) {
                    try {
                        val json = JSONObject(errorBody)
                        json.optJSONObject("error")?.optString("message", "") ?: errorBody
                    } catch (_: Exception) { errorBody }
                } else e.message()

                if (code == 400 || code == 401 || code == 403) {
                    results.add("$label: ❌ Invalid (HTTP $code)")
                } else if (code == 429) {
                    results.add("$label: ⏳ Valid (Rate limit cooling down)")
                    successCount++
                } else {
                    results.add("$label: ❌ HTTP $code ($errorMsg)")
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                results.add("$label: ❌ ${e.localizedMessage ?: "Connection error"}")
            }
        }

        if (successCount > 0) {
            Result.success(results.joinToString(" · "))
        } else {
            Result.failure(Exception(results.joinToString(" · ")))
        }
    }

    suspend fun chatWithCall(
        transcript: String,
        summary: String,
        question: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (!isApiKeyConfigured()) {
            return@withContext Result.failure(ApiKeyMissingException())
        }

        val hasRealTranscript = transcript.isNotBlank() &&
            !transcript.contains("Tap 🔑") &&
            !transcript.contains("API Key required") &&
            !transcript.contains("On-Device Speech Analysis")

        val prompt = buildString {
            appendLine("You are an intelligent assistant analyzing a specific recorded phone call.")
            appendLine("Answer the user's question thoroughly based on the call content below.")
            appendLine("If the information wasn't mentioned in the call, clearly say so.")
            appendLine("Be specific — quote exact words/numbers when relevant.")
            appendLine("")
            if (hasRealTranscript) {
                appendLine("--- FULL CALL TRANSCRIPTION ---")
                appendLine(transcript)
                appendLine("")
            }
            appendLine("--- CALL SUMMARY ---")
            appendLine(summary)
            appendLine("")
            appendLine("--- USER QUESTION ---")
            appendLine(question)
        }

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = prompt)))),
            generationConfig = GenerationConfig(temperature = 0.2f)
        )

        try {
            val (_, response) = executeWithModelFallback(apiKey, request)
            val answer = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (!answer.isNullOrBlank()) {
                Result.success(answer.trim())
            } else {
                Result.failure(Exception("No answer received from Gemini."))
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }
}

open class GeminiApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ApiKeyMissingException(message: String = "API Key is missing.") : GeminiApiException(message)
class ApiKeyInvalidException(message: String = "API Key is invalid.") : GeminiApiException(message)
class ApiQuotaExceededException(message: String = "API Quota exceeded.") : GeminiApiException(message)

