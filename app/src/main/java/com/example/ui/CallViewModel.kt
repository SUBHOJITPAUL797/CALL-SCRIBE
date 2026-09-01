package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.DocumentsContract
import android.util.Base64
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.ApiKeyManager
import com.example.data.Recording
import com.example.data.RecordingRepository
import com.example.data.SimpleEncryption
import com.example.network.ApiKeyInvalidException
import com.example.network.ApiKeyMissingException
import com.example.network.ApiQuotaExceededException
import com.example.network.AppUpdateInfo
import com.example.network.GeminiRepository
import com.example.network.GitHubUpdateRepository
import com.example.update.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class CallViewModel(
    private val repository: RecordingRepository,
    private val geminiRepository: GeminiRepository,
    private val gitHubUpdateRepository: GitHubUpdateRepository = GitHubUpdateRepository(),
    private val apiKeyManager: ApiKeyManager? = null
) : ViewModel() {

    val searchQuery = MutableStateFlow("")
    val isSyncing = MutableStateFlow(false)
    val syncStatus = MutableStateFlow("")
    val syncProgress = MutableStateFlow(0f)
    val syncProcessedCount = MutableStateFlow(0)
    val syncTotalCount = MutableStateFlow(0)
    val syncErrorCount = MutableStateFlow(0)

    val showApiKeyDialog = MutableStateFlow(false)
    val selectedFolderForLimit = MutableStateFlow<Uri?>(null)
    val folderTotalRecordings = MutableStateFlow(0)

    val updateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val isCheckingUpdate = MutableStateFlow(false)
    val isDownloadingUpdate = MutableStateFlow(false)
    val downloadProgress = MutableStateFlow(0f)
    val updateStatusMessage = MutableStateFlow<String?>(null)

    private var syncJob: Job? = null

    init {
        // Automatically check for GitHub updates silently in the background on app start
        checkForUpdates(manual = false)
    }

    fun isApiKeyConfigured(): Boolean {
        return geminiRepository.isApiKeyConfigured()
    }

    fun getApiKey(): String {
        return apiKeyManager?.getApiKey() ?: BuildConfig.GEMINI_API_KEY
    }

    fun saveApiKey(newKey: String) {
        apiKeyManager?.setApiKey(newKey)
        showApiKeyDialog.value = false
        updateStatusMessage.value = if (newKey.isNotBlank()) "Gemini API Key saved." else "API Key cleared."
    }

    fun dismissApiKeyDialog() {
        showApiKeyDialog.value = false
    }

    fun dismissLimitDialog() {
        selectedFolderForLimit.value = null
    }

    fun checkForUpdates(manual: Boolean = false) {
        if (isCheckingUpdate.value) return

        viewModelScope.launch {
            isCheckingUpdate.value = true
            val currentVersion = BuildConfig.VERSION_NAME
            
            gitHubUpdateRepository.checkForUpdate(currentVersion)
                .onSuccess { info ->
                    if (info.hasUpdate) {
                        updateInfo.value = info
                    } else if (manual) {
                        updateStatusMessage.value = "App is up to date (v$currentVersion)"
                    }
                }
                .onFailure { error ->
                    if (manual) {
                        updateStatusMessage.value = "Could not check updates: ${error.localizedMessage ?: "Network error"}"
                    }
                }
            isCheckingUpdate.value = false
        }
    }

    fun dismissUpdateDialog() {
        updateInfo.value = null
    }

    fun clearUpdateStatusMessage() {
        updateStatusMessage.value = null
    }

    fun downloadAndInstallUpdate(context: Context) {
        val info = updateInfo.value ?: return
        val url = info.downloadUrl

        if (url.isNullOrBlank()) {
            AppUpdateManager.openBrowserReleasePage(context, info.releasePageUrl)
            return
        }

        viewModelScope.launch {
            isDownloadingUpdate.value = true
            downloadProgress.value = 0f

            val result = AppUpdateManager.downloadApk(
                context = context,
                downloadUrl = url,
                versionTag = info.latestVersionName
            ) { progress ->
                downloadProgress.value = progress
            }

            isDownloadingUpdate.value = false

            result.onSuccess { apkFile ->
                AppUpdateManager.installApk(context, apkFile)
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    "Download failed: ${error.localizedMessage}. Opening browser...",
                    Toast.LENGTH_LONG
                ).show()
                AppUpdateManager.openBrowserReleasePage(context, info.releasePageUrl)
            }
        }
    }

    val recordings = combine(repository.allRecordings, searchQuery) { list, query ->
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            list
        } else {
            list.filter {
                it.title.contains(trimmedQuery, ignoreCase = true) ||
                it.decodedTranscription.contains(trimmedQuery, ignoreCase = true) ||
                it.decodedSummary.contains(trimmedQuery, ignoreCase = true)
            }
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        isSyncing.value = false
        syncStatus.value = "Sync cancelled by user."
    }

    fun onFolderSelected(context: Context, treeUri: Uri) {
        if (isSyncing.value) return

        if (!isApiKeyConfigured()) {
            showApiKeyDialog.value = true
            updateStatusMessage.value = "Please enter your Gemini API Key first."
            return
        }

        val appContext = context.applicationContext
        viewModelScope.launch {
            isSyncing.value = true
            syncStatus.value = "Scanning folder for recordings..."
            syncProgress.value = 0f

            withContext(Dispatchers.IO) {
                try {
                    val audioFiles = mutableListOf<AudioFileInfo>()
                    val treeDocId = try {
                        DocumentsContract.getTreeDocumentId(treeUri)
                    } catch (_: Exception) {
                        DocumentsContract.getDocumentId(treeUri)
                    }

                    scanDirectoryRecursively(appContext, treeUri, treeDocId, audioFiles, currentDepth = 0, maxDepth = 3)

                    if (audioFiles.isEmpty()) {
                        syncStatus.value = "No audio recordings found in selected folder."
                        kotlinx.coroutines.delay(2000)
                        isSyncing.value = false
                        return@withContext
                    }

                    // Sort newest recordings first
                    audioFiles.sortByDescending { it.lastModified }

                    isSyncing.value = false
                    folderTotalRecordings.value = audioFiles.size
                    selectedFolderForLimit.value = treeUri
                } catch (t: Throwable) {
                    syncStatus.value = "Scan failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
                    isSyncing.value = false
                }
            }
        }
    }

    fun startSyncWithLimit(context: Context, limit: Int) {
        val treeUri = selectedFolderForLimit.value ?: return
        selectedFolderForLimit.value = null

        if (isSyncing.value) return
        val appContext = context.applicationContext

        syncJob = viewModelScope.launch {
            isSyncing.value = true
            syncStatus.value = "Preparing recordings..."
            syncProgress.value = 0f
            syncProcessedCount.value = 0
            syncErrorCount.value = 0

            withContext(Dispatchers.IO) {
                try {
                    val audioFiles = mutableListOf<AudioFileInfo>()
                    val treeDocId = try {
                        DocumentsContract.getTreeDocumentId(treeUri)
                    } catch (_: Exception) {
                        DocumentsContract.getDocumentId(treeUri)
                    }

                    scanDirectoryRecursively(appContext, treeUri, treeDocId, audioFiles, currentDepth = 0, maxDepth = 3)

                    if (audioFiles.isEmpty()) {
                        syncStatus.value = "No audio recordings found."
                        return@withContext
                    }

                    // Sort newest recordings first
                    audioFiles.sortByDescending { it.lastModified }

                    val targetFiles = if (limit > 0 && limit < audioFiles.size) {
                        audioFiles.take(limit)
                    } else {
                        audioFiles
                    }

                    syncTotalCount.value = targetFiles.size
                    syncStatus.value = "Checking ${targetFiles.size} recordings..."

                    var processedCount = 0
                    var skippedCount = 0
                    var errorCount = 0

                    for ((index, fileInfo) in targetFiles.withIndex()) {
                        if (!isActive) break

                        syncProgress.value = (index.toFloat() / targetFiles.size.toFloat()).coerceIn(0f, 1f)

                        val existing = repository.getByUri(fileInfo.uri.toString())
                        if (existing == null) {
                            syncStatus.value = "Processing (${index + 1}/${targetFiles.size}): ${fileInfo.name}"
                            val processResult = processAudioFile(appContext, fileInfo.uri, fileInfo.name, fileInfo.mimeType, fileInfo.size)
                            
                            if (processResult.isSuccess) {
                                processedCount++
                                syncProcessedCount.value = processedCount
                            } else {
                                val exception = processResult.exceptionOrNull()
                                errorCount++
                                syncErrorCount.value = errorCount

                                if (exception is ApiKeyMissingException || exception is ApiKeyInvalidException) {
                                    // Fatal API Key error: ABORT immediately!
                                    syncStatus.value = "Aborted: ${exception.message}"
                                    showApiKeyDialog.value = true
                                    return@withContext
                                }

                                if (exception is ApiQuotaExceededException) {
                                    syncStatus.value = "Rate limit reached. Waiting 5 seconds..."
                                    kotlinx.coroutines.delay(5000)
                                } else {
                                    val errorMsg = exception?.message ?: "Unknown error"
                                    syncStatus.value = "Error on ${fileInfo.name}: $errorMsg"
                                    kotlinx.coroutines.delay(1000)
                                }
                            }
                        } else {
                            skippedCount++
                        }
                    }

                    syncProgress.value = 1f
                    val summaryMessage = when {
                        errorCount > 0 && processedCount == 0 -> "Sync finished with $errorCount error(s). Skipped $skippedCount existing."
                        errorCount > 0 -> "Processed $processedCount new file(s) ($errorCount failed, $skippedCount existing)."
                        processedCount > 0 -> "Sync complete! Successfully analyzed $processedCount recording(s)."
                        else -> "All ${targetFiles.size} recordings are already up to date."
                    }
                    syncStatus.value = summaryMessage

                } catch (t: Throwable) {
                    if (t !is kotlinx.coroutines.CancellationException) {
                        syncStatus.value = "Sync failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
                    }
                } finally {
                    kotlinx.coroutines.delay(3000)
                    isSyncing.value = false
                }
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val MAX_FILE_SIZE_BYTES = 15L * 1024 * 1024 // 15 MB safe inline limit

    private suspend fun processAudioFile(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String?,
        fileSize: Long
    ): Result<Unit> {
        return try {
            val contentResolver = context.contentResolver
            val resolvedMime = mimeType ?: contentResolver.getType(uri) ?: "audio/mp3"

            // Memory and size safety check
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return Result.failure(Exception("File exceeds 15MB limit (${fileSize / (1024 * 1024)}MB)"))
            }

            val bytes = contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(16384)
                var totalRead = 0
                var read: Int
                while (inputStream.read(data, 0, data.size).also { read = it } != -1) {
                    totalRead += read
                    if (totalRead > MAX_FILE_SIZE_BYTES) {
                        return@use null // File exceeds limit during reading
                    }
                    buffer.write(data, 0, read)
                }
                buffer.toByteArray()
            } ?: return Result.failure(Exception("Could not read file or file exceeds 15MB"))

            val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val result = geminiRepository.transcribeAndSummarizeAudio(base64Audio, resolvedMime)

            result.fold(
                onSuccess = { (transcription, summary) ->
                    val recording = Recording(
                        title = fileName,
                        contentEncrypted = SimpleEncryption.encrypt(transcription),
                        summaryEncrypted = SimpleEncryption.encrypt(summary),
                        sourceUri = uri.toString()
                    )
                    repository.insert(recording)
                    Result.success(Unit)
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun deleteRecording(id: Int) {
        viewModelScope.launch {
            repository.deleteById(id)
        }
    }

    fun playAudio(context: Context, recording: Recording) {
        val uriStr = recording.sourceUri
        if (uriStr.isNullOrBlank()) {
            Toast.makeText(context, "No source audio file associated with this recording.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val audioUri = Uri.parse(uriStr)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(audioUri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No audio player app found to play recording.", Toast.LENGTH_SHORT).show()
        }
    }

    fun syncToCalendar(context: Context, recording: Recording) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, "Call: ${recording.title}")
            putExtra(CalendarContract.Events.DESCRIPTION, "Summary:\n${recording.decodedSummary}\n\nTranscription:\n${recording.decodedTranscription}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No calendar application available.", Toast.LENGTH_SHORT).show()
        }
    }
}

class CallViewModelFactory(
    private val repository: RecordingRepository,
    private val geminiRepository: GeminiRepository,
    private val gitHubUpdateRepository: GitHubUpdateRepository = GitHubUpdateRepository(),
    private val apiKeyManager: ApiKeyManager? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CallViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CallViewModel(repository, geminiRepository, gitHubUpdateRepository, apiKeyManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
