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
    val content: Content? = null
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

    // Increased timeout for audio processing
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
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

class GeminiRepository {
    suspend fun summarizeTranscription(transcription: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
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
            "Error generating summary: ${e.localizedMessage}"
        }
    }

    suspend fun transcribeAndSummarizeAudio(base64Audio: String, mimeType: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        val request = GenerateContentRequest(
            systemInstruction = Content(
                parts = listOf(Part(text = "You are an assistant. The user provides an audio file. You must first output 'TRANSCRIPTION:' followed by a full text transcription of the audio. Then output 'SUMMARY:' followed by a 150-word action-item summary of the transcript. Do NOT stray from this format."))
            ),
            contents = listOf(Content(
                parts = listOf(
                    Part(text = "Please transcribe and summarize this meeting recording."),
                    Part(inlineData = InlineData(mimeType = mimeType, data = base64Audio))
                )
            )),
            generationConfig = GenerationConfig(temperature = 0.2f)
        )
        try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            val fullText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: return@withContext null
            
            // Basic parsing of the structured output
            val transcriptionLabelPos = fullText.indexOf("TRANSCRIPTION:")
            val summaryLabelPos = fullText.indexOf("SUMMARY:")

            if (transcriptionLabelPos != -1 && summaryLabelPos != -1 && summaryLabelPos > transcriptionLabelPos) {
                val transcription = fullText.substring(transcriptionLabelPos + 14, summaryLabelPos).trim()
                val summary = fullText.substring(summaryLabelPos + 8).trim()
                Pair(transcription, summary)
            } else {
                Pair(fullText, "Summary not properly formatted.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
