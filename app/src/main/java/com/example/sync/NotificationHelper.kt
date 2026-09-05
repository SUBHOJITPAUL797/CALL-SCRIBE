package com.example.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.data.CallMetadataParser

object NotificationHelper {

    const val CHANNEL_COMMITMENTS = "channel_commitments"
    const val CHANNEL_SYNC = "channel_sync"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return

            // High-importance channel for commitments and action items
            val commitmentsChannel = NotificationChannel(
                CHANNEL_COMMITMENTS,
                "Call Commitments & Tasks",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when promises, action items, and important dates are detected in calls"
                enableVibration(true)
                setShowBadge(true)
            }

            // Low-importance channel for sync status
            val syncChannel = NotificationChannel(
                CHANNEL_SYNC,
                "Call Sync & Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background scan and new call detection updates"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(commitmentsChannel)
            notificationManager.createNotificationChannel(syncChannel)
        }
    }

    fun notifyCommitments(
        context: Context,
        callTitle: String,
        actionItems: List<String>,
        dates: List<String>,
        recordingId: Int
    ) {
        if (actionItems.isEmpty() && dates.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        createNotificationChannels(context)

        val cleanTitle = CallMetadataParser.cleanCallTitle(callTitle)
        val title = "📌 Action Items Detected: $cleanTitle"

        val summaryText = buildString {
            if (actionItems.isNotEmpty()) append("${actionItems.size} task(s) ")
            if (dates.isNotEmpty()) append("${dates.size} date(s)")
        }.trim()

        val bigText = buildString {
            if (actionItems.isNotEmpty()) {
                append("✅ Action Items:\n")
                actionItems.forEach { item ->
                    append(" • $item\n")
                }
            }
            if (dates.isNotEmpty()) {
                if (actionItems.isNotEmpty()) append("\n")
                append("📅 Dates & Deadlines:\n")
                dates.forEach { date ->
                    append(" • $date\n")
                }
            }
        }.trimEnd()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            recordingId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = context.applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.ic_popup_reminder

        val notification = NotificationCompat.Builder(context, CHANNEL_COMMITMENTS)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(summaryText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(2000 + recordingId, notification)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    fun notifyNewCallDetected(
        context: Context,
        callTitle: String,
        recordingId: Int,
        isAutoAnalyzed: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        createNotificationChannels(context)

        val cleanTitle = CallMetadataParser.cleanCallTitle(callTitle)
        val title = if (isAutoAnalyzed) "⚡ Call Analyzed: $cleanTitle" else "📞 New Call: $cleanTitle"
        val text = if (isAutoAnalyzed) "Full transcription and AI insights are ready." else "New call recording detected. Tap to analyze."

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            recordingId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = context.applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.stat_notify_chat

        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(1000 + recordingId, notification)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }
}
