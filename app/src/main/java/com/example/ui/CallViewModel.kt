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
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

import com.example.data.AutoAnalyzeMode
import com.example.data.CallPreferencesManager
import com.example.data.CallerProfile
import com.example.data.CallerProfileBuilder
import com.example.data.CommitmentExtractor
import com.example.sync.CallSyncWorker
import com.example.sync.NotificationHelper
import com.example.sync.SyncLock

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
    private val nvidiaRepository: NvidiaRepository? = null,
    private val preferencesManager: CallPreferencesManager? = null
) : ViewModel() {

    val searchQuery = MutableStateFlow("")
    val isSyncing = MutableStateFlow(false)
    val syncStatus = MutableStateFlow("")
    val syncProgress = MutableStateFlow(0f)
    val syncProcessedCount = MutableStateFlow(0)
    val syncTotalCount = MutableStateFlow(0)
    val syncErrorCount = MutableStateFlow(0)

    val showApiKeyDialog = MutableStateFlow(false)
    val showRulesDialog = MutableStateFlow(false)
    val selectedFolderForLimit = MutableStateFlow<Uri?>(null)
    val folderTotalRecordings = MutableStateFlow(0)
    val folderPendingRecordings = MutableStateFlow(0)

    // Auto-Analyze & Sync Rule States
    val autoAnalyzeMode = MutableStateFlow(preferencesManager?.getAutoAnalyzeMode() ?: AutoAnalyzeMode.UNKNOWN_ONLY)
    val autoAnalyzeTargets = MutableStateFlow(preferencesManager?.getAutoAnalyzeTargets() ?: emptySet())
    val autoSyncEnabled = MutableStateFlow(preferencesManager?.isAutoSyncEnabled() ?: true)
    val commitmentRemindersEnabled = MutableStateFlow(preferencesManager?.isCommitmentRemindersEnabled() ?: true)
    val completedActionItemKeys = MutableStateFlow<Set<String>>(preferencesManager?.getAllCompletedActionItems() ?: emptySet())

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

    // Caller Profile State
    val selectedCallerProfileKey = MutableStateFlow<String?>(null)

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

    fun dismissRulesDialog() {
        showRulesDialog.value = false
    }

    // --- Auto-Analyze Rules & Preferences ---
    fun setAutoAnalyzeMode(mode: AutoAnalyzeMode) {
        preferencesManager?.setAutoAnalyzeMode(mode)
        autoAnalyzeMode.value = mode
    }

    fun addAutoAnalyzeTarget(target: String) {
        preferencesManager?.addAutoAnalyzeTarget(target)
        autoAnalyzeTargets.value = preferencesManager?.getAutoAnalyzeTargets() ?: emptySet()
    }

    fun removeAutoAnalyzeTarget(target: String) {
        preferencesManager?.removeAutoAnalyzeTarget(target)
        autoAnalyzeTargets.value = preferencesManager?.getAutoAnalyzeTargets() ?: emptySet()
    }

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        preferencesManager?.setAutoSyncEnabled(enabled)
        autoSyncEnabled.value = enabled
        if (enabled) {
            CallSyncWorker.schedulePeriodicSync(context)
        } else {
            CallSyncWorker.cancelPeriodicSync(context)
        }
    }

    fun setCommitmentRemindersEnabled(enabled: Boolean) {
        preferencesManager?.setCommitmentRemindersEnabled(enabled)
        commitmentRemindersEnabled.value = enabled
    }

    fun isActionItemCompleted(recordingId: Int, itemText: String): Boolean {
        val key = "${recordingId}_${itemText.hashCode()}"
        return completedActionItemKeys.value.contains(key) ||
            (preferencesManager?.isActionItemCompleted(recordingId, itemText) == true)
    }

    fun toggleActionItem(recordingId: Int, itemText: String) {
        val currentlyCompleted = isActionItemCompleted(recordingId, itemText)
        val newStatus = !currentlyCompleted
        preferencesManager?.setActionItemCompleted(recordingId, itemText, newStatus)
        val key = "${recordingId}_${itemText.hashCode()}"
        val updated = completedActionItemKeys.value.toMutableSet()
        if (newStatus) updated.add(key) else updated.remove(key)
        completedActionItemKeys.value = updated
    }

    // --- Chat With Call Methods ---
    fun openChat(recording: Recording) {
        activeChatRecording.value = recording
        val hasRealTranscript = recording.decodedTranscription.isNotBlank() &&
            !recording.decodedTranscription.contains("Transcription requires") &&
            !recording.decodedTranscription.contains("API Key") &&
            !recording.decodedTranscription.contains("On-Device Speech Analysis")

        val mode = when {
            isApiKeyConfigured() -> "Gemini AI"
            isNvidiaKeyConfigured() -> "NVIDIA Llama AI"
            else -> "On-Device AI"
        }

        val initialText = if (hasRealTranscript) {
            "Hi! I am your call assistant for '${CallMetadataParser.cleanCallTitle(recording.title)}' ($mode).\nAsk me anything about what was discussed, promised, or scheduled in this call."
        } else if (isApiKeyConfigured()) {
            "Hi! This call was saved earlier before your Gemini API key was active.\n\n⚡ Tap '⚡ Transcribe' at the top of this chat (or on the call card) so Gemini can listen to the audio recording!"
        } else {
            "Hi! This call has no AI transcript yet. Tap 🔑 in the top bar to set your free Gemini API key."
        }

        chatMessages.value = listOf(
            ChatMessage(
                sender = MessageSender.AI,
                text = initialText
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

        val hasRealTranscript = recording.decodedTranscription.isNotBlank() &&
            !recording.decodedTranscription.contains("Transcription requires") &&
            !recording.decodedTranscription.contains("API Key") &&
            !recording.decodedTranscription.contains("On-Device Speech Analysis")

        if (!hasRealTranscript) {
            val reply = if (isApiKeyConfigured() && recording.sourceUri != null) {
                "⚠️ This call has not been transcribed with Gemini yet. Please tap '⚡ Transcribe' at the top of this chat (or on the call card) so I can listen to the recording and answer your question!"
            } else {
                LocalAnalysisEngine.answerCallQuestionLocally(
                    recording.decodedTranscription, recording.decodedSummary, cleanQuestion
                )
            }
            chatMessages.value = chatMessages.value + ChatMessage(MessageSender.AI, reply)
            isChatLoading.value = false
            return
        }

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
        val distinctList = list.distinctBy { it.sourceUri ?: it.id.toString() }
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            distinctList
        } else {
            distinctList.filter {
                it.title.contains(trimmedQuery, ignoreCase = true) ||
                it.decodedTranscription.contains(trimmedQuery, ignoreCase = true) ||
                it.decodedSummary.contains(trimmedQuery, ignoreCase = true)
            }
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Caller Profiles: groups all calls by caller/contact
    val callerProfiles = combine(recordings, completedActionItemKeys, autoAnalyzeTargets) { recs, completedKeys, targets ->
        CallerProfileBuilder.buildProfiles(recs, completedKeys, targets)
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedCallerProfile = combine(callerProfiles, selectedCallerProfileKey) { profiles, key ->
        if (key == null) null else profiles.find { it.callerKey == key }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun openCallerProfile(callerKey: String) {
        selectedCallerProfileKey.value = callerKey
    }

    fun openCallerProfileForRecording(recording: Recording) {
        val meta = CallMetadataParser.parse(recording.title)
        val digits = meta.cleanTitle.filter { it.isDigit() }
        val key = if (digits.length >= 6 && meta.cleanTitle.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }) {
            digits
        } else {
            meta.cleanTitle.trim().lowercase(java.util.Locale.ROOT)
        }
        openCallerProfile(key)
    }

    fun closeCallerProfile() {
        selectedCallerProfileKey.value = null
    }

    fun toggleCallerAutoAnalyze(callerTarget: String) {
        val targets = autoAnalyzeTargets.value
        val isAlready = targets.any { it.equals(callerTarget, ignoreCase = true) }
        if (isAlready) {
            removeAutoAnalyzeTarget(callerTarget)
        } else {
            addAutoAnalyzeTarget(callerTarget)
        }
    }

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

        // Persist folder URI for background sync & auto-detection
        preferencesManager?.setPersistedFolderUri(treeUri.toString())
        CallSyncWorker.schedulePeriodicSync(context)

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

                    // Count how many actually need analysis vs already analyzed
                    var pendingCount = 0
                    for (file in audioFiles) {
                        val existing = repository.getByUri(file.uri.toString())
                        val hasReal = existing != null &&
                            existing.decodedTranscription.isNotBlank() &&
                            !existing.decodedTranscription.contains("Transcription requires") &&
                            !existing.decodedTranscription.contains("On-Device Speech Analysis")
                        if (!hasReal) {
                            pendingCount++
                        }
                    }

                    isSyncing.value = false
                    folderTotalRecordings.value = audioFiles.size
                    folderPendingRecordings.value = pendingCount
                    selectedFolderForLimit.value = treeUri
                } catch (t: Throwable) {
                    syncStatus.value = "Scan failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
                    isSyncing.value = false
                }
            }
        }
    }

    /** Automatically scans persisted folder for new recordings when app starts or resumes */
    fun checkForNewRecordingsOnResume(context: Context) {
        val folderUriStr = preferencesManager?.getPersistedFolderUri() ?: return
        val treeUri = try { Uri.parse(folderUriStr) } catch (_: Exception) { return }
        val appContext = context.applicationContext

        viewModelScope.launch(Dispatchers.IO) {
            SyncLock.mutex.withLock {
                try {
                    val audioFiles = mutableListOf<AudioFileInfo>()
                    val treeDocId = try {
                        DocumentsContract.getTreeDocumentId(treeUri)
                    } catch (_: Exception) {
                        DocumentsContract.getDocumentId(treeUri)
                    }

                    scanDirectoryRecursively(appContext, treeUri, treeDocId, audioFiles, currentDepth = 0, maxDepth = 3)

                    var newDetected = 0
                    for (file in audioFiles) {
                        if (file.size <= 0L) continue

                        val existing = repository.getByUri(file.uri.toString())
                        if (existing == null) {
                            val placeholder = Recording(
                                id = 0,
                                title = file.name,
                                contentEncrypted = SimpleEncryption.encrypt(""),
                                summaryEncrypted = SimpleEncryption.encrypt("Pending AI Analysis\n\nTap ⚡ Transcribe & Analyze Call to view insights."),
                                timestamp = if (file.lastModified > 0) file.lastModified else System.currentTimeMillis(),
                                sourceUri = file.uri.toString()
                            )
                            val insertedId = repository.insert(placeholder).toInt()
                            newDetected++

                            val mode = preferencesManager?.getAutoAnalyzeMode() ?: AutoAnalyzeMode.UNKNOWN_ONLY
                            val targets = preferencesManager?.getAutoAnalyzeTargets() ?: emptySet()
                            val shouldAuto = CallMetadataParser.matchesAutoAnalyzeRule(file.name, mode, targets)

                            if (shouldAuto && (isApiKeyConfigured() || isNvidiaKeyConfigured())) {
                                processAudioFile(
                                    context = appContext,
                                    uri = file.uri,
                                    fileName = file.name,
                                    mimeType = file.mimeType,
                                    fileSize = file.size,
                                    existingId = insertedId
                                )
                                val updatedRec = repository.getById(insertedId)
                                if (updatedRec != null && preferencesManager?.isCommitmentRemindersEnabled() == true) {
                                    val actions = CommitmentExtractor.extractActionItems(updatedRec.decodedSummary)
                                    val dates = CommitmentExtractor.extractDates(updatedRec.decodedSummary)
                                    NotificationHelper.notifyCommitments(appContext, file.name, actions, dates, insertedId)
                                }
                                NotificationHelper.notifyNewCallDetected(appContext, file.name, insertedId, isAutoAnalyzed = true)
                            }
                        }
                    }

                    if (newDetected > 0) {
                        withContext(Dispatchers.Main) {
                            updateStatusMessage.value = "Detected $newDetected new call recording(s)!"
                        }
                    }
                } catch (_: Exception) {
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
                SyncLock.mutex.withLock {
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
                            return@withLock
                        }

                        audioFiles.sortByDescending { it.lastModified }

                        // Strictly partition into files needing analysis vs already analyzed
                        val filesNeedingAnalysis = mutableListOf<AudioFileInfo>()
                        var alreadyAnalyzedCount = 0

                        for (file in audioFiles) {
                            if (file.size <= 0L) continue

                            val existing = repository.getByUri(file.uri.toString())
                            val hasRealTranscript = existing != null &&
                                existing.decodedTranscription.isNotBlank() &&
                                !existing.decodedTranscription.contains("Transcription requires") &&
                                !existing.decodedTranscription.contains("On-Device Speech Analysis")

                            if (hasRealTranscript) {
                                alreadyAnalyzedCount++
                            } else {
                                filesNeedingAnalysis.add(file)
                            }
                        }

                        // If all files in folder are already analyzed, exit immediately with zero API calls & zero duplicates
                        if (filesNeedingAnalysis.isEmpty()) {
                            syncStatus.value = "All ${audioFiles.size} recordings are already analyzed & up to date! (0 duplicates)"
                            isSyncing.value = false
                            return@withLock
                        }

                    // Apply limit strictly to the unanalyzed/pending files
                    val targetFiles = if (limit > 0 && limit < filesNeedingAnalysis.size) {
                        filesNeedingAnalysis.take(limit)
                    } else {
                        filesNeedingAnalysis
                    }

                    syncTotalCount.value = targetFiles.size
                    syncStatus.value = "Analyzing ${targetFiles.size} new calls ($alreadyAnalyzedCount already analyzed & excluded)..."

                    var processedCount = 0
                    var errorCount = 0

                    for ((index, fileInfo) in targetFiles.withIndex()) {
                        if (!isActive) break

                        syncProgress.value = (index.toFloat() / targetFiles.size.toFloat()).coerceIn(0f, 1f)

                        val existing = repository.getByUri(fileInfo.uri.toString())
                        val actionLabel = if (existing == null) "Analyzing" else "Re-analyzing"
                        syncStatus.value = "$actionLabel (${index + 1}/${targetFiles.size}): ${fileInfo.name}"

                        val processResult = processAudioFile(
                            appContext,
                            fileInfo.uri,
                            fileInfo.name,
                            fileInfo.mimeType,
                            fileInfo.size,
                            existingId = existing?.id
                        )

                        if (processResult.isSuccess) {
                            processedCount++
                            syncProcessedCount.value = processedCount
                            // Pacing delay between calls to stay comfortably within Google's 15 RPM free tier limit
                            if (index < targetFiles.size - 1 && isApiKeyConfigured()) {
                                kotlinx.coroutines.delay(4000)
                            }
                        } else {
                            errorCount++
                            syncErrorCount.value = errorCount
                            val err = processResult.exceptionOrNull()
                            if (err is ApiQuotaExceededException || err?.message?.contains("429") == true) {
                                syncStatus.value = "⏳ Gemini rate limit (15/min) reached. Pausing 15s to reset quota..."
                                kotlinx.coroutines.delay(15000)
                            } else {
                                val errorMsg = err?.message ?: "Processing error"
                                syncStatus.value = "Note on ${fileInfo.name}: $errorMsg"
                                kotlinx.coroutines.delay(800)
                            }
                        }
                    }

                    syncProgress.value = 1f
                    val summaryMessage = when {
                        errorCount > 0 && processedCount == 0 -> "Finished with $errorCount note(s). Excluded $alreadyAnalyzedCount already analyzed."
                        errorCount > 0 -> "Analyzed $processedCount new call(s) ($alreadyAnalyzedCount already analyzed & excluded, 0 duplicates)."
                        processedCount > 0 -> "Sync complete! Analyzed $processedCount call(s) ($alreadyAnalyzedCount excluded, 0 duplicates)."
                        else -> "All recordings are up to date ($alreadyAnalyzedCount excluded, 0 duplicates)."
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
        fileSize: Long,
        existingId: Int? = null
    ): Result<Unit> {
        return try {
            val contentResolver = context.contentResolver
            val resolvedMime = mimeType ?: contentResolver.getType(uri) ?: "audio/mp3"

            var transcription = ""
            var summary = ""
            var audioBytes: ByteArray? = null

            // ── Step 1: Try Gemini (primary, cloud audio speech-to-text + summary) ──────────────
            if (isApiKeyConfigured() && fileSize <= MAX_FILE_SIZE_GEMINI) {
                val bytes = readAudioBytes(context, uri, MAX_FILE_SIZE_GEMINI)
                audioBytes = bytes
                if (bytes != null) {
                    val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    var geminiResult = geminiRepository.transcribeAndSummarizeAudio(base64Audio, resolvedMime)

                    // If rate limit (429) hit, wait 3 seconds and retry once
                    if (geminiResult.exceptionOrNull() is ApiQuotaExceededException) {
                        kotlinx.coroutines.delay(3000)
                        geminiResult = geminiRepository.transcribeAndSummarizeAudio(base64Audio, resolvedMime)
                    }

                    if (geminiResult.isSuccess) {
                        val pair = geminiResult.getOrThrow()
                        transcription = pair.first
                        summary = pair.second
                    } else if (geminiResult.exceptionOrNull() is ApiQuotaExceededException && !isNvidiaKeyConfigured()) {
                        // Rate limit/quota exceeded and no NVIDIA key configured
                        return Result.failure(geminiResult.exceptionOrNull()!!)
                    }
                }
            }

            // ── Step 2: Try NVIDIA Canary ASR if Gemini transcription failed / unconfigured ─────────
            if (transcription.isBlank() && isNvidiaKeyConfigured() && fileSize <= MAX_FILE_SIZE_NVIDIA) {
                val bytes = audioBytes ?: readAudioBytes(context, uri, MAX_FILE_SIZE_NVIDIA)
                if (bytes != null) {
                    val asrRes = nvidiaRepository?.transcribeAudio(bytes, fileName, resolvedMime)
                    if (asrRes?.isSuccess == true) {
                        transcription = asrRes.getOrThrow()
                    }
                }
            }

            // ── Step 3: Try NVIDIA for Summarization if we have a transcript but no summary ────────
            if (transcription.isNotBlank() && summary.isBlank() && isNvidiaKeyConfigured()) {
                val sumResult = nvidiaRepository?.summarizeTranscript(transcription, fileName)
                if (sumResult?.isSuccess == true) {
                    summary = sumResult.getOrThrow()
                }
            }

            // ── Step 4: Local on-device fallback (only if not configured or general failure) ─────
            if (transcription.isBlank()) {
                val (localTrans, localSum) = LocalAnalysisEngine.analyzeLocally("", fileName)
                transcription = localTrans
                summary = localSum
            }

            val recording = Recording(
                id = existingId ?: 0,
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
                    id = existingId ?: 0,
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

    fun reanalyzeRecording(context: Context, recording: Recording) {
        val uriStr = recording.sourceUri ?: return
        if (!isApiKeyConfigured() && !isNvidiaKeyConfigured()) {
            updateStatusMessage.value = "⚠️ Please configure an API Key (Gemini or NVIDIA) first."
            return
        }

        viewModelScope.launch {
            updateStatusMessage.value = "Analyzing '${CallMetadataParser.cleanCallTitle(recording.title)}'..."
            val result = withContext(Dispatchers.IO) {
                try {
                    val uri = Uri.parse(uriStr)
                    val fileSize = try {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                    } catch (_: Exception) { 0L }
                    val mime = context.contentResolver.getType(uri) ?: "audio/mp3"
                    processAudioFile(
                        context = context.applicationContext,
                        uri = uri,
                        fileName = recording.title,
                        mimeType = mime,
                        fileSize = fileSize,
                        existingId = recording.id
                    )
                } catch (e: Exception) { Result.failure(e) }
            }

            if (result.isSuccess) {
                val updated = withContext(Dispatchers.IO) { repository.getByUri(uriStr) }
                if (activeChatRecording.value?.id == recording.id && updated != null) {
                    activeChatRecording.value = updated
                    chatMessages.value = chatMessages.value + ChatMessage(
                        MessageSender.AI,
                        "✅ Call transcribed successfully with Gemini! I now have the full verbatim transcript. Ask me anything about this call!"
                    )
                }
                if (updated != null && preferencesManager?.isCommitmentRemindersEnabled() != false) {
                    val actions = CommitmentExtractor.extractActionItems(updated.decodedSummary)
                    val dates = CommitmentExtractor.extractDates(updated.decodedSummary)
                    if (actions.isNotEmpty() || dates.isNotEmpty()) {
                        NotificationHelper.notifyCommitments(
                            context = context.applicationContext,
                            callTitle = updated.title,
                            actionItems = actions,
                            dates = dates,
                            recordingId = updated.id
                        )
                    }
                }
                updateStatusMessage.value = "Analysis complete! ✅"
            } else {
                val err = result.exceptionOrNull()
                if (err is ApiQuotaExceededException || err?.message?.contains("429") == true) {
                    updateStatusMessage.value = "⏳ Gemini rate limit reached (15 calls/min). Resets in 60s. Please wait!"
                } else {
                    updateStatusMessage.value = "Could not analyze: ${err?.localizedMessage ?: "Unknown error"}"
                }
            }
        }
    }

    fun deleteRecording(id: Int) {
        viewModelScope.launch {
            if (audioPlayer.playingRecordingId.value == id) {
                audioPlayer.stop()
            }
            if (activeChatRecording.value?.id == id) {
                closeChat()
            }
            repository.deleteById(id)
        }
    }

    fun syncToCalendar(context: Context, recording: Recording) {
        val cleanTitle = CallMetadataParser.cleanCallTitle(recording.title)
        val safeSummary = recording.decodedSummary.take(1500)
        val safeTranscript = recording.decodedTranscription.take(1500)
        val description = buildString {
            if (safeSummary.isNotBlank()) {
                append("Summary:\n$safeSummary\n\n")
            }
            if (safeTranscript.isNotBlank()) {
                append("Transcription:\n$safeTranscript")
            }
        }.trim()

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, "Call: $cleanTitle")
            putExtra(CalendarContract.Events.DESCRIPTION, description)
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
    private val nvidiaRepository: NvidiaRepository? = null,
    private val preferencesManager: CallPreferencesManager? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CallViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CallViewModel(
                repository, geminiRepository, gitHubUpdateRepository, apiKeyManager, nvidiaRepository, preferencesManager
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
