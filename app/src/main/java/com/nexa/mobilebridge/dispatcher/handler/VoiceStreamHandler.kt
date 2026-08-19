package com.nexa.mobilebridge.dispatcher.handler

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * VoiceStreamHandler — Ultra-Low Latency Bi-Directional PCM Audio Streamer
 * Used for Google Gemini Multimodal Live API Real-Time Voice Calls.
 *
 * Features:
 * - Hardware Acoustic Echo Cancellation (AEC) & Noise Suppression (NS)
 * - Anti-Jitter Pre-buffering: Eliminates choppy audio gaps / stuttering
 * - Smart Echo-Gate: Prevents loudspeaker acoustic feedback from triggering false Barge-In
 * - Dynamic Earpiece <-> Loudspeaker Hardware Routing
 * - Digital 16-bit PCM Soft-Clipping Gain Booster
 * - Instant Barge-In Interruption (<5ms buffer flush)
 */
class VoiceStreamHandler(private val context: Context) {

    private val TAG = "VoiceStreamHandler"

    // ── Input AudioRecord Constants (16kHz Mono 16-bit) ────────────────────────
    private val INPUT_SAMPLE_RATE = 16000
    private val INPUT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val INPUT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // ── Output AudioTrack Constants (24kHz Mono 16-bit) ────────────────────────
    private val OUTPUT_SAMPLE_RATE = 24000
    private val OUTPUT_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
    private val OUTPUT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    private var isStreaming = false

    // Output playback queue with blocking take for zero-gap playback
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()

    // Track active playback timestamp to prevent false barge-in
    @Volatile
    private var lastPlaybackChunkTime: Long = 0L

    // Speakerphone state (Default: false = Earpiece speaker atas)
    var isLoudspeakerActive: Boolean = false
        private set

    /**
     * Start capturing microphone audio and streaming PCM chunks in Base64.
     * @param scope - CoroutineScope for recording loop
     * @param onAudioChunk - Callback receiving Base64 PCM chunk
     */
    @SuppressLint("MissingPermission")
    fun startStreaming(
        scope: CoroutineScope,
        onAudioChunk: (String) -> Unit
    ) {
        if (isStreaming) return
        isStreaming = true

        initAudioTrack()
        setLoudspeaker(isLoudspeakerActive)

        val minBufSize = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            INPUT_CHANNEL_CONFIG,
            INPUT_AUDIO_FORMAT
        )
        val bufferSize = (minBufSize * 2).coerceAtLeast(2048)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_SAMPLE_RATE,
                INPUT_CHANNEL_CONFIG,
                INPUT_AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed!")
                isStreaming = false
                return
            }

            // Enable Hardware Acoustic Echo Cancellation & Noise Suppression
            val sessionId = audioRecord?.audioSessionId ?: 0
            if (sessionId != 0) {
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                            enabled = true
                            Log.i(TAG, "🛡️ Hardware AcousticEchoCanceler ACTIVE")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not enable AEC: ${e.message}")
                    }
                }
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                            enabled = true
                            Log.i(TAG, "🛡️ Hardware NoiseSuppressor ACTIVE")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not enable NoiseSuppressor: ${e.message}")
                    }
                }
            }

            audioRecord?.startRecording()
            Log.i(TAG, "🎙️ AudioRecord started streaming (16kHz PCM Mono)")

            // 1. Recording Loop (Microphone -> Server with Smart Echo Gating)
            recordingJob = scope.launch(Dispatchers.IO) {
                val audioBuffer = ByteArray(1024) // ~32ms chunks
                while (isActive && isStreaming) {
                    val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readBytes > 0) {
                        val chunk = if (readBytes == audioBuffer.size) {
                            audioBuffer
                        } else {
                            audioBuffer.copyOf(readBytes)
                        }

                        // Smart Echo Gate during Loudspeaker Playback:
                        // When speaker is actively outputting sound, check mic energy.
                        // If it's just speaker leakage (< 2200 RMS), do not forward to Google
                        // to prevent Google server-side false Barge-In stuttering!
                        val isPlaying = (System.currentTimeMillis() - lastPlaybackChunkTime) < 250
                        if (isLoudspeakerActive && isPlaying) {
                            val rms = calculateRms(chunk)
                            if (rms < 2200.0) {
                                // Suppress echo leakage
                                continue
                            }
                        }

                        val base64Chunk = Base64.encodeToString(chunk, Base64.NO_WRAP)
                        onAudioChunk(base64Chunk)
                    }
                }
            }

            // 2. Anti-Jitter Playback Loop (Continuous Smooth Stream)
            playbackJob = scope.launch(Dispatchers.IO) {
                while (isActive && isStreaming) {
                    try {
                        val chunk = playbackQueue.poll(50, TimeUnit.MILLISECONDS)
                        if (chunk != null && audioTrack != null) {
                            lastPlaybackChunkTime = System.currentTimeMillis()
                            audioTrack?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                        }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting voice stream: ${e.message}")
            stopStreaming()
        }
    }

    /**
     * Calculate RMS (Root Mean Square) energy of PCM 16-bit audio chunk.
     */
    private fun calculateRms(pcmBytes: ByteArray): Double {
        var sum = 0.0
        val sampleCount = pcmBytes.size / 2
        if (sampleCount == 0) return 0.0
        for (i in 0 until pcmBytes.size - 1 step 2) {
            val sample = (pcmBytes[i + 1].toInt() shl 8) or (pcmBytes[i].toInt() and 0xFF)
            sum += sample * sample
        }
        return sqrt(sum / sampleCount)
    }

    /**
     * Initialize AudioTrack for smooth low-latency playback (24kHz Mono).
     * Configured with VOICE_COMMUNICATION to enable dynamic hardware routing.
     */
    private fun initAudioTrack() {
        try {
            val minTrackBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                OUTPUT_CHANNEL_CONFIG,
                OUTPUT_AUDIO_FORMAT
            )

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(OUTPUT_SAMPLE_RATE)
                .setChannelMask(OUTPUT_CHANNEL_CONFIG)
                .setEncoding(OUTPUT_AUDIO_FORMAT)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minTrackBuf * 4) // 4x buffer to prevent any underruns
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.setVolume(1.0f)
            audioTrack?.play()
            Log.i(TAG, "🔊 AudioTrack initialized (24kHz PCM)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}")
        }
    }

    /**
     * Dynamically toggle between Earpiece (Speaker Atas) and Loudspeaker (Speaker Bawah Utama).
     * @param isLoudspeaker - true for Loudspeaker, false for Earpiece
     */
    fun setLoudspeaker(isLoudspeaker: Boolean) {
        this.isLoudspeakerActive = isLoudspeaker
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isLoudspeaker) {
                val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speaker != null) {
                    audioManager.setCommunicationDevice(speaker)
                    Log.i(TAG, "🔊 Audio Output: LOUDSPEAKER (Speaker Bawah Utama)")
                }
            } else {
                val earpiece = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                if (earpiece != null) {
                    audioManager.setCommunicationDevice(earpiece)
                    Log.i(TAG, "👂 Audio Output: EARPIECE (Speaker Atas Layar)")
                } else {
                    audioManager.clearCommunicationDevice()
                }
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = isLoudspeaker
            Log.i(TAG, "🔊 Legacy Speakerphone state: $isLoudspeaker")
        }
    }

    /**
     * Apply digital 16-bit PCM software gain booster (2.2x) with soft clipping.
     */
    private fun boostPcmGain(rawBytes: ByteArray, multiplier: Float = 2.2f): ByteArray {
        val boosted = ByteArray(rawBytes.size)
        for (i in 0 until rawBytes.size - 1 step 2) {
            val low = rawBytes[i].toInt() and 0xFF
            val high = rawBytes[i + 1].toInt()
            val sample = (high shl 8) or low

            var boostedSample = (sample * multiplier).toInt()
            boostedSample = boostedSample.coerceIn(-32768, 32767)

            boosted[i] = (boostedSample and 0xFF).toByte()
            boosted[i + 1] = ((boostedSample shr 8) and 0xFF).toByte()
        }
        return boosted
    }

    /**
     * Enqueue a PCM 24kHz audio chunk from Gemini Live for real-time smooth playback.
     */
    fun enqueueAudioChunk(pcmBase64: String) {
        try {
            val rawBytes = Base64.decode(pcmBase64, Base64.NO_WRAP)
            val amplifiedBytes = boostPcmGain(rawBytes, 2.2f)
            playbackQueue.offer(amplifiedBytes)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode incoming PCM audio chunk: ${e.message}")
        }
    }

    /**
     * Instantly flush output buffer upon user speech interruption (Barge-In).
     */
    fun flushOutputBuffer() {
        Log.i(TAG, "⚡ Interruption (Barge-In) received — flushing playback buffer")
        playbackQueue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing AudioTrack: ${e.message}")
        }
    }

    /**
     * Stop streaming and release all audio resources.
     */
    fun stopStreaming() {
        isStreaming = false
        recordingJob?.cancel()
        playbackJob?.cancel()
        playbackQueue.clear()

        try { echoCanceler?.release() } catch (_: Exception) {}
        echoCanceler = null

        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
        }
        audioManager.mode = AudioManager.MODE_NORMAL

        Log.i(TAG, "🛑 Voice stream stopped & audio hardware reset to normal.")
    }
}
