package com.nexa.mobilebridge.data.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class RecordedVoiceFile(
    val file: File,
    val mimeType: String,
    val durationMs: Long
)

/**
 * Lightweight Telegram-style OGG/Opus voice note recorder (24 kbps mono, ~3 KB/s).
 * Uses native Android 16 MediaRecorder (zero NDK bloat), with automatic M4A/AAC fallback
 * if a device codec rejects OGG/OPUS preparation.
 */
@Singleton
class VoiceNoteRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "VoiceNoteRecorder"
        private const val BITRATE_BPS = 24_000
        private const val SAMPLE_RATE_HZ = 16_000
        private const val MAX_STORED_VOICE_FILES = 100
        private const val VOICE_DIR_NAME = "voice_notes"
    }

    private val lock = Any()
    private var recorder: MediaRecorder? = null
    private var activeFile: File? = null
    private var activeMimeType: String = VoiceNoteMetadata.MIME_OGG
    private var startedAtMs: Long = 0L
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioManager: AudioManager?
        get() = context.getSystemService(AudioManager::class.java)

    private fun ensureVoiceDir(): File {
        val dir = File(context.filesDir, VOICE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun pruneOldFiles(dir: File) {
        try {
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            if (files.size <= MAX_STORED_VOICE_FILES) return
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_STORED_VOICE_FILES)
                .forEach { old -> old.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Voice cache prune warning: ${e.message}")
        }
    }

    private fun requestTransientAudioFocus() {
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioManager?.requestAudioFocus(req)
            audioFocusRequest = req
        } catch (e: Exception) {
            Log.w(TAG, "AudioFocus request skipped: ${e.message}")
        }
    }

    private fun abandonTransientAudioFocus() {
        val req = audioFocusRequest ?: return
        audioFocusRequest = null
        try {
            audioManager?.abandonAudioFocusRequest(req)
        } catch (_: Exception) {
        }
    }

    /**
     * Begin recording a new voice note for [clientMsgId].
     * Returns true if hardware MediaRecorder started successfully.
     */
    fun start(clientMsgId: String): Boolean = synchronized(lock) {
        cancelLocked()
        val dir = ensureVoiceDir()
        pruneOldFiles(dir)
        val safeId = clientMsgId.replace(Regex("[^A-Za-z0-9_-]"), "")

        requestTransientAudioFocus()

        // Primary: Telegram/WhatsApp-grade OGG Opus 24 kbps mono
        val oggFile = File(dir, "vn_$safeId.ogg")
        if (tryStartRecorder(oggFile, MediaRecorder.OutputFormat.OGG, MediaRecorder.AudioEncoder.OPUS, VoiceNoteMetadata.MIME_OGG)) {
            return@synchronized true
        }
        oggFile.delete()

        // Fallback: MPEG-4 AAC 24 kbps mono
        val m4aFile = File(dir, "vn_$safeId.m4a")
        if (tryStartRecorder(m4aFile, MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC, VoiceNoteMetadata.MIME_MP4)) {
            return@synchronized true
        }
        m4aFile.delete()
        abandonTransientAudioFocus()
        false
    }

    private fun tryStartRecorder(
        outputFile: File,
        outputFormat: Int,
        audioEncoder: Int,
        mimeType: String
    ): Boolean {
        var mr: MediaRecorder? = null
        return try {
            mr = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(outputFormat)
                setAudioEncoder(audioEncoder)
                setAudioEncodingBitRate(BITRATE_BPS)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioChannels(1)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            recorder = mr
            activeFile = outputFile
            activeMimeType = mimeType
            startedAtMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed starting MediaRecorder ($mimeType): ${e.message}")
            try {
                mr?.reset()
                mr?.release()
            } catch (_: Exception) {
            }
            false
        }
    }

    /**
     * Poll peak microphone amplitude (0..32767) for live waveform rendering.
     */
    fun sampleAmplitude(): Int = synchronized(lock) {
        try {
            recorder?.maxAmplitude ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Stop and finalize the current recording, returning the saved audio file and elapsed duration.
     */
    fun stopAndFinalize(): RecordedVoiceFile? = synchronized(lock) {
        val mr = recorder ?: return@synchronized null
        val file = activeFile
        val mime = activeMimeType
        val elapsed = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)

        recorder = null
        activeFile = null
        startedAtMs = 0L

        return@synchronized try {
            mr.stop()
            mr.release()
            abandonTransientAudioFocus()
            if (file != null && file.exists() && file.length() >= 128L) {
                RecordedVoiceFile(file = file, mimeType = mime, durationMs = elapsed)
            } else {
                file?.delete()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder stop() aborted cleanly: ${e.message}")
            try {
                mr.release()
            } catch (_: Exception) {
            }
            abandonTransientAudioFocus()
            file?.delete()
            null
        }
    }

    /**
     * Cancel active recording and delete the temporary audio file immediately.
     */
    fun cancelAndDelete() = synchronized(lock) {
        cancelLocked()
    }

    private fun cancelLocked() {
        val mr = recorder
        val file = activeFile
        recorder = null
        activeFile = null
        startedAtMs = 0L
        if (mr != null) {
            try {
                mr.stop()
            } catch (_: Exception) {
            }
            try {
                mr.reset()
                mr.release()
            } catch (_: Exception) {
            }
        }
        abandonTransientAudioFocus()
        try {
            file?.delete()
        } catch (_: Exception) {
        }
    }
}
