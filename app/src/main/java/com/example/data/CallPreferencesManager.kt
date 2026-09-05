package com.example.data

import android.content.Context
import android.content.SharedPreferences

enum class AutoAnalyzeMode(val displayName: String, val description: String) {
    UNKNOWN_ONLY("Unknown Numbers Only", "Automatically analyze unsaved / unknown numbers"),
    SPECIFIC_CONTACTS("Specific Contacts / Numbers", "Automatically analyze designated VIP numbers & contacts"),
    ALL("All Calls", "Automatically transcribe & analyze every new call"),
    MANUAL_ONLY("Manual Only", "Show new calls immediately; analyze when you tap")
}

class CallPreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // ── Persisted Call Recordings Folder ──────────────────────────────────────

    fun getPersistedFolderUri(): String? {
        return prefs.getString(KEY_PERSISTED_FOLDER_URI, null)?.takeIf { it.isNotBlank() }
    }

    fun setPersistedFolderUri(uriString: String?) {
        prefs.edit().apply {
            if (uriString.isNullOrBlank()) {
                remove(KEY_PERSISTED_FOLDER_URI)
            } else {
                putString(KEY_PERSISTED_FOLDER_URI, uriString.trim())
            }
        }.apply()
    }

    // ── Background Auto-Sync ──────────────────────────────────────────────────

    fun isAutoSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_SYNC_ENABLED, true)
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC_ENABLED, enabled).apply()
    }

    // ── Auto-Analyze Rules ────────────────────────────────────────────────────

    fun getAutoAnalyzeMode(): AutoAnalyzeMode {
        val saved = prefs.getString(KEY_AUTO_ANALYZE_MODE, AutoAnalyzeMode.UNKNOWN_ONLY.name)
        return try {
            AutoAnalyzeMode.valueOf(saved ?: AutoAnalyzeMode.UNKNOWN_ONLY.name)
        } catch (_: Exception) {
            AutoAnalyzeMode.UNKNOWN_ONLY
        }
    }

    fun setAutoAnalyzeMode(mode: AutoAnalyzeMode) {
        prefs.edit().putString(KEY_AUTO_ANALYZE_MODE, mode.name).apply()
    }

    // ── Specific Contacts / Numbers to Auto-Analyze ───────────────────────────

    fun getAutoAnalyzeTargets(): Set<String> {
        return prefs.getStringSet(KEY_AUTO_ANALYZE_TARGETS, emptySet()) ?: emptySet()
    }

    fun addAutoAnalyzeTarget(target: String) {
        val clean = target.trim()
        if (clean.isBlank()) return
        val current = getAutoAnalyzeTargets().toMutableSet()
        current.add(clean)
        prefs.edit().putStringSet(KEY_AUTO_ANALYZE_TARGETS, current).apply()
    }

    fun removeAutoAnalyzeTarget(target: String) {
        val current = getAutoAnalyzeTargets().toMutableSet()
        current.remove(target.trim())
        prefs.edit().putStringSet(KEY_AUTO_ANALYZE_TARGETS, current).apply()
    }

    // ── Commitment & Important Dates Reminders ───────────────────────────────

    fun isCommitmentRemindersEnabled(): Boolean {
        return prefs.getBoolean(KEY_COMMITMENT_REMINDERS_ENABLED, true)
    }

    fun setCommitmentRemindersEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COMMITMENT_REMINDERS_ENABLED, enabled).apply()
    }

    // ── Checked / Completed Action Items Checklist ────────────────────────────

    fun getAllCompletedActionItems(): Set<String> {
        return prefs.getStringSet(KEY_COMPLETED_ACTION_ITEMS, emptySet()) ?: emptySet()
    }

    fun isActionItemCompleted(recordingId: Int, itemText: String): Boolean {
        val key = "${recordingId}_${itemText.hashCode()}"
        val set = prefs.getStringSet(KEY_COMPLETED_ACTION_ITEMS, emptySet()) ?: emptySet()
        return set.contains(key)
    }

    fun setActionItemCompleted(recordingId: Int, itemText: String, completed: Boolean) {
        val key = "${recordingId}_${itemText.hashCode()}"
        val current = (prefs.getStringSet(KEY_COMPLETED_ACTION_ITEMS, emptySet()) ?: emptySet()).toMutableSet()
        if (completed) {
            current.add(key)
        } else {
            current.remove(key)
        }
        prefs.edit().putStringSet(KEY_COMPLETED_ACTION_ITEMS, current).apply()
    }

    companion object {
        private const val PREFS_NAME = "call_scribe_prefs"
        private const val KEY_PERSISTED_FOLDER_URI = "persisted_folder_uri"
        private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        private const val KEY_AUTO_ANALYZE_MODE = "auto_analyze_mode"
        private const val KEY_AUTO_ANALYZE_TARGETS = "auto_analyze_targets"
        private const val KEY_COMMITMENT_REMINDERS_ENABLED = "commitment_reminders_enabled"
        private const val KEY_COMPLETED_ACTION_ITEMS = "completed_action_items"
    }
}
