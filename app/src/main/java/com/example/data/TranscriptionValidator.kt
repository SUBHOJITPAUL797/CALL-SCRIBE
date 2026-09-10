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

    // Matches repeating multi-word or single-word phrases (1 to 4 words) repeated 3+ times back to back.
    // Works across Latin, Devanagari (Hindi), Bengali, and all Unicode letter/number scripts.
    private val SPACED_PHRASE_REPETITION_REGEX = Regex(
        """(?i)(?:^|[\s,।!?])([\p{L}\p{N}]{1,15}(?:[\s,।]+[\p{L}\p{N}]{1,15}){0,3})(?:[\s,।]+\1){2,}(?:[\s,।!?]|$)"""
    )

    // Matches contiguous character/syllable repetitions without spaces: "शाशाशाशा..." or "শাশাশাশা..."
    private val CHAR_REPETITION_REGEX = Regex("""([^\s]{1,4})\1{3,}""")

    /**
     * Checks whether a transcription and its optional summary indicate an unusable or hallucinated result.
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

        // 3. Autoregressive repetition loop check without spaces e.g. "शाशाशाशा..." or "শাশাশাশা..."
        if (CHAR_REPETITION_REGEX.containsMatchIn(cleanTrans)) return true

        // 4. Spaced multi-word repetition loop e.g. "बापापा पो बापापा पो बापापा पो"
        if (SPACED_PHRASE_REPETITION_REGEX.containsMatchIn(cleanTrans)) return true

        // 5. Compression / Vocabulary Uniqueness Ratio check:
        // When Whisper gets trapped in an autoregressive loop on noise/silence,
        // it repeats a tiny set of words over and over
        val tokens = cleanTrans.split(Regex("""[\s,।!?\.\-]+""")).filter { it.isNotBlank() }
        if (tokens.size >= 8) {
            val uniqueTokens = tokens.map { it.lowercase() }.toSet()
            val ratio = uniqueTokens.size.toDouble() / tokens.size.toDouble()
            // If fewer than 32% of words are unique in an 8+ word transcript, it is a repetition loop
            if (ratio < 0.32) return true
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
