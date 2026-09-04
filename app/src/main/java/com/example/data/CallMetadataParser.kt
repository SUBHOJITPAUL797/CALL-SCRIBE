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
    private val prefixRegex = Regex("""(?i)^(call\s*recording|call|recording|rec|audio|voice)[\s_\-]*""")

    // Direction markers to strip
    private val directionRegex = Regex("""(?i)[\s_\-]*(incoming|outgoing|in|out)[\s_\-]*""")

    // Date-time stamps like 260307_125256 or 20240307_125256
    private val dateTimeRegex = Regex("""\d{6,8}[_\-]\d{4,6}""")

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
}
