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
import com.example.data.CallMetadataParser
import com.example.data.LocalAnalysisEngine
import com.example.data.Recording
import com.example.data.RecordingRepository
import com.example.data.SimpleEncryption
import com.example.network.ApiKeyInvalidException
import com.example.network.ApiKeyMissingException
import com.example.network.ApiQuotaExceededException
import com.example.network.AppUpdateInfo
import com.example.network.GeminiRepository
import com.example.network.GitHubUpdateRepository
import com.example.network.NvidiaRepository
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

enum class MessageSender { USER, AI }

data class ChatMessage(
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

class CallViewModel(
    private val repository: RecordingRepository,
    private val geminiRepository: GeminiRepository,
    private val gitHubUpdateRepository: GitHubUpdateRepository = GitHubUpdateRepository(),
    private val apiKeyManager: ApiKeyManager? = null,
    private val nvidiaRepository: NvidiaRepository? = null
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
    private val skippedUpdateVersion = MutableStateFlow<String?>(null)

    // In-App Native Audio Player
    val audioPlayer = AudioPlayerManager(viewModelScope)

    // Chat with Call State
    val activeChatRecording = MutableStateFlow<Recording?>(null)
    val chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val isChatLoading = MutableStateFlow(false)

    private var syncJob: Job? = null

    init {
        checkForUpdates(manual = false)
    }

    fun isApiKeyConfigured(): Boolean = geminiRepository.isApiKeyConfigured()

    fun isNvidiaKeyConfigured(): Boolean = nvidiaRepository?.isApiKeyConfigured() == true

    fun getApiKey(): String = apiKeyManager?.getApiKey() ?: BuildConfig.GEMINI_API_KEY

    fun getNvidiaApiKey(): String = apiKeyManager?.getNvidiaApiKey() ?: ""

    fun saveApiKey(newKey: String) {
        apiKeyManager?.setApiKey(newKey)
        showApiKeyDialog.value = false
        val mode = when {
            newKey.isNotBlank() -> "Gemini API Key saved ✅"
            isNvidiaKeyConfigured() -> "Gemini key cleared. NVIDIA fallback still active."
            else -> "API Key cleared — using On-Device mode."
        }
        updateStatusMessage.value = mode
    }

    fun saveNvidiaApiKey(newKey: String) {
        apiKeyManager?.setNvidiaApiKey(newKey)
        updateStatusMessage.value = if (newKey.isNotBlank()) "NVIDIA API Key saved ✅" else "NVIDIA key cleared."
    }

    fun testApiKey(apiKey: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = geminiRepository.testApiKey(apiKey)
            res.onSuccess { msg -> onResult(true, msg) }
               .onFailure { err -> onResult(false, err.localizedMessage ?: "Connection error") }
        }
    }

    fun testNvidiaApiKey(apiKey: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val tempRepo = NvidiaRepository(apiKeyProvider = { apiKey })
            val res = tempRepo.testApiKey(apiKey)
            res.onSuccess { msg -> onResult(true, msg) }
               .onFailure { err -> onResult(false, err.localizedMessage ?: "Connection error") }
        }
    }

    fun dismissApiKeyDialog() { showApiKeyDialog.value = false }

    fun dismissLimitDialog() {
        selectedFolderForLimit.value = null
    }

    // --- Chat With Call Methods ---
    fun openChat(recording: Recording) {
        activeChatRecording.value = recording
        val mode = when {
            isApiKeyConfigured() -> "Gemini AI"
            isNvidiaKeyConfigured() -> "NVIDIA Llama AI"
            else -> "On-Device AI"
        }
        chatMessages.value = listOf(
            ChatMessage(
                sender = MessageSender.AI,
                text = "Hi! I am your call assistant for '${CallMetadataParser.cleanCallTitle(recording.title)}' ($mode).\nAsk me anything about what was discussed, promised, or scheduled in this call."
            )
        )
    }

    fun closeChat() {
        activeChatRecording.value = null
        chatMessages.value = emptyList()
        isChatLoading.value = false
    }

    fun sendChatMessage(question: String) {
        val recording = activeChatRecording.value ?: return
        val cleanQuestion = question.trim()
        if (cleanQuestion.isBlank() || isChatLoading.value) return

        val currentList = chatMessages.value.toMutableList()
        currentList.add(ChatMessage(MessageSender.USER, cleanQuestion))
        chatMessages.value = currentList
        isChatLoading.value = true

        viewModelScope.launch {
            val answer = when {
                // 1. Try Gemini first
                isApiKeyConfigured() -> {
                    val geminiResult = geminiRepository.chatWithCall(
                        transcript = recording.decodedTranscription,
                        summary = recording.decodedSummary,
                        question = cleanQuestion
                    )
                    if (geminiResult.isSuccess) {
                        geminiResult.getOrThrow()
                    } else {
                        // 2. Gemini failed → try NVIDIA
                        val nvidiaResult = nvidiaRepository?.chatWithCall(
                            transcript = recording.decodedTranscription,
                            summary = recording.decodedSummary,
                            question = cleanQuestion
                        )
                        nvidiaResult?.getOrElse {
                            LocalAnalysisEngine.answerCallQuestionLocally(
                                recording.decodedTranscription, recording.decodedSummary, cleanQuestion
                            )
                        } ?: LocalAnalysisEngine.answerCallQuestionLocally(
                            recording.decodedTranscription, recording.decodedSummary, cleanQuestion
                        )
                    }
                }
                // 2. No Gemini → try NVIDIA
                isNvidiaKeyConfigured() -> {
                    val nvidiaResult = nvidiaRepository?.chatWithCall(
                        transcript = recording.decodedTranscription,
                        summary = recording.decodedSummary,
                        question = cleanQuestion
                    )
                    nvidiaResult?.getOrElse {
                        LocalAnalysisEngine.answerCallQuestionLocally(
                            recording.decodedTranscription, recording.decodedSummary, cleanQuestion
                        )
                    } ?: LocalAnalysisEngine.answerCallQuestionLocally(
                        recording.decodedTranscription, recording.decodedSummary, cleanQuestion
                    )
                }
                // 3. No keys → on-device
                else -> LocalAnalysisEngine.answerCallQuestionLocally(
                    recording.decodedTranscription, recording.decodedSummary, cleanQuestion
                )
            }

            chatMessages.value = chatMessages.value + ChatMessage(MessageSender.AI, answer)
            isChatLoading.value = false
        }
    }

    // --- Audio Playback Methods ---
    fun toggleAudioPlay(context: Context, recording: Recording) {
        val uriStr = recording.sourceUri
        if (uriStr.isNullOrBlank()) {
            Toast.makeText(context, "No audio file associated with this recording.", Toast.LENGTH_SHORT).show()
            return
        }
        audioPlayer.playOrPause(context, recording.id, Uri.parse(uriStr))
    }

    fun seekAudio(positionMs: Int) {
        audioPlayer.seekTo(positionMs)
    }

    fun checkForUpdates(manual: Boolean = false) {
        if (isCheckingUpdate.value) return

        viewModelScope.launch {
            isCheckingUpdate.value = true
            val currentVersion = BuildConfig.VERSION_NAME

            gitHubUpdateRepository.checkForUpdate(
                currentVersion = currentVersion,
                skippedVersion = skippedUpdateVersion.value
            )
                .onSuccess { info ->
                    if (info.hasUpdate) {
                        updateInfo.value = info
                    } else if (manual) {
                        updateStatusMessage.value = "✅ App is up to date (v$currentVersion)"
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

    /** Permanently dismiss the update dialog — don't show again for this version */
    fun dismissUpdateDialog() {
        updateInfo.value = null
    }

    /** Skip this specific version — won't be shown again until a newer release */
    fun skipThisUpdate() {
        val version = updateInfo.value?.latestVersionName
        if (version != null) {
            skippedUpdateVersion.value = version
        }
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
            updateStatusMessage.value = "On-Device Local AI Mode (Zero API Key required)."
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
            val modeLabel = if (isApiKeyConfigured()) "Gemini AI" else "On-Device AI"
            syncStatus.value = "Preparing recordings ($modeLabel)..."
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

                    audioFiles.sortByDescending { it.lastModified }

                    val targetFiles = if (limit > 0 && limit < audioFiles.size) {
                        audioFiles.take(limit)
                    } else {
                        audioFiles
                    }

                    syncTotalCount.value = targetFiles.size
                    syncStatus.value = "Checking ${targetFiles.size} recordings ($modeLabel)..."

                    var processedCount = 0
                    var skippedCount = 0
                    var errorCount = 0

                    for ((index, fileInfo) in targetFiles.withIndex()) {
                        if (!isActive) break

                        syncProgress.value = (index.toFloat() / targetFiles.size.toFloat()).coerceIn(0f, 1f)

                        val existing = repository.getByUri(fileInfo.uri.toString())
                        if (existing == null) {
                            syncStatus.value = "Analyzing (${index + 1}/${targetFiles.size}): ${fileInfo.name}"
                            val processResult = processAudioFile(appContext, fileInfo.uri, fileInfo.name, fileInfo.mimeType, fileInfo.size)

                            if (processResult.isSuccess) {
                                processedCount++
                                syncProcessedCount.value = processedCount
                            } else {
                                errorCount++
                                syncErrorCount.value = errorCount
                                val errorMsg = processResult.exceptionOrNull()?.message ?: "Processing error"
                                syncStatus.value = "Note on ${fileInfo.name}: $errorMsg"
                                kotlinx.coroutines.delay(800)
                            }
                        } else {
                            skippedCount++
                        }
                    }

                    syncProgress.value = 1f
                    val summaryMessage = when {
                        errorCount > 0 && processedCount == 0 -> "Sync finished with $errorCount note(s). Skipped $skippedCount existing."
                        errorCount > 0 -> "Processed $processedCount new file(s) ($errorCount fallback, $skippedCount existing)."
                        processedCount > 0 -> "Sync complete! Successfully analyzed $processedCount recording(s)."
                        else -> "All ${targetFiles.size} recordings are already up to date."
                    }
                    syncStatus.value = summaryMessage

                } catch (t: Throwable) {
                    if (t !is kotlinx.coroutines.CancellationException) {
                        syncStatus.value = "Sync failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
                    }
                } finally {
                    kotlinx.coroutines.delay(2500)
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

    private val MAX_FILE_SIZE_GEMINI = 15L * 1024 * 1024  // 15 MB — Gemini inline limit
    private val MAX_FILE_SIZE_NVIDIA = 25L * 1024 * 1024  // 25 MB — NVIDIA ASR limit

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

            var transcription = ""
            var summary = ""

            // ── Step 1: Try Gemini (primary, cloud, best quality) ──────────────
            if (isApiKeyConfigured() && fileSize <= MAX_FILE_SIZE_GEMINI) {
                val bytes = readAudioBytes(context, uri, MAX_FILE_SIZE_GEMINI)
                if (bytes != null) {
                    val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val geminiResult = geminiRepository.transcribeAndSummarizeAudio(base64Audio, resolvedMime)
                    if (geminiResult.isSuccess) {
                        val pair = geminiResult.getOrThrow()
                        transcription = pair.first
                        summary = pair.second
                    }
                    // If Gemini failed with quota, we fall through to NVIDIA below
                }
            }

            // ── Step 2: Try NVIDIA (fallback when Gemini unavailable/quota) ──
            if (transcription.isBlank() && isNvidiaKeyConfigured() && fileSize <= MAX_FILE_SIZE_NVIDIA) {
                val bytes = readAudioBytes(context, uri, MAX_FILE_SIZE_NVIDIA)
                if (bytes != null) {
                    // ASR: get transcript
                    val asrResult = nvidiaRepository?.transcribeAudio(bytes, fileName, resolvedMime)
                    if (asrResult?.isSuccess == true) {
                        transcription = asrResult.getOrThrow()
                        // LLM: summarize transcript
                        val sumResult = nvidiaRepository.summarizeTranscript(transcription, fileName)
                        summary = sumResult.getOrElse { "Summary unavailable." }
                    }
                }
            }

            // ── Step 3: Local on-device fallback ─────────────────────────────
            if (transcription.isBlank()) {
                val (localTrans, localSum) = LocalAnalysisEngine.analyzeLocally("", fileName)
                transcription = localTrans
                summary = localSum
            }

            val recording = Recording(
                title = fileName,
                contentEncrypted = SimpleEncryption.encrypt(transcription),
                summaryEncrypted = SimpleEncryption.encrypt(summary),
                sourceUri = uri.toString()
            )
            repository.insert(recording)
            Result.success(Unit)
        } catch (t: Throwable) {
            // Absolute safety net — NEVER lose a recording entry
            try {
                val (localTrans, localSum) = LocalAnalysisEngine.analyzeLocally("", fileName)
                repository.insert(Recording(
                    title = fileName,
                    contentEncrypted = SimpleEncryption.encrypt(localTrans),
                    summaryEncrypted = SimpleEncryption.encrypt(localSum),
                    sourceUri = uri.toString()
                ))
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(t)
            }
        }
    }

    fun deleteRecording(id: Int) {
        viewModelScope.launch {
            if (audioPlayer.playingRecordingId.value == id) {
                audioPlayer.stop()
            }
            repository.deleteById(id)
        }
    }

    fun syncToCalendar(context: Context, recording: Recording) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, "Call: ${CallMetadataParser.cleanCallTitle(recording.title)}")
            putExtra(CalendarContract.Events.DESCRIPTION, "Summary:\n${recording.decodedSummary}\n\nTranscription:\n${recording.decodedTranscription}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No calendar application available.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}

class CallViewModelFactory(
    private val repository: RecordingRepository,
    private val geminiRepository: GeminiRepository,
    private val gitHubUpdateRepository: GitHubUpdateRepository = GitHubUpdateRepository(),
    private val apiKeyManager: ApiKeyManager? = null,
    private val nvidiaRepository: NvidiaRepository? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CallViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CallViewModel(
                repository, geminiRepository, gitHubUpdateRepository, apiKeyManager, nvidiaRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
