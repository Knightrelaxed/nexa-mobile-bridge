package com.nexa.mobilebridge.ui.call

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
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
import com.nexa.mobilebridge.dispatcher.handler.AudioPlayerHandler
import com.nexa.mobilebridge.dispatcher.handler.TtsHandler
import com.nexa.mobilebridge.dispatcher.handler.VoiceRecorderHandler
import com.nexa.mobilebridge.protocol.CallEvent
import com.nexa.mobilebridge.protocol.NexaActions
import com.nexa.mobilebridge.protocol.NexaCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

class FakeCallActivity : ComponentActivity() {

    private val TAG = "FakeCallActivity"

    private lateinit var ttsHandler: TtsHandler
    private lateinit var audioPlayerHandler: AudioPlayerHandler
    private lateinit var voiceRecorderHandler: VoiceRecorderHandler
    private lateinit var voiceStreamHandler: com.nexa.mobilebridge.dispatcher.handler.VoiceStreamHandler

    private var commandId: String = ""
    private var callerName: String = "N.E.X.A Assistant"
    private var callMessage: String = "Tuan Faqih, ada interupsi penting dari N.E.X.A."

    companion object {
        private const val TAG = "FakeCallActivity"

        @Volatile
        var isCallActive: Boolean = false
    }

    // Mutable state hoisted to Activity level so coroutines can update it
    private val callState = mutableStateOf(CallState.RINGING)
    private val recordingElapsedMs = mutableLongStateOf(0L)

    // Track how many times user has rejected (sent to server for importance evaluation)
    private var rejectionCount = 0

    // BroadcastReceiver for server audio events (Live Audio Play, Interrupted, Reply Complete)
    private val callReplyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.nexa.mobilebridge.CALL_REPLY_COMPLETE" || intent?.action == "com.nexa.mobilebridge.END_CALL") {
                Log.i(TAG, "📩 End Call signal received — waiting for audio playback to finish cleanly")
                lifecycleScope.launch(Dispatchers.Main) {
                    var waitLoops = 0
                    while (voiceStreamHandler.isPlayingAudio() && waitLoops < 50) {
                        kotlinx.coroutines.delay(100)
                        waitLoops++
                    }
                    kotlinx.coroutines.delay(400) // Natural silence tail before closing UI
                    voiceStreamHandler.stopStreaming()
                    callState.value = CallState.FINISHED
                }
            } else if (intent?.action == "com.nexa.mobilebridge.CALL_LIVE_EVENT") {
                val rawJson = intent.getStringExtra("raw_json") ?: return
                if (rawJson.contains("CALL_AUDIO_PLAY")) {
                    val pcmRegex = Regex("\"pcm_chunk\"\\s*:\\s*\"([^\"]+)\"")
                    val match = pcmRegex.find(rawJson)
                    val pcmChunk = match?.groups?.get(1)?.value
                    if (pcmChunk != null) {
                        voiceStreamHandler.enqueueAudioChunk(pcmChunk)
                    }
                } else if (rawJson.contains("CALL_AUDIO_INTERRUPTED")) {
                    voiceStreamHandler.flushOutputBuffer()
                }
            }
        }
    }

    // Runtime permission launcher for RECORD_AUDIO
    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "RECORD_AUDIO permission granted — starting live stream")
            startContinuousLiveVoice()
        } else {
            Log.w(TAG, "RECORD_AUDIO permission denied — closing call")
            sendCallEvent(NexaActions.CALL_REJECTED)
            callState.value = CallState.FINISHED
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
        voiceStreamHandler = com.nexa.mobilebridge.dispatcher.handler.VoiceStreamHandler(applicationContext)

        commandId = intent.getStringExtra("command_id") ?: "call_${System.currentTimeMillis()}"
        callerName = intent.getStringExtra("caller_name") ?: "N.E.X.A Voice Bridge"
        callMessage = intent.getStringExtra("message") ?: "Halo Tuan Faqih, saya mendengarkan."
        val isOutgoingCall = intent.getBooleanExtra("IS_OUTGOING_CALL", false) || (intent.action == "com.nexa.mobilebridge.ACTION_CALL_NEXA")
        val playRingtone = if (isOutgoingCall) false else intent.getBooleanExtra("play_ringtone", true)

        // Register receiver for server live audio & reply events
        val filter = IntentFilter().apply {
            addAction("com.nexa.mobilebridge.CALL_REPLY_COMPLETE")
            addAction("com.nexa.mobilebridge.CALL_LIVE_EVENT")
            addAction("com.nexa.mobilebridge.END_CALL")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callReplyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(callReplyReceiver, filter)
        }

        // VoIP Background Navigation: Back button minimizes to background without killing call
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val state = callState.value
                if (state == CallState.ACCEPTED_SPEAKING || state == CallState.RECORDING || state == CallState.WAITING_REPLY) {
                    Log.i(TAG, "📱 Back pressed during active call — moving task to background (VoIP style)")
                    moveTaskToBack(true)
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
            val elapsedMs by recordingElapsedMs

            // Start call flow immediately on UI render
            LaunchedEffect(Unit) {
                if (isOutgoingCall) {
                    Log.i(TAG, "🚀 Outgoing call to N.E.X.A initiated — connecting live session immediately")
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
                    voiceStreamHandler.stopStreaming()
                    finishAndRemoveTask()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, android.R.anim.fade_out)
                }
            }

            val isLoudspeaker = remember { mutableStateOf(false) }

            FakeCallScreen(
                callerName = callerName,
                callMessage = callMessage,
                callState = state,
                isLoudspeaker = isLoudspeaker.value,
                recordingElapsedMs = elapsedMs,
                onAnswer = { handleAccept() },
                onReject = { handleReject() },
                onToggleLoudspeaker = { enabled ->
                    isLoudspeaker.value = enabled
                    voiceStreamHandler.setLoudspeaker(enabled)
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

        // Notify server: call accepted (launches Google Gemini Live session)
        sendCallEvent(NexaActions.CALL_ACCEPTED)

        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startContinuousLiveVoice()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startContinuousLiveVoice() {
        voiceStreamHandler.startStreaming(lifecycleScope) { pcmChunk ->
            val payload = buildJsonObject {
                put("type", "CALL_AUDIO_STREAM")
                put("command_id", commandId)
                put("pcm_chunk", pcmChunk)
            }.toString()
            NexaBridgeService.sendEvent(payload)
        }
    }

    // ─── Reject & Hangup Handler ──────────────────────────────────────────────
    private fun handleReject() {
        if (callState.value == CallState.RINGING) {
            callState.value = CallState.REJECTED_SPEAKING
            audioPlayerHandler.stopAll()
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
            Log.i(TAG, "🛑 User hung up active live call — closing session")
            audioPlayerHandler.stopAll()
            voiceStreamHandler.stopStreaming()
            sendCallEvent("CALL_FINISHED")
            callState.value = CallState.FINISHED
        }
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
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
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
        Log.w(TAG, "⚠️ onNewIntent received while call in state: ${callState.value} — bringing to front.")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "📱 Orientation/Config change handled smoothly without activity recreation")
    }

    override fun onDestroy() {
        super.onDestroy()
        isCallActive = false
        cancelOngoingCallNotification()
        try { voiceStreamHandler.stopStreaming() } catch (e: Exception) {}
        try { unregisterReceiver(callReplyReceiver) } catch (e: Exception) { /* already unregistered */ }
        audioPlayerHandler.stopAll()
    }
}

// ─── Main Screen Composable ───────────────────────────────────────────────────
@Composable
fun FakeCallScreen(
    callerName: String,
    callMessage: String,
    callState: CallState,
    isLoudspeaker: Boolean = false,
    recordingElapsedMs: Long,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onToggleLoudspeaker: (Boolean) -> Unit = {}
) {
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
                // ── Header ──────────────────────────────────────────────
                CallHeader(callState = callState, callerName = callerName)

                // ── Center: Avatar + Message / Recording UI ──────────────
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Pulsing Orb
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
                                imageVector = if (callState == CallState.RECORDING)
                                    Icons.Default.Phone else Icons.Default.Phone,
                                contentDescription = "Call",
                                tint = Color(0xFF0A192F),
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(40.dp))

                    // Dynamic center content based on state
                    when (callState) {
                        CallState.RINGING, CallState.ACCEPTED_SPEAKING, CallState.REJECTED_SPEAKING -> {
                            // Show message card
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
                        CallState.RECORDING -> {
                            // Recording UI with countdown timer
                            RecordingSection(elapsedMs = recordingElapsedMs, totalMs = 10_000L)
                        }
                        CallState.WAITING_REPLY -> {
                            // Waiting for server reply
                            WaitingReplySection()
                        }
                        CallState.FINISHED -> { /* nothing */ }
                    }
                }

                // ── Bottom Buttons ───────────────────────────────────────
                Box(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (callState == CallState.RINGING) {
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
                    } else if (callState != CallState.FINISHED) {
                        // Ongoing call active: Show Loudspeaker Toggle (Left) & End Call (Right) - Icon Only
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 48.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val haptic = LocalHapticFeedback.current

                            // ── Left: Loudspeaker Toggle (Icon Only) ──
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isLoudspeaker) {
                                            Brush.radialGradient(
                                                colors = listOf(Color(0xFF00E5FF), Color(0xFF00838F))
                                            )
                                        } else {
                                            Brush.radialGradient(
                                                colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                                            )
                                        }
                                    )
                                    .border(
                                        2.dp,
                                        if (isLoudspeaker) Color(0xFF84FFFF) else Color(0xFF475569),
                                        CircleShape
                                    )
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onToggleLoudspeaker(!isLoudspeaker)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                SpeakerIcon(
                                    isLoudspeaker = isLoudspeaker,
                                    tint = if (isLoudspeaker) Color(0xFF002B36) else Color(0xFF94A3B8),
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            // ── Right: End Call (Icon Only) ──
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(Color(0xFFFF1744), Color(0xFFB71C1C))
                                        )
                                    )
                                    .border(2.dp, Color(0xFFFF8A80), CircleShape)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onReject()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Tutup Panggilan",
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
    }
}

// ─── Header Composable ────────────────────────────────────────────────────────
@Composable
fun CallHeader(callState: CallState, callerName: String) {
    val statusText = when (callState) {
        CallState.RINGING          -> "INCOMING TRANSMISSION"
        CallState.ACCEPTED_SPEAKING -> "SECURE CONNECTION"
        CallState.RECORDING        -> "VOICE INPUT ACTIVE"
        CallState.WAITING_REPLY    -> "PROCESSING REPLY"
        CallState.REJECTED_SPEAKING -> "LAST MESSAGE"
        CallState.FINISHED         -> "DISCONNECTING"
    }
    val statusColor = when (callState) {
        CallState.RINGING          -> Color(0xFFFF5252)
        CallState.ACCEPTED_SPEAKING, CallState.RECORDING -> Color(0xFF64FFDA)
        CallState.WAITING_REPLY    -> Color(0xFFFFD740)
        CallState.REJECTED_SPEAKING -> Color(0xFFFF6E40)
        CallState.FINISHED         -> Color(0xFF8892B0)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 64.dp)
    ) {
        Text(
            text = statusText,
            color = statusColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 3.sp
        )
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
