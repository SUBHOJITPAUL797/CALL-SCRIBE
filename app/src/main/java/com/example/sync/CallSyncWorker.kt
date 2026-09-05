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
import com.example.data.CallMetadataParser
import com.example.data.CallPreferencesManager
import com.example.data.CommitmentExtractor
import com.example.data.LocalAnalysisEngine
import com.example.data.Recording
import com.example.data.SimpleEncryption
import com.example.di.DefaultAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class CallSyncWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = CallPreferencesManager(appContext)

        // Check if background auto-sync is enabled
        if (!prefs.isAutoSyncEnabled()) {
            return@withContext Result.success()
        }

        val folderUriStr = prefs.getPersistedFolderUri() ?: return@withContext Result.success()
        val treeUri = try {
            Uri.parse(folderUriStr)
        } catch (_: Exception) {
            return@withContext Result.success()
        }

        val repository = DefaultAppContainer.getRepository(appContext)
        val geminiRepo = DefaultAppContainer.getGeminiRepository(appContext)
        val nvidiaRepo = DefaultAppContainer.getNvidiaRepository(appContext)
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
                return@withContext Result.success()
            }

            // Process newest files first
            audioFiles.sortByDescending { it.lastModified }

            for (fileInfo in audioFiles) {
                val fileUriStr = fileInfo.uri.toString()
                val existing = repository.getByUri(fileUriStr)

                // Only consider newly discovered audio files
                if (existing != null) continue

                // 1. Immediately insert placeholder recording into Room so it appears in the app
                val placeholder = Recording(
                    id = 0,
                    title = fileInfo.name,
                    contentEncrypted = SimpleEncryption.encrypt(""),
                    summaryEncrypted = SimpleEncryption.encrypt("Pending AI Analysis\n\nTap ⚡ Transcribe & Analyze Call to view insights."),
                    timestamp = if (fileInfo.lastModified > 0) fileInfo.lastModified else System.currentTimeMillis(),
                    sourceUri = fileUriStr
                )
                val insertedId = repository.insert(placeholder).toInt()

                // 2. Check if this call matches the user's Auto-Analyze rule
                val shouldAutoAnalyze = CallMetadataParser.matchesAutoAnalyzeRule(fileInfo.name, mode, targets)

                if (shouldAutoAnalyze && (geminiRepo.isApiKeyConfigured() || nvidiaRepo.isApiKeyConfigured())) {
                    // Perform background audio analysis
                    val analysisResult = processAudio(
                        context = appContext,
                        uri = fileInfo.uri,
                        fileName = fileInfo.name,
                        mimeType = fileInfo.mimeType,
                        fileSize = fileInfo.size,
                        geminiRepo = geminiRepo,
                        nvidiaRepo = nvidiaRepo
                    )

                    val finalTranscription = analysisResult.first
                    val finalSummary = analysisResult.second

                    val updatedRecording = Recording(
                        id = insertedId,
                        title = fileInfo.name,
                        contentEncrypted = SimpleEncryption.encrypt(finalTranscription),
                        summaryEncrypted = SimpleEncryption.encrypt(finalSummary),
                        timestamp = if (fileInfo.lastModified > 0) fileInfo.lastModified else System.currentTimeMillis(),
                        sourceUri = fileUriStr
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
        } catch (t: Throwable) {
            Result.retry()
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
                    val size = if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    val lastModified = if (modIdx != -1 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        subDirs.add(docId)
                    } else if (isAudioFile(name, mime)) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        results.add(AudioFileInfo(fileUri, name, mime, size, lastModified))
                    }
                }
            }

            for (subDirDocId in subDirs) {
                scanDirectoryRecursively(context, treeUri, subDirDocId, results, currentDepth + 1, maxDepth)
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun processAudio(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String?,
        fileSize: Long,
        geminiRepo: com.example.network.GeminiRepository,
        nvidiaRepo: com.example.network.NvidiaRepository
    ): Pair<String, String> {
        val maxFileSize = 15L * 1024 * 1024
        val resolvedMime = mimeType ?: context.contentResolver.getType(uri) ?: "audio/mp3"

        var transcription = ""
        var summary = ""

        if (geminiRepo.isApiKeyConfigured() && fileSize <= maxFileSize) {
            val bytes = readAudioBytes(context, uri, maxFileSize)
            if (bytes != null) {
                val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val geminiRes = geminiRepo.transcribeAndSummarizeAudio(base64Audio, resolvedMime)
                if (geminiRes.isSuccess) {
                    val pair = geminiRes.getOrThrow()
                    transcription = pair.first
                    summary = pair.second
                }
            }
        }

        if (transcription.isNotBlank() && summary.isBlank() && nvidiaRepo.isApiKeyConfigured()) {
            val sumRes = nvidiaRepo.summarizeTranscript(transcription, fileName)
            if (sumRes.isSuccess) {
                summary = sumRes.getOrThrow()
            }
        }

        if (transcription.isBlank()) {
            val (locTrans, locSum) = LocalAnalysisEngine.analyzeLocally("", fileName)
            transcription = locTrans
            summary = locSum
        }

        return Pair(transcription, summary)
    }

    private suspend fun readAudioBytes(
        context: Context,
        uri: Uri,
        maxBytes: Long
    ): ByteArray? = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16384)
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
                ExistingPeriodicWorkPolicy.KEEP,
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
