package com.example.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.AudioDurationHelper
import com.example.data.CallMetadataParser
import com.example.data.CallPreferencesManager
import com.example.data.CommitmentExtractor
import com.example.data.LocalAnalysisEngine
import com.example.data.PreferredEngine
import com.example.data.Recording
import com.example.data.SimpleEncryption
import com.example.data.TranscriptionValidator
import com.example.di.DefaultAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class CallSyncWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        SyncLock.mutex.withLock {
            val prefs = DefaultAppContainer.getPreferencesManager(appContext)

            // Check if background auto-sync is enabled
            if (!prefs.isAutoSyncEnabled()) {
                return@withLock Result.success()
            }

            val folderUriStr = prefs.getPersistedFolderUri() ?: return@withLock Result.success()
            val treeUri = try {
                Uri.parse(folderUriStr)
            } catch (_: Exception) {
                return@withLock Result.success()
            }

            val repository = DefaultAppContainer.getRepository(appContext)
            val geminiRepo = DefaultAppContainer.getGeminiRepository(appContext)
            val nvidiaRepo = DefaultAppContainer.getNvidiaRepository(appContext)
            val cloudflareRepo = DefaultAppContainer.getCloudflareWorkerRepository(appContext)
            val preferredEngine = prefs.getPreferredEngine()
            val mode = prefs.getAutoAnalyzeMode()
            val targets = prefs.getAutoAnalyzeTargets()
            val commitmentRemindersEnabled = prefs.isCommitmentRemindersEnabled()

            try {
                val audioFiles = mutableListOf<AudioFileInfo>()
                val treeDocId = try {
                    DocumentsContract.getTreeDocumentId(treeUri)
                } catch (_: Exception) {
                    DocumentsContract.getDocumentId(treeUri)
                }

                scanDirectoryRecursively(appContext, treeUri, treeDocId, audioFiles, currentDepth = 0, maxDepth = 3)

                if (audioFiles.isEmpty()) {
                    return@withLock Result.success()
                }

                // Process newest files first
                audioFiles.sortByDescending { it.lastModified }

                for (fileInfo in audioFiles) {
                    if (isStopped || !coroutineContext.isActive) break

                    // Skip 0-byte files (e.g. actively being recorded by call recorder)
                    if (fileInfo.size <= 0L) continue

                    val fileUriStr = fileInfo.uri.toString()
                    val existing = repository.getByUri(fileUriStr)

                    // If already successfully analyzed with non-blank transcription, skip it
                    if (existing != null && existing.decodedTranscription.isNotBlank() && !isUnusableTranscription(existing.decodedTranscription, existing.decodedSummary)) {
                        continue
                    }

                    // 1. If placeholder already exists, reuse its ID; otherwise insert placeholder into Room
                    val insertedId = if (existing != null) {
                        existing.id
                    } else {
                        val duration = AudioDurationHelper.getDurationMs(appContext, fileInfo.uri)
                        val placeholder = Recording(
                            id = 0,
                            title = fileInfo.name,
                            contentEncrypted = SimpleEncryption.encrypt(""),
                            summaryEncrypted = SimpleEncryption.encrypt("Pending AI Analysis\n\nTap ⚡ Transcribe & Analyze Call to view insights."),
                            timestamp = if (fileInfo.lastModified > 0) fileInfo.lastModified else System.currentTimeMillis(),
                            sourceUri = fileUriStr,
                            durationMs = duration
                        )
                        repository.insert(placeholder).toInt()
                    }

                    // 2. Check if this call matches the user's Auto-Analyze rule
                    val shouldAutoAnalyze = CallMetadataParser.matchesAutoAnalyzeRule(fileInfo.name, mode, targets)

                    val hasAnyEngine = geminiRepo.isApiKeyConfigured() || nvidiaRepo.isApiKeyConfigured() || cloudflareRepo.isConfigured() || preferredEngine == PreferredEngine.ON_DEVICE

                    if (shouldAutoAnalyze && hasAnyEngine) {
                        val spokenLanguage = prefs.getSpokenLanguage().code
                        // Perform background audio analysis
                        val analysisResult = processAudio(
                            context = appContext,
                            uri = fileInfo.uri,
                            fileName = fileInfo.name,
                            mimeType = fileInfo.mimeType,
                            fileSize = fileInfo.size,
                            geminiRepo = geminiRepo,
                            nvidiaRepo = nvidiaRepo,
                            cloudflareRepo = cloudflareRepo,
                            preferredEngine = preferredEngine,
                            spokenLanguage = spokenLanguage
                        )

                        val finalTranscription = analysisResult.first
                        val finalSummary = analysisResult.second
                        val duration = AudioDurationHelper.getDurationMs(appContext, fileInfo.uri).let {
                            if (it > 0) it else (existing?.durationMs ?: 0)
                        }

                        val updatedRecording = Recording(
                            id = insertedId,
                            title = fileInfo.name,
                            contentEncrypted = SimpleEncryption.encrypt(finalTranscription),
                            summaryEncrypted = SimpleEncryption.encrypt(finalSummary),
                            timestamp = if (fileInfo.lastModified > 0) fileInfo.lastModified else System.currentTimeMillis(),
                            sourceUri = fileUriStr,
                            durationMs = duration
                        )
                        repository.insert(updatedRecording)

                        // 3. Commitments & Important Dates Detection
                        if (commitmentRemindersEnabled) {
                            val actionItems = CommitmentExtractor.extractActionItems(finalSummary)
                            val dates = CommitmentExtractor.extractDates(finalSummary)

                            if (actionItems.isNotEmpty() || dates.isNotEmpty()) {
                                NotificationHelper.notifyCommitments(
                                    context = appContext,
                                    callTitle = fileInfo.name,
                                    actionItems = actionItems,
                                    dates = dates,
                                    recordingId = insertedId
                                )
                            }
                        }

                        // Notification: Auto-analyzed call ready
                        NotificationHelper.notifyNewCallDetected(
                            context = appContext,
                            callTitle = fileInfo.name,
                            recordingId = insertedId,
                            isAutoAnalyzed = true
                        )

                        // Brief delay to be polite to API rate limits
                        kotlinx.coroutines.delay(3000)
                    } else {
                        // Not auto-analyzed: notify new call detected for manual analysis
                        NotificationHelper.notifyNewCallDetected(
                            context = appContext,
                            callTitle = fileInfo.name,
                            recordingId = insertedId,
                            isAutoAnalyzed = false
                        )
                    }
                }

                Result.success()
            } catch (_: SecurityException) {
                // Folder permission was revoked; do not retry indefinitely
                Result.failure()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cooperative WorkManager cancellation - do NOT swallow or retry
                throw e
            } catch (t: Throwable) {
                Result.retry()
            }
        }
    }

    private data class AudioFileInfo(
        val uri: Uri,
        val name: String,
        val mimeType: String?,
        val size: Long,
        val lastModified: Long
    )

    private fun isAudioFile(name: String, mime: String?): Boolean {
        if (mime != null && (mime.startsWith("audio/") || mime == "video/3gpp" || mime == "application/ogg")) {
            return true
        }
        val lowerName = name.lowercase()
        val audioExtensions = listOf(".mp3", ".m4a", ".wav", ".aac", ".opus", ".ogg", ".3gp", ".amr", ".flac", ".wma", ".m4p", ".caf")
        return audioExtensions.any { lowerName.endsWith(it) }
    }

    private fun scanDirectoryRecursively(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        results: MutableList<AudioFileInfo>,
        currentDepth: Int,
        maxDepth: Int
    ) {
        if (currentDepth > maxDepth) return

        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )

            val subDirs = mutableListOf<String>()

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val docId = if (idIdx != -1) cursor.getString(idIdx) else null ?: continue
                    val name = if (nameIdx != -1) cursor.getString(nameIdx) else null ?: "recording"
                    val mime = if (mimeIdx != -1) cursor.getString(mimeIdx) else null
                    var size = if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    val lastModified = if (modIdx != -1 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        subDirs.add(docId)
                    } else if (isAudioFile(name, mime)) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        if (size <= 0L) {
                            size = try {
                                context.contentResolver.openFileDescriptor(fileUri, "r")?.use { it.statSize } ?: 0L
                            } catch (_: Exception) { 0L }
                            if (size <= 0L) {
                                val hasContent = try {
                                    context.contentResolver.openInputStream(fileUri)?.use { it.read() != -1 } ?: false
                                } catch (_: Exception) { false }
                                if (hasContent) size = 1L
                            }
                        }
                        results.add(AudioFileInfo(fileUri, name, mime, size, lastModified))
                    }
                }
            }

            for (subDirDocId in subDirs) {
                scanDirectoryRecursively(context, treeUri, subDirDocId, results, currentDepth + 1, maxDepth)
            }
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun isUnusableTranscription(transcription: String, summary: String): Boolean {
        return TranscriptionValidator.isUnusableTranscription(transcription, summary)
    }

    private suspend fun processAudio(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String?,
        fileSize: Long,
        geminiRepo: com.example.network.GeminiRepository,
        nvidiaRepo: com.example.network.NvidiaRepository,
        cloudflareRepo: com.example.network.CloudflareWorkerRepository,
        preferredEngine: PreferredEngine,
        spokenLanguage: String = "auto"
    ): Pair<String, String> {
        val maxFileSizeGemini = 15L * 1024 * 1024
        val maxFileSizeNvidia = 25L * 1024 * 1024
        val maxFileSizeCloudflare = 24L * 1024 * 1024  // 24 MB — Safe under Cloudflare 25 MB payload limit
        val resolvedMime = mimeType ?: context.contentResolver.getType(uri) ?: "audio/mp3"

        var transcription = ""
        var summary = ""
        var audioBytes: ByteArray? = null

        // 0. If ON_DEVICE preferred
        if (preferredEngine == PreferredEngine.ON_DEVICE) {
            val (locTrans, locSum) = LocalAnalysisEngine.analyzeLocally("", fileName)
            return Pair(locTrans, locSum)
        }

        // 1. Try Cloudflare Worker first if preferred or in AUTO mode
        val tryCloudflareFirst = (preferredEngine == PreferredEngine.CLOUDFLARE || preferredEngine == PreferredEngine.AUTO) && cloudflareRepo.isConfigured()
        if (tryCloudflareFirst && fileSize <= maxFileSizeCloudflare) {
            audioBytes = readAudioBytes(context, uri, maxFileSizeCloudflare)
            if (audioBytes != null) {
                val cfRes = cloudflareRepo.analyzeAudio(audioBytes, fileName, resolvedMime, spokenLanguage)
                if (cfRes.isSuccess) {
                    val pair = cfRes.getOrThrow()
                    transcription = pair.first
                    summary = pair.second
                }
            }
        }

        // 2. Try Gemini (if preferred, or if Cloudflare wasn't configured / gave no speech in AUTO mode)
        val cloudflareGaveNoSpeech = isUnusableTranscription(transcription, summary)
        val tryGemini = (preferredEngine == PreferredEngine.GEMINI || (preferredEngine == PreferredEngine.AUTO && cloudflareGaveNoSpeech) || transcription.isBlank()) && geminiRepo.isApiKeyConfigured()
        if (tryGemini && fileSize <= maxFileSizeGemini) {
            val bytes = audioBytes ?: readAudioBytes(context, uri, maxFileSizeGemini)
            audioBytes = null // Release raw byte reference before Base64 encoding
            if (bytes != null) {
                val base64Audio = try {
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                } catch (_: OutOfMemoryError) {
                    System.gc()
                    null
                }
                if (base64Audio != null) {
                    val geminiRes = geminiRepo.transcribeAndSummarizeAudio(base64Audio, resolvedMime)
                    if (geminiRes.isSuccess) {
                        val pair = geminiRes.getOrThrow()
                        if (!isUnusableTranscription(pair.first, pair.second) || transcription.isBlank() || isUnusableTranscription(transcription, summary)) {
                            transcription = pair.first
                            summary = pair.second
                        }
                    }
                }
            }
        }

        // 3. Fallback to Cloudflare if Gemini was preferred but failed, and Cloudflare wasn't tried yet
        if (transcription.isBlank() && !tryCloudflareFirst && cloudflareRepo.isConfigured() && fileSize <= maxFileSizeCloudflare) {
            val bytes = readAudioBytes(context, uri, maxFileSizeCloudflare)
            if (bytes != null) {
                val cfRes = cloudflareRepo.analyzeAudio(bytes, fileName, resolvedMime, spokenLanguage)
                if (cfRes.isSuccess) {
                    val pair = cfRes.getOrThrow()
                    transcription = pair.first
                    summary = pair.second
                }
            }
        }

        // 4. Try NVIDIA Canary ASR if transcription still blank
        if (transcription.isBlank() && nvidiaRepo.isApiKeyConfigured() && fileSize <= maxFileSizeNvidia) {
            val bytes = readAudioBytes(context, uri, maxFileSizeNvidia)
            if (bytes != null) {
                val asrRes = nvidiaRepo.transcribeAudio(bytes, fileName, resolvedMime)
                if (asrRes.isSuccess) {
                    transcription = asrRes.getOrThrow()
                }
            }
        }

        // 5. Summarization fallback if needed
        if (transcription.isNotBlank() && summary.isBlank()) {
            if (cloudflareRepo.isConfigured()) {
                val cfSum = cloudflareRepo.summarizeTranscript(transcription, fileName)
                if (cfSum.isSuccess) summary = cfSum.getOrThrow()
            }
            if (summary.isBlank() && nvidiaRepo.isApiKeyConfigured()) {
                val sumRes = nvidiaRepo.summarizeTranscript(transcription, fileName)
                if (sumRes.isSuccess) summary = sumRes.getOrThrow()
            }
            if (summary.isBlank()) {
                val (_, locSum) = LocalAnalysisEngine.analyzeLocally(transcription, fileName)
                summary = locSum
            }
        }

        // 6. Local on-device fallback if still blank
        if (transcription.isBlank()) {
            val (locTrans, locSum) = LocalAnalysisEngine.analyzeLocally("", fileName)
            transcription = locTrans
            summary = locSum
        } else if (summary.isBlank()) {
            val (_, locSum) = LocalAnalysisEngine.analyzeLocally(transcription, fileName)
            summary = locSum
        }

        audioBytes = null
        transcription = TranscriptionValidator.sanitizeTranscription(transcription, summary)

        return Pair(transcription, summary)
    }

    private fun hasSufficientHeap(requiredBytes: Long): Boolean {
        val rt = Runtime.getRuntime()
        val freeMemory = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        val needed = (requiredBytes * 2L) + (32L * 1024 * 1024)
        if (freeMemory < needed) {
            System.gc()
            val afterGc = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
            return afterGc >= needed
        }
        return true
    }

    private suspend fun readAudioBytes(
        context: Context,
        uri: Uri,
        maxBytes: Long
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val statSize = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            } catch (_: Throwable) { -1L }

            if (statSize > maxBytes) {
                return@withContext null
            }

            val estimatedBytes = if (statSize > 0) statSize else minOf(maxBytes, 10L * 1024 * 1024)
            if (!hasSufficientHeap(estimatedBytes)) {
                System.gc()
            }

            if (statSize in 1..maxBytes) {
                val targetSize = statSize.toInt()
                val bytes = ByteArray(targetSize)
                var totalRead = 0
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    while (totalRead < targetSize) {
                        val count = stream.read(bytes, totalRead, targetSize - totalRead)
                        if (count <= 0) break
                        totalRead += count
                    }
                }
                if (totalRead == targetSize) bytes else bytes.copyOf(totalRead)
            } else {
                val initialCap = minOf(maxBytes.toInt(), 1024 * 1024)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArrayOutputStream(initialCap)
                    val chunk = ByteArray(32768)
                    var total = 0L
                    var read: Int
                    while (stream.read(chunk, 0, chunk.size).also { read = it } != -1) {
                        total += read
                        if (total > maxBytes) return@use null
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                }
            }
        } catch (_: OutOfMemoryError) {
            System.gc()
            null
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        const val WORK_NAME_PERIODIC = "call_scribe_periodic_sync"
        const val WORK_NAME_ONE_TIME = "call_scribe_one_time_sync"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<CallSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }

        fun syncOnce(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<CallSyncWorker>().build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME_ONE_TIME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }

        fun cancelPeriodicSync(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME_PERIODIC)
        }
    }
}
