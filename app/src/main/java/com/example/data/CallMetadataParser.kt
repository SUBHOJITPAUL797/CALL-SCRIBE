package com.example.data

import java.util.Locale

data class CallMetadata(
    val cleanTitle: String,
    val contactOrNumber: String?,
    val direction: CallDirection,
    val originalFileName: String
)

enum class CallDirection {
    INCOMING,
    OUTGOING,
    UNKNOWN
}

object CallMetadataParser {

    // Matches phone numbers: optional country code, optional groupings
    private val phoneRegex = Regex("""(?:\+?\d{1,3}[\-.\s]?)?\(?\d{2,4}\)?[\-.\s]?\d{3,4}[\-.\s]?\d{3,4}""")

    // Prefixes to strip (case-insensitive)
    private val prefixRegex = Regex("""(?i)^(call[_\s\-]*recording|call|recording|rec|audio|voice)[\s_\-]*""")

    // Direction markers to strip
    private val directionRegex = Regex("""(?i)[\s_\-]*(incoming|outgoing|in|out)[\s_\-]*""")

    // Date-time stamps like 260307_125256 or 20240307_125256 or 20240307
    private val dateTimeRegex = Regex("""\d{6,8}[_\-]\d{4,6}|\b(19|20)\d{6}\b""")

    // Remaining separators
    private val separatorRegex = Regex("""[\s_\-]+""")

    fun parse(fileName: String): CallMetadata {
        val baseName = fileName.substringBeforeLast(".")
        val lower = baseName.lowercase(Locale.ROOT)

        val direction = when {
            lower.contains("incoming") || lower.contains("in_") || lower.contains("_in") -> CallDirection.INCOMING
            lower.contains("outgoing") || lower.contains("out_") || lower.contains("_out") -> CallDirection.OUTGOING
            else -> CallDirection.UNKNOWN
        }

        // Try extracting phone number
        val phoneNumber = try {
            phoneRegex.find(baseName)?.value?.trim()
        } catch (_: Exception) {
            null
        }

        // Clean up filename
        var cleaned = try {
            baseName
                .replace(prefixRegex, "")
                .replace(directionRegex, " ")
                .replace(dateTimeRegex, "")
                .replace(separatorRegex, " ")
                .trim()
        } catch (_: Exception) {
            baseName
        }

        if (cleaned.isBlank()) {
            cleaned = baseName
        }

        val contactOrNumber = when {
            !phoneNumber.isNullOrBlank() -> phoneNumber
            cleaned != baseName && cleaned.isNotBlank() -> cleaned
            else -> null
        }

        return CallMetadata(
            cleanTitle = cleaned,
            contactOrNumber = contactOrNumber,
            direction = direction,
            originalFileName = fileName
        )
    }

    fun cleanCallTitle(fileName: String): String {
        return try {
            parse(fileName).cleanTitle
        } catch (_: Exception) {
            fileName.substringBeforeLast(".")
        }
    }

    fun formatDuration(durationMs: Int): String {
        if (durationMs <= 0) return "00:00"
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    fun isUnknownNumber(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.ROOT)
        if (lower.contains("unknown") || lower.contains("private")) {
            return true
        }

        val baseName = fileName.substringBeforeLast(".")
        val withoutTime = baseName
            .replace(prefixRegex, "")
            .replace(directionRegex, " ")
            .replace(dateTimeRegex, "")
            .replace(separatorRegex, " ")
            .trim()

        val digitCount = withoutTime.count { it.isDigit() }
        val letterCount = withoutTime.count { it.isLetter() }

        // If it starts with + or has 6+ digits and no letters, it's an unknown number / raw phone number
        if ((withoutTime.startsWith("+") && digitCount >= 5) || (digitCount >= 6 && letterCount == 0)) {
            return true
        }

        // If whole title is digits/plus/separators only
        if (withoutTime.isNotBlank() && withoutTime.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }) {
            return true
        }

        return false
    }

    fun matchesAutoAnalyzeRule(
        fileName: String,
        mode: AutoAnalyzeMode,
        targets: Set<String>
    ): Boolean {
        return when (mode) {
            AutoAnalyzeMode.ALL -> true
            AutoAnalyzeMode.MANUAL_ONLY -> false
            AutoAnalyzeMode.UNKNOWN_ONLY -> isUnknownNumber(fileName)
            AutoAnalyzeMode.SPECIFIC_CONTACTS -> {
                if (targets.isEmpty()) return false
                val baseName = fileName.substringBeforeLast(".")
                val meta = parse(fileName)
                val cleanNormalized = meta.cleanTitle.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it.isWhitespace() }
                val fileDigits = baseName.replace(dateTimeRegex, "").filter { it.isDigit() }

                targets.any { target ->
                    val targetClean = target.trim()
                    val targetNormalized = targetClean.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it.isWhitespace() }
                    val targetDigits = targetClean.filter { it.isDigit() }

                    (targetNormalized.isNotBlank() && cleanNormalized.contains(targetNormalized)) ||
                    (targetDigits.length >= 6 && fileDigits.contains(targetDigits)) ||
                    (fileDigits.length >= 6 && targetDigits.length >= 6 && targetDigits.contains(fileDigits))
                }
            }
        }
    }
}

object CommitmentExtractor {
    private val actionItemHeaderRegex = Regex("""(?im)^[#*_ ]*(?:✅\s*)?(?:action items?|commitments?|to[- ]?dos?|tasks?)[^\n]*$""")
    private val datesHeaderRegex = Regex("""(?im)^[#*_ ]*(?:📅\s*)?(?:dates?|times?|deadlines?|schedules?)[^\n]*$""")
    private val nextHeaderRegex = Regex("""(?im)^[#*]{2,}\s+[^\n]+""")

    fun extractActionItems(summary: String): List<String> {
        return extractSectionItems(summary, actionItemHeaderRegex)
    }

    fun extractDates(summary: String): List<String> {
        return extractSectionItems(summary, datesHeaderRegex)
    }

    private fun extractSectionItems(text: String, headerRegex: Regex): List<String> {
        val headerMatch = headerRegex.find(text) ?: return emptyList()
        val startIndex = headerMatch.range.last + 1
        val remaining = text.substring(startIndex)

        val nextHeader = nextHeaderRegex.find(remaining)
        val sectionContent = if (nextHeader != null) {
            remaining.substring(0, nextHeader.range.first)
        } else {
            remaining
        }

        return sectionContent.lines()
            .map { line ->
                line.trim()
                    .removePrefix("•")
                    .removePrefix("-")
                    .removePrefix("*")
                    .trim()
                    .replace(Regex("""^\d+\.\s*"""), "")
                    .trim()
            }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.length > 3 && !it.equals("None", ignoreCase = true) && !it.contains("No explicit action", ignoreCase = true) }
    }
}
