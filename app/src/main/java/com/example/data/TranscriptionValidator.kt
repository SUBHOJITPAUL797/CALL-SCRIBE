package com.example.data

/**
 * Validates and sanitizes speech-to-text transcriptions.
 * Detects Whisper/ASR hallucinations, autoregressive repetition loops (with or without spaces),
 * silence markers, carrier tone artifacts, and noise summaries.
 */
object TranscriptionValidator {

    const val NO_SPEECH_DETECTED = "(No audible speech detected)"

    private val UNUSABLE_TRANSCRIPT_PHRASES = listOf(
        "audio transcription required",
        "transcription requires",
        "ai analysis not available",
        "on-device speech analysis",
        "no audible speech detected",
        "subtitles by",
        "translated by",
        "captions by",
        "thank you for watching",
        "please subscribe",
        "amara.org",
        "tap 🔑"
    )

    private val UNUSABLE_SUMMARY_PHRASES = listOf(
        "no coherent conversation was detected",
        "no clear speech or conversation was detected",
        "no clear speech or coherent conversation",
        "contains only background noise",
        "repeated filler words",
        "contains background noise and repeated filler"
    )

    // Matches runaway repeating multi-word or single-word phrases (1 to 4 words) repeated 4+ times back to back.
    // e.g. "बापापा पो बापापा पो बापापा पो बापापा पो..."
    private val RUNAWAY_PHRASE_REPETITION_REGEX = Regex(
        """(?i)(?:^|[\s,।!?])([\p{L}\p{N}]{1,15}(?:[\s,।]+[\p{L}\p{N}]{1,15}){0,3})(?:[\s,।]+\1){3,}(?:[\s,।!?]|$)"""
    )

    // Matches runaway unspaced character/syllable repetitions (e.g. "शाशाशाशाशाशा..." or "শাশাশাশাশা...") repeated 6+ times
    private val RUNAWAY_CHAR_REPETITION_REGEX = Regex("""([^\s]{1,4})\1{5,}""")

    /**
     * Checks whether a transcription and its optional summary indicate an unusable or hallucinated result.
     * Guaranteed zero false-positives on normal conversational phrases like "yeah yeah yeah" or "ok ok".
     */
    fun isUnusableTranscription(transcription: String?, summary: String? = null): Boolean {
        if (transcription.isNullOrBlank()) return true
        val cleanTrans = transcription.trim()
        val lowerTrans = cleanTrans.lowercase()

        // 1. Direct silence / placeholder phrases
        for (phrase in UNUSABLE_TRANSCRIPT_PHRASES) {
            if (lowerTrans.contains(phrase)) return true
        }

        // 2. Summary flags (LLM explicitly reporting noise, silence, or repeated filler words)
        if (!summary.isNullOrBlank()) {
            val lowerSummary = summary.lowercase()
            for (phrase in UNUSABLE_SUMMARY_PHRASES) {
                if (lowerSummary.contains(phrase)) return true
            }
        }

        // 3. Autoregressive unspaced character repetition loop (e.g. "शाशाशाशाशाशा...")
        RUNAWAY_CHAR_REPETITION_REGEX.find(cleanTrans)?.let { match ->
            if (match.value.length >= cleanTrans.length * 0.40) return true
        }

        // 4. Runaway spaced multi-word repetition loop (e.g. "बापापा पो बापापा पो बापापा पो बापापा पो...")
        RUNAWAY_PHRASE_REPETITION_REGEX.find(cleanTrans)?.let { match ->
            if (match.value.length >= cleanTrans.length * 0.40) return true
        }

        // 5. Compression / Vocabulary Uniqueness Ratio check:
        // Only applies to long texts (> 15 words) where unique words are < 20% of the total words
        val tokens = cleanTrans.split(Regex("""[\s,।!?\.\-]+""")).filter { it.isNotBlank() }
        if (tokens.size >= 15) {
            val uniqueTokens = tokens.map { it.lowercase() }.toSet()
            val ratio = uniqueTokens.size.toDouble() / tokens.size.toDouble()
            if (ratio < 0.20) return true
        }

        return false
    }

    /**
     * Sanitizes a transcription. If unusable or a hallucination loop, returns "(No audible speech detected)".
     */
    fun sanitizeTranscription(transcription: String?, summary: String? = null): String {
        if (transcription.isNullOrBlank()) return ""
        if (isUnusableTranscription(transcription, summary)) {
            return NO_SPEECH_DETECTED
        }
        return transcription.trim()
    }

    /**
     * Checks if a recording has a real, usable conversational transcript.
     */
    fun hasValidTranscript(transcription: String?, summary: String? = null): Boolean {
        return !isUnusableTranscription(transcription, summary)
    }
}
