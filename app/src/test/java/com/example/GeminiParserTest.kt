package com.example

import com.example.data.SimpleEncryption
import com.example.network.GeminiResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiParserTest {

    @Test
    fun testSimpleEncryptionRoundTrip() {
        val sample = "Hello, this is a test transcription with Unicode: 😊 🚀 & symbols!"
        val encrypted = SimpleEncryption.encrypt(sample)
        val decrypted = SimpleEncryption.decrypt(encrypted)
        assertEquals(sample, decrypted)
    }

    @Test
    fun testSimpleEncryptionEmptyString() {
        val empty = ""
        val encrypted = SimpleEncryption.encrypt(empty)
        val decrypted = SimpleEncryption.decrypt(encrypted)
        assertEquals(empty, decrypted)
    }

    @Test
    fun testStandardFormatParsing() {
        val raw = """
            TRANSCRIPTION:
            Speaker 1: Hello everyone.
            Speaker 2: Hi, let us start the meeting.
            
            SUMMARY:
            - Meeting started on time.
            - Discussed general agenda.
        """.trimIndent()

        val (transcription, summary) = GeminiResponseParser.parseAudioAnalysis(raw)
        assertEquals("Speaker 1: Hello everyone.\nSpeaker 2: Hi, let us start the meeting.", transcription)
        assertEquals("- Meeting started on time.\n- Discussed general agenda.", summary)
    }

    @Test
    fun testMarkdownBoldFormatParsing() {
        val raw = """
            **TRANSCRIPTION:**
            Hello, we need to finalize the quarterly review.
            
            **SUMMARY:**
            * Finalize the quarterly review.
        """.trimIndent()

        val (transcription, summary) = GeminiResponseParser.parseAudioAnalysis(raw)
        assertEquals("Hello, we need to finalize the quarterly review.", transcription)
        assertEquals("* Finalize the quarterly review.", summary)
    }

    @Test
    fun testMarkdownHeaderFormatParsing() {
        val raw = """
            ## Transcription
            Team discussed the backend database migration.
            
            ## Action Items
            1. Run migrations before deploy.
        """.trimIndent()

        val (transcription, summary) = GeminiResponseParser.parseAudioAnalysis(raw)
        assertEquals("Team discussed the backend database migration.", transcription)
        assertEquals("1. Run migrations before deploy.", summary)
    }

    @Test
    fun testFallbackWhenNoHeaders() {
        val raw = """
            This is a call transcription with no headers.
            
            This is the concluding takeaway paragraph.
        """.trimIndent()

        val (transcription, summary) = GeminiResponseParser.parseAudioAnalysis(raw)
        assertTrue(transcription.isNotBlank())
        assertTrue(summary.isNotBlank())
    }
}
