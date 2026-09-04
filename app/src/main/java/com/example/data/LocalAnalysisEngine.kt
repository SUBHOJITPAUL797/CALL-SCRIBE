package com.example.data

import java.util.Locale

object LocalAnalysisEngine {

    private val actionKeywords = listOf(
        "will", "shall", "need to", "needs to", "have to", "has to", "must",
        "should", "let's", "let us", "please", "agreed", "agree to", "agreed to",
        "follow up", "schedule", "send", "email", "call back", "remind",
        "deadline", "prepare", "finish", "complete", "check with", "confirm"
    )

    private val dateKeywords = listOf(
        "today", "tomorrow", "yesterday", "tonight", "monday", "tuesday", "wednesday",
        "thursday", "friday", "saturday", "sunday", "morning", "afternoon", "evening",
        "next week", "this week", "next month", "o'clock", "am", "pm", "january",
        "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december"
    )

    private val monetaryPattern = Regex("""(?i)(?:[$€£₹]|rs\.?|usd|inr|dollars?|bucks?)\s*\d+(?:,\d+)*(?:\.\d+)?|\d+(?:,\d+)*(?:\.\d+)?\s*(?:[$€£₹]|rs\.?|usd|inr|dollars?|bucks?)""")
    private val phonePattern = Regex("""\b(?:\+?\d{1,3}[.\s]?)?\(?\d{3}\)?[.\s]?\d{3}[.\s]?\d{4}\b""")

    /**
     * Returns a structured summary + transcript.
     * When transcript is empty (no Gemini key), returns an honest "needs API key" message
     * instead of fake placeholder content.
     */
    fun analyzeLocally(transcript: String, fileName: String): Pair<String, String> {
        val cleanTranscript = transcript.trim()

        // No transcript available (no Gemini API key was configured)
        if (cleanTranscript.isBlank() ||
            cleanTranscript.equals("No speech detected.", ignoreCase = true) ||
            cleanTranscript.contains("On-Device Speech Analysis")) {

            val smartTitle = try { CallMetadataParser.cleanCallTitle(fileName) } catch (_: Exception) { fileName }

            val noKeyTranscript = buildString {
                appendLine("⚠️  Transcription requires a Gemini API Key.")
                appendLine("")
                appendLine("This call recording has NOT been transcribed yet.")
                appendLine("To unlock full AI analysis:")
                appendLine("  1. Tap the 🔑 key icon in the top bar")
                appendLine("  2. Enter your free Google Gemini API key")
                appendLine("     (get one free at aistudio.google.com)")
                appendLine("  3. Re-sync this folder to analyze all calls")
            }.trim()

            val noKeySummary = buildString {
                appendLine("⚠️  AI Analysis Not Available")
                appendLine("")
                appendLine("Call: $smartTitle")
                appendLine("")
                appendLine("To get a full summary of this call (who said what,")
                appendLine("action items, decisions, key details), you need a")
                appendLine("free Gemini API key.")
                appendLine("")
                appendLine("➡  Tap 🔑 in the top bar → Add your free API key")
                appendLine("   → Re-sync folder → Full AI analysis unlocked!")
            }.trim()

            return Pair(noKeyTranscript, noKeySummary)
        }

        // Real transcript available — do extractive analysis
        val sentences = cleanTranscript
            .split(Regex("""(?<=[.!?])\s+|\n+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 5 }

        val actionItems = mutableListOf<String>()
        val datesFound = mutableListOf<String>()
        val financialFound = mutableListOf<String>()

        for (sentence in sentences) {
            val lower = sentence.lowercase(Locale.ROOT)
            if (actionKeywords.any { lower.contains(it) }) {
                if (!actionItems.contains(sentence)) actionItems.add(sentence)
            }
            if (dateKeywords.any { lower.contains(it) }) {
                if (!datesFound.contains(sentence)) datesFound.add(sentence)
            }
            if (monetaryPattern.containsMatchIn(sentence) || phonePattern.containsMatchIn(sentence)) {
                if (!financialFound.contains(sentence)) financialFound.add(sentence)
            }
        }

        val summary = buildString {
            appendLine("📋 CALL ANALYSIS (On-Device)")
            appendLine("• Speech segments analyzed: ${sentences.size}")

            if (actionItems.isNotEmpty()) {
                appendLine("\n✅ ACTION ITEMS & COMMITMENTS:")
                actionItems.take(6).forEach { appendLine("• $it") }
            } else {
                appendLine("\n📝 KEY POINTS:")
                sentences.take(4).forEach { appendLine("• $it") }
            }

            if (datesFound.isNotEmpty()) {
                appendLine("\n📅 DATES & TIMELINES:")
                datesFound.take(3).forEach { appendLine("• $it") }
            }

            if (financialFound.isNotEmpty()) {
                appendLine("\n🔢 KEY DETAILS / NUMBERS:")
                financialFound.take(3).forEach { appendLine("• $it") }
            }

            appendLine("\n💡 TIP: Add a Gemini API key for richer structured summaries.")
        }.trim()

        return Pair(cleanTranscript, summary)
    }

    /**
     * Answers a user query about a call on-device using extractive keyword and intent matching.
     */
    fun answerCallQuestionLocally(
        transcript: String,
        summary: String,
        question: String
    ): String {
        val q = question.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return "Please ask a question about the call."

        // No real transcript — guide user to add API key
        val isEmptyTranscript = transcript.isBlank() ||
            transcript.contains("Transcription requires") ||
            transcript.contains("API Key") ||
            transcript.contains("On-Device Speech Analysis")

        if (isEmptyTranscript) {
            return buildString {
                appendLine("⚠️  I don't have a transcript to search through for this call.")
                appendLine("")
                appendLine("This call hasn't been transcribed yet because no Gemini API key is set.")
                appendLine("")
                appendLine("To chat about this call:")
                appendLine("  1. Tap 🔑 in the top bar")
                appendLine("  2. Add your free Gemini API key (aistudio.google.com)")
                appendLine("  3. Re-sync this folder")
                appendLine("")
                appendLine("After that, I'll have the full transcript and can answer anything about this call.")
            }.trim()
        }

        val sentences = transcript
            .split(Regex("""(?<=[.!?])\s+|\n+"""))
            .map { it.trim() }
            .filter { it.length > 5 }

        // Intent: Action items
        if (q.contains("action") || q.contains("task") || q.contains("todo") || q.contains("to do") || q.contains("next step")) {
            val actions = sentences.filter { s ->
                val l = s.lowercase(Locale.ROOT)
                actionKeywords.any { l.contains(it) }
            }
            return if (actions.isNotEmpty()) {
                "✅ Action items from this call:\n" + actions.take(5).joinToString("\n") { "• $it" }
            } else {
                "No explicit action items were detected in this conversation."
            }
        }

        // Intent: Date, Time, Deadlines
        if (q.contains("when") || q.contains("date") || q.contains("time") || q.contains("deadline") || q.contains("schedule") || q.contains("meet")) {
            val dateMatches = sentences.filter { s ->
                val l = s.lowercase(Locale.ROOT)
                dateKeywords.any { l.contains(it) }
            }
            return if (dateMatches.isNotEmpty()) {
                "📅 Dates and schedules mentioned:\n" + dateMatches.take(4).joinToString("\n") { "• $it" }
            } else {
                "No specific dates or times were detected in the call transcript."
            }
        }

        // Intent: Money, Numbers
        if (q.contains("price") || q.contains("cost") || q.contains("money") || q.contains("amount") || q.contains("rate") || q.contains("pay") || q.contains("number")) {
            val numberMatches = sentences.filter { s ->
                monetaryPattern.containsMatchIn(s) || phonePattern.containsMatchIn(s) || s.any { it.isDigit() }
            }
            return if (numberMatches.isNotEmpty()) {
                "🔢 Numbers and amounts mentioned:\n" + numberMatches.take(4).joinToString("\n") { "• $it" }
            } else {
                "No financial figures or phone numbers were detected in this call."
            }
        }

        // Intent: Summary / Overview
        if (q.contains("summar") || q.contains("about") || q.contains("brief") || q.contains("overview") || q.contains("tldr") || q.contains("what happened")) {
            return if (summary.isNotBlank() && !summary.contains("⚠️")) {
                summary
            } else {
                "📋 Key excerpt:\n" + sentences.take(4).joinToString("\n") { "• $it" }
            }
        }

        // General keyword search
        val stopWords = setOf("what", "where", "who", "whom", "how", "why", "the", "a", "an", "is", "was", "are", "were", "did", "do", "does", "in", "on", "at", "about", "for", "with", "this", "that")
        val tokens = q.split(Regex("""\W+""")).filter { it.length > 2 && !stopWords.contains(it) }

        if (tokens.isEmpty()) {
            return if (sentences.isNotEmpty()) {
                "Here is what was said:\n\"${sentences.take(2).joinToString(" ")}\""
            } else {
                "Transcript is empty. Please re-sync after adding a Gemini API key."
            }
        }

        val ranked = sentences.map { sentence ->
            val lower = sentence.lowercase(Locale.ROOT)
            val score = tokens.count { lower.contains(it) }
            Pair(sentence, score)
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }

        return if (ranked.isNotEmpty()) {
            "Based on the call:\n" + ranked.take(3).joinToString("\n\n") { "• \"${it.first}\"" }
        } else {
            "I couldn't find a direct reference to \"$question\" in this call transcript.\n\nTry asking about: action items, dates, amounts, agreements, or what was discussed."
        }
    }
}
