package com.example.data

import java.util.Locale

data class CallerActionItem(
    val recordingId: Int,
    val callTitle: String,
    val callTimestamp: Long,
    val text: String,
    val isCompleted: Boolean
)

data class CallerProfile(
    val callerKey: String,
    val displayName: String,
    val phoneNumber: String?,
    val recordings: List<Recording>,
    val totalCalls: Int,
    val incomingCount: Int,
    val outgoingCount: Int,
    val actionItems: List<CallerActionItem>,
    val pendingActionItemsCount: Int,
    val datesAndDeadlines: List<String>,
    val latestCallTimestamp: Long,
    val isAutoAnalyzeTarget: Boolean
)

object CallerProfileBuilder {

    fun computeCallerKey(fileNameOrTitle: String): String {
        val meta = CallMetadataParser.parse(fileNameOrTitle)
        val extractedPhone = meta.contactOrNumber?.takeIf { it.any { c -> c.isDigit() } && !it.any { c -> c.isLetter() } }
        val phoneDigits = extractedPhone?.filter { it.isDigit() } ?: ""
        val cleanDigits = meta.cleanTitle.filter { it.isDigit() }

        // Key: If extracted phone has >= 6 digits, normalize (last 10 digits if >= 10); otherwise cleaned name
        return if (phoneDigits.length >= 6) {
            if (phoneDigits.length >= 10) phoneDigits.takeLast(10) else phoneDigits
        } else if (cleanDigits.length >= 6 && meta.cleanTitle.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }) {
            if (cleanDigits.length >= 10) cleanDigits.takeLast(10) else cleanDigits
        } else {
            meta.cleanTitle.trim().lowercase(Locale.ROOT)
        }
    }

    fun buildProfiles(
        recordings: List<Recording>,
        completedActionItemKeys: Set<String>,
        autoAnalyzeTargets: Set<String>
    ): List<CallerProfile> {
        if (recordings.isEmpty()) return emptyList()

        // Group recordings by extracted caller identity
        val grouped = mutableMapOf<String, MutableList<Recording>>()
        val displayNames = mutableMapOf<String, String>()
        val phoneNumbers = mutableMapOf<String, String?>()

        for (rec in recordings) {
            val meta = CallMetadataParser.parse(rec.title)
            val extractedPhone = meta.contactOrNumber?.takeIf { it.any { c -> c.isDigit() } && !it.any { c -> c.isLetter() } }
            val key = computeCallerKey(rec.title)

            if (key.isBlank()) continue

            grouped.getOrPut(key) { mutableListOf() }.add(rec)

            val cleanName = meta.cleanTitle.trim()
            val nameHasLetters = cleanName.any { it.isLetter() }
            val candidateName = if (nameHasLetters) cleanName else (extractedPhone ?: cleanName)

            // Prefer alphabetic contact names over raw numbers
            if (!displayNames.containsKey(key) || (nameHasLetters && displayNames[key]?.any { it.isLetter() } != true)) {
                displayNames[key] = candidateName
            }
            if (!phoneNumbers.containsKey(key) && extractedPhone != null) {
                phoneNumbers[key] = extractedPhone
            }
        }

        return grouped.map { (key, groupRecordings) ->
            // Sort calls newest first
            val sortedRecs = groupRecordings.sortedByDescending { it.timestamp }
            val latestTimestamp = sortedRecs.firstOrNull()?.timestamp ?: 0L
            val dispName = displayNames[key] ?: key
            val phone = phoneNumbers[key]

            var inCount = 0
            var outCount = 0
            val allActionItems = mutableListOf<CallerActionItem>()
            val allDates = mutableListOf<String>()

            for (rec in sortedRecs) {
                val meta = CallMetadataParser.parse(rec.title)
                when (meta.direction) {
                    CallDirection.INCOMING -> inCount++
                    CallDirection.OUTGOING -> outCount++
                    CallDirection.UNKNOWN -> {}
                }

                val items = CommitmentExtractor.extractActionItems(rec.decodedSummary)
                for (item in items) {
                    val itemKey = "${rec.id}_${item.hashCode()}"
                    val isDone = completedActionItemKeys.contains(itemKey)
                    allActionItems.add(
                        CallerActionItem(
                            recordingId = rec.id,
                            callTitle = CallMetadataParser.cleanCallTitle(rec.title),
                            callTimestamp = rec.timestamp,
                            text = item,
                            isCompleted = isDone
                        )
                    )
                }

                val dates = CommitmentExtractor.extractDates(rec.decodedSummary)
                allDates.addAll(dates)
            }

            val pendingCount = allActionItems.count { !it.isCompleted }

            val isAutoTarget = autoAnalyzeTargets.any { target ->
                val targetClean = target.trim()
                if (targetClean.isBlank()) return@any false
                val targetDigits = targetClean.filter { it.isDigit() }
                val targetNormalized = if (targetDigits.length >= 10) targetDigits.takeLast(10) else targetDigits
                if (targetNormalized.length >= 6 && (key == targetNormalized || key.contains(targetNormalized) || targetNormalized.contains(key))) return@any true
                dispName.equals(targetClean, ignoreCase = true) ||
                    dispName.contains(targetClean, ignoreCase = true)
            }

            CallerProfile(
                callerKey = key,
                displayName = dispName,
                phoneNumber = phone,
                recordings = sortedRecs,
                totalCalls = sortedRecs.size,
                incomingCount = inCount,
                outgoingCount = outCount,
                actionItems = allActionItems,
                pendingActionItemsCount = pendingCount,
                datesAndDeadlines = allDates.distinct(),
                latestCallTimestamp = latestTimestamp,
                isAutoAnalyzeTarget = isAutoTarget
            )
        }.sortedWith(
            // Sort callers with pending tasks first, then by latest call timestamp
            compareByDescending<CallerProfile> { it.pendingActionItemsCount > 0 }
                .thenByDescending { it.latestCallTimestamp }
        )
    }
}
