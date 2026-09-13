package com.example

import com.example.data.CallMetadataParser
import com.example.data.CallerProfileBuilder
import com.example.data.TranscriptionValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NewFeaturesVerificationTest {

    @Test
    fun testSamsungTimestampParsing() {
        val timestamp = CallMetadataParser.extractCallTimestamp("Call with Alice_260307_125256.m4a", fallbackLastModified = 0L)
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }

        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, cal.get(Calendar.MONTH))
        assertEquals(7, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(52, cal.get(Calendar.MINUTE))
        assertEquals(56, cal.get(Calendar.SECOND))
    }

    @Test
    fun testStandardYyyymmddHhmmssTimestampParsing() {
        val timestamp = CallMetadataParser.extractCallTimestamp("20260910_204852_+919876543210.mp3", fallbackLastModified = 0L)
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }

        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, cal.get(Calendar.MONTH))
        assertEquals(10, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(20, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(48, cal.get(Calendar.MINUTE))
        assertEquals(52, cal.get(Calendar.SECOND))
    }

    @Test
    fun testIsoDateTimeParsing() {
        val timestamp = CallMetadataParser.extractCallTimestamp("Call_2024-05-15_14-30-00.opus", fallbackLastModified = 0L)
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }

        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(Calendar.MAY, cal.get(Calendar.MONTH))
        assertEquals(15, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun testTimestampOrderingNewestToOldest() {
        val file1 = "Call_260307_125256.m4a" // March 7, 2026
        val file2 = "Call_260309_150000.m4a" // March 9, 2026
        val file3 = "Call_240101_100000.m4a" // Jan 1, 2024

        val t1 = CallMetadataParser.extractCallTimestamp(file1)
        val t2 = CallMetadataParser.extractCallTimestamp(file2)
        val t3 = CallMetadataParser.extractCallTimestamp(file3)

        assertTrue("March 9, 2026 must be after March 7, 2026", t2 > t1)
        assertTrue("March 7, 2026 must be after Jan 1, 2024", t1 > t3)

        val files = listOf(file1, file3, file2)
        val sorted = files.sortedByDescending { CallMetadataParser.extractCallTimestamp(it) }

        assertEquals(listOf(file2, file1, file3), sorted)
    }

    @Test
    fun testTimestampFallbackWhenNoDateInName() {
        val knownLastModified = 1700000000000L
        val timestamp = CallMetadataParser.extractCallTimestamp("Voice_recording_without_date.mp3", fallbackLastModified = knownLastModified)
        assertEquals(knownLastModified, timestamp)
    }

    @Test
    fun testSpeechValidatorNormalSpeechPreserved() {
        val normalText1 = "Yeah yeah yeah, I will send you the document by tomorrow afternoon."
        val normalSummary = "Speaker confirmed they will send the document tomorrow."
        assertFalse(TranscriptionValidator.isUnusableTranscription(normalText1, normalSummary))
        assertEquals(normalText1, TranscriptionValidator.sanitizeTranscription(normalText1, normalSummary))

        val normalText2 = "Ok ok ok, I understand everything now."
        assertFalse(TranscriptionValidator.isUnusableTranscription(normalText2, normalSummary))

        val normalText3 = "हाँ हाँ हाँ बिल्कुल ठीक है मैं कल आऊंगा"
        assertFalse(TranscriptionValidator.isUnusableTranscription(normalText3, normalSummary))
    }

    @Test
    fun testSpeechValidatorRunawayWhisperLoopDetected() {
        val runawayLoop = "बाप पो बने की बापापा पो बापापा पो बापापा पो बापापा पो बापापा पो बापापा पो बापापा पो बापापा पो"
        val noiseSummary = "No coherent conversation was detected in the provided phone call transcript. The transcript appears to contain background noise."

        assertTrue(TranscriptionValidator.isUnusableTranscription(runawayLoop, noiseSummary))
        assertEquals(
            TranscriptionValidator.NO_SPEECH_DETECTED,
            TranscriptionValidator.sanitizeTranscription(runawayLoop, noiseSummary)
        )
    }

    @Test
    fun testSummaryNoiseDetection() {
        val text = "some brief audio snippet"
        val noiseSummary = "No clear speech or conversation was detected in this recording."

        assertTrue(TranscriptionValidator.isUnusableTranscription(text, noiseSummary))
        assertEquals(
            TranscriptionValidator.NO_SPEECH_DETECTED,
            TranscriptionValidator.sanitizeTranscription(text, noiseSummary)
        )
    }

    @Test
    fun testFormatDuration() {
        assertEquals("00:00", CallMetadataParser.formatDuration(0))
        assertEquals("00:00", CallMetadataParser.formatDuration(-500))
        assertEquals("00:45", CallMetadataParser.formatDuration(45_000))
        assertEquals("01:05", CallMetadataParser.formatDuration(65_000))
        assertEquals("12:34", CallMetadataParser.formatDuration(754_000))
        assertEquals("01:05:20", CallMetadataParser.formatDuration(3_920_000))
    }

    @Test
    fun testCallerKeyConsistency() {
        val key1 = CallerProfileBuilder.computeCallerKey("Call with +1 (415) 555-2671_20260307.mp3")
        val key2 = CallerProfileBuilder.computeCallerKey("4155552671_outgoing.m4a")

        assertEquals("4155552671", key1)
        assertEquals("Phone number keys must normalize to last 10 digits", key1, key2)
    }

    @Test
    fun testNvidiaTranscriptionHelpfulMessage() = kotlinx.coroutines.runBlocking {
        val repo = com.example.network.NvidiaRepository(apiKeyProvider = { "nvapi-test-key-12345678" })
        val result = repo.transcribeAudio(ByteArray(100), "recording.mp3", "audio/mp3")
        assertFalse("Nvidia transcribeAudio should fail gracefully", result.isSuccess)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Message should clearly direct user to Cloudflare or Gemini", msg.contains("Cloudflare") && msg.contains("Gemini"))
        assertFalse("Message must never return raw HTTP 404", msg.contains("HTTP 404"))
    }
}
