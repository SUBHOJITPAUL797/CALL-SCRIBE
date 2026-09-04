package com.example.network

import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    @POST("v1beta/models/gemini-1.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
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
        """(?i)(?:\*{1,2}|#{1,4}\s*)?(?:SUMMARY|ACTION ITEMS|KEY TAKEAWAYS|SUMMARY & ACTION ITEMS)(?:\*{1,2})?:?(?:\*{1,2})?\s*"""
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
            }
        } else if (summaryMatch != null) {
            val summaryHeaderStart = summaryMatch.range.first
            val summaryStart = summaryMatch.range.last + 1
            val transcription = trimmed.substring(0, summaryHeaderStart).trim()
            val summary = trimmed.substring(summaryStart).trim()
            return Pair(
                transcription.ifBlank { "Transcription not separated." },
                summary.ifBlank { "No summary generated." }
            )
        }

        // Fallback: Check if response has clear paragraphs
        val paragraphs = trimmed.split("\n\n").filter { it.isNotBlank() }
        return if (paragraphs.size > 1) {
            Pair(paragraphs.dropLast(1).joinToString("\n\n").trim(), paragraphs.last().trim())
        } else {
            Pair(trimmed, "No separate summary section generated.")
        }
    }
}

class ApiKeyMissingException(message: String = "Gemini API key is not configured. Please enter your API key in Settings.") : Exception(message)
class ApiKeyInvalidException(message: String = "Invalid Gemini API key. Please check your key in Settings.") : Exception(message)
class ApiQuotaExceededException(message: String = "Gemini API quota exceeded or rate limit reached. Please wait a moment.") : Exception(message)

class GeminiRepository(
    private val apiKeyProvider: () -> String = { BuildConfig.GEMINI_API_KEY }
) {
    fun isApiKeyConfigured(): Boolean {
        val key = apiKeyProvider().trim()
        return key.isNotBlank() &&
               !key.equals("MY_GEMINI_API_KEY", ignoreCase = true) &&
               !key.equals("YOUR_API_KEY", ignoreCase = true) &&
               key.length > 10
    }

    suspend fun summarizeTranscription(transcription: String): String = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (!isApiKeyConfigured()) {
            return@withContext "Error: Gemini API key is not configured. Please enter your API key in Settings."
        }
        val request = GenerateContentRequest(
            systemInstruction = Content(
                parts = listOf(Part(text = "You are a helpful assistant that summarizes call transcripts into key action items. Return a clear, concise bulleted list of action items, or a short paragraph summary if no clear action items exist. Do not exceed 200 words. Format clearly."))
            ),
            contents = listOf(Content(
                parts = listOf(Part(text = transcription))
            )),
            generationConfig = GenerationConfig(temperature = 0.3f)
        )
        try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
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
            return@withContext Result.failure(
                ApiKeyMissingException()
            )
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

        val request = GenerateContentRequest(
            systemInstruction = Content(
                parts = listOf(Part(text = "You are an assistant. The user provides an audio file. You must first output 'TRANSCRIPTION:' followed by a full text transcription of the audio. Then output 'SUMMARY:' followed by a 150-word action-item summary of the transcript. Do NOT stray from this format."))
            ),
            contents = listOf(Content(
                parts = listOf(
                    Part(text = "Please transcribe and summarize this meeting recording."),
                    Part(inlineData = InlineData(mimeType = normalizedMime, data = base64Audio))
                )
            )),
            generationConfig = GenerationConfig(temperature = 0.2f)
        )
        try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
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
                429 -> ApiQuotaExceededException("Gemini rate limit or quota exceeded (HTTP 429). Please wait a moment.")
                else -> {
                    val message = if (!errorBody.isNullOrBlank()) "API Error ($code): $errorBody" else "HTTP error: $code ${e.message()}"
                    Exception(message, e)
                }
            }
            Result.failure(exception)
        } catch (e: Throwable) {
            Result.failure(Exception(e.localizedMessage ?: "Unknown error occurred during analysis", e))
        }
    }

    suspend fun testApiKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank() || trimmed.length < 10) {
            return@withContext Result.failure(Exception("API Key is too short or empty."))
        }

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = "Hello")))),
            generationConfig = GenerationConfig(temperature = 0.1f)
        )
        try {
            val response = RetrofitClient.service.generateContent(trimmed, request)
            if (response.candidates?.isNotEmpty() == true) {
                Result.success("API Key is valid and working!")
            } else {
                Result.success("Connected to Gemini successfully.")
            }
        } catch (e: retrofit2.HttpException) {
            val code = e.code()
            if (code == 400 || code == 401 || code == 403) {
                Result.failure(ApiKeyInvalidException("Invalid API key (HTTP $code). Please verify in Google AI Studio."))
            } else if (code == 429) {
                Result.success("Key is valid, but current quota/rate limit is reached.")
            } else {
                Result.failure(Exception("HTTP $code: ${e.message()}"))
            }
        } catch (e: Throwable) {
            Result.failure(Exception(e.localizedMessage ?: "Connection error", e))
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

        val prompt = buildString {
            appendLine("You are an intelligent assistant analyzing a specific call recording.")
            appendLine("Answer the user's question clearly, politely, and concisely based strictly on the transcription and summary below.")
            appendLine("If the information was not mentioned in the call, explicitly state that it was not discussed.")
            appendLine("\n--- CALL TRANSCRIPTION ---")
            appendLine(transcript)
            appendLine("\n--- CALL SUMMARY ---")
            appendLine(summary)
            appendLine("\n--- USER QUESTION ---")
            appendLine(question)
        }

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = prompt)))),
            generationConfig = GenerationConfig(temperature = 0.3f)
        )

        try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            val answer = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (!answer.isNullOrBlank()) {
                Result.success(answer.trim())
            } else {
                Result.failure(Exception("No answer received from Gemini."))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
