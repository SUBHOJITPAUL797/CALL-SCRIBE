package com.example

import com.example.data.PreferredEngine
import com.example.network.CloudflareWorkerRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudflareWorkerTest {

    @Test
    fun testPreferredEngineEnum() {
        val engines = PreferredEngine.values()
        assertEquals(5, engines.size)

        val names = engines.map { it.name }
        assertTrue(names.contains("AUTO"))
        assertTrue(names.contains("CLOUDFLARE"))
        assertTrue(names.contains("GEMINI"))
        assertTrue(names.contains("NVIDIA"))
        assertTrue(names.contains("ON_DEVICE"))

        // Check that all engines have valid non-empty display name and description
        engines.forEach { engine ->
            assertTrue(engine.displayName.isNotBlank())
            assertTrue(engine.description.isNotBlank())
        }
    }

    @Test
    fun testCloudflareWorkerRepositoryIsConfigured() {
        // Unconfigured instances
        val repoEmpty = CloudflareWorkerRepository(urlProvider = { "" })
        assertFalse(repoEmpty.isConfigured())

        val repoWhitespace = CloudflareWorkerRepository(urlProvider = { "   " })
        assertFalse(repoWhitespace.isConfigured())

        val repoInvalid = CloudflareWorkerRepository(urlProvider = { "invalid-url-without-scheme" })
        assertFalse(repoInvalid.isConfigured())

        // Configured instances
        val repoHttps = CloudflareWorkerRepository(urlProvider = { "https://call-scribe.subdomain.workers.dev" })
        assertTrue(repoHttps.isConfigured())

        val repoWithTrailingSlash = CloudflareWorkerRepository(urlProvider = { "https://call-scribe.subdomain.workers.dev/" })
        assertTrue(repoWithTrailingSlash.isConfigured())

        val repoHttp = CloudflareWorkerRepository(urlProvider = { "http://localhost:8787" })
        assertTrue(repoHttp.isConfigured())
    }

    @Test
    fun testDefaultCloudflareWorkerUrl() {
        assertEquals("https://callscribe-ai.subhojit.workers.dev", com.example.data.ApiKeyManager.DEFAULT_CLOUDFLARE_WORKER_URL)
        val defaultRepo = CloudflareWorkerRepository(urlProvider = { com.example.data.ApiKeyManager.DEFAULT_CLOUDFLARE_WORKER_URL })
        assertTrue(defaultRepo.isConfigured())
    }

    @Test
    fun testCloudflareTranscribeResponseParsing() {
        // Standard Cloudflare Worker /transcribe response
        val jsonStandard = JSONObject("""{"success": true, "transcription": "Hello, I am calling regarding tomorrow's meeting."}""")
        val textStandard = jsonStandard.optString("transcription", jsonStandard.optString("text", "")).trim()
        assertEquals("Hello, I am calling regarding tomorrow's meeting.", textStandard)

        // Raw Whisper output format
        val jsonWhisperRaw = JSONObject("""{"text": "Call transcription from Whisper model."}""")
        val textWhisper = jsonWhisperRaw.optString("transcription", jsonWhisperRaw.optString("text", "")).trim()
        assertEquals("Call transcription from Whisper model.", textWhisper)

        // Empty response
        val jsonEmpty = JSONObject("""{"error": "No speech detected"}""")
        val textEmpty = jsonEmpty.optString("transcription", jsonEmpty.optString("text", "")).trim()
        assertTrue(textEmpty.isBlank())
    }

    @Test
    fun testCloudflareSummarizeResponseParsing() {
        val json = JSONObject("""
            {
                "success": true,
                "summary": "### 📌 Overview\n- Client discussed contract renewal\n\n### ⚡ Action Items\n- [ ] Send quote by Friday"
            }
        """.trimIndent())

        val summary = json.optString("summary", "").trim()
        assertTrue(summary.contains("Overview"))
        assertTrue(summary.contains("Send quote by Friday"))
    }

    @Test
    fun testCloudflareAnalyzeResponseParsing() {
        val json = JSONObject("""
            {
                "success": true,
                "transcription": "This is a full call transcription test.",
                "summary": "### 📌 Overview\n- Call overview summary",
                "model_asr": "@cf/openai/whisper",
                "model_llm": "@cf/meta/llama-3.1-8b-instruct"
            }
        """.trimIndent())

        val transcription = json.optString("transcription", "").trim()
        val summary = json.optString("summary", "").trim()

        assertEquals("This is a full call transcription test.", transcription)
        assertTrue(summary.contains("Call overview summary"))
    }

    @Test
    fun testCloudflareChatResponseParsing() {
        val json = JSONObject("""
            {
                "reply": "The caller mentioned that the deadline is next Wednesday at 5 PM."
            }
        """.trimIndent())

        val reply = json.optString("reply", "").trim()
        assertEquals("The caller mentioned that the deadline is next Wednesday at 5 PM.", reply)
    }

    @Test
    fun testCloudflareHealthResponseParsing() {
        val json = JSONObject("""
            {
                "status": "ok",
                "service": "Call Scribe AI Worker",
                "message": "Cloudflare Workers AI (Whisper + Llama 3.1) is online"
            }
        """.trimIndent())

        val status = json.optString("status")
        val message = json.optString("message")

        assertEquals("ok", status)
        assertNotNull(message)
        assertTrue(message.contains("Whisper"))
    }
}
