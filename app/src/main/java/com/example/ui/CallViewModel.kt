package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.DocumentsContract
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Recording
import com.example.data.RecordingRepository
import com.example.data.SimpleEncryption
import com.example.network.GeminiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class CallViewModel(
    private val repository: RecordingRepository,
    private val geminiRepository: GeminiRepository
) : ViewModel() {

    val searchQuery = MutableStateFlow("")
    val isSyncing = MutableStateFlow(false)
    val syncStatus = MutableStateFlow("")

    val recordings = combine(repository.allRecordings, searchQuery) { list, query ->
        if (query.isBlank()) {
            list
        } else {
            list.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.decodedTranscription.contains(query, ignoreCase = true) ||
                it.decodedSummary.contains(query, ignoreCase = true)
            }
        }
    }
    .flowOn(kotlinx.coroutines.Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun syncFolder(context: Context, treeUri: Uri) {
        if (isSyncing.value) return
        
        viewModelScope.launch {
            isSyncing.value = true
            syncStatus.value = "Scanning folder..."
            
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    val projection = arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    )

                    val audioFiles = mutableListOf<Pair<Uri, String>>()

                    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val documentId = cursor.getString(0)
                            val name = cursor.getString(1)
                            val mime = cursor.getString(2)
                            
                            if (mime != null && mime.startsWith("audio/")) {
                                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                                audioFiles.add(Pair(fileUri, name))
                            }
                        }
                    }

                    syncStatus.value = "Found ${audioFiles.size} audio files. Processing new ones..."
                    var processedCount = 0

                    for ((uri, name) in audioFiles) {
                        val uriString = uri.toString()
                        val existing = repository.getByUri(uriString)
                        if (existing == null) {
                            syncStatus.value = "Processing: $name"
                            processAudioFile(context, uri, name)
                            processedCount++
                        }
                    }

                    syncStatus.value = "Sync complete. Processed $processedCount new files."
                } catch (e: Exception) {
                    syncStatus.value = "Sync error: ${e.message}"
                }
            }
            kotlinx.coroutines.delay(3000)
            isSyncing.value = false
        }
    }

    private suspend fun processAudioFile(context: Context, uri: Uri, fileName: String) {
        try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: "audio/mp3"
            
            // Read file into bytes to base64 encode
            val bytes = contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArrayOutputStream()
                var read: Int
                val data = ByteArray(16384)
                while (inputStream.read(data, 0, data.size).also { read = it } != -1) {
                    buffer.write(data, 0, read)
                }
                buffer.flush()
                buffer.toByteArray()
            }

            if (bytes != null) {
                val base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
                
                val result = geminiRepository.transcribeAndSummarizeAudio(base64Audio, mimeType)
                if (result != null) {
                    val recording = Recording(
                        title = fileName,
                        contentEncrypted = SimpleEncryption.encrypt(result.first),
                        summaryEncrypted = SimpleEncryption.encrypt(result.second),
                        sourceUri = uri.toString()
                    )
                    repository.insert(recording)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun deleteRecording(id: Int) {
        viewModelScope.launch {
            repository.deleteById(id)
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
            e.printStackTrace()
        }
    }
}

class CallViewModelFactory(
    private val repository: RecordingRepository,
    private val geminiRepository: GeminiRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CallViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CallViewModel(repository, geminiRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
