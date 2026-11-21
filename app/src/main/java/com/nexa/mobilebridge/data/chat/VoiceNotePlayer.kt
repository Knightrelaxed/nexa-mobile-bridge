package com.nexa.mobilebridge.data.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class VoicePlaybackState(
    val activeMessageId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
) {
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * Lightweight native MediaPlayer controller for local OGG/Opus & M4A voice note bubbles.
 * Zero background polling when idle, transient AudioFocus management, and leak-free error cleanup.
 */
@Singleton
class VoiceNotePlayer @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "VoiceNotePlayer"
        private const val PROGRESS_TICK_MS = 50L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val speechAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var activeFocusRequest: AudioFocusRequest? = null
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private fun requestTransientFocus() {
        val am = audioManager ?: return
        abandonTransientFocus()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(speechAudioAttributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    scope.launch { stop() }
                }
            }
            .build()
        activeFocusRequest = req
        try {
            am.requestAudioFocus(req)
        } catch (e: Exception) {
            Log.w(TAG, "AudioFocus request failed: ${e.message}")
        }
    }

    private fun abandonTransientFocus() {
        val req = activeFocusRequest ?: return
        activeFocusRequest = null
        try {
            audioManager?.abandonAudioFocusRequest(req)
        } catch (_: Exception) {
        }
    }

    fun togglePlay(messageId: String, audioPath: String, fallbackDurationMs: Long) {
        val current = _state.value
        val player = mediaPlayer

        if (current.activeMessageId == messageId && player != null) {
            if (current.isPlaying) {
                try {
                    player.pause()
                    val pos = player.currentPosition.toLong().coerceAtLeast(0L)
                    progressJob?.cancel()
                    abandonTransientFocus()
                    _state.value = current.copy(isPlaying = false, positionMs = pos)
                } catch (e: Exception) {
                    Log.w(TAG, "Pause error: ${e.message}")
                    stop()
                }
            } else {
                try {
                    requestTransientFocus()
                    player.start()
                    _state.value = current.copy(isPlaying = true)
                    startProgressTicker()
                } catch (e: Exception) {
                    Log.w(TAG, "Resume error: ${e.message}")
                    stop()
                }
            }
            return
        }

        // Start playback of a new message bubble
        stop()
        val file = File(audioPath)
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "Voice file missing: $audioPath")
            return
        }

        var mp: MediaPlayer? = null
        try {
            requestTransientFocus()
            mp = MediaPlayer().apply {
                setAudioAttributes(speechAudioAttributes)
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    stop()
                }
                setOnErrorListener { _, _, _ ->
                    stop()
                    true
                }
            }
            val actualDuration = mp.duration.toLong().takeIf { it > 0L } ?: fallbackDurationMs.coerceAtLeast(1L)
            mediaPlayer = mp
            mp.start()
            _state.value = VoicePlaybackState(
                activeMessageId = messageId,
                isPlaying = true,
                positionMs = 0L,
                durationMs = actualDuration
            )
            startProgressTicker()
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing voice note: ${e.message}")
            if (mp != null && mediaPlayer !== mp) {
                try {
                    mp.release()
                } catch (_: Exception) {
                }
            }
            stop()
        }
    }

    fun seekToFraction(messageId: String, audioPath: String, fallbackDurationMs: Long, fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (_state.value.activeMessageId != messageId || mediaPlayer == null) {
            togglePlay(messageId, audioPath, fallbackDurationMs)
        }
        val mp = mediaPlayer ?: return
        val dur = _state.value.durationMs.coerceAtLeast(1L)
        val targetMs = (dur * clamped).toInt().coerceIn(0, dur.toInt())
        try {
            mp.seekTo(targetMs)
            _state.value = _state.value.copy(positionMs = targetMs.toLong())
        } catch (e: Exception) {
            Log.w(TAG, "Seek error: ${e.message}")
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        abandonTransientFocus()
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Exception) {
            }
            try {
                mp.release()
            } catch (_: Exception) {
            }
        }
        _state.value = VoicePlaybackState()
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val mp = mediaPlayer
                if (mp == null || !_state.value.isPlaying) break
                val pos = try {
                    mp.currentPosition.toLong().coerceAtLeast(0L)
                } catch (_: Exception) {
                    break
                }
                _state.value = _state.value.copy(positionMs = pos)
                delay(PROGRESS_TICK_MS)
            }
        }
    }
}
