package com.example.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioPlayerManager(private val scope: CoroutineScope) {

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _playingRecordingId = MutableStateFlow<Int?>(null)
    val playingRecordingId: StateFlow<Int?> = _playingRecordingId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0)
    val currentPositionMs: StateFlow<Int> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0)
    val durationMs: StateFlow<Int> = _durationMs.asStateFlow()

    fun playOrPause(context: Context, recordingId: Int, uri: Uri) {
        val currentId = _playingRecordingId.value
        val player = mediaPlayer

        if (currentId == recordingId && player != null) {
            if (player.isPlaying) {
                player.pause()
                _isPlaying.value = false
                stopProgressUpdates()
            } else {
                player.start()
                _isPlaying.value = true
                startProgressUpdates()
            }
            return
        }

        // Start new playback
        stop()

        try {
            val newPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                val fdSuccess = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    setDataSource(pfd.fileDescriptor)
                    true
                } ?: false

                if (!fdSuccess) {
                    _playingRecordingId.value = null
                    return
                }

                prepare()
            }

            mediaPlayer = newPlayer
            _playingRecordingId.value = recordingId
            _durationMs.value = newPlayer.duration.coerceAtLeast(0)
            _currentPositionMs.value = 0

            newPlayer.setOnCompletionListener {
                _isPlaying.value = false
                _currentPositionMs.value = 0
                stopProgressUpdates()
            }

            newPlayer.setOnErrorListener { _, _, _ ->
                stop()
                true
            }

            newPlayer.start()
            _isPlaying.value = true
            startProgressUpdates()

        } catch (e: Exception) {
            e.printStackTrace()
            stop()
        }
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.let { player ->
            try {
                player.seekTo(positionMs)
                _currentPositionMs.value = positionMs
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        stopProgressUpdates()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        _playingRecordingId.value = null
        _isPlaying.value = false
        _currentPositionMs.value = 0
        _durationMs.value = 0
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch(Dispatchers.Main) {
            while (isActive && _isPlaying.value) {
                mediaPlayer?.let { player ->
                    try {
                        if (player.isPlaying) {
                            _currentPositionMs.value = player.currentPosition
                        }
                    } catch (_: Exception) {}
                }
                delay(200)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        stop()
    }
}
