package com.nexa.mobilebridge.dispatcher.handler

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * VoiceStreamHandler — Ultra-Low Latency Bi-Directional PCM Audio Streamer
 * Used for Google Gemini Multimodal Live API Real-Time Voice Calls.
 *
 * Input Pipeline  : AudioRecord (16.000 Hz, 16-bit, Mono PCM) -> Base64 Chunks (~40-80ms)
 * Output Pipeline : AudioTrack (24.000 Hz, 16-bit, Mono PCM) -> Real-time Speaker Render
 * Interruption    : Instant buffer flush (<5ms) upon receiving Barge-In signal
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
    private var recordingJob: Job? = null
    private var isStreaming = false

    // Output playback queue
    private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()
    private var playbackJob: Job? = null

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

            audioRecord?.startRecording()
            Log.i(TAG, "🎙️ AudioRecord started streaming (16kHz PCM Mono)")

            // 1. Recording Loop
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
                        val base64Chunk = Base64.encodeToString(chunk, Base64.NO_WRAP)
                        onAudioChunk(base64Chunk)
                    }
                }
            }

            // 2. Playback Loop
            playbackJob = scope.launch(Dispatchers.IO) {
                while (isActive && isStreaming) {
                    val chunk = playbackQueue.poll()
                    if (chunk != null && audioTrack != null) {
                        audioTrack?.write(chunk, 0, chunk.size)
                    } else {
                        kotlinx.coroutines.delay(10L)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting voice stream: ${e.message}")
            stopStreaming()
        }
    }

    /**
     * Initialize AudioTrack for low-latency streaming playback (24kHz Mono).
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
                .setBufferSizeInBytes(minTrackBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            Log.i(TAG, "🔊 AudioTrack initialized and ready for streaming playback (24kHz PCM)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}")
        }
    }

    /**
     * Enqueue a PCM 24kHz audio chunk from Gemini Live for real-time playback.
     * @param pcmBase64 - Base64 encoded 24kHz 16-bit Mono PCM audio
     */
    fun enqueueAudioChunk(pcmBase64: String) {
        try {
            val rawBytes = Base64.decode(pcmBase64, Base64.NO_WRAP)
            playbackQueue.offer(rawBytes)
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

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_) {}
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_) {}
        audioTrack = null

        Log.i(TAG, "🛑 Voice stream stopped & audio hardware released.")
    }
}
