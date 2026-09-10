package com.example.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

object AudioDurationHelper {
    private val memoryCache = ConcurrentHashMap<String, Int>()

    /**
     * Extracts audio duration in milliseconds from a SAF Content Uri or File Uri.
     * Uses OpenFileDescriptor so it is fast (~1-3ms) and avoids permission traps.
     * Results are cached in memory for instant subsequent lookups.
     */
    fun getDurationMs(context: Context, uri: Uri): Int {
        val uriStr = uri.toString()
        memoryCache[uriStr]?.let { return it }

        val retriever = MediaMetadataRetriever()
        val duration = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durStr?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            } ?: 0
        } catch (_: Throwable) {
            0
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }

        if (duration > 0) {
            memoryCache[uriStr] = duration
        }
        return duration
    }

    fun getCachedDuration(uriString: String): Int = memoryCache[uriString] ?: 0

    fun cacheDuration(uriString: String, durationMs: Int) {
        if (durationMs > 0) {
            memoryCache[uriString] = durationMs
        }
    }
}
