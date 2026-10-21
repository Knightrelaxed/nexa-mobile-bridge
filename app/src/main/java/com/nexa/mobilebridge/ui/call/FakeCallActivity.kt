package com.nexa.mobilebridge.ui.call

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.graphics.drawable.Icon
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import com.nexa.mobilebridge.service.ScreenCaptureService
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.text.font.FontStyle
import com.nexa.mobilebridge.dispatcher.handler.AudioOutputRoute
import com.nexa.mobilebridge.dispatcher.handler.CameraStreamHandler
import com.nexa.mobilebridge.dispatcher.handler.VoiceStreamHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nexa.mobilebridge.NexaBridgeService
import com.nexa.mobilebridge.core.network.LiveCallEventListener
import com.nexa.mobilebridge.dispatcher.handler.AudioPlayerHandler
import com.nexa.mobilebridge.dispatcher.handler.TtsHandler
import com.nexa.mobilebridge.dispatcher.handler.VoiceRecorderHandler
import com.nexa.mobilebridge.protocol.CallEvent
import com.nexa.mobilebridge.protocol.NexaActions
import com.nexa.mobilebridge.protocol.NexaCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

// ─── State Machine ────────────────────────────────────────────────────────────
enum class CallState {
    RINGING,            // Incoming call — show swipe buttons
    ACCEPTED_SPEAKING,  // User accepted — N.E.X.A speaks, no buttons
    RECORDING,          // N.E.X.A done speaking — recording user voice (10s countdown)
    WAITING_REPLY,      // Recording done — waiting for server audio reply
    REJECTED_SPEAKING,  // User rejected — N.E.X.A speaks last message, no buttons
    FINISHED            // Activity will call finish()
}

// ─── Live Voice Diagnostic HUD State ──────────────────────────────────────────
enum class LiveVoiceHudState(val label: String, val colorHex: Long) {
    LISTENING("Mendengarkan..", 0xFF64FFDA),        // Cyan Glow
    THINKING("Berpikir..", 0xFF10B981),             // Emerald Green
    PROCESSING("Mengerjakan..", 0xFFF59E0B),        // Amber Gold
    SPEAKING("Mentransmisikan..", 0xFF38BDF8),       // Electric Blue
    SLOW_NETWORK("Jaringan Lemah..", 0xFFFB923C),   // Orange Coral
    RECONNECTING("Memulihkan Koneksi..", 0xFFEF4444),// Crimson Red
    DISCONNECTING("Mengakhiri..", 0xFF94A3B8)       // Slate Gray
}

class FakeCallActivity : ComponentActivity() {

    private val TAG = "FakeCallActivity"

    private lateinit var ttsHandler: TtsHandler
    private lateinit var audioPlayerHandler: AudioPlayerHandler
    private lateinit var voiceRecorderHandler: VoiceRecorderHandler
    private lateinit var voiceStreamHandler: com.nexa.mobilebridge.dispatcher.handler.VoiceStreamHandler
    private lateinit var cameraStreamHandler: CameraStreamHandler
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var commandId: String = ""
    private var callerName: String = "N.E.X.A Assistant"
    private var callMessage: String = "Tuan Faqih, ada interupsi penting dari N.E.X.A."

    companion object {
        private const val TAG = "FakeCallActivity"

        @Volatile
        var isCallActive: Boolean = false

        const val ACTION_PIP_TOGGLE_SPEAKER = "com.nexa.mobilebridge.PIP_ACTION_TOGGLE_SPEAKER"
        const val ACTION_PIP_END_CALL = "com.nexa.mobilebridge.PIP_ACTION_END_CALL"
    }

    // Mutable state hoisted to Activity level so coroutines and PiP mode can observe it
    private val callState = mutableStateOf(CallState.RINGING)
    private val hudState = mutableStateOf(LiveVoiceHudState.LISTENING)
    private val recordingElapsedMs = mutableLongStateOf(0L)
    private val isInPipMode = mutableStateOf(false)
    private val isLoudspeaker = mutableStateOf(false)
    private val audioRoute = mutableStateOf(AudioOutputRoute.EARPIECE)
    private val isCameraActive = mutableStateOf(false)
    private val isBackCamera = mutableStateOf(true)
    private val isMicMuted = mutableStateOf(false)
    private val isScreenShareActive = mutableStateOf(false)
    private val assistantMessage = mutableStateOf("")
    private val userTranscript = mutableStateOf("")
    private var callScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentSurfaceProvider: androidx.camera.core.Preview.SurfaceProvider? = null

    private fun toggleMicMute() {
        isMicMuted.value = !isMicMuted.value
        voiceStreamHandler.setMicrophoneMuted(isMicMuted.value)
        ScreenCaptureService.isMicMuted.value = isMicMuted.value
        Log.i(TAG, "Microphone mute state updated: ${isMicMuted.value}")
    }

    private fun interruptAssistant() {
        voiceStreamHandler.flushOutputBuffer()
        hudState.value = LiveVoiceHudState.LISTENING
        pendingStateEntryTime = 0L
        Log.i(TAG, "Assistant interrupted by user touch")
    }

    private fun selectAudioRoute(route: AudioOutputRoute) {
        voiceStreamHandler.setExplicitAudioRoute(route)
        audioRoute.value = voiceStreamHandler.currentRoute
        isLoudspeaker.value = (audioRoute.value == AudioOutputRoute.SPEAKER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                setPictureInPictureParams(buildPipParams())
            } catch (_: Exception) {}
        }
        Log.i(TAG, "Audio route selected: $route")
    }


    // Tracks when hudState entered THINKING or PROCESSING (used by watchdog to detect true slow network)
    @Volatile
    private var pendingStateEntryTime: Long = 0L

    // Track how many times user has rejected (sent to server for importance evaluation)
    private var rejectionCount = 0

    // Zero-IPC Direct Live Call Event Listener (Direct background path to avoid Main Thread Binder stalls)
    private val liveCallEventListener = object : LiveCallEventListener {
        override fun onAudioPlay(pcmChunk: String) {
            voiceStreamHandler.enqueueAudioChunk(pcmChunk)
            if (hudState.value != LiveVoiceHudState.SPEAKING && hudState.value != LiveVoiceHudState.DISCONNECTING) {
                runOnUiThread {
                    if (hudState.value != LiveVoiceHudState.DISCONNECTING) {
                        hudState.value = LiveVoiceHudState.SPEAKING
                    }
                    pendingStateEntryTime = 0L
                }
            }
        }

        override fun onAudioInterrupted() {
            voiceStreamHandler.flushOutputBuffer()
            runOnUiThread {
                hudState.value = LiveVoiceHudState.LISTENING
                pendingStateEntryTime = 0L
            }
        }

        override fun onLiveReady() {
            runOnUiThread {
                hudState.value = LiveVoiceHudState.LISTENING
                pendingStateEntryTime = 0L
            }
        }

        override fun onStatusUpdate(status: String?, text: String?, userTranscriptVal: String?) {
            runOnUiThread {
                if (status != null) {
                    when (status) {
                        "DISCONNECTING" -> {
                            hudState.value = LiveVoiceHudState.DISCONNECTING
                            pendingStateEntryTime = 0L
                        }
                        "THINKING" -> {
                            hudState.value = LiveVoiceHudState.LISTENING
                            pendingStateEntryTime = 0L
                        }
                        "PROCESSING" -> {
                            hudState.value = LiveVoiceHudState.PROCESSING
                            pendingStateEntryTime = System.currentTimeMillis()
                        }
                        "SPEAKING" -> {
                            if (hudState.value != LiveVoiceHudState.DISCONNECTING) {
                                hudState.value = LiveVoiceHudState.SPEAKING
                            }
                            pendingStateEntryTime = 0L
                        }
                        "LISTENING" -> {
                            if (hudState.value != LiveVoiceHudState.DISCONNECTING) {
                                hudState.value = LiveVoiceHudState.LISTENING
                            }
                            pendingStateEntryTime = 0L
                        }
                        "RECONNECTING" -> {
                            hudState.value = LiveVoiceHudState.RECONNECTING
                            pendingStateEntryTime = 0L
                        }
                    }
                }
                if (!text.isNullOrBlank()) {
                    assistantMessage.value = text
                    ScreenCaptureService.assistantSubtitle.value = text
                }
                if (!userTranscriptVal.isNullOrBlank()) {
                    userTranscript.value = userTranscriptVal
                }
            }
        }
    }

    // BroadcastReceiver for server audio events and PiP RemoteAction controls
    private val callReplyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_TOGGLE_SPEAKER) {
                voiceStreamHandler.toggleAudioRoute()
                val current = voiceStreamHandler.currentRoute
                audioRoute.value = current
                isLoudspeaker.value = (current == AudioOutputRoute.SPEAKER)
                Log.i(TAG, "PiP RemoteAction: Audio route toggled -> $current")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        setPictureInPictureParams(buildPipParams())
                    } catch (e: Exception) {}
                }
            } else if (intent?.action == ACTION_PIP_END_CALL) {
                Log.i(TAG, "PiP RemoteAction: End Call triggered")
                handleReject()
            } else if (intent?.action == "com.nexa.mobilebridge.CALL_REPLY_COMPLETE" || intent?.action == "com.nexa.mobilebridge.END_CALL") {
                Log.i(TAG, "End Call signal received — waiting for farewell audio playback to finish cleanly")
                hudState.value = LiveVoiceHudState.DISCONNECTING
                lifecycleScope.launch(Dispatchers.Main) {
                    var waitLoops = 0
                    while (voiceStreamHandler.isPlayingAudio() && waitLoops < 60) {
                        kotlinx.coroutines.delay(50)
                        waitLoops++
                    }
                    kotlinx.coroutines.delay(300) // Natural silence tail before closing UI
                    voiceStreamHandler.stopStreaming()
                    callState.value = CallState.FINISHED
                }
            } else if (intent?.action == "com.nexa.mobilebridge.CALL_LIVE_EVENT") {
                // If direct listener is already attached, skip broadcast processing to prevent double handling & UI lag
                if (NexaBridgeService.activeWebSocketClient?.liveCallListener != null) return
                val rawJson = intent.getStringExtra("raw_json") ?: return
                if (rawJson.contains("CALL_AUDIO_PLAY")) {
                    if (hudState.value != LiveVoiceHudState.DISCONNECTING) {
                        hudState.value = LiveVoiceHudState.SPEAKING
                    }
                    pendingStateEntryTime = 0L // Reset — no longer pending
                    val pcmRegex = Regex("\"pcm_chunk\"\\s*:\\s*\"([^\"]+)\"")
                    val match = pcmRegex.find(rawJson)
                    val pcmChunk = match?.groups?.get(1)?.value
                    if (pcmChunk != null) {
                        voiceStreamHandler.enqueueAudioChunk(pcmChunk)
                    }
                } else if (rawJson.contains("CALL_STATUS_UPDATE")) {
                    if (rawJson.contains("\"DISCONNECTING\"")) {
                        hudState.value = LiveVoiceHudState.DISCONNECTING
                        pendingStateEntryTime = 0L
                    } else if (hudState.value == LiveVoiceHudState.DISCONNECTING) {
                        // Ignore state resets if call is already disconnecting
                    } else if (rawJson.contains("\"THINKING\"")) {
                        // Gemini Live thinks while speaking in parallel — retain clean symmetrical LISTENING eyes
                        hudState.value = LiveVoiceHudState.LISTENING
                        pendingStateEntryTime = 0L
                    } else if (rawJson.contains("\"PROCESSING\"")) {
                        // Backend tool / task execution — activate Amber Gold Mengerjakan visor eyes
                        hudState.value = LiveVoiceHudState.PROCESSING
                        pendingStateEntryTime = System.currentTimeMillis()
                    } else if (rawJson.contains("\"SPEAKING\"")) {
                        hudState.value = LiveVoiceHudState.SPEAKING
                        pendingStateEntryTime = 0L
                    } else if (rawJson.contains("\"LISTENING\"")) {
                        hudState.value = LiveVoiceHudState.LISTENING
                        pendingStateEntryTime = 0L
                    } else if (rawJson.contains("\"RECONNECTING\"")) {
                        hudState.value = LiveVoiceHudState.RECONNECTING
                        pendingStateEntryTime = 0L
                    }
                } else if (rawJson.contains("CALL_AUDIO_INTERRUPTED")) {
                    voiceStreamHandler.flushOutputBuffer()
                    hudState.value = LiveVoiceHudState.LISTENING
                    pendingStateEntryTime = 0L
                }

                // Dynamic transcript/text updates from server if present
                try {
                    val jsonObj = org.json.JSONObject(rawJson)
                    if (jsonObj.has("text")) {
                        val tVal = jsonObj.optString("text")
                        if (tVal.isNotBlank()) {
                            assistantMessage.value = tVal
                            ScreenCaptureService.assistantSubtitle.value = tVal
                        }
                    }
                    if (jsonObj.has("user_transcript")) {
                        val uVal = jsonObj.optString("user_transcript")
                        if (uVal.isNotBlank()) {
                            userTranscript.value = uVal
                        }
                    }
                } catch (_: Exception) {
                    val textRegex = Regex("\"text\"\\s*:\\s*\"([^\"]+)\"")
                    val textMatch = textRegex.find(rawJson)
                    if (textMatch != null) {
                        val tVal = textMatch.groups[1]?.value
                        if (!tVal.isNullOrBlank()) {
                            assistantMessage.value = tVal
                            ScreenCaptureService.assistantSubtitle.value = tVal
                        }
                    }
                    val uRegex = Regex("\"user_transcript\"\\s*:\\s*\"([^\"]+)\"")
                    val uMatch = uRegex.find(rawJson)
                    if (uMatch != null) {
                        val uVal = uMatch.groups[1]?.value
                        if (!uVal.isNullOrBlank()) {
                            userTranscript.value = uVal
                        }
                    }
                }
            }
        }
    }

    // Runtime permission launcher for RECORD_AUDIO & BLUETOOTH_CONNECT
    private val callPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false
        } else true
        Log.i(TAG, "Permissions check: RECORD_AUDIO=$audioGranted, BLUETOOTH_CONNECT=$btGranted")
        if (audioGranted) {
            Log.i(TAG, "RECORD_AUDIO permission granted — starting live stream")
            startContinuousLiveVoice()
        } else {
            Log.w(TAG, "RECORD_AUDIO permission denied — closing call")
            sendCallEvent(NexaActions.CALL_REJECTED)
            callState.value = CallState.FINISHED
        }
    }

    // Runtime permission launcher for RECORD_AUDIO (single permission fallback)
    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "RECORD_AUDIO permission granted")
            if (callState.value == CallState.RECORDING) {
                startVoiceRecording()
            } else {
                startContinuousLiveVoice()
            }
        } else {
            Log.w(TAG, "RECORD_AUDIO permission denied — closing call")
            sendCallEvent(NexaActions.CALL_REJECTED)
            callState.value = CallState.FINISHED
        }
    }

    // Runtime permission launcher for CAMERA (Live Video Call)
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "CAMERA permission granted — starting camera stream")
            startCameraStream()
        } else {
            Log.w(TAG, "CAMERA permission denied by user")
            isCameraActive.value = false
        }
    }

    // Runtime launcher for Screen Capture (MediaProjection)
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.i(TAG, "Screen capture permission granted by user")
            if (isCameraActive.value) {
                stopCameraStream()
            }
            isScreenShareActive.value = true
            ScreenCaptureService.start(
                context = this,
                resultCode = result.resultCode,
                data = result.data!!,
                commandId = commandId
            )
            // Allow ScreenCaptureService to startForeground with microphone type before moving activity to back
            window.decorView.postDelayed({
                if (isScreenShareActive.value && !isFinishing) {
                    moveTaskToBack(true)
                }
            }, 350L)
        } else {
            Log.w(TAG, "Screen capture permission denied by user")
            isScreenShareActive.value = false
        }
    }

    private fun toggleScreenShare() {
        if (ScreenCaptureService.isRunning || isScreenShareActive.value) {
            Log.i(TAG, "Stopping ScreenCaptureService")
            ScreenCaptureService.stop(this)
            isScreenShareActive.value = false
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(
                    this,
                    "Mohon izinkan N.E.X.A menampilkan overlay di atas aplikasi lain",
                    Toast.LENGTH_LONG
                ).show()
                val overlayIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(overlayIntent)
                return
            }
            Log.i(TAG, "Launching MediaProjection screen capture intent")
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCallActive = true
        try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } catch (_: Exception) {}
        setupShowWhenLocked()

        ttsHandler = TtsHandler(applicationContext)
        audioPlayerHandler = AudioPlayerHandler(applicationContext)
        voiceRecorderHandler = VoiceRecorderHandler(applicationContext)
        voiceStreamHandler = VoiceStreamHandler(applicationContext)
        NexaBridgeService.activeWebSocketClient?.liveCallListener = liveCallEventListener
        voiceStreamHandler.onAudioRouteChanged = { route ->
            lifecycleScope.launch(Dispatchers.Main) {
                audioRoute.value = route
                isLoudspeaker.value = (route == AudioOutputRoute.SPEAKER)
                Log.i(TAG, "Observed audio route change: $route")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode.value) {
                    try {
                        setPictureInPictureParams(buildPipParams())
                    } catch (_: Exception) {}
                }
            }
        }
        cameraStreamHandler = CameraStreamHandler(applicationContext)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Hook up ScreenCaptureService floating overlay callbacks
        ScreenCaptureService.onEndCallRequested = {
            runOnUiThread { handleReject() }
        }
        ScreenCaptureService.onToggleMicMuteRequested = {
            runOnUiThread { toggleMicMute() }
        }
        ScreenCaptureService.onInterruptRequested = {
            runOnUiThread { interruptAssistant() }
        }
        ScreenCaptureService.onReturnToCallRequested = {
            val returnIntent = Intent(this@FakeCallActivity, FakeCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(returnIntent)
        }

        // Keep ScreenCaptureService reactive states synchronized with live engine
        lifecycleScope.launch {
            while (isActive) {
                if (ScreenCaptureService.isRunning) {
                    ScreenCaptureService.audioEnergy.value = voiceStreamHandler.currentAudioEnergy
                    ScreenCaptureService.isAssistantSpeaking.value = voiceStreamHandler.isPlayingAudio()
                    ScreenCaptureService.isMicMuted.value = isMicMuted.value
                }
                if (isScreenShareActive.value != ScreenCaptureService.isSharingActive.value) {
                    isScreenShareActive.value = ScreenCaptureService.isSharingActive.value
                }
                delay(100L)
            }
        }

        commandId = intent.getStringExtra("command_id") ?: "call_${System.currentTimeMillis()}"
        callerName = intent.getStringExtra("caller_name") ?: "N.E.X.A Voice Bridge"
        callMessage = intent.getStringExtra("message") ?: "Halo Tuan Faqih, saya mendengarkan."
        val isOutgoingCall = intent.getBooleanExtra("IS_OUTGOING_CALL", false) || (intent.action == "com.nexa.mobilebridge.ACTION_CALL_NEXA")
        val playRingtone = if (isOutgoingCall) false else intent.getBooleanExtra("play_ringtone", true)

        // Register receiver for server live audio & reply events as well as PiP RemoteActions
        val filter = IntentFilter().apply {
            addAction("com.nexa.mobilebridge.CALL_REPLY_COMPLETE")
            addAction("com.nexa.mobilebridge.CALL_LIVE_EVENT")
            addAction("com.nexa.mobilebridge.END_CALL")
            addAction(ACTION_PIP_TOGGLE_SPEAKER)
            addAction(ACTION_PIP_END_CALL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callReplyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(callReplyReceiver, filter)
        }

        // VoIP Background Navigation: Back button enters Picture-in-Picture (WhatsApp style)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val state = callState.value
                if (state == CallState.ACCEPTED_SPEAKING || state == CallState.RECORDING || state == CallState.WAITING_REPLY) {
                    Log.i(TAG, "Back pressed during active call — entering Picture-in-Picture (WhatsApp style)")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            val entered = enterPictureInPictureMode(buildPipParams())
                            if (!entered) {
                                moveTaskToBack(true)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to enter PiP: ${e.message}")
                            moveTaskToBack(true)
                        }
                    } else {
                        moveTaskToBack(true)
                    }
                } else if (state == CallState.RINGING) {
                    handleReject()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setContent {
            // Observe state for recomposition
            val state by callState
            val currentHudState by hudState
            val elapsedMs by recordingElapsedMs
            val inPip by isInPipMode
            val loudspeakerActive by isLoudspeaker
            val currentAudioRoute by audioRoute
            val cameraActive by isCameraActive
            val backCamera by isBackCamera
            val micMuted by isMicMuted
            val screenShareActive by isScreenShareActive
            val asstText by assistantMessage
            val userText by userTranscript

            // Start call flow immediately on UI render
            LaunchedEffect(Unit) {
                if (isOutgoingCall) {
                    Log.i(TAG, "Outgoing call to N.E.X.A initiated — connecting live session immediately")
                    handleAccept()
                } else if (playRingtone) {
                    audioPlayerHandler.playRingtone(
                        NexaCommand(
                            type = "EXECUTE_COMMAND",
                            command_id = commandId,
                            action = "PLAY_RINGTONE",
                            params = buildJsonObject {}
                        )
                    )
                }
            }

            // Auto-finish and remove task completely when state becomes FINISHED
            LaunchedEffect(state) {
                if (state == CallState.FINISHED) {
                    cancelOngoingCallNotification()
                    delay(300L)
                    stopCameraStream()
                    try { ScreenCaptureService.stop(this@FakeCallActivity) } catch (_: Exception) {}
                    voiceStreamHandler.stopStreaming()
                    finishAndRemoveTask()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, android.R.anim.fade_out)
                }
            }

            FakeCallScreen(
                callerName = callerName,
                callMessage = callMessage,
                callState = state,
                hudState = currentHudState,
                isInPipMode = inPip,
                isSpeaking = voiceStreamHandler.isPlayingAudio(),
                audioEnergy = voiceStreamHandler.currentAudioEnergy,
                isLoudspeaker = loudspeakerActive,
                audioRoute = currentAudioRoute,
                recordingElapsedMs = elapsedMs,
                isCameraActive = cameraActive,
                isBackCamera = backCamera,
                isMicMuted = micMuted,
                isScreenShareActive = screenShareActive,
                availableAudioRoutes = voiceStreamHandler.getAvailableAudioRoutes(),
                assistantText = asstText,
                userTranscriptText = userText,
                onAnswer = { handleAccept() },
                onReject = { handleReject() },
                onEyeTapped = { target, tapCount -> handleTactileEyeTap(target, tapCount) },
                onToggleMicMute = { toggleMicMute() },
                onToggleScreenShare = { toggleScreenShare() },
                onSelectAudioRoute = { route -> selectAudioRoute(route) },
                onInterruptAssistant = { interruptAssistant() },
                onEnterPiP = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            enterPictureInPictureMode(buildPipParams())
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to enter PiP: ${e.message}")
                        }
                    }
                },
                onToggleLoudspeaker = { enabled ->
                    voiceStreamHandler.setLoudspeaker(enabled)
                    val current = voiceStreamHandler.currentRoute
                    audioRoute.value = current
                    isLoudspeaker.value = (current == AudioOutputRoute.SPEAKER)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            setPictureInPictureParams(buildPipParams())
                        } catch (e: Exception) {}
                    }
                },
                onToggleAudioRoute = {
                    voiceStreamHandler.toggleAudioRoute()
                    val current = voiceStreamHandler.currentRoute
                    audioRoute.value = current
                    isLoudspeaker.value = (current == AudioOutputRoute.SPEAKER)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            setPictureInPictureParams(buildPipParams())
                        } catch (e: Exception) {}
                    }
                },
                onToggleCamera = { toggleCamera() },
                onSwitchCamera = { switchCameraLens() },
                onPreviewSurfaceReady = { provider ->
                    currentSurfaceProvider = provider
                    cameraStreamHandler.setPreviewSurfaceProvider(provider)
                    if (isCameraActive.value) {
                        cameraStreamHandler.startCamera(
                            lifecycleOwner = this@FakeCallActivity,
                            useBackCamera = isBackCamera.value,
                            previewSurface = provider
                        ) { frameBase64 ->
                            sendVideoFrame(frameBase64)
                        }
                    }
                }
            )
        }
    }


    // ─── Accept Handler (Live Voice Streaming Mode) ──────────────────────────
    private fun handleAccept() {
        if (callState.value != CallState.RINGING) return
        callState.value = CallState.ACCEPTED_SPEAKING
        audioPlayerHandler.stopAll()

        // Show VoIP Ongoing Notification in status bar
        showOngoingCallNotification()

        // Pre-configure Picture-in-Picture params with auto-enter enabled (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                setPictureInPictureParams(buildPipParams())
            } catch (e: Exception) {}
        }

        // Notify server: call accepted (launches Google Gemini Live session)
        sendCallEvent(NexaActions.CALL_ACCEPTED)

        val audioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        if (audioGranted && btGranted) {
            startContinuousLiveVoice()
        } else {
            val toRequest = mutableListOf<String>()
            if (!audioGranted) toRequest.add(Manifest.permission.RECORD_AUDIO)
            if (!btGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                toRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            callPermissionsLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun startContinuousLiveVoice() {
        NexaBridgeService.enableCallMicrophone(true)
        voiceStreamHandler.startStreaming(callScope) { pcmChunk ->
            val payload = buildJsonObject {
                put("type", "CALL_AUDIO_STREAM")
                put("command_id", commandId)
                put("pcm_chunk", pcmChunk)
            }.toString()
            NexaBridgeService.sendEvent(payload)
        }

        // Real-time HUD State Watchdog
        lifecycleScope.launch(Dispatchers.Default) {
            var disconnectedStartTime = 0L

            while (isActive && isCallActive) {
                val isWsConnected = NexaBridgeService.isConnected()
                val isSpeaking = voiceStreamHandler.isPlayingAudio()
                val now = System.currentTimeMillis()
                val entryTime = pendingStateEntryTime

                withContext(Dispatchers.Main) {
                    val currentHud = hudState.value

                    if (!isWsConnected) {
                        if (disconnectedStartTime == 0L) {
                            disconnectedStartTime = now
                        }
                        // WebSocket disconnected — highest priority override
                        hudState.value = LiveVoiceHudState.RECONNECTING

                        // Trigger auto-reconnect attempt after 3s of disconnection
                        if (now - disconnectedStartTime > 3000L && (now - disconnectedStartTime) % 5000L < 300L) {
                            NexaBridgeService.reconnect()
                        }

                        // Auto-terminate call cleanly if disconnected for more than 20 seconds
                        if (now - disconnectedStartTime > 20000L && currentHud != LiveVoiceHudState.DISCONNECTING) {
                            Log.w(TAG, "Koneksi terputus lebih dari 20 detik — mengakhiri sesi panggilan otomatis.")
                            hudState.value = LiveVoiceHudState.DISCONNECTING
                            voiceStreamHandler.stopStreaming()
                            callState.value = CallState.FINISHED
                        }
                    } else {
                        disconnectedStartTime = 0L
                        if (currentHud == LiveVoiceHudState.RECONNECTING) {
                            // WebSocket just reconnected — fall back to listening
                            hudState.value = LiveVoiceHudState.LISTENING
                            pendingStateEntryTime = 0L
                        } else if (isSpeaking) {
                            // AudioTrack actively playing Nexa's voice
                            hudState.value = LiveVoiceHudState.SPEAKING
                            pendingStateEntryTime = 0L
                        } else if (currentHud == LiveVoiceHudState.SPEAKING && !isSpeaking) {
                            // Nexa just finished speaking — mic returns to listening
                            hudState.value = LiveVoiceHudState.LISTENING
                            pendingStateEntryTime = 0L
                        } else if (currentHud == LiveVoiceHudState.THINKING && entryTime > 0L && now - entryTime > 7000) {
                            // Neural generation delayed > 7s
                            hudState.value = LiveVoiceHudState.SLOW_NETWORK
                        } else if (currentHud == LiveVoiceHudState.PROCESSING && entryTime > 0L && now - entryTime > 15000) {
                            // Backend tool execution took > 15s (network or heavy API delay)
                            hudState.value = LiveVoiceHudState.SLOW_NETWORK
                        }
                    }
                    // All other states (LISTENING, THINKING, PROCESSING) are driven
                    // exclusively by server CALL_STATUS_UPDATE — watchdog does NOT override them
                }
                delay(250)
            }
        }
    }

    // ─── Reject & Hangup Handler ──────────────────────────────────────────────
    private fun handleReject() {
        if (callState.value == CallState.RINGING) {
            callState.value = CallState.REJECTED_SPEAKING
            audioPlayerHandler.stopAll()
            stopCameraStream()
            try { ScreenCaptureService.stop(this) } catch (_: Exception) {}
            voiceStreamHandler.stopStreaming()
            rejectionCount++

            sendCallEvent(NexaActions.CALL_REJECTED, rejectionCount = rejectionCount)

            lifecycleScope.launch {
                val rejectionMsg = "Baik, saya mengerti. Panggilan ditolak."
                ttsHandler.execute(
                    NexaCommand(
                        type = "EXECUTE_COMMAND",
                        command_id = "reject_tts_${System.currentTimeMillis()}",
                        action = "SPEAK_TEXT",
                        params = buildJsonObject { put("text", rejectionMsg) }
                    )
                )
                callState.value = CallState.FINISHED
            }
        } else {
            // Ending active ongoing live voice call
            Log.i(TAG, "User hung up active live call — closing session")
            NexaBridgeService.enableCallMicrophone(false)
            audioPlayerHandler.stopAll()
            stopCameraStream()
            try { ScreenCaptureService.stop(this) } catch (_: Exception) {}
            voiceStreamHandler.stopStreaming()
            try { callScope.cancel() } catch (_: Exception) {}
            callScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            sendCallEvent("CALL_FINISHED")
            callState.value = CallState.FINISHED
        }
    }

    // ─── Camera Control Methods (Live Video Streaming) ───────────────────────
    private fun toggleCamera() {
        if (isCameraActive.value) {
            stopCameraStream()
        } else {
            val hasCameraPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (hasCameraPermission) {
                startCameraStream()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCameraStream() {
        isCameraActive.value = true
        cameraStreamHandler.startCamera(
            lifecycleOwner = this,
            useBackCamera = isBackCamera.value,
            previewSurface = currentSurfaceProvider
        ) { frameBase64 ->
            sendVideoFrame(frameBase64)
        }
    }

    private fun switchCameraLens() {
        if (!isCameraActive.value) return
        isBackCamera.value = !isBackCamera.value
        cameraStreamHandler.switchCamera(
            lifecycleOwner = this,
            useBackCamera = isBackCamera.value,
            previewSurface = currentSurfaceProvider
        ) { frameBase64 ->
            sendVideoFrame(frameBase64)
        }
    }

    private fun stopCameraStream() {
        isCameraActive.value = false
        try {
            cameraStreamHandler.stopCamera()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping camera: ${e.message}")
        }
    }

    private var cameraFrameCount = 0

    private fun sendVideoFrame(frameBase64: String) {
        if (!isCameraActive.value) return

        cameraFrameCount++
        if (cameraFrameCount % 5 == 1) {
            Log.i(TAG, "Camera frame sent to Nexa: #$cameraFrameCount (${frameBase64.length / 1024} KB)")
        }

        val payload = buildJsonObject {
            put("type", "CALL_VIDEO_FRAME")
            put("command_id", commandId)
            put("image_chunk", frameBase64)
        }.toString()
        NexaBridgeService.sendEvent(payload)
    }


    // ─── Voice Recording Phase ────────────────────────────────────────────────
    private fun beginRecordingPhase() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startVoiceRecording()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecording() {
        lifecycleScope.launch {
            val audioBase64 = voiceRecorderHandler.record(durationMs = 10_000L) { elapsedMs ->
                recordingElapsedMs.longValue = elapsedMs
            }

            // Recording complete — transition to waiting for server reply
            callState.value = CallState.WAITING_REPLY

            // Send audio to server
            sendCallEvent(NexaActions.CALL_AUDIO_REPLY, audioBase64 = audioBase64)

            // Wait for server reply (PLAY_AUDIO_STREAM command handled by NexaBridgeService dispatcher)
            // For now: close after a timeout (server reply will call finish() via another command)
            // TODO: In production, NexaBridgeService will send PLAY_AUDIO_STREAM which triggers finish
            delay(30_000L) // 30s timeout — server should reply well within this
            if (callState.value == CallState.WAITING_REPLY) {
                Log.w(TAG, "Server reply timeout — closing activity")
                callState.value = CallState.FINISHED
            }
        }
    }

    // ─── Send Event to Server via WebSocket ───────────────────────────────────
    private fun sendCallEvent(
        event: String,
        audioBase64: String? = null,
        rejectionCount: Int? = null
    ) {
        val callEvent = CallEvent(
            type = "CALL_EVENT",
            event = event,
            command_id = commandId,
            caller_name = callerName,
            rejection_count = rejectionCount,
            audio_base64 = audioBase64,
            audio_format = if (audioBase64 != null) "PCM_16BIT_16KHZ_MONO" else null
        )
        val payload = try {
            Json.encodeToString(CallEvent.serializer(), callEvent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize CallEvent: ${e.message}")
            return
        }

        val sent = NexaBridgeService.sendEvent(payload)
        Log.i(TAG, "📡 CallEvent [$event] sent=$sent")
    }

    // ─── VoIP Ongoing Notification ───────────────────────────────────────────
    private val NOTIF_CALL_ONGOING_ID = 1005
    private val CALL_ONGOING_CHANNEL_ID = "nexa_call_ongoing_channel"

    private fun showOngoingCallNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CALL_ONGOING_CHANNEL_ID,
                    "N.E.X.A Panggilan Suara",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Status panggilan suara aktif N.E.X.A"
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }

            // Tapping notification reopens existing in-call UI immediately
            val contentIntent = Intent(this, FakeCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            // "Matikan Telepon" action button right in the notification shade
            val endCallIntent = Intent(this, FakeCallActivity::class.java).apply {
                action = "ACTION_END_CALL_FROM_NOTIF"
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val endCallPendingIntent = PendingIntent.getActivity(
                this, 1, endCallIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val notification = NotificationCompat.Builder(this, CALL_ONGOING_CHANNEL_ID)
                .setSmallIcon(applicationInfo.icon)
                .setContentTitle("Panggilan N.E.X.A Aktif")
                .setContentText("Sedang berlangsung di latar belakang (Ketuk untuk kembali)")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Matikan Telepon", endCallPendingIntent)
                .build()

            notificationManager.notify(NOTIF_CALL_ONGOING_ID, notification)
            Log.i(TAG, "🔔 Ongoing call notification displayed.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show ongoing notification: ${e.message}")
        }
    }

    private fun cancelOngoingCallNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIF_CALL_ONGOING_ID)
            Log.i(TAG, "🔕 Ongoing call notification removed.")
        } catch (_: Exception) {}
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────
    private fun setupShowWhenLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager?.requestDismissKeyguard(this, null)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == "ACTION_END_CALL_FROM_NOTIF") {
            Log.i(TAG, "🔴 End call requested from notification button action")
            handleReject()
            return
        }
        Log.w(TAG, "onNewIntent received while call in state: ${callState.value} — bringing to front.")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "Orientation/Config change handled smoothly without activity recreation")
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode.value = isInPictureInPictureMode
        Log.i(TAG, "PiP mode changed: isInPip=$isInPictureInPictureMode")
        if (!isInPictureInPictureMode) {
            // Returned to fullscreen — refresh PiP params
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                (callState.value == CallState.ACCEPTED_SPEAKING || callState.value == CallState.RECORDING || callState.value == CallState.WAITING_REPLY)
            ) {
                try {
                    setPictureInPictureParams(buildPipParams())
                } catch (e: Exception) {}
            }
        }
    }

    private fun handleTactileEyeTap(target: String, tapCount: Int) {
        val payload = buildJsonObject {
            put("type", "CALL_TACTILE_EVENT")
            put("command_id", commandId)
            put("target", target)
            put("tap_count", tapCount)
            put("hud_state", hudState.value.name)
            put("timestamp", System.currentTimeMillis())
        }.toString()
        NexaBridgeService.sendEvent(payload)
        Log.i(TAG, "Eye tactile tap: target=$target, tapCount=$tapCount")

        if (tapCount >= 2) {
            voiceStreamHandler.flushOutputBuffer()
            if (hudState.value == LiveVoiceHudState.SPEAKING) {
                hudState.value = LiveVoiceHudState.LISTENING
            }
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw IllegalStateException("PiP requires Android O+")
        }

        val builder = PictureInPictureParams.Builder()
            // Rasio 4:3 sangat proporsional untuk sepasang mata cybernetic OLED
            .setAspectRatio(Rational(4, 3))

        // Android 12+ (API 31+): Seamless auto-enter on gesture navigation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
        }

        val actions = mutableListOf<RemoteAction>()

        // 1. Action: Audio Route Toggle
        val speakerIconRes = if (audioRoute.value == AudioOutputRoute.SPEAKER) {
            android.R.drawable.stat_sys_speakerphone
        } else {
            android.R.drawable.ic_lock_silent_mode_off
        }
        val speakerTitle = when (audioRoute.value) {
            AudioOutputRoute.BLUETOOTH -> "Bluetooth TWS"
            AudioOutputRoute.WIRED_HEADSET -> "Headset Kabel"
            AudioOutputRoute.SPEAKER -> "Speaker ON"
            AudioOutputRoute.EARPIECE -> "Earpiece"
        }
        val speakerIntent = Intent(ACTION_PIP_TOGGLE_SPEAKER).setPackage(packageName)
        val speakerPendingIntent = PendingIntent.getBroadcast(
            this,
            101,
            speakerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, speakerIconRes),
                speakerTitle,
                speakerTitle,
                speakerPendingIntent
            )
        )

        // 2. Action: End Call (Tutup Telepon)
        val endCallIntent = Intent(ACTION_PIP_END_CALL).setPackage(packageName)
        val endCallPendingIntent = PendingIntent.getBroadcast(
            this,
            102,
            endCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Tutup Telepon",
                "Tutup Telepon",
                endCallPendingIntent
            )
        )

        builder.setActions(actions)
        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isCallActive = false
        if (callState.value == CallState.FINISHED) {
            cancelOngoingCallNotification()
            NexaBridgeService.enableCallMicrophone(false)
            try { ScreenCaptureService.stop(this) } catch (e: Exception) {}
            ScreenCaptureService.onEndCallRequested = null
            ScreenCaptureService.onToggleMicMuteRequested = null
            ScreenCaptureService.onInterruptRequested = null
            ScreenCaptureService.onReturnToCallRequested = null
            try { cameraStreamHandler.stopCamera() } catch (e: Exception) {}
            try { voiceStreamHandler.stopStreaming() } catch (e: Exception) {}
            try { callScope.cancel() } catch (_: Exception) {}
            if (NexaBridgeService.activeWebSocketClient?.liveCallListener === liveCallEventListener) {
                NexaBridgeService.activeWebSocketClient?.liveCallListener = null
            }
            try { unregisterReceiver(callReplyReceiver) } catch (e: Exception) { /* already unregistered */ }
            audioPlayerHandler.stopAll()
        }
    }
}

// ─── Main Screen Composable ───────────────────────────────────────────────────
@Composable
fun FakeCallScreen(
    callerName: String,
    callMessage: String,
    callState: CallState,
    hudState: LiveVoiceHudState = LiveVoiceHudState.LISTENING,
    isInPipMode: Boolean = false,
    isSpeaking: Boolean = false,
    audioEnergy: Float = 0f,
    isLoudspeaker: Boolean = false,
    audioRoute: AudioOutputRoute = AudioOutputRoute.EARPIECE,
    recordingElapsedMs: Long,
    isCameraActive: Boolean = false,
    isBackCamera: Boolean = true,
    isMicMuted: Boolean = false,
    isScreenShareActive: Boolean = false,
    availableAudioRoutes: List<AudioOutputRoute> = listOf(AudioOutputRoute.SPEAKER, AudioOutputRoute.EARPIECE),
    assistantText: String = "",
    userTranscriptText: String = "",
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onToggleLoudspeaker: (Boolean) -> Unit = {},
    onToggleAudioRoute: () -> Unit = {},
    onSelectAudioRoute: (AudioOutputRoute) -> Unit = {},
    onToggleCamera: () -> Unit = {},
    onSwitchCamera: () -> Unit = {},
    onToggleMicMute: () -> Unit = {},
    onToggleScreenShare: () -> Unit = {},
    onInterruptAssistant: () -> Unit = {},
    onEnterPiP: () -> Unit = {},
    onPreviewSurfaceReady: (androidx.camera.core.Preview.SurfaceProvider) -> Unit = {},
    onEyeTapped: (target: String, tapCount: Int) -> Unit = { _, _ -> }
) {
    if (isInPipMode) {
        // Pure Minimalist Cybernetic PiP Mode (WhatsApp Style)
        // 100% ONLY Expressive Eyes in deep OLED black canvas.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF040B16)),
            contentAlignment = Alignment.Center
        ) {
            NexaExpressiveEyes(
                hudState = hudState,
                isSpeaking = isSpeaking,
                audioEnergy = audioEnergy,
                onEyeTapped = onEyeTapped,
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(0.65f)
            )
        }
        return
    }

    if (callState == CallState.RINGING) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale1 by infiniteTransition.animateFloat(
            initialValue = 0.8f, targetValue = 1.3f,
            animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulseScale1"
        )
        val pulseScale2 by infiniteTransition.animateFloat(
            initialValue = 1.0f, targetValue = 1.6f,
            animationSpec = infiniteRepeatable(tween(2000, easing = LinearOutSlowInEasing), RepeatMode.Restart),
            label = "pulseScale2"
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.6f, targetValue = 0.0f,
            animationSpec = infiniteRepeatable(tween(2000, easing = LinearOutSlowInEasing), RepeatMode.Restart),
            label = "pulseAlpha"
        )

        val backgroundBrush = Brush.verticalGradient(
            colors = listOf(Color(0xFF040B16), Color(0xFF0A192F), Color(0xFF020C1B))
        )

        Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
            Box(
                modifier = Modifier.fillMaxSize().background(backgroundBrush)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Header
                    CallHeader(callState = callState, hudState = hudState, callerName = callerName)

                    // Incoming call pulsing phone orb & message card
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(180.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().scale(pulseScale2)
                                    .clip(CircleShape)
                                    .background(Color(0xFF64FFDA).copy(alpha = pulseAlpha))
                            )
                            Box(
                                modifier = Modifier.size(130.dp).scale(pulseScale1)
                                    .clip(CircleShape)
                                    .background(Color(0xFF64FFDA).copy(alpha = 0.2f))
                            )
                            Box(
                                modifier = Modifier.size(100.dp).clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                Color(0xFFE6F1FF), Color(0xFF64FFDA), Color(0xFF0A192F)
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = "Incoming Call",
                                    tint = Color(0xFF0A192F),
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF112240).copy(alpha = 0.7f)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF233554)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            Text(
                                text = callMessage,
                                color = Color(0xFFCCD6F6),
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(24.dp),
                                lineHeight = 24.sp
                            )
                        }
                    }

                    // Swipe-Up Action Buttons
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            DynamicSwipeUpButton(
                                label = "TOLAK",
                                icon = Icons.Default.Close,
                                accentColor = Color(0xFFD50000),
                                glowColor = Color(0xFFFF5252),
                                onTriggered = onReject
                            )
                            DynamicSwipeUpButton(
                                label = "ANGKAT",
                                icon = Icons.Default.Phone,
                                accentColor = Color(0xFF00BFA5),
                                glowColor = Color(0xFF64FFDA),
                                onTriggered = onAnswer
                            )
                        }
                    }
                }
            }
        }
        return
    }

    // ── Active Live Session (Gemini Live Clean OLED & Video Layout) ───────────
    val haptic = LocalHapticFeedback.current
    var showAudioRouteDropdown by remember { mutableStateOf(false) }

    val flipRotation by animateFloatAsState(
        targetValue = if (isBackCamera) 0f else 180f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "flipCameraRotation"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF020617)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // ── Layer 0: Background ──────────────────────────────────────────
            if (isCameraActive) {
                // Full Screen Edge-to-Edge Camera Preview
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            onPreviewSurfaceReady(this.surfaceProvider)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // ── Layer 1: Scrim Gradients (Top & Bottom subtle dark shade) ───
                // Top Scrim for status bar and top icons legibility
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xB3000000), Color.Transparent)
                            )
                        )
                )

                // Bottom Scrim for subtitle card and bottom dock legibility
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xB3000000), Color(0xE6000000))
                            )
                        )
                )
            } else {
                // Audio-Only Pure Dark OLED Canvas
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF020617), Color(0xFF0B1120), Color(0xFF020617))
                            )
                        )
                )
            }

            // ── Layer 2: Main Foreground Scaffold ─────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 24.dp)
                    .padding(top = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // ── Top Bar: Status Pill (Left) & Controls (Right) ────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiveStatusPill(state = hudState)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Putar Kamera Button (Beralih kamera depan/belakang) Sesuai Gambar 3
                        if (isCameraActive) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xCC1E1E1E),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF)),
                                modifier = Modifier
                                    .size(46.dp)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onSwitchCamera()
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.FlipCameraAndroid,
                                        contentDescription = "Putar Kamera (Beralih kamera depan/belakang)",
                                        tint = Color(0xFFF1F5F9),
                                        modifier = Modifier
                                            .size(24.dp)
                                            .graphicsLayer { rotationZ = flipRotation }
                                    )
                                }
                            }
                        }

                        // Top Right Pill: [ ◱ PiP │ ··· MoreHoriz ] Sesuai Gambar 3
                        Surface(
                            shape = RoundedCornerShape(23.dp),
                            color = Color(0xCC1E1E1E),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onEnterPiP()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PictureInPictureAlt,
                                        contentDescription = "Mode Layar Kecil (Picture-in-Picture)",
                                        tint = Color(0xFFCBD5E1),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(18.dp)
                                        .background(Color(0x33FFFFFF))
                                )

                                Box {
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showAudioRouteDropdown = true
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreHoriz,
                                            contentDescription = "Pengaturan & Opsi Lainnya",
                                            tint = Color(0xFFCBD5E1),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    AudioRouteOptionsMenu(
                                        expanded = showAudioRouteDropdown,
                                        currentRoute = audioRoute,
                                        availableRoutes = availableAudioRoutes,
                                        onDismiss = { showAudioRouteDropdown = false },
                                        onSelectRoute = { route ->
                                            showAudioRouteDropdown = false
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onSelectAudioRoute(route)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Center & Lower Area ───────────────────────────────────────
                if (!isCameraActive) {
                    // Mode Audio-Only: Teks Respons Asisten di Atas + Cybernetic OLED Eyes di Tengah
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (assistantText.isNotBlank()) {
                            Text(
                                text = assistantText,
                                color = Color(0xFFF1F5F9),
                                fontSize = 21.sp,
                                lineHeight = 30.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Start,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 20.dp, start = 8.dp, end = 8.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.height(20.dp))
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            NexaExpressiveEyes(
                                hudState = hudState,
                                isSpeaking = isSpeaking,
                                audioEnergy = audioEnergy,
                                onEyeTapped = onEyeTapped,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        }

                        // Floating barge-in pill & transcript capsule (elemen elips di tengah)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            AnimatedVisibility(
                                visible = isSpeaking || hudState == LiveVoiceHudState.SPEAKING,
                                enter = fadeIn() + scaleIn(initialScale = 0.85f),
                                exit = fadeOut() + scaleOut(targetScale = 0.85f)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color(0xFF1E293B).copy(alpha = 0.92f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                                    modifier = Modifier
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onInterruptAssistant()
                                        }
                                        .padding(bottom = 10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF38BDF8))
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Ketuk untuk menyela",
                                            color = Color(0xFFF1F5F9),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = userTranscriptText.isNotBlank(),
                                enter = fadeIn() + scaleIn(initialScale = 0.9f),
                                exit = fadeOut() + scaleOut(targetScale = 0.9f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(24.dp),
                                        color = Color(0xFF1E2024),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x26FFFFFF)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = userTranscriptText,
                                            color = Color(0xFFE2E8F0),
                                            fontSize = 15.sp,
                                            lineHeight = 22.sp,
                                            fontStyle = FontStyle.Italic,
                                            fontWeight = FontWeight.Normal,
                                            textAlign = TextAlign.Start,
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Mode Video Kamera Layar Penuh:
                    // Area tengah transparan bersih; Subtitle Card melayang di atas dok bawah Sesuai Gambar 3
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.Start
                    ) {
                        // "Ketuk untuk menyela" pill (Floating Barge-in Button)
                        AnimatedVisibility(
                            visible = isSpeaking || hudState == LiveVoiceHudState.SPEAKING,
                            enter = fadeIn() + scaleIn(initialScale = 0.85f),
                            exit = fadeOut() + scaleOut(targetScale = 0.85f),
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color(0xCC1E1E1E),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF)),
                                modifier = Modifier.clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onInterruptAssistant()
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF38BDF8))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Ketuk untuk menyela",
                                        color = Color(0xFFF1F5F9),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        // Floating Subtitle Card ("Transkrip Suara / Respons Teks" Sesuai Gambar 3)
                        val displaySubtitle = if (isSpeaking || hudState == LiveVoiceHudState.SPEAKING) {
                            assistantText
                        } else if (userTranscriptText.isNotBlank()) {
                            userTranscriptText
                        } else {
                            ""
                        }

                        if (displaySubtitle.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color(0xEE1A1A1A),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x26FFFFFF)),
                                modifier = Modifier
                                    .fillMaxWidth(0.92f)
                                    .padding(bottom = 12.dp, start = 4.dp)
                            ) {
                                Text(
                                    text = displaySubtitle,
                                    color = Color(0xFFF8FAFC),
                                    fontSize = 16.sp,
                                    lineHeight = 22.sp,
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }
                }

                // ── Bottom Dock (5 Control Buttons Sesuai Gambar 3) ───────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Camera Toggle (Aktifkan/Nonaktifkan Kamera — Biru Gemini saat aktif)
                    Surface(
                        shape = CircleShape,
                        color = if (isCameraActive) Color(0xFF2563EB) else Color(0xFF26262A),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isCameraActive) Color(0xFF60A5FA) else Color(0x33FFFFFF)
                        ),
                        modifier = Modifier
                            .size(54.dp)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onToggleCamera()
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isCameraActive) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = "Aktifkan/Nonaktifkan Kamera",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // 2. Screen Share Toggle (Bagikan Layar — Ikon [ ↑ ] Upload Sesuai Gambar 3)
                    Surface(
                        shape = CircleShape,
                        color = if (isScreenShareActive) Color(0xFF38BDF8) else Color(0xFF26262A),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isScreenShareActive) Color(0xFF7DD3FC) else Color(0x33FFFFFF)
                        ),
                        modifier = Modifier
                            .size(54.dp)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onToggleScreenShare()
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Upload,
                                contentDescription = "Bagikan Layar",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // 3. Center Gemini Live Voice Capsule (Indikator Suara Gemini — Sedang Aktif)
                    LiveGeminiVoiceCapsule(
                        hudState = hudState,
                        isSpeaking = isSpeaking,
                        audioEnergy = audioEnergy,
                        isCameraMode = isCameraActive,
                        onTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (isSpeaking) onInterruptAssistant()
                        }
                    )

                    // 4. Mic Mute Toggle (Mute / Matikan Suara Mikrofon)
                    Surface(
                        shape = CircleShape,
                        color = if (isMicMuted) Color(0xFFEF4444) else Color(0xFF26262A),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isMicMuted) Color(0xFFF87171) else Color(0x33FFFFFF)
                        ),
                        modifier = Modifier
                            .size(54.dp)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onToggleMicMute()
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Mute / Matikan Suara Mikrofon",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // 5. Hang Up / Close Call (Akhiri Panggilan — Charcoal Gelap Sesuai Gambar 3)
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF26262A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF)),
                        modifier = Modifier
                            .size(54.dp)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onReject()
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Akhiri Panggilan",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Gemini Live Bottom Central Voice Capsule ────────────────────────────────
@Composable
fun LiveGeminiVoiceCapsule(
    hudState: LiveVoiceHudState,
    isSpeaking: Boolean,
    audioEnergy: Float,
    isCameraMode: Boolean = false,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "capsuleAnim")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val capsuleBackgroundBrush = if (isCameraMode) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF0F172A),
                Color(0xFF132035),
                Color(0xFF1D4ED8),
                Color(0xFF38BDF8)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(Color(0xFF0F172A), Color(0xFF0F172A))
        )
    }

    Surface(
        shape = RoundedCornerShape(27.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isCameraMode) Color(0x6638BDF8) else Color(0x3338BDF8)
        ),
        modifier = modifier
            .width(96.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(capsuleBackgroundBrush)
            .clickable { onTap() }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val centerY = h / 2f
            val centerX = w / 2f

            if (isSpeaking || hudState == LiveVoiceHudState.SPEAKING) {
                // Multi-bar animated waveform driven by audio energy
                val barCount = 5
                val barWidth = 4.dp.toPx()
                val totalBarsWidth = (barCount * barWidth) + ((barCount - 1) * 5.dp.toPx())
                val startX = (w - totalBarsWidth) / 2f

                val energyBoost = (audioEnergy * 2.5f).coerceIn(0f, 1f)

                for (i in 0 until barCount) {
                    val barX = startX + i * (barWidth + 5.dp.toPx())
                    val sineOffset = kotlin.math.sin((wavePhase * 2 * Math.PI + i * 0.9)).toFloat()
                    val dynamicFactor = 0.35f + 0.65f * kotlin.math.abs(sineOffset)
                    val barHeight = (10.dp.toPx() + (22.dp.toPx() * (dynamicFactor + energyBoost * 0.8f))).coerceIn(8.dp.toPx(), h - 12.dp.toPx())

                    val barColor = when (i % 3) {
                        0 -> Color(0xFF38BDF8) // Electric Sky Blue
                        1 -> Color(0xFF64FFDA) // Cyan
                        else -> Color(0xFF818CF8) // Indigo
                    }

                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(barX, centerY - barHeight / 2f),
                        size = Size(barWidth, barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
            } else if (hudState == LiveVoiceHudState.PROCESSING || hudState == LiveVoiceHudState.THINKING) {
                // Shimmering thinking wave
                val glowRadius = 14.dp.toPx() * pulseScale
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF10B981).copy(alpha = 0.7f), Color(0xFFF59E0B).copy(alpha = 0.3f), Color.Transparent),
                        center = Offset(centerX, centerY),
                        radius = glowRadius
                    ),
                    radius = glowRadius,
                    center = Offset(centerX, centerY)
                )
                drawCircle(
                    color = Color(0xFF10B981),
                    radius = 5.dp.toPx(),
                    center = Offset(centerX, centerY)
                )
            } else {
                // Gentle breathing cyan dot (Listening / Idle)
                val dotRadius = 5.5.dp.toPx() * pulseScale
                val glowRadius = 16.dp.toPx() * pulseScale

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF38BDF8).copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(centerX, centerY),
                        radius = glowRadius
                    ),
                    radius = glowRadius,
                    center = Offset(centerX, centerY)
                )
                drawCircle(
                    color = Color(0xFF38BDF8),
                    radius = dotRadius,
                    center = Offset(centerX, centerY)
                )
            }
        }
    }
}

// ─── Audio Route Options Dropdown Menu ─────────────────────────────────────────
@Composable
fun AudioRouteOptionsMenu(
    expanded: Boolean,
    currentRoute: AudioOutputRoute,
    availableRoutes: List<AudioOutputRoute> = listOf(AudioOutputRoute.SPEAKER, AudioOutputRoute.EARPIECE),
    onDismiss: () -> Unit,
    onSelectRoute: (AudioOutputRoute) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .background(Color(0xFF0F172A))
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
    ) {
        val routes = listOf(
            Triple(AudioOutputRoute.SPEAKER, "Loudspeaker", Icons.AutoMirrored.Filled.VolumeUp),
            Triple(AudioOutputRoute.EARPIECE, "Earpiece (Telepon)", Icons.Default.Hearing),
            Triple(AudioOutputRoute.BLUETOOTH, "Bluetooth Headset", Icons.Default.Bluetooth),
            Triple(AudioOutputRoute.WIRED_HEADSET, "Headset Kabel", Icons.Default.Headset)
        )

        routes.forEach { (route, label, icon) ->
            val isSelected = currentRoute == route
            val isAvailable = availableRoutes.contains(route)
            DropdownMenuItem(
                enabled = isAvailable,
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = when {
                                isSelected -> Color(0xFF38BDF8)
                                isAvailable -> Color(0xFF94A3B8)
                                else -> Color(0xFF475569)
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isAvailable) label else "$label (Tidak Terhubung)",
                            color = when {
                                isSelected -> Color(0xFFF1F5F9)
                                isAvailable -> Color(0xFFCBD5E1)
                                else -> Color(0xFF64748B)
                            },
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                },
                trailingIcon = {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                onClick = {
                    if (isAvailable) {
                        onSelectRoute(route)
                    }
                },
                colors = MenuDefaults.itemColors(
                    textColor = Color(0xFFF1F5F9),
                    disabledTextColor = Color(0xFF64748B)
                )
            )
        }
    }
}

// ─── Header Composable ────────────────────────────────────────────────────────
@Composable
fun CallHeader(
    callState: CallState,
    hudState: LiveVoiceHudState = LiveVoiceHudState.LISTENING,
    callerName: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 56.dp)
    ) {
        if (callState == CallState.RINGING) {
            Text(
                text = "INCOMING TRANSMISSION",
                color = Color(0xFFFF5252),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp
            )
        } else if (callState == CallState.FINISHED) {
            Text(
                text = "DISCONNECTING",
                color = Color(0xFF8892B0),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp
            )
        } else if (callState == CallState.REJECTED_SPEAKING) {
            Text(
                text = "LAST MESSAGE",
                color = Color(0xFFFF6E40),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp
            )
        } else {
            // Live Active Call: Show clean, executive status pill
            LiveStatusPill(state = hudState)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = callerName,
            color = Color(0xFFE6F1FF),
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "N.E.X.A AUTONOMOUS AGENT",
            color = Color(0xFF8892B0),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.5.sp
        )
    }
}

@Composable
fun LiveStatusPill(state: LiveVoiceHudState, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "hudPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )
    val color = Color(state.colorHex)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF0D1B2A).copy(alpha = 0.85f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = dotAlpha))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = state.label,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp
            )
        }
    }
}

// ─── Recording UI Composable ──────────────────────────────────────────────────
@Composable
fun RecordingSection(elapsedMs: Long, totalMs: Long = 10_000L) {
    val remainingMs = (totalMs - elapsedMs).coerceAtLeast(0L)
    val remainingSec = remainingMs / 1000L
    val timerText = "00:%02d".format(remainingSec)

    val progress = (elapsedMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)

    val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
    val micGlow by infiniteTransition.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "micGlow"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(0.9f)
    ) {
        // Timer
        Text(
            text = timerText,
            color = Color(0xFF64FFDA),
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Bicaralah sekarang...",
            color = Color(0xFF8892B0),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Progress bar
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = Color(0xFFFF5252),
            trackColor = Color(0xFF112240)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Mic active indicator — glowing dot
        Box(
            modifier = Modifier
                .size(20.dp)
                .scale(micGlow)
                .clip(CircleShape)
                .background(Color(0xFFFF5252))
        )
    }
}

// ─── Waiting Reply UI ─────────────────────────────────────────────────────────
@Composable
fun WaitingReplySection() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(0.9f)
    ) {
        CircularProgressIndicator(
            color = Color(0xFF64FFDA),
            strokeWidth = 3.dp,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "MENUNGGU BALASAN N.E.X.A",
            color = Color(0xFF8892B0),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp
        )
    }
}

// ─── Glowing Floating Upward Chevrons ─────────────────────────────────────────
@Composable
fun UpwardChevrons(
    tint: Color,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "chevronAnim")
    val animProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "chevronAnim"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        for (i in 0..2) {
            val phase = (animProgress + i * 0.33f) % 1f
            val y = h - (phase * h)
            val chevronAlpha = ((1f - phase) * alpha * 0.85f).coerceIn(0f, 1f)

            val path = Path().apply {
                moveTo(w * 0.22f, y + 5.dp.toPx())
                lineTo(w * 0.5f, y)
                lineTo(w * 0.78f, y + 5.dp.toPx())
            }
            drawPath(
                path = path,
                color = tint.copy(alpha = chevronAlpha),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

// ─── Dynamic Futuristic Swipe-Up Capsule Button ──────────────────────────────
@Composable
fun DynamicSwipeUpButton(
    label: String,
    icon: ImageVector,
    accentColor: Color,
    glowColor: Color,
    onTriggered: () -> Unit
) {
    val density = LocalDensity.current
    val trackHeight = 180.dp
    val trackWidth = 84.dp
    val discSize = 68.dp

    val maxDragDistance = with(density) { -104.dp.toPx() }
    val threshold = with(density) { -75.dp.toPx() }

    var offsetY by remember { mutableFloatStateOf(0f) }
    var isTriggered by remember { mutableStateOf(false) }

    val animatedOffsetY by animateFloatAsState(
        targetValue = offsetY,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "swipeSpring"
    )

    val progress = (animatedOffsetY / maxDragDistance).coerceIn(0f, 1f)
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(RoundedCornerShape(42.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.08f + progress * 0.25f),
                        Color(0xFF0A192F).copy(alpha = 0.85f)
                    )
                )
            )
            .border(
                width = (1.5f + progress * 1.5f).dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        glowColor.copy(alpha = 0.5f + progress * 0.5f),
                        accentColor.copy(alpha = 0.2f + progress * 0.4f)
                    )
                ),
                shape = RoundedCornerShape(42.dp)
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        // 1. Top Section: Label & Upward Floating Animated Chevrons
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, bottom = 76.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = glowColor.copy(alpha = 0.9f),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )

            UpwardChevrons(
                tint = glowColor,
                alpha = (1f - progress).coerceIn(0f, 1f),
                modifier = Modifier
                    .width(36.dp)
                    .height(44.dp)
            )
        }

        // 2. Interactive Sliding Glowing Disc
        Box(
            modifier = Modifier
                .padding(bottom = 8.dp)
                .offset { IntOffset(0, animatedOffsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (!isTriggered) offsetY = 0f
                        },
                        onDragCancel = {
                            if (!isTriggered) offsetY = 0f
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        if (isTriggered) return@detectVerticalDragGestures
                        offsetY = (offsetY + dragAmount).coerceIn(maxDragDistance, 0f)
                        if (offsetY <= threshold && !isTriggered) {
                            isTriggered = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTriggered()
                        }
                    }
                }
                .size(discSize)
                .scale(1.0f + (progress * 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            // Glow Halo behind disc
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.25f + progress * 0.3f)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                glowColor.copy(alpha = 0.4f + progress * 0.45f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Disc Surface
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                glowColor,
                                accentColor,
                                accentColor.copy(alpha = 0.85f)
                            )
                        )
                    )
                    .border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = Color(0xFF040B16),
                    modifier = Modifier
                        .size(32.dp)
                        .scale(1.0f + (progress * 0.1f))
                )
            }
        }
    }
}

// ─── Professional Speaker & Speaker Slashed Vector Icon ─────────────────────────
@Composable
fun SpeakerIcon(
    isLoudspeaker: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Draw clean minimalist speaker cone & body
        val bodyPath = Path().apply {
            moveTo(w * 0.16f, h * 0.36f)
            lineTo(w * 0.38f, h * 0.36f)
            lineTo(w * 0.64f, h * 0.16f)
            lineTo(w * 0.64f, h * 0.84f)
            lineTo(w * 0.38f, h * 0.64f)
            lineTo(w * 0.16f, h * 0.64f)
            close()
        }
        drawPath(bodyPath, color = tint)

        if (isLoudspeaker) {
            // Draw Sound Wave Arcs (Loudspeaker Active)
            drawArc(
                color = tint,
                startAngle = -40f,
                sweepAngle = 80f,
                useCenter = false,
                topLeft = Offset(w * 0.46f, h * 0.30f),
                size = Size(w * 0.38f, h * 0.40f),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = tint,
                startAngle = -40f,
                sweepAngle = 80f,
                useCenter = false,
                topLeft = Offset(w * 0.36f, h * 0.18f),
                size = Size(w * 0.58f, h * 0.64f),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )
        } else {
            // Draw Elegant Diagonal Slash Line (Earpiece / Muted Speaker)
            drawLine(
                color = Color(0xFFFF5252),
                start = Offset(w * 0.14f, h * 0.14f),
                end = Offset(w * 0.86f, h * 0.86f),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}
