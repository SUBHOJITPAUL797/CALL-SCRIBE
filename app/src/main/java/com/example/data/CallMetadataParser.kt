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

    private val metadataCache = java.util.concurrent.ConcurrentHashMap<String, CallMetadata>()

    // Matches phone numbers: optional country code, optional groupings (7 to 20 chars)
    private val phoneRegex = Regex("""\+?[0-9][0-9\s\-()]{5,18}[0-9]""")

    // Prefixes to strip (case-insensitive) - requires delimiter or end of string so names like Callum or Recep aren't chopped
    private val prefixRegex = Regex("""(?i)^(call[_\s\-]*recording|call|recording|rec|audio|voice)(?:[\s_\-]+|$)""")

    // Direction markers to strip - requires delimiter or word boundaries so names like Kevin, Martin, Robin aren't chopped
    private val directionRegex = Regex("""(?i)(?<=^|[\s_\-])(incoming|outgoing|in|out)(?=[\s_\-]|$)""")

    // Direction matcher regexes with delimiters
    private val incomingMatchRegex = Regex("""(?i)(?<=^|[\s_\-])(incoming|in)(?=[\s_\-]|$)""")
    private val outgoingMatchRegex = Regex("""(?i)(?<=^|[\s_\-])(outgoing|out)(?=[\s_\-]|$)""")

    // ISO date (e.g. 2024-03-07 or 2024_03_07)
    private val isoDateRegex = Regex("""(?<=[^0-9]|^)\d{4}[_\-.]\d{2}[_\-.]\d{2}(?=[^0-9]|$)""")

    // Date-time stamps like 260307_125256 or 20240307_125256, or strict YYYYMMDD calendar dates (1900-2099)
    private val dateTimeRegex = Regex("""(?<=[^0-9]|^)(?:\d{6,8}[_\-]\d{4,6}|(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01]))(?=[^0-9]|$)""")

    // Remaining separators
    private val separatorRegex = Regex("""[\s_\-]+""")

    fun parse(fileName: String): CallMetadata {
        metadataCache[fileName]?.let { return it }
        val result = parseInternal(fileName)
        metadataCache[fileName] = result
        return result
    }

    private fun parseInternal(fileName: String): CallMetadata {
        val baseName = fileName.substringBeforeLast(".")

        val direction = when {
            incomingMatchRegex.containsMatchIn(baseName) -> CallDirection.INCOMING
            outgoingMatchRegex.containsMatchIn(baseName) -> CallDirection.OUTGOING
            else -> CallDirection.UNKNOWN
        }

        // Strip date-times first so date digits (e.g. 20240307 or 125256) are not misidentified as phone numbers
        val nameWithoutDate = try {
            baseName.replace(dateTimeRegex, "").replace(isoDateRegex, "")
        } catch (_: Exception) {
            baseName
        }

        // Try extracting phone number from nameWithoutDate
        val phoneNumber = try {
            phoneRegex.find(nameWithoutDate)?.value?.trim()
        } catch (_: Exception) {
            null
        }

        // Clean up filename
        var cleaned = try {
            baseName
                .replace(prefixRegex, "")
                .replace(directionRegex, " ")
                .replace(dateTimeRegex, "")
                .replace(isoDateRegex, "")
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
            .replace(isoDateRegex, "")
            .replace(separatorRegex, " ")
            .trim()

        val digitCount = withoutTime.count { it.isDigit() }
        val letterCount = withoutTime.count { it.isLetter() }

        // If it starts with + or has 6+ digits and no letters, it's an unknown number / raw phone number
        if ((withoutTime.startsWith("+") && digitCount >= 5) || (digitCount >= 6 && letterCount == 0)) {
            return true
        }

        // If whole title is digits/plus/separators only, require at least 6 digits so track numbers like "01" or "1" aren't treated as phone numbers
        if (digitCount >= 6 && withoutTime.isNotBlank() && withoutTime.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }) {
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
                val fileDigits = baseName.replace(dateTimeRegex, "").replace(isoDateRegex, "").filter { it.isDigit() }

                targets.any { target ->
                    val targetClean = target.trim()
                    val targetNormalized = targetClean.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it.isWhitespace() }
                    val targetDigits = targetClean.filter { it.isDigit() }

                    // Word-boundary match for contact name to avoid false positives (e.g. "Dan" in "Daniel", while matching "Dr Smith" in "Dr Smith Appointment")
                    val nameMatches = targetNormalized.isNotBlank() && (
                        cleanNormalized == targetNormalized ||
                        Regex("""\b${Regex.escape(targetNormalized)}\b""").containsMatchIn(cleanNormalized)
                    )

                    val phoneMatches = (targetDigits.length >= 6 && fileDigits.contains(targetDigits)) ||
                        (fileDigits.length >= 6 && targetDigits.length >= 6 && targetDigits.contains(fileDigits))

                    nameMatches || phoneMatches
                }
            }
        }
    }
}

object CommitmentExtractor {
    private val actionItemsCache = java.util.concurrent.ConcurrentHashMap<Int, List<String>>()
    private val datesCache = java.util.concurrent.ConcurrentHashMap<Int, List<String>>()

    private val actionItemHeaderRegex = Regex("""(?im)^[#*_ ]*(?:✅\s*)?(?:action items?|commitments?|to[- ]?dos?|tasks?)[^\n]*$""")
    private val datesHeaderRegex = Regex("""(?im)^[#*_ ]*(?:📅\s*)?(?:dates?|times?|deadlines?|schedules?)[^\n]*$""")
    // Only markdown headers (#) should delineate sections, not bold asterisks (**) which can format bullet points
    private val nextHeaderRegex = Regex("""(?im)^#{1,6}\s+[^\n]+""")
    private val numberedBulletRegex = Regex("""^\d+\.\s*""")

    fun extractActionItems(summary: String): List<String> {
        val trimmed = summary.trim()
        if (trimmed.isBlank() || trimmed.contains("Not Available") || trimmed.contains("Pending AI Analysis") || trimmed.contains("Audio Transcription Required") || trimmed.contains("Transcription requires")) {
            return emptyList()
        }
        val key = trimmed.hashCode()
        actionItemsCache[key]?.let { return it }
        val result = extractSectionItems(trimmed, actionItemHeaderRegex)
        actionItemsCache[key] = result
        return result
    }

    fun extractDates(summary: String): List<String> {
        val trimmed = summary.trim()
        if (trimmed.isBlank() || trimmed.contains("Not Available") || trimmed.contains("Pending AI Analysis") || trimmed.contains("Audio Transcription Required") || trimmed.contains("Transcription requires")) {
            return emptyList()
        }
        val key = trimmed.hashCode()
        datesCache[key]?.let { return it }
        val result = extractSectionItems(trimmed, datesHeaderRegex)
        datesCache[key] = result
        return result
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
                    .replace(numberedBulletRegex, "")
                    .trim()
            }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.length > 3 && !it.equals("None", ignoreCase = true) && !it.contains("No explicit action", ignoreCase = true) }
    }
}
