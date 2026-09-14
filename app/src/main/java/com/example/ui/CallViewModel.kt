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
import com.example.data.TranscriptionValidator
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

import com.example.data.AudioDurationHelper
import com.example.data.AutoAnalyzeMode
import com.example.data.CallPreferencesManager
import com.example.data.CallerProfile
import com.example.data.CallerProfileBuilder
import com.example.data.CommitmentExtractor
import com.example.data.PreferredEngine
import com.example.data.SpokenLanguage
import com.example.network.CloudflareWorkerRepository
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
    private val preferencesManager: CallPreferencesManager? = null,
    private val cloudflareRepository: CloudflareWorkerRepository? = null
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
    val analyzingRecordingId = MutableStateFlow<Int?>(null)
    private val skippedUpdateVersion = MutableStateFlow<String?>(null)

    // In-App Native Audio Player
    val audioPlayer = AudioPlayerManager(viewModelScope)
    private val _recordingDurations = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val recordingDurations: StateFlow<Map<Int, Int>> = _recordingDurations.asStateFlow()

    fun prefetchDurations(context: Context, recs: List<Recording>) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val newDurations = mutableMapOf<Int, Int>()
            for (rec in recs) {
                if (rec.durationMs > 0) {
                    if (!_recordingDurations.value.containsKey(rec.id)) {
                        newDurations[rec.id] = rec.durationMs
                    }
                    rec.sourceUri?.let { AudioDurationHelper.cacheDuration(it, rec.durationMs) }
                } else if (!rec.sourceUri.isNullOrBlank()) {
                    val cached = AudioDurationHelper.getCachedDuration(rec.sourceUri)
                    val duration = if (cached > 0) {
                        cached
                    } else {
                        val uri = try { Uri.parse(rec.sourceUri) } catch (_: Exception) { null }
                        if (uri != null) AudioDurationHelper.getDurationMs(appContext, uri) else 0
                    }
                    if (duration > 0) {
                        newDurations[rec.id] = duration
                        repository.updateDuration(rec.id, duration)
                    }
                }
            }
            if (newDurations.isNotEmpty()) {
                _recordingDurations.update { current -> current + newDurations }
            }
        }
    }

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

    // AI Engine Preferences & Cloudflare Worker State
    val preferredEngine = MutableStateFlow(preferencesManager?.getPreferredEngine() ?: PreferredEngine.AUTO)
    val spokenLanguage = MutableStateFlow(preferencesManager?.getSpokenLanguage() ?: SpokenLanguage.AUTO)
    val cloudflareWorkerUrl = MutableStateFlow(apiKeyManager?.getCloudflareWorkerUrl() ?: "")
    val cloudflareWorkerToken = MutableStateFlow(apiKeyManager?.getCloudflareWorkerToken() ?: "")

    fun isApiKeyConfigured(): Boolean = geminiRepository.isApiKeyConfigured()

    fun isNvidiaKeyConfigured(): Boolean = nvidiaRepository?.isApiKeyConfigured() == true

    fun isCloudflareConfigured(): Boolean = cloudflareRepository?.isConfigured() == true || (apiKeyManager?.isCloudflareConfigured() == true)

    fun getApiKey(): String = apiKeyManager?.getApiKey() ?: BuildConfig.GEMINI_API_KEY

    fun getNvidiaApiKey(): String = apiKeyManager?.getNvidiaApiKey() ?: ""

    fun getCloudflareUrl(): String = apiKeyManager?.getCloudflareWorkerUrl() ?: ""

    fun getCloudflareToken(): String = apiKeyManager?.getCloudflareWorkerToken() ?: ""

    fun setPreferredEngine(engine: PreferredEngine) {
        preferencesManager?.setPreferredEngine(engine)
        preferredEngine.value = engine
        updateStatusMessage.value = "Engine: ${engine.displayName}"
    }

    fun setSpokenLanguage(language: SpokenLanguage) {
        preferencesManager?.setSpokenLanguage(language)
        spokenLanguage.value = language
        updateStatusMessage.value = "Language: ${language.displayName}"
    }

    fun saveApiKey(newKey: String) {
        apiKeyManager?.setApiKey(newKey)
        showApiKeyDialog.value = false
        val mode = when {
            newKey.isNotBlank() -> "Gemini API Key saved ✅"
            isCloudflareConfigured() -> "Gemini key cleared. Cloudflare Worker still active."
            isNvidiaKeyConfigured() -> "Gemini key cleared. NVIDIA fallback still active."
            else -> "API Key cleared — using On-Device mode."
        }
        updateStatusMessage.value = mode
    }

    fun saveNvidiaApiKey(newKey: String) {
        apiKeyManager?.setNvidiaApiKey(newKey)
        updateStatusMessage.value = if (newKey.isNotBlank()) "NVIDIA API Key saved ✅" else "NVIDIA key cleared."
    }

    fun saveCloudflareConfig(url: String, token: String) {
        val cleanUrl = url.trim().trimEnd('/')
        val cleanToken = token.trim()
        apiKeyManager?.setCloudflareWorkerUrl(cleanUrl)
        apiKeyManager?.setCloudflareWorkerToken(cleanToken)
        cloudflareWorkerUrl.value = cleanUrl
        cloudflareWorkerToken.value = cleanToken
        updateStatusMessage.value = if (cleanUrl.isNotBlank()) "Cloudflare Worker saved ✅" else "Cloudflare Worker cleared."
    }

    fun saveAllEngineSettings(
        engine: PreferredEngine,
        cloudflareUrl: String,
        cloudflareToken: String,
        nvidiaKey: String,
        geminiKey: String,
        language: SpokenLanguage = SpokenLanguage.AUTO
    ) {
        preferencesManager?.setPreferredEngine(engine)
        preferredEngine.value = engine

        preferencesManager?.setSpokenLanguage(language)
        spokenLanguage.value = language

        val cleanUrl = cloudflareUrl.trim().trimEnd('/')
        val cleanToken = cloudflareToken.trim()
        apiKeyManager?.setCloudflareWorkerUrl(cleanUrl)
        apiKeyManager?.setCloudflareWorkerToken(cleanToken)
        cloudflareWorkerUrl.value = cleanUrl
        cloudflareWorkerToken.value = cleanToken

        apiKeyManager?.setNvidiaApiKey(nvidiaKey)
        apiKeyManager?.setApiKey(geminiKey)

        showApiKeyDialog.value = false
        updateStatusMessage.value = "Settings saved ✅ Engine: ${engine.displayName} (${language.displayName})"
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

    fun testCloudflareWorker(url: String, token: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val tempRepo = cloudflareRepository ?: CloudflareWorkerRepository(
                urlProvider = { url },
                tokenProvider = { token }
            )
            val res = tempRepo.testConnection(testUrl = url, testToken = token)
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

    private var chatJob: Job? = null

    private fun hasValidTranscript(transcription: String): Boolean {
        return TranscriptionValidator.hasValidTranscript(transcription)
    }

    // --- Chat With Call Methods ---
    fun openChat(recording: Recording) {
        activeChatRecording.value = recording
        val hasRealTranscript = hasValidTranscript(recording.decodedTranscription)

        val mode = when {
            preferredEngine.value == PreferredEngine.CLOUDFLARE && isCloudflareConfigured() -> "Cloudflare AI"
            preferredEngine.value == PreferredEngine.NVIDIA && isNvidiaKeyConfigured() -> "NVIDIA Llama AI"
            preferredEngine.value == PreferredEngine.GEMINI && isApiKeyConfigured() -> "Gemini AI"
            isCloudflareConfigured() -> "Cloudflare AI"
            isApiKeyConfigured() -> "Gemini AI"
            isNvidiaKeyConfigured() -> "NVIDIA Llama AI"
            else -> "On-Device AI"
        }

        val initialText = if (hasRealTranscript) {
            "Hi! I am your call assistant for '${CallMetadataParser.cleanCallTitle(recording.title)}' ($mode).\nAsk me anything about what was discussed, promised, or scheduled in this call."
        } else if (isApiKeyConfigured() || isCloudflareConfigured() || isNvidiaKeyConfigured()) {
            "Hi! This call was saved earlier before your AI engine was active.\n\n⚡ Tap '⚡ Transcribe' at the top of this chat (or on the call card) to analyze the audio recording!"
        } else {
            "Hi! This call has no AI transcript yet. Tap 🔑 in the top bar to configure Cloudflare Worker, Gemini, or NVIDIA."
        }

        chatMessages.value = listOf(
            ChatMessage(
                sender = MessageSender.AI,
                text = initialText
            )
        )
    }

    fun closeChat() {
        chatJob?.cancel()
        chatJob = null
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

        val hasRealTranscript = hasValidTranscript(recording.decodedTranscription)

        if (!hasRealTranscript) {
            val reply = if ((isApiKeyConfigured() || isCloudflareConfigured()) && recording.sourceUri != null) {
                "⚠️ This call has not been transcribed yet. Please tap '⚡ Transcribe' at the top of this chat (or on the call card) so I can listen to the recording and answer your question!"
            } else {
                LocalAnalysisEngine.answerCallQuestionLocally(
                    recording.decodedTranscription, recording.decodedSummary, cleanQuestion
                )
            }
            chatMessages.value = chatMessages.value + ChatMessage(MessageSender.AI, reply)
            isChatLoading.value = false
            return
        }

        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            try {
                val currentEngine = preferredEngine.value
                val answer = when {
                    // 1. Explicit On-Device offline selection
                    currentEngine == PreferredEngine.ON_DEVICE -> {
                        LocalAnalysisEngine.answerCallQuestionLocally(
                            recording.decodedTranscription, recording.decodedSummary, cleanQuestion
                        )
                    }

                    // 2. Cloudflare Worker preferred (only if user explicitly selected CLOUDFLARE)
                    currentEngine == PreferredEngine.CLOUDFLARE && isCloudflareConfigured() -> {
                        val cfRes = cloudflareRepository?.chatWithCall(recording.decodedTranscription, recording.decodedSummary, cleanQuestion)
                        cfRes?.getOrElse {
                            LocalAnalysisEngine.answerCallQuestionLocally(recording.decodedTranscription, recording.decodedSummary, cleanQuestion)
                        } ?: LocalAnalysisEngine.answerCallQuestionLocally(recording.decodedTranscription, recording.decodedSummary, cleanQuestion)
                    }

                    // 3. NVIDIA NIM Llama 3.1 70B (Primary for chat whenever NVIDIA key is configured!)
                    isNvidiaKeyConfigured() -> {
                        val nvidiaResult = nvidiaRepository?.chatWithCall(
                            transcript = recording.decodedTranscription,
                            summary = recording.decodedSummary,
                            question = cleanQuestion
                        )
                        nvidiaResult?.getOrElse {
                            if (isApiKeyConfigured()) {
                                geminiRepository.chatWithCall(recording.decodedTranscription, recording.decodedSummary, cleanQuestion).getOrDefault("No answer")
                            } else {
                                LocalAnalysisEngine.answerCallQuestionLocally(recording.decodedTranscription, recording.decodedSummary, cleanQuestion)
                            }
                        } ?: LocalAnalysisEngine.answerCallQuestionLocally(recording.decodedTranscription, recording.decodedSummary, cleanQuestion)
                    }

                    // 4. Gemini (if Gemini key configured and no NVIDIA key)
                    isApiKeyConfigured() -> {
                        val geminiResult = geminiRepository.chatWithCall(
                            transcript = recording.decodedTranscription,
                            summary = recording.decodedSummary,
                            question = cleanQuestion
                        )
                        geminiResult.getOrElse {
                            LocalAnalysisEngine.answerCallQuestionLocally(recording.decodedTranscription, recording.decodedSummary, cleanQuestion)
                        }
                    }

                    // 5. Cloudflare Worker fallback (only if neither NVIDIA nor Gemini configured)
                    isCloudflareConfigured() -> {
                        val cfResult = cloudflareRepository?.chatWithCall(
                            transcript = recording.decodedTranscription,
                            summary = recording.decodedSummary,
                            question = cleanQuestion
                        )
                        cfResult?.getOrElse {
                            LocalAnalysisEngine.answerCallQuestionLocally(recording.decodedTranscription, recording.decodedSummary, cleanQuestion)
                        } ?: LocalAnalysisEngine.answerCallQuestionLocally(recording.decodedTranscription, recording.decodedSummary, cleanQuestion)
                    }

                    // 6. On-device fallback
                    else -> LocalAnalysisEngine.answerCallQuestionLocally(
                        recording.decodedTranscription, recording.decodedSummary, cleanQuestion
                    )
                }

                chatMessages.value = chatMessages.value + ChatMessage(MessageSender.AI, answer)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                chatMessages.value = chatMessages.value + ChatMessage(
                    MessageSender.AI,
                    "⚠️ Could not answer question: ${e.localizedMessage ?: "Unknown error"}"
                )
            } finally {
                isChatLoading.value = false
            }
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
                updateInfo.value = null
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

    private var hasRepairedTimestamps = false

    private fun repairTimestampsIfNecessary(recs: List<Recording>) {
        if (hasRepairedTimestamps) return
        hasRepairedTimestamps = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var repairedCount = 0
                for (rec in recs) {
                    val parsed = CallMetadataParser.extractCallTimestamp(rec.title, rec.timestamp)
                    if (Math.abs(parsed - rec.timestamp) > 60_000L) {
                        repository.updateTimestamp(rec.id, parsed)
                        repairedCount++
                    }
                }
                if (repairedCount > 0) {
                    android.util.Log.i("CallScribe", "Repaired $repairedCount recording timestamps in Room to match call date from filename")
                }
            } catch (e: Throwable) {
                android.util.Log.w("CallScribe", "Timestamp repair non-fatal error: ${e.localizedMessage}")
            }
        }
    }

    val recordings = combine(repository.allRecordings, searchQuery) { list, query ->
        val distinctList = list
            .groupBy { it.sourceUri ?: it.id.toString() }
            .values
            .map { group ->
                // If there are duplicate rows for the same sourceUri, pick the one with real analysis
                group.firstOrNull { hasRealAnalysis(it) }
                    ?: group.firstOrNull { !isPlaceholderSummary(it.decodedSummary) }
                    ?: group.maxByOrNull { it.id }
                    ?: group.first()
            }
            .map { rec ->
                // Auto-repair any database entries that have real transcription but fake placeholder summary
                if (isPlaceholderSummary(rec.decodedSummary) && rec.decodedTranscription.isNotBlank() &&
                    rec.decodedTranscription != TranscriptionValidator.NO_SPEECH_DETECTED &&
                    !rec.decodedTranscription.contains("Audio Transcription Required") &&
                    !rec.decodedTranscription.contains("Transcription requires") &&
                    !rec.decodedTranscription.contains("On-Device Speech Analysis")) {
                    val (_, localSum) = LocalAnalysisEngine.analyzeLocally(rec.decodedTranscription, rec.title)
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            repository.insert(rec.copy(summaryEncrypted = SimpleEncryption.encrypt(localSum)))
                        } catch (_: Throwable) {}
                    }
                    rec.copy(summaryEncrypted = SimpleEncryption.encrypt(localSum))
                } else {
                    rec
                }
            }
            .sortedByDescending { it.timestamp }

        if (!hasRepairedTimestamps && distinctList.isNotEmpty()) {
            repairTimestampsIfNecessary(distinctList)
        }

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

    // Caller Profiles: groups all calls by caller/contact with debounce and concurrency safety
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val callerProfiles = combine(
        recordings.debounce(300L),
        completedActionItemKeys,
        autoAnalyzeTargets
    ) { recs, completedKeys, targets ->
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
        val key = CallerProfileBuilder.computeCallerKey(recording.title)
        openCallerProfile(key)
    }

    fun closeCallerProfile() {
        selectedCallerProfileKey.value = null
    }

    fun toggleCallerAutoAnalyze(callerTarget: String) {
        val cleanTarget = callerTarget.trim()
        val targets = autoAnalyzeTargets.value
        val isAlready = targets.any { it.equals(cleanTarget, ignoreCase = true) }
        if (isAlready) {
            removeAutoAnalyzeTarget(cleanTarget)
        } else {
            addAutoAnalyzeTarget(cleanTarget)
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

                    audioFiles.sortByDescending { it.effectiveTimestamp }

                    // Count how many actually need analysis vs already analyzed
                    var pendingCount = 0
                    for (file in audioFiles) {
                        val existing = repository.getByUri(file.uri.toString())
                        val hasReal = existing != null && hasValidTranscript(existing.decodedTranscription)
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
            val toAutoAnalyze = mutableListOf<Pair<AudioFileInfo, Int>>()
            var newDetected = 0

            SyncLock.mutex.withLock {
                try {
                    val audioFiles = mutableListOf<AudioFileInfo>()
                    val treeDocId = try {
                        DocumentsContract.getTreeDocumentId(treeUri)
                    } catch (_: Exception) {
                        DocumentsContract.getDocumentId(treeUri)
                    }

                    scanDirectoryRecursively(appContext, treeUri, treeDocId, audioFiles, currentDepth = 0, maxDepth = 3)
                    audioFiles.sortByDescending { it.effectiveTimestamp }

                    for (file in audioFiles) {
                        if (file.size <= 0L) continue

                        val existing = repository.getByUri(file.uri.toString())
                        if (existing == null) {
                            val duration = AudioDurationHelper.getDurationMs(appContext, file.uri)
                            val placeholder = Recording(
                                id = 0,
                                title = file.name,
                                contentEncrypted = SimpleEncryption.encrypt(""),
                                summaryEncrypted = SimpleEncryption.encrypt("Pending AI Analysis\n\nTap ⚡ Transcribe & Analyze Call to view insights."),
                                timestamp = file.effectiveTimestamp,
                                sourceUri = file.uri.toString(),
                                durationMs = duration
                            )
                            val insertedId = repository.insert(placeholder).toInt()
                            newDetected++

                            val mode = preferencesManager?.getAutoAnalyzeMode() ?: AutoAnalyzeMode.UNKNOWN_ONLY
                            val targets = preferencesManager?.getAutoAnalyzeTargets() ?: emptySet()
                            val shouldAuto = CallMetadataParser.matchesAutoAnalyzeRule(file.name, mode, targets)

                            val canAnalyze = isCloudflareConfigured() || isApiKeyConfigured() || isNvidiaKeyConfigured() || (preferredEngine.value == PreferredEngine.ON_DEVICE)
                            if (shouldAuto && canAnalyze) {
                                toAutoAnalyze.add(Pair(file, insertedId))
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            } // Mutex ends immediately after fast scan & placeholder insertion!

            if (newDetected > 0) {
                withContext(Dispatchers.Main) {
                    updateStatusMessage.value = "Detected $newDetected new call recording(s)!"
                }
            }

            // Run auto-analysis outside the mutex lock, strictly newest first!
            for ((file, insertedId) in toAutoAnalyze) {
                if (!isActive) break
                try {
                    processAudioFile(
                        context = appContext,
                        uri = file.uri,
                        fileName = file.name,
                        mimeType = file.mimeType,
                        fileSize = file.size,
                        existingId = insertedId,
                        fileLastModified = file.effectiveTimestamp
                    )
                    val updatedRec = repository.getById(insertedId)
                    if (updatedRec != null && preferencesManager?.isCommitmentRemindersEnabled() == true) {
                        val actions = CommitmentExtractor.extractActionItems(updatedRec.decodedSummary)
                        val dates = CommitmentExtractor.extractDates(updatedRec.decodedSummary)
                        NotificationHelper.notifyCommitments(appContext, file.name, actions, dates, insertedId)
                    }
                    NotificationHelper.notifyNewCallDetected(appContext, file.name, insertedId, isAutoAnalyzed = true)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun startSyncWithLimit(context: Context, limit: Int, forceReanalyzeAll: Boolean = false) {
        val appContext = context.applicationContext
        try {
            val treeUri = selectedFolderForLimit.value ?: return
            selectedFolderForLimit.value = null

            val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
                android.util.Log.e("CallScribe", "Sync coroutine uncaught error: ${throwable.localizedMessage}", throwable)
                com.example.CallScribeApplication.recordCrash(appContext, Thread.currentThread(), throwable)
                syncStatus.value = "Sync failed: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}"
                isSyncing.value = false
            }

            syncJob = viewModelScope.launch(handler) {
                try {
                    isSyncing.value = true
                    val modeLabel = preferredEngine.value.displayName
                    syncStatus.value = "Preparing recordings ($modeLabel)..."
                    syncProgress.value = 0f
                    syncProcessedCount.value = 0
                    syncErrorCount.value = 0

                    val (targetFiles, alreadyAnalyzedCount) = withContext(Dispatchers.IO) {
                        SyncLock.mutex.withLock {
                            try {
                                val audioFiles = mutableListOf<AudioFileInfo>()
                                val treeDocId = try {
                                    DocumentsContract.getTreeDocumentId(treeUri)
                                } catch (_: Throwable) {
                                    DocumentsContract.getDocumentId(treeUri)
                                }

                                scanDirectoryRecursively(appContext, treeUri, treeDocId, audioFiles, currentDepth = 0, maxDepth = 3)

                                if (audioFiles.isEmpty()) {
                                    syncStatus.value = "No audio recordings found."
                                    return@withLock Pair(emptyList<AudioFileInfo>(), 0)
                                }

                                audioFiles.sortByDescending { it.effectiveTimestamp }

                                // Strictly partition into files needing analysis vs already analyzed
                                val filesNeedingAnalysis = mutableListOf<AudioFileInfo>()
                                var alreadyAnalyzed = 0

                                for (file in audioFiles) {
                                    if (file.size <= 0L) continue

                                    val existing = try { repository.getByUri(file.uri.toString()) } catch (_: Throwable) { null }
                                    val hasRealTranscript = existing != null && hasValidTranscript(existing.decodedTranscription)

                                    if (hasRealTranscript && !forceReanalyzeAll) {
                                        alreadyAnalyzed++
                                    } else {
                                        filesNeedingAnalysis.add(file)
                                    }
                                }

                                if (filesNeedingAnalysis.isEmpty()) {
                                    syncStatus.value = "All ${audioFiles.size} recordings are already analyzed & up to date! (0 duplicates)"
                                    return@withLock Pair(emptyList<AudioFileInfo>(), alreadyAnalyzed)
                                }

                                filesNeedingAnalysis.sortByDescending { it.effectiveTimestamp }

                                val selected = if (limit > 0 && limit < filesNeedingAnalysis.size) {
                                    filesNeedingAnalysis.take(limit)
                                } else {
                                    filesNeedingAnalysis
                                }

                                // Ensure placeholders exist for UI display
                                for (file in selected) {
                                    val existing = try { repository.getByUri(file.uri.toString()) } catch (_: Throwable) { null }
                                    if (existing == null) {
                                        val duration = AudioDurationHelper.getDurationMs(appContext, file.uri)
                                        val placeholder = Recording(
                                            id = 0,
                                            title = file.name,
                                            contentEncrypted = SimpleEncryption.encrypt(""),
                                            summaryEncrypted = SimpleEncryption.encrypt("Pending AI Analysis\n\nTap ⚡ Transcribe & Analyze Call to view insights."),
                                            timestamp = file.effectiveTimestamp,
                                            sourceUri = file.uri.toString(),
                                            durationMs = duration
                                        )
                                        repository.insert(placeholder)
                                    }
                                }

                                Pair(selected, alreadyAnalyzed)
                            } catch (t: Throwable) {
                                if (t !is kotlinx.coroutines.CancellationException) {
                                    android.util.Log.e("CallScribe", "Sync folder scan failed: ${t.localizedMessage}", t)
                                    syncStatus.value = "Scan failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
                                }
                                Pair(emptyList<AudioFileInfo>(), 0)
                            }
                        } // end mutex withLock
                    }

                    if (targetFiles.isEmpty()) {
                        isSyncing.value = false
                        return@launch
                    }

                    syncTotalCount.value = targetFiles.size
                    syncStatus.value = "Analyzing ${targetFiles.size} new calls ($alreadyAnalyzedCount already analyzed & excluded)..."

                    var processedCount = 0
                    var errorCount = 0

                    withContext(Dispatchers.IO) {
                        for ((index, fileInfo) in targetFiles.withIndex()) {
                            if (!isActive) break

                            val rawProg = if (targetFiles.isNotEmpty()) (index.toFloat() / targetFiles.size.toFloat()) else 0f
                            syncProgress.value = if (rawProg.isNaN() || rawProg.isInfinite()) 0f else rawProg.coerceIn(0f, 1f)

                            val existing = try { repository.getByUri(fileInfo.uri.toString()) } catch (_: Throwable) { null }
                            val actionLabel = if (existing == null || existing.decodedTranscription.isBlank()) "Analyzing" else "Re-analyzing"
                            syncStatus.value = "$actionLabel (${index + 1}/${targetFiles.size}): ${fileInfo.name}"

                            val processResult = try {
                                processAudioFile(
                                    appContext,
                                    fileInfo.uri,
                                    fileInfo.name,
                                    fileInfo.mimeType,
                                    fileInfo.size,
                                    existingId = existing?.id,
                                    fileLastModified = fileInfo.effectiveTimestamp
                                )
                            } catch (e: Throwable) {
                                Result.failure(e)
                            }

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
                                    val errorMsg = err?.localizedMessage ?: "Processing error"
                                    syncStatus.value = "Note on ${fileInfo.name}: $errorMsg"
                                    kotlinx.coroutines.delay(800)
                                }
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
                        android.util.Log.e("CallScribe", "Sync failed in launch: ${t.localizedMessage}", t)
                        syncStatus.value = "Sync failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
                    }
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        kotlinx.coroutines.delay(1000)
                        isSyncing.value = false
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("CallScribe", "Failed to start sync: ${t.localizedMessage}", t)
            com.example.CallScribeApplication.recordCrash(appContext, Thread.currentThread(), t)
            syncStatus.value = "Could not start sync: ${t.localizedMessage ?: "Unexpected error"}"
            isSyncing.value = false
        }
    }

    private data class AudioFileInfo(
        val uri: Uri,
        val name: String,
        val mimeType: String?,
        val size: Long,
        val lastModified: Long,
        val effectiveTimestamp: Long = CallMetadataParser.extractCallTimestamp(name, lastModified)
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

    private val MAX_FILE_SIZE_GEMINI = 15L * 1024 * 1024  // 15 MB — Gemini inline limit
    private val MAX_FILE_SIZE_NVIDIA = 25L * 1024 * 1024  // 25 MB — NVIDIA ASR limit
    private val MAX_FILE_SIZE_CLOUDFLARE = 24L * 1024 * 1024  // 24 MB — Safe under Cloudflare 25 MB payload limit

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
                android.util.Log.w("CallScribe", "Audio file exceeded size limit of $maxBytes bytes: $uri (actual: $statSize)")
                return@withContext null
            }

            val estimatedBytes = if (statSize > 0) statSize else minOf(maxBytes, 10L * 1024 * 1024)
            if (!hasSufficientHeap(estimatedBytes)) {
                android.util.Log.w("CallScribe", "Heap constrained for $estimatedBytes bytes, requesting GC before audio read.")
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
                        if (total > maxBytes) {
                            android.util.Log.w("CallScribe", "Audio file exceeded size limit of $maxBytes bytes: $uri")
                            return@use null
                        }
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                }
            }
        } catch (_: OutOfMemoryError) {
            android.util.Log.e("CallScribe", "OutOfMemoryError reading audio: $uri. Invoking GC.")
            System.gc()
            null
        } catch (t: Throwable) {
            android.util.Log.e("CallScribe", "Failed to read audio bytes from $uri: ${t.localizedMessage}", t)
            null
        }
    }

    private fun isUnusableTranscription(transcription: String, summary: String): Boolean {
        return TranscriptionValidator.isUnusableTranscription(transcription, summary)
    }

    private suspend fun processAudioFile(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String?,
        fileSize: Long,
        existingId: Int? = null,
        fileLastModified: Long = 0L
    ): Result<Boolean> {
        return try {
            val contentResolver = context.contentResolver
            val resolvedMime = mimeType ?: contentResolver.getType(uri) ?: "audio/mp3"

            var transcription = ""
            var summary = ""
            var audioBytes: ByteArray? = null
            var lastEngineError: Throwable? = null
            var usedGeminiFailover = false
            val currentEngine = preferredEngine.value

            // ── Mode 0: Explicit On-Device Offline Selection ─────────────────────────────
            if (currentEngine == PreferredEngine.ON_DEVICE) {
                val existingTranscript = if (existingId != null) {
                    val prev = repository.getById(existingId)
                    prev?.decodedTranscription?.takeIf { hasValidTranscript(it) } ?: ""
                } else ""
                val (localTrans, localSum) = LocalAnalysisEngine.analyzeLocally(existingTranscript, fileName)
                transcription = localTrans
                summary = localSum
            } else {
                // ── Mode 1: Try Gemini First for transcription (whenever Gemini is preferred, NVIDIA is preferred, or AUTO mode with Gemini configured) ───
                val tryGeminiFirst = when (currentEngine) {
                    PreferredEngine.GEMINI -> isApiKeyConfigured()
                    PreferredEngine.NVIDIA -> isApiKeyConfigured()
                    PreferredEngine.AUTO -> isApiKeyConfigured()
                    else -> false
                }
                if (tryGeminiFirst && fileSize <= MAX_FILE_SIZE_GEMINI) {
                    val bytes = audioBytes ?: readAudioBytes(context, uri, MAX_FILE_SIZE_GEMINI)
                    audioBytes = null // Release raw byte buffer reference before Base64 encoding & network call
                    if (bytes != null) {
                        val base64Audio = try {
                            Base64.encodeToString(bytes, Base64.NO_WRAP)
                        } catch (_: OutOfMemoryError) {
                            System.gc()
                            android.util.Log.e("CallScribe", "OOM encoding Base64 for Gemini: $fileName")
                            null
                        }

                        if (base64Audio != null) {
                            var geminiResult = geminiRepository.transcribeAndSummarizeAudio(base64Audio, resolvedMime)

                            // If rate limit (429) hit, wait 5 seconds and retry once
                            if (geminiResult.exceptionOrNull() is ApiQuotaExceededException) {
                                kotlinx.coroutines.delay(5000)
                                geminiResult = geminiRepository.transcribeAndSummarizeAudio(base64Audio, resolvedMime)
                            }

                            if (geminiResult.isSuccess) {
                                val pair = geminiResult.getOrThrow()
                                if (!isUnusableTranscription(pair.first, pair.second) || transcription.isBlank() || isUnusableTranscription(transcription, summary)) {
                                    transcription = pair.first
                                    summary = pair.second
                                }
                            } else {
                                lastEngineError = geminiResult.exceptionOrNull()
                            }
                        }
                    }
                }

                // ── Mode 2: Try Cloudflare Worker (ONLY if CLOUDFLARE is preferred, or if Gemini wasn't configured / failed in AUTO mode) ───
                val tryCloudflare = when (currentEngine) {
                    PreferredEngine.CLOUDFLARE -> isCloudflareConfigured()
                    PreferredEngine.AUTO, PreferredEngine.NVIDIA -> transcription.isBlank() && !isApiKeyConfigured() && isCloudflareConfigured()
                    else -> false
                }
                if (tryCloudflare && transcription.isBlank() && fileSize <= MAX_FILE_SIZE_CLOUDFLARE) {
                    val bytes = readAudioBytes(context, uri, MAX_FILE_SIZE_CLOUDFLARE)
                    if (bytes != null) {
                        val cfResult = cloudflareRepository?.analyzeAudio(bytes, fileName, resolvedMime, spokenLanguage.value.code)
                        if (cfResult?.isSuccess == true) {
                            val pair = cfResult.getOrThrow()
                            transcription = pair.first
                            summary = pair.second
                        } else {
                            if (currentEngine == PreferredEngine.CLOUDFLARE || lastEngineError == null) {
                                lastEngineError = cfResult?.exceptionOrNull()
                            }
                        }
                    }
                }

                // ── Mode 3: Gemini Fallback for Cloudflare failure ─
                if (transcription.isBlank() && isApiKeyConfigured() && fileSize <= MAX_FILE_SIZE_GEMINI && !tryGeminiFirst) {
                    val bytes = readAudioBytes(context, uri, MAX_FILE_SIZE_GEMINI)
                    if (bytes != null) {
                        val base64Audio = try {
                            Base64.encodeToString(bytes, Base64.NO_WRAP)
                        } catch (_: OutOfMemoryError) {
                            System.gc()
                            null
                        }
                        if (base64Audio != null) {
                            val geminiResult = geminiRepository.transcribeAndSummarizeAudio(base64Audio, resolvedMime)
                            if (geminiResult.isSuccess) {
                                val pair = geminiResult.getOrThrow()
                                transcription = pair.first
                                summary = pair.second
                                usedGeminiFailover = true
                            } else {
                                lastEngineError = geminiResult.exceptionOrNull()
                            }
                        }
                    }
                }

                // ── Mode 4: Audio Engine Requirement Check for NVIDIA Preferred Mode ─
                if (transcription.isBlank() && currentEngine == PreferredEngine.NVIDIA) {
                    lastEngineError = Exception("Audio transcription requires Google Gemini. Please configure your Gemini API Key in 🔑 Settings.")
                }

                // ── Mode 5: Summarization priority: NVIDIA NIM Llama 3.1 70B ───
                if (transcription.isNotBlank() && transcription != TranscriptionValidator.NO_SPEECH_DETECTED) {
                    if (isPlaceholderSummary(summary)) {
                        summary = ""
                    }
                    // Whenever NVIDIA NIM API key is configured, ALWAYS let NVIDIA Llama 3.1 70B summarize!
                    if (isNvidiaKeyConfigured() && (currentEngine == PreferredEngine.NVIDIA || currentEngine == PreferredEngine.GEMINI || currentEngine == PreferredEngine.AUTO)) {
                        val sumResult = nvidiaRepository?.summarizeTranscript(transcription, fileName)
                        if (sumResult?.isSuccess == true && sumResult.getOrThrow().isNotBlank()) {
                            summary = sumResult.getOrThrow()
                        }
                    }
                    if (summary.isBlank() && isCloudflareConfigured() && currentEngine == PreferredEngine.CLOUDFLARE) {
                        val cfSum = cloudflareRepository?.summarizeTranscript(transcription, fileName)
                        if (cfSum?.isSuccess == true && cfSum.getOrThrow().isNotBlank()) {
                            summary = cfSum.getOrThrow()
                        }
                    }
                    if (summary.isBlank() || isPlaceholderSummary(summary)) {
                        val (_, localSum) = LocalAnalysisEngine.analyzeLocally(transcription, fileName)
                        summary = localSum
                    }
                }

                // ── Mode 6: Local on-device fallback if cloud engines are unconfigured or failed ──
                if (transcription.isBlank()) {
                    if (currentEngine != PreferredEngine.ON_DEVICE) {
                        val reportedErr = lastEngineError ?: when (currentEngine) {
                            PreferredEngine.CLOUDFLARE -> when {
                                fileSize > MAX_FILE_SIZE_CLOUDFLARE -> Exception("Audio file (${fileSize / (1024 * 1024)}MB) exceeds Cloudflare limit (24MB). Please select Google Gemini in 🔑 Settings.")
                                !isCloudflareConfigured() -> Exception("Cloudflare Worker URL is not configured.")
                                !isApiKeyConfigured() -> Exception("Cloudflare Whisper cannot decode mobile call audio (.m4a/.amr). Please select Google Gemini in 🔑 Settings for full call transcription.")
                                else -> Exception("Cloudflare Whisper could not detect speech in this recording. Please try Google Gemini.")
                            }
                            PreferredEngine.GEMINI -> when {
                                fileSize > MAX_FILE_SIZE_GEMINI -> Exception("Audio file (${fileSize / (1024 * 1024)}MB) exceeds Gemini limit (15MB).")
                                !isApiKeyConfigured() -> Exception("Google Gemini API key is not configured in 🔑 Settings.")
                                else -> Exception("Gemini could not transcribe audio.")
                            }
                            PreferredEngine.NVIDIA -> when {
                                !isApiKeyConfigured() -> Exception("NVIDIA NIM requires Google Gemini to transcribe audio. Please configure your Gemini API Key in 🔑 Settings.")
                                !isNvidiaKeyConfigured() -> Exception("NVIDIA NIM API key is not configured in 🔑 Settings.")
                                else -> Exception("Could not transcribe audio for NVIDIA analysis.")
                            }
                            else -> Exception("Could not analyze call. Check AI Engine settings or network connection.")
                        }
                        return Result.failure(reportedErr)
                    }
                    val (localTrans, localSum) = LocalAnalysisEngine.analyzeLocally("", fileName)
                    transcription = localTrans
                    summary = localSum
                } else if (summary.isBlank() || isPlaceholderSummary(summary)) {
                    val (_, localSum) = LocalAnalysisEngine.analyzeLocally(transcription, fileName)
                    summary = localSum
                }
            }

            audioBytes = null // Guarantee memory release
            transcription = TranscriptionValidator.sanitizeTranscription(transcription, summary)

            val existingRec = existingId?.let { withContext(Dispatchers.IO) { repository.getById(it) } }
            val existingTimestamp = existingRec?.timestamp
            val rawTimestamp = if (fileLastModified > 0) fileLastModified else (existingTimestamp ?: System.currentTimeMillis())
            val finalTimestamp = CallMetadataParser.extractCallTimestamp(fileName, rawTimestamp)
            val duration = AudioDurationHelper.getDurationMs(context, uri).let { if (it > 0) it else (existingRec?.durationMs ?: 0) }

            val recording = Recording(
                id = existingId ?: 0,
                title = fileName,
                contentEncrypted = SimpleEncryption.encrypt(transcription),
                summaryEncrypted = SimpleEncryption.encrypt(summary),
                timestamp = finalTimestamp,
                sourceUri = uri.toString(),
                durationMs = duration
            )
            repository.insert(recording)
            Result.success(usedGeminiFailover)
        } catch (oom: OutOfMemoryError) {
            android.util.Log.e("CallScribe", "OutOfMemoryError in processAudioFile: ${oom.localizedMessage}. Invoking GC.", oom)
            System.gc()
            Result.failure(Exception("Recording is too large for current available device memory. Please close background apps or use On-Device mode."))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Result.failure(t)
        }
    }

    fun reanalyzeRecording(context: Context, recording: Recording) {
        val appContext = context.applicationContext
        try {
            val uriStr = recording.sourceUri
            if (uriStr.isNullOrBlank()) {
                android.util.Log.w("CallScribe", "reanalyzeRecording called on recording without sourceUri: ${recording.id}")
                updateStatusMessage.value = "⚠️ Audio source URI not available for this recording."
                return
            }
            if (preferredEngine.value == PreferredEngine.CLOUDFLARE && !isCloudflareConfigured()) {
                android.util.Log.w("CallScribe", "reanalyzeRecording: Cloudflare Worker URL is not configured")
                updateStatusMessage.value = "☁️ Cloudflare Worker URL not configured. Please set Worker URL in 🔑 Settings."
                showApiKeyDialog.value = true
                return
            }

            if (preferredEngine.value == PreferredEngine.GEMINI && !isApiKeyConfigured()) {
                android.util.Log.w("CallScribe", "reanalyzeRecording: Gemini API Key is not configured")
                updateStatusMessage.value = "🤖 Gemini API Key not configured. Please enter your key in 🔑 Settings."
                showApiKeyDialog.value = true
                return
            }

            if (preferredEngine.value == PreferredEngine.NVIDIA && !isCloudflareConfigured() && !isApiKeyConfigured()) {
                android.util.Log.w("CallScribe", "reanalyzeRecording: NVIDIA mode requires Cloudflare or Gemini for transcription")
                updateStatusMessage.value = "⚡ NVIDIA NIM requires Cloudflare Worker or Gemini to transcribe audio. Please configure in 🔑 Settings."
                showApiKeyDialog.value = true
                return
            }

            if (preferredEngine.value == PreferredEngine.ON_DEVICE && !hasValidTranscript(recording.decodedTranscription)) {
                android.util.Log.w("CallScribe", "reanalyzeRecording: Cannot transcribe raw audio in On-Device mode")
                updateStatusMessage.value = "📱 On-Device mode cannot transcribe raw audio. Configure Cloudflare Worker or Gemini in 🔑 Settings."
                showApiKeyDialog.value = true
                return
            }

            if (!isApiKeyConfigured() && !isNvidiaKeyConfigured() && !isCloudflareConfigured() && preferredEngine.value != PreferredEngine.ON_DEVICE) {
                android.util.Log.w("CallScribe", "reanalyzeRecording: No AI engine configured and engine is not ON_DEVICE")
                updateStatusMessage.value = "⚠️ Please configure an AI Engine (Cloudflare or Gemini) to transcribe audio."
                showApiKeyDialog.value = true
                return
            }

            val cleanTitle = try {
                CallMetadataParser.cleanCallTitle(recording.title)
            } catch (_: Throwable) { recording.title }

            val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
                android.util.Log.e("CallScribe", "reanalyzeRecording coroutine uncaught error: ${throwable.localizedMessage}", throwable)
                com.example.CallScribeApplication.recordCrash(appContext, Thread.currentThread(), throwable)
                updateStatusMessage.value = "⚠️ Analysis error: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}"
                analyzingRecordingId.value = null
            }

            viewModelScope.launch(handler) {
                analyzingRecordingId.value = recording.id
                updateStatusMessage.value = "Analyzing '$cleanTitle'..."
                android.util.Log.i("CallScribe", "Starting analysis for id=${recording.id}, title='${recording.title}', uri=$uriStr, engine=${preferredEngine.value}")

                try {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            val uri = Uri.parse(uriStr)
                            val fileSize = try {
                                appContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                            } catch (e: Throwable) {
                                android.util.Log.w("CallScribe", "openFileDescriptor statSize failed: ${e.localizedMessage}")
                                0L
                            }
                            val mime = try {
                                appContext.contentResolver.getType(uri) ?: "audio/mp3"
                            } catch (e: Throwable) {
                                android.util.Log.w("CallScribe", "contentResolver.getType failed: ${e.localizedMessage}")
                                "audio/mp3"
                            }

                            processAudioFile(
                                context = appContext,
                                uri = uri,
                                fileName = recording.title,
                                mimeType = mime,
                                fileSize = fileSize,
                                existingId = recording.id,
                                fileLastModified = recording.timestamp
                            )
                        } catch (e: Throwable) {
                            android.util.Log.e("CallScribe", "processAudioFile failed: ${e.localizedMessage}", e)
                            Result.failure(e)
                        }
                    }

                    if (result.isSuccess) {
                        android.util.Log.i("CallScribe", "Analysis succeeded for id=${recording.id}")
                        val updated = withContext(Dispatchers.IO) {
                            try {
                                repository.getById(recording.id) ?: repository.getByUri(uriStr)
                            } catch (e: Throwable) {
                                android.util.Log.e("CallScribe", "repository lookup failed: ${e.localizedMessage}", e)
                                null
                            }
                        }
                        if (activeChatRecording.value?.id == recording.id && updated != null) {
                            activeChatRecording.value = updated
                            chatMessages.value = chatMessages.value + ChatMessage(
                                MessageSender.AI,
                                "✅ Call transcribed successfully! I now have the full transcript. Ask me anything about this call!"
                            )
                        }
                        if (updated != null && preferencesManager?.isCommitmentRemindersEnabled() != false) {
                            try {
                                val actions = CommitmentExtractor.extractActionItems(updated.decodedSummary)
                                val dates = CommitmentExtractor.extractDates(updated.decodedSummary)
                                if (actions.isNotEmpty() || dates.isNotEmpty()) {
                                    NotificationHelper.notifyCommitments(
                                        context = appContext,
                                        callTitle = updated.title,
                                        actionItems = actions,
                                        dates = dates,
                                        recordingId = updated.id
                                    )
                                }
                            } catch (t: Throwable) {
                                android.util.Log.w("CallScribe", "notifyCommitments failed: ${t.localizedMessage}", t)
                            }
                        }
                        val hasValidSummary = updated != null && hasRealAnalysis(updated)
                        val usedFailover = result.getOrDefault(false)
                        if (hasValidSummary) {
                            if (usedFailover) {
                                updateStatusMessage.value = "Analysis complete via Google Gemini (Cloudflare audio format limit reached) ✅"
                            } else {
                                updateStatusMessage.value = "Analysis complete! ✅"
                            }
                        } else if (updated != null && (updated.decodedTranscription.equals(TranscriptionValidator.NO_SPEECH_DETECTED, ignoreCase = true) || updated.decodedTranscription.contains("No speech detected", ignoreCase = true))) {
                            updateStatusMessage.value = "⚠️ No audible speech detected in this recording."
                        } else {
                            updateStatusMessage.value = "⚠️ Could not transcribe audio. Check AI Engine settings."
                        }
                    } else {
                        val err = result.exceptionOrNull()
                        android.util.Log.e("CallScribe", "Analysis failed for id=${recording.id}: ${err?.localizedMessage}", err)
                        val isGemini429 = (err is ApiQuotaExceededException || (err?.message?.contains("429") == true && preferredEngine.value == PreferredEngine.GEMINI))
                        if (isGemini429) {
                            updateStatusMessage.value = "⏳ Google AI Studio 1-minute speed limit reached. Daily 1,500 calls active! Resets in 30–60s."
                        } else if (err?.message?.contains("429") == true) {
                            updateStatusMessage.value = "⏳ 1-minute speed limit reached. Resets in 30–60s. Please wait!"
                        } else {
                            val rawMsg = err?.localizedMessage ?: err?.javaClass?.simpleName ?: "Unknown error"
                            val cleanMsg = when {
                                rawMsg.contains("3010") || rawMsg.contains("Invalid audio input", ignoreCase = true) ->
                                    "Cloudflare Whisper cannot decode mobile call audio (.m4a/.amr). Please select Google Gemini in 🔑 Settings."
                                rawMsg.contains("3006") || rawMsg.contains("too large", ignoreCase = true) ->
                                    "Audio exceeds Cloudflare Whisper model limit. Please select Google Gemini in 🔑 Settings for full calls."
                                rawMsg.contains("1102") || rawMsg.contains("503") ->
                                    "Cloudflare Worker execution limit reached on long recording. Please select Google Gemini in 🔑 Settings for full calls."
                                else -> rawMsg
                            }
                            updateStatusMessage.value = "Analysis failed: $cleanMsg"
                        }
                    }
                } catch (t: Throwable) {
                    if (t !is kotlinx.coroutines.CancellationException) {
                        android.util.Log.e("CallScribe", "Unexpected error in reanalyzeRecording: ${t.localizedMessage}", t)
                        com.example.CallScribeApplication.recordCrash(appContext, Thread.currentThread(), t)
                        updateStatusMessage.value = "Could not analyze: ${t.localizedMessage ?: "Unexpected error"}"
                    }
                } finally {
                    analyzingRecordingId.value = null
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("CallScribe", "Fatal guard caught error in reanalyzeRecording: ${t.localizedMessage}", t)
            com.example.CallScribeApplication.recordCrash(appContext, Thread.currentThread(), t)
            updateStatusMessage.value = "Could not start analysis: ${t.localizedMessage ?: "Error"}"
            analyzingRecordingId.value = null
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
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, recording.timestamp)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, recording.timestamp + 30 * 60 * 1000)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No calendar application available.", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        fun isPlaceholderSummary(summary: String?): Boolean {
            if (summary.isNullOrBlank()) return true
            val trimmed = summary.trim()
            return trimmed == "Analysis complete." ||
                trimmed == "Summary unavailable." ||
                trimmed.contains("Pending AI Analysis") ||
                trimmed.contains("AI Analysis Not Available") ||
                trimmed.equals("No speech detected.", ignoreCase = true)
        }

        fun hasRealAnalysis(recording: Recording): Boolean {
            val sum = recording.decodedSummary.trim()
            val trans = recording.decodedTranscription.trim()
            if (isPlaceholderSummary(sum)) return false
            if (trans.isBlank() ||
                trans.contains("Audio Transcription Required") ||
                trans.contains("Transcription requires") ||
                trans.contains("On-Device Speech Analysis") ||
                trans.equals(TranscriptionValidator.NO_SPEECH_DETECTED, ignoreCase = true)) {
                return false
            }
            return true
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
    private val preferencesManager: CallPreferencesManager? = null,
    private val cloudflareRepository: CloudflareWorkerRepository? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CallViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CallViewModel(
                repository, geminiRepository, gitHubUpdateRepository, apiKeyManager, nvidiaRepository, preferencesManager, cloudflareRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
