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
    private val phonePattern = Regex("""\b(?:\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}\b""")

    /**
     * Produces a structured local on-device summary and action item list without requiring an API key.
     */
    fun analyzeLocally(transcript: String, fileName: String): Pair<String, String> {
        val cleanTranscript = transcript.trim()
        if (cleanTranscript.isBlank() || cleanTranscript.equals("No speech detected.", ignoreCase = true)) {
            val smartTitle = CallMetadataParser.cleanCallTitle(fileName)
            val fallbackTranscript = "Audio recording for $smartTitle. (On-Device Speech Analysis)"
            val fallbackSummary = buildString {
                appendLine("• Recording: $smartTitle")
                appendLine("• Mode: On-Device Analysis (Zero API Key)")
                appendLine("• Ready to play, review, or chat with this call.")
            }.trim()
            return Pair(fallbackTranscript, fallbackSummary)
        }

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
            appendLine("ON-DEVICE SUMMARY")
            appendLine("• Total Speech Segments: ${sentences.size}")

            if (actionItems.isNotEmpty()) {
                appendLine("\nACTION ITEMS & COMMITMENTS:")
                actionItems.take(5).forEach { item ->
                    appendLine("• $item")
                }
            } else {
                appendLine("\nKEY POINTS:")
                sentences.take(3).forEach { appendLine("• $it") }
            }

            if (datesFound.isNotEmpty()) {
                appendLine("\nTIMELINES & DATES:")
                datesFound.take(3).forEach { appendLine("• $it") }
            }

            if (financialFound.isNotEmpty()) {
                appendLine("\nDETAILS / NUMBERS:")
                financialFound.take(3).forEach { appendLine("• $it") }
            }
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

        val sentences = transcript
            .split(Regex("""(?<=[.!?])\s+|\n+"""))
            .map { it.trim() }
            .filter { it.length > 5 }

        // Intent 1: Action items
        if (q.contains("action") || q.contains("task") || q.contains("todo") || q.contains("to do") || q.contains("next step")) {
            val actions = sentences.filter { s ->
                val l = s.lowercase(Locale.ROOT)
                actionKeywords.any { l.contains(it) }
            }
            return if (actions.isNotEmpty()) {
                "Here are the action items identified in this call:\n" +
                        actions.take(4).joinToString("\n") { "• $it" }
            } else {
                "No explicit action items or promises were detected in this conversation."
            }
        }

        // Intent 2: Date, Time, Deadlines, Meeting
        if (q.contains("when") || q.contains("date") || q.contains("time") || q.contains("deadline") || q.contains("schedule") || q.contains("meet")) {
            val dateMatches = sentences.filter { s ->
                val l = s.lowercase(Locale.ROOT)
                dateKeywords.any { l.contains(it) }
            }
            return if (dateMatches.isNotEmpty()) {
                "Mentioned dates and schedules:\n" +
                        dateMatches.take(4).joinToString("\n") { "• $it" }
            } else {
                "No specific dates or times were detected in the call transcript."
            }
        }

        // Intent 3: Money, Price, Cost, Numbers
        if (q.contains("price") || q.contains("cost") || q.contains("money") || q.contains("amount") || q.contains("rate") || q.contains("pay") || q.contains("phone") || q.contains("number")) {
            val numberMatches = sentences.filter { s ->
                monetaryPattern.containsMatchIn(s) || phonePattern.containsMatchIn(s) || s.any { it.isDigit() }
            }
            return if (numberMatches.isNotEmpty()) {
                "Numbers and amounts discussed:\n" +
                        numberMatches.take(4).joinToString("\n") { "• $it" }
            } else {
                "No financial figures or phone numbers were detected in this call."
            }
        }

        // Intent 4: Summary / Overview
        if (q.contains("summar") || q.contains("about") || q.contains("brief") || q.contains("overview") || q.contains("tldr")) {
            return if (summary.isNotBlank()) {
                summary
            } else {
                "Key excerpt:\n" + sentences.take(3).joinToString("\n") { "• $it" }
            }
        }

        // General search: Token match relevance
        val stopWords = setOf("what", "where", "who", "whom", "how", "why", "the", "a", "an", "is", "was", "are", "were", "did", "do", "does", "in", "on", "at", "about", "for", "with", "this", "that")
        val tokens = q.split(Regex("""\W+""")).filter { it.length > 2 && !stopWords.contains(it) }

        if (tokens.isEmpty()) {
            return if (sentences.isNotEmpty()) {
                "Here is what was said in the call:\n\"${sentences.first()}\""
            } else {
                "Transcript is empty."
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
            "I couldn't find a direct reference to \"$question\" in this call transcript."
        }
    }
}
