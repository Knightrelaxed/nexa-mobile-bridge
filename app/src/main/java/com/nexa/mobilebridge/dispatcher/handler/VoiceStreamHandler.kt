package com.nexa.mobilebridge.dispatcher.handler

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
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
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

enum class AudioOutputRoute(val label: String) {
    EARPIECE("Earpiece"),
    SPEAKER("Loudspeaker"),
    BLUETOOTH("Bluetooth Headset"),
    WIRED_HEADSET("Headset Kabel")
}

/**
 * VoiceStreamHandler — Ultra-Reliable Full-Duplex PCM Audio Streamer
 * Tuned for Google Gemini Multimodal Live API Real-Time Voice Calls.
 *
 * Architecture:
 * 1. Continuous Full-Duplex Uplink: Pure 1:1 unclipped 16kHz audio transmission without digital zero-gating.
 * 2. Hardware Session Pairing: AEC coupled to AudioTrack hardware session to cancel speaker feedback natively.
 * 3. Anti-Jitter Playback: Continuous non-blocking playback queue with zero stutter.
 * 4. Dynamic Hardware Routing: Automatic smart switching between Bluetooth (TWS), Wired Headset, Earpiece, and Loudspeaker.
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

    @Volatile
    private var totalFramesWritten: Long = 0L

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

    @Volatile
    var currentRoute: AudioOutputRoute = AudioOutputRoute.EARPIECE
        private set

    var onAudioRouteChanged: ((AudioOutputRoute) -> Unit)? = null

    private var userExplicitSpeaker: Boolean = false
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var communicationDeviceListener: AudioManager.OnCommunicationDeviceChangedListener? = null

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
            registerAudioDeviceListener()
            applySmartAudioRoute()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                audioRecord?.addOnRoutingChangedListener({ record ->
                    val routed = record.routedDevice
                    Log.i(TAG, "[AUDIO_ROUTE] AudioRecord actively routed to: ${routed?.productName} (type=${routed?.type})")
                }, Handler(Looper.getMainLooper()))
            }

            // Bind hardware effects to unified session
            // Only AcousticEchoCanceler (AEC) is activated. Manual NoiseSuppressor (NS) and AutomaticGainControl (AGC)
            // are intentionally disabled: mobile hardware NS clips weak consonant onsets ('s', 'k', 't', 'p', 'h')
            // and hardware AGC pumps ambient noise into cloud ASR, destabilizing Gemini Multimodal Live recognition.
            if (hardwareSessionId != 0) {
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        echoCanceler = AcousticEchoCanceler.create(hardwareSessionId)?.apply {
                            enabled = true
                            Log.i(TAG, "Hardware AEC Active (Session: $hardwareSessionId)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Hardware AEC unavailable: ${e.message}")
                    }
                }
                updateHardwareEffectsForRoute(currentRoute)
            }

            audioRecord?.startRecording()
            Log.i(TAG, "AudioRecord active (16kHz PCM, AEC Coupled)")

            // 1. Microphone Loop (Continuous Full-Duplex PCM Stream)
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

                        // Pure 1:1 unclipped 16kHz audio transmission to Google Gemini Live.
                        // Hardware AEC natively cancels loudspeaker feedback without digital zero-gating,
                        // eliminating artificial audio dropouts and enabling smooth barge-in.
                        val base64Chunk = Base64.encodeToString(chunk, Base64.NO_WRAP)
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
                            val bytesWritten = audioTrack?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING) ?: 0
                            if (bytesWritten > 0) {
                                totalFramesWritten += (bytesWritten / 2)
                            }
                        } else {
                            // When queue drains, verify if hardware DAC has really finished playing buffered frames
                            if (!isReallyPlaying()) {
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

    fun isReallyPlaying(): Boolean {
        val track = audioTrack ?: return false
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return false
        val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return (totalFramesWritten > head) || playbackQueue.isNotEmpty()
    }

    fun isPlayingAudio(): Boolean {
        return isAssistantSpeaking || isReallyPlaying()
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
            totalFramesWritten = 0L
            audioTrack?.setVolume(1.0f)
            audioTrack?.play()
            Log.i(TAG, "AudioTrack ready (24kHz PCM, Paired Session: $sharedSessionId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}")
        }
    }

    private fun isBluetoothDevice(device: AudioDeviceInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            device.type == AudioDeviceInfo.TYPE_HEARING_AID ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        } else {
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }

    private fun isWiredHeadsetDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
               device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
               device.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun isHeadsetDevice(device: AudioDeviceInfo): Boolean {
        return isBluetoothDevice(device) || isWiredHeadsetDevice(device)
    }

    private fun registerAudioDeviceListener() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioDeviceCallback == null) {
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    val hasNewHeadset = addedDevices?.any { isHeadsetDevice(it) } == true
                    Log.i(TAG, "[AUDIO_ROUTE] Audio devices plugged in. Headset attached: $hasNewHeadset")
                    if (hasNewHeadset) {
                        userExplicitSpeaker = false
                    }
                    applySmartAudioRoute()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    val hadHeadset = removedDevices?.any { isHeadsetDevice(it) } == true
                    Log.i(TAG, "[AUDIO_ROUTE] Audio devices removed. Headset detached: $hadHeadset")
                    applySmartAudioRoute()
                }
            }
            audioDeviceCallback = callback
            try {
                audioManager.registerAudioDeviceCallback(callback, null)
                Log.i(TAG, "[AUDIO_ROUTE] AudioDeviceCallback registered successfully")
            } catch (e: Exception) {
                Log.w(TAG, "[AUDIO_ROUTE] Failed to register AudioDeviceCallback: ${e.message}")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && communicationDeviceListener == null) {
            val commListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                Log.i(TAG, "[AUDIO_ROUTE] Communication device changed by system: ${device?.productName} (type=${device?.type})")
                val isBt = device != null && isBluetoothDevice(device)
                val isWired = device != null && isWiredHeadsetDevice(device)
                val isSpk = device != null && device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                val newRoute = when {
                    userExplicitSpeaker || isSpk -> AudioOutputRoute.SPEAKER
                    isBt -> AudioOutputRoute.BLUETOOTH
                    isWired -> AudioOutputRoute.WIRED_HEADSET
                    else -> AudioOutputRoute.EARPIECE
                }
                if (currentRoute != newRoute) {
                    currentRoute = newRoute
                    isLoudspeakerActive = (currentRoute == AudioOutputRoute.SPEAKER)
                    updateHardwareEffectsForRoute(currentRoute)
                    onAudioRouteChanged?.invoke(currentRoute)
                }
            }
            communicationDeviceListener = commListener
            try {
                audioManager.addOnCommunicationDeviceChangedListener(context.mainExecutor, commListener)
                Log.i(TAG, "[AUDIO_ROUTE] OnCommunicationDeviceChangedListener registered")
            } catch (e: Exception) {
                Log.w(TAG, "[AUDIO_ROUTE] Could not register OnCommunicationDeviceChangedListener: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun applySmartAudioRoute(forceSpeaker: Boolean? = null) {
        if (forceSpeaker != null) {
            userExplicitSpeaker = forceSpeaker
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val available = audioManager.availableCommunicationDevices
                val currentComm = audioManager.communicationDevice

                if (userExplicitSpeaker) {
                    val speaker = available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (currentComm?.id != speaker?.id) {
                        if (speaker != null) {
                            val ok = audioManager.setCommunicationDevice(speaker)
                            Log.i(TAG, "[AUDIO_ROUTE] Forced LOUDSPEAKER (success=$ok)")
                        } else {
                            audioManager.clearCommunicationDevice()
                            Log.i(TAG, "[AUDIO_ROUTE] Cleared communication device -> Default Speaker")
                        }
                    }
                    currentRoute = AudioOutputRoute.SPEAKER
                    audioRecord?.preferredDevice = null
                    audioTrack?.preferredDevice = speaker
                } else {
                    // Priority 1: Bluetooth Headset (SCO preferred, then BLE, then any BT)
                    val btDevice = available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                        ?: available.firstOrNull { isBluetoothDevice(it) }

                    if (btDevice != null) {
                        if (currentComm?.id != btDevice.id) {
                            val ok = audioManager.setCommunicationDevice(btDevice)
                            Log.i(TAG, "[AUDIO_ROUTE] Auto-routed -> BLUETOOTH (${btDevice.productName}, type=${btDevice.type}, success=$ok)")
                        }
                        currentRoute = AudioOutputRoute.BLUETOOTH

                        // Explicitly assign Bluetooth input device to AudioRecord (Microphone)
                        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        val btInput = inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                            ?: inputDevices.firstOrNull { isBluetoothDevice(it) }

                        if (btInput != null) {
                            val okInput = audioRecord?.setPreferredDevice(btInput) ?: false
                            Log.i(TAG, "[AUDIO_ROUTE] Explicitly assigned AudioRecord preferred input -> ${btInput.productName} (type=${btInput.type}, ok=$okInput)")
                        }
                        audioTrack?.preferredDevice = btDevice
                    } else {
                        // Priority 2: Wired / USB Headset
                        val wiredDevice = available.firstOrNull { isWiredHeadsetDevice(it) }
                        if (wiredDevice != null) {
                            if (currentComm?.id != wiredDevice.id) {
                                val ok = audioManager.setCommunicationDevice(wiredDevice)
                                Log.i(TAG, "[AUDIO_ROUTE] Auto-routed -> WIRED_HEADSET (${wiredDevice.productName}, type=${wiredDevice.type}, success=$ok)")
                            }
                            currentRoute = AudioOutputRoute.WIRED_HEADSET

                            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                            val wiredInput = inputDevices.firstOrNull { isWiredHeadsetDevice(it) }
                            if (wiredInput != null) {
                                audioRecord?.setPreferredDevice(wiredInput)
                            }
                            audioTrack?.preferredDevice = wiredDevice
                        } else {
                            // Priority 3: Clear communication device so Android auto-routes to Earpiece
                            if (currentComm != null) {
                                audioManager.clearCommunicationDevice()
                            }
                            currentRoute = AudioOutputRoute.EARPIECE
                            audioRecord?.preferredDevice = null
                            audioTrack?.preferredDevice = null
                            Log.i(TAG, "[AUDIO_ROUTE] Natural Earpiece active (no headset detected)")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[AUDIO_ROUTE] Error updating communication device: ${e.message}")
            }
        } else {
            // Android 11 and lower
            try {
                if (userExplicitSpeaker) {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                    currentRoute = AudioOutputRoute.SPEAKER
                    Log.i(TAG, "[AUDIO_ROUTE] Legacy LOUDSPEAKER active")
                } else {
                    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    val hasBt = devices.any { isBluetoothDevice(it) }
                    val hasWired = devices.any { isWiredHeadsetDevice(it) }

                    if (hasBt) {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                        currentRoute = AudioOutputRoute.BLUETOOTH
                        Log.i(TAG, "[AUDIO_ROUTE] Legacy BLUETOOTH SCO active")
                    } else if (hasWired) {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                        currentRoute = AudioOutputRoute.WIRED_HEADSET
                        Log.i(TAG, "[AUDIO_ROUTE] Legacy WIRED HEADSET active")
                    } else {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                        currentRoute = AudioOutputRoute.EARPIECE
                        Log.i(TAG, "[AUDIO_ROUTE] Legacy EARPIECE active")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[AUDIO_ROUTE] Legacy audio route error: ${e.message}")
            }
        }

        isLoudspeakerActive = (currentRoute == AudioOutputRoute.SPEAKER)
        updateHardwareEffectsForRoute(currentRoute)
        onAudioRouteChanged?.invoke(currentRoute)

        // Ensure voice call stream volume is audibly set without requiring digital PCM clipping
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            if (currentVol < (maxVol * 0.75f).toInt()) {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.85f).toInt(), 0)
                Log.i(TAG, "[AUDIO] Voice call volume optimized: ${(maxVol * 0.85f).toInt()}/$maxVol")
            }
        } catch (_: Exception) {}
    }

    private fun updateHardwareEffectsForRoute(route: AudioOutputRoute) {
        val isBt = (route == AudioOutputRoute.BLUETOOTH)
        try {
            // When Bluetooth headset is connected, headset hardware already performs AEC.
            // Phone AEC is disabled for Bluetooth to prevent phase cancellation artifacts.
            echoCanceler?.enabled = !isBt
            Log.i(TAG, "[DSP] Hardware AEC set to: ${!isBt} (Route: $route)")
        } catch (e: Exception) {
            Log.w(TAG, "[DSP] Failed to toggle hardware effects: ${e.message}")
        }
    }

    fun setLoudspeaker(isLoudspeaker: Boolean) {
        applySmartAudioRoute(forceSpeaker = isLoudspeaker)
    }

    fun toggleAudioRoute() {
        if (currentRoute == AudioOutputRoute.SPEAKER) {
            applySmartAudioRoute(forceSpeaker = false)
        } else {
            applySmartAudioRoute(forceSpeaker = true)
        }
    }

    fun enqueueAudioChunk(pcmBase64: String) {
        try {
            val rawBytes = Base64.decode(pcmBase64, Base64.NO_WRAP)
            lastAudioReceivedTime = System.currentTimeMillis()
            isAssistantSpeaking = true
            playbackQueue.offer(rawBytes)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode incoming PCM audio chunk: ${e.message}")
        }
    }

    fun flushOutputBuffer() {
        Log.i(TAG, "Output buffer flush request received")
        playbackQueue.clear()
        isAssistantSpeaking = false
        lastAudioReceivedTime = 0L
        currentAudioEnergy = 0f
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            totalFramesWritten = (audioTrack?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL
            audioTrack?.play()
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing AudioTrack: ${e.message}")
        }
    }

    fun stopStreaming() {
        isStreaming = false
        isAssistantSpeaking = false
        totalFramesWritten = 0L
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
        audioDeviceCallback?.let {
            try {
                audioManager.unregisterAudioDeviceCallback(it)
            } catch (_: Exception) {}
        }
        audioDeviceCallback = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            communicationDeviceListener?.let {
                try {
                    audioManager.removeOnCommunicationDeviceChangedListener(it)
                } catch (_: Exception) {}
            }
            communicationDeviceListener = null
            try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
        } else {
            try {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            } catch (_: Exception) {}
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        userExplicitSpeaker = false
        currentRoute = AudioOutputRoute.EARPIECE
        isLoudspeakerActive = false

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
