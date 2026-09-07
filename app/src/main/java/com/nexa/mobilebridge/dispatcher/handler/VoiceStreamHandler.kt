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
import android.media.audiofx.AutomaticGainControl
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

/**
 * VoiceStreamHandler — Ultra-Reliable Full-Duplex PCM Audio Streamer
 * Tuned for Google Gemini Multimodal Live API Real-Time Voice Calls.
 *
 * Architecture:
 * 1. Turn-Aware Uplink Gating: When assistant is actively speaking or playing audio chunks,
 *    uplink mic stream to Google is paused, completely preventing acoustic echo feedback and false barge-in.
 * 2. Instant Zero-Threshold Listening Mode: The moment assistant finishes speaking, mic opens
 *    with 100% full sensitivity (0 RMS threshold) so every word from user is immediately captured.
 * 3. Anti-Jitter Jitter Buffer: Continuous non-blocking playback with zero stutter.
 * 4. Hardware Session Pairing: AEC and NS bound directly to hardware audio session.
 * 5. Dynamic Hardware Routing: Seamless switching between Earpiece and Loudspeaker.
 */
class VoiceStreamHandler(private val context: Context) {

    private val TAG = "VoiceStreamHandler"

    // ── Audio Constants ────────────────────────────────────────────────────────
    private val INPUT_SAMPLE_RATE = 16000
    private val INPUT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val INPUT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private val OUTPUT_SAMPLE_RATE = 24000
    private val OUTPUT_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
    private val OUTPUT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var autoGainControl: AutomaticGainControl? = null

    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    private var isStreaming = false

    private val playbackQueue = LinkedBlockingQueue<ByteArray>()

    // Turn tracking
    @Volatile
    var isAssistantSpeaking = false
        private set

    @Volatile
    var lastAudioReceivedTime: Long = 0L
        private set

    @Volatile
    var currentAudioEnergy: Float = 0f
        private set

    var isLoudspeakerActive: Boolean = false
        private set

    /**
     * Start full-duplex audio stream with hardware DSP session pairing.
     */
    @SuppressLint("MissingPermission")
    fun startStreaming(
        scope: CoroutineScope,
        onAudioChunk: (String) -> Unit
    ) {
        if (isStreaming) return
        isStreaming = true

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

            val hardwareSessionId = audioRecord?.audioSessionId ?: 0
            initAudioTrack(hardwareSessionId)
            setLoudspeaker(isLoudspeakerActive)

            // Bind hardware effects to unified session
            if (hardwareSessionId != 0) {
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        echoCanceler = AcousticEchoCanceler.create(hardwareSessionId)?.apply {
                            enabled = true
                            Log.i(TAG, " Hardware AEC Active (Session: $hardwareSessionId)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Hardware AEC unavailable: ${e.message}")
                    }
                }
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        noiseSuppressor = NoiseSuppressor.create(hardwareSessionId)?.apply {
                            enabled = true
                            Log.i(TAG, " Hardware NoiseSuppressor Active")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Hardware NS unavailable: ${e.message}")
                    }
                }
                if (AutomaticGainControl.isAvailable()) {
                    try {
                        autoGainControl = AutomaticGainControl.create(hardwareSessionId)?.apply {
                            enabled = true
                        }
                    } catch (_: Exception) {}
                }
            }

            audioRecord?.startRecording()
            Log.i(TAG, " AudioRecord active (16kHz PCM, AEC Coupled)")

            // 1. Microphone Loop (Turn-Aware Uplink Gating)
            recordingJob = scope.launch(Dispatchers.IO) {
                // 3200 bytes = 1600 samples = 100ms PCM window @ 16kHz 16-bit Mono (Standard Google Gemini Live chunk)
                val audioBuffer = ByteArray(3200)
                while (isActive && isStreaming) {
                    val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readBytes > 0) {
                        val chunk = if (readBytes == audioBuffer.size) {
                            audioBuffer
                        } else {
                            audioBuffer.copyOf(readBytes)
                        }

                        // Turn-Aware Echo Suppression:
                        // When assistant is actively speaking, mute mic uplink to prevent
                        // speaker audio from leaking into Google VAD and causing stuttering.
                        val now = System.currentTimeMillis()
                        val isPlaying = isAssistantSpeaking || (now - lastAudioReceivedTime < 300)

                        if (isPlaying) {
                            continue // Suppress speaker feedback loop
                        }

                        // Boost mic gain 1.6x so Gemini Live VAD clearly hears user without shouting
                        val boostedChunk = boostPcmGain(chunk, 1.6f)
                        val base64Chunk = Base64.encodeToString(boostedChunk, Base64.NO_WRAP)
                        onAudioChunk(base64Chunk)
                    } else if (readBytes < 0) {
                        Log.w(TAG, "AudioRecord read returned error code: $readBytes")
                        kotlinx.coroutines.delay(40)
                    }
                }
            }

            // 2. Anti-Jitter Playback Loop
            playbackJob = scope.launch(Dispatchers.IO) {
                while (isActive && isStreaming) {
                    try {
                        val chunk = playbackQueue.poll(50, TimeUnit.MILLISECONDS)
                        if (chunk != null && audioTrack != null) {
                            isAssistantSpeaking = true
                            lastAudioReceivedTime = System.currentTimeMillis()
                            currentAudioEnergy = calculatePcmRms(chunk)
                            audioTrack?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                        } else {
                            // When queue drains, clear speaking flag and energy after 300ms tail
                            if (System.currentTimeMillis() - lastAudioReceivedTime > 300) {
                                isAssistantSpeaking = false
                                currentAudioEnergy = 0f
                            }
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

    fun isPlayingAudio(): Boolean {
        return isAssistantSpeaking || playbackQueue.isNotEmpty() || (System.currentTimeMillis() - lastAudioReceivedTime < 500)
    }

    private fun initAudioTrack(sharedSessionId: Int) {
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

            val builder = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minTrackBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && sharedSessionId != 0) {
                builder.setSessionId(sharedSessionId)
            }

            audioTrack = builder.build()
            audioTrack?.setVolume(1.0f)
            audioTrack?.play()
            Log.i(TAG, " AudioTrack ready (24kHz PCM, Paired Session: $sharedSessionId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}")
        }
    }

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
                    Log.i(TAG, " Audio Output -> LOUDSPEAKER")
                }
            } else {
                val earpiece = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                if (earpiece != null) {
                    audioManager.setCommunicationDevice(earpiece)
                    Log.i(TAG, " Audio Output -> EARPIECE")
                } else {
                    audioManager.clearCommunicationDevice()
                }
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = isLoudspeaker
        }
    }

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

    fun enqueueAudioChunk(pcmBase64: String) {
        try {
            val rawBytes = Base64.decode(pcmBase64, Base64.NO_WRAP)
            val amplifiedBytes = boostPcmGain(rawBytes, 2.2f)
            lastAudioReceivedTime = System.currentTimeMillis()
            isAssistantSpeaking = true
            playbackQueue.offer(amplifiedBytes)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode incoming PCM audio chunk: ${e.message}")
        }
    }

    fun flushOutputBuffer() {
        Log.i(TAG, " Output buffer flush request received")
        playbackQueue.clear()
        isAssistantSpeaking = false
        lastAudioReceivedTime = 0L
        currentAudioEnergy = 0f
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing AudioTrack: ${e.message}")
        }
    }

    fun stopStreaming() {
        isStreaming = false
        isAssistantSpeaking = false
        recordingJob?.cancel()
        playbackJob?.cancel()
        playbackQueue.clear()

        try { echoCanceler?.release() } catch (_: Exception) {}
        echoCanceler = null

        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null

        try { autoGainControl?.release() } catch (_: Exception) {}
        autoGainControl = null

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

        Log.i(TAG, " Voice stream stopped & audio hardware released.")
    }

    private fun calculatePcmRms(bytes: ByteArray): Float {
        if (bytes.size < 2) return 0f
        var sum = 0.0
        val sampleCount = bytes.size / 2
        for (i in 0 until sampleCount) {
            val sample = (bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)
            val shortSample = sample.toShort().toFloat() / 32768.0f
            sum += shortSample * shortSample
        }
        val rms = Math.sqrt(sum / sampleCount).toFloat()
        return (rms * 3.5f).coerceIn(0f, 1f)
    }
}
