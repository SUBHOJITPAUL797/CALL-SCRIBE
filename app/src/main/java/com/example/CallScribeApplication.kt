package com.example

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallScribeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        setupGlobalCrashHandler()
    }

    private fun setupGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(applicationContext, thread, throwable)
            } catch (t: Throwable) {
                Log.e("CallScribeCrash", "Failed to record crash: ${t.localizedMessage}", t)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    companion object {
        private const val PREFS_CRASH = "call_scribe_crash_log"
        private const val KEY_LAST_CRASH = "last_crash_content"
        private const val KEY_CRASH_TIMESTAMP = "last_crash_timestamp"
        private const val FILE_LAST_CRASH = "last_crash_report.txt"

        fun recordCrash(context: Context, thread: Thread?, throwable: Throwable) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val threadName = thread?.name ?: "Unknown"

            val report = buildString {
                appendLine("=== CALL SCRIBE CRASH REPORT ===")
                appendLine("Time: $timestamp")
                appendLine("Thread: $threadName")
                appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                appendLine("Error: ${throwable.javaClass.name}: ${throwable.localizedMessage ?: "No message"}")
                appendLine("")
                appendLine("=== STACK TRACE ===")
                appendLine(stackTrace)
            }

            Log.e("CallScribeCrash", report)

            // 1. Save to internal app files
            try {
                val file = File(context.filesDir, FILE_LAST_CRASH)
                file.writeText(report)
            } catch (e: Throwable) {
                Log.e("CallScribeCrash", "Could not write crash file: ${e.localizedMessage}")
            }

            // 2. Save to SharedPreferences for redundancy
            try {
                context.getSharedPreferences(PREFS_CRASH, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, report)
                    .putLong(KEY_CRASH_TIMESTAMP, System.currentTimeMillis())
                    .apply()
            } catch (e: Throwable) {
                Log.e("CallScribeCrash", "Could not write crash prefs: ${e.localizedMessage}")
            }
        }

        fun getLastCrashReport(context: Context): String? {
            // Check file first
            try {
                val file = File(context.filesDir, FILE_LAST_CRASH)
                if (file.exists() && file.length() > 0) {
                    val content = file.readText().trim()
                    if (content.isNotBlank()) return content
                }
            } catch (_: Throwable) {}

            // Fallback to SharedPreferences
            try {
                val fromPrefs = context.getSharedPreferences(PREFS_CRASH, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_CRASH, null)
                if (!fromPrefs.isNullOrBlank()) return fromPrefs
            } catch (_: Throwable) {}

            return null
        }

        fun clearCrashReport(context: Context) {
            try {
                val file = File(context.filesDir, FILE_LAST_CRASH)
                if (file.exists()) file.delete()
            } catch (_: Throwable) {}

            try {
                context.getSharedPreferences(PREFS_CRASH, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            } catch (_: Throwable) {}
        }
    }
}
