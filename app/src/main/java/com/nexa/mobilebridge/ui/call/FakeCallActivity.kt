package com.nexa.mobilebridge.ui.call

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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

    // Mutable state hoisted to Activity level so coroutines can update it
    private val callState = mutableStateOf(CallState.RINGING)
    private val recordingElapsedMs = mutableLongStateOf(0L)

    // Track how many times user has rejected (sent to server for importance evaluation)
    private var rejectionCount = 0

    // BroadcastReceiver for server audio events (Live Audio Play, Interrupted, Reply Complete)
    private val callReplyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.nexa.mobilebridge.CALL_REPLY_COMPLETE") {
                Log.i(TAG, "📩 CALL_REPLY_COMPLETE received — closing call UI")
                voiceStreamHandler.stopStreaming()
                callState.value = CallState.FINISHED
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
        setupShowWhenLocked()

        ttsHandler = TtsHandler(applicationContext)
        audioPlayerHandler = AudioPlayerHandler(applicationContext)
        voiceRecorderHandler = VoiceRecorderHandler(applicationContext)
        voiceStreamHandler = com.nexa.mobilebridge.dispatcher.handler.VoiceStreamHandler(applicationContext)

        commandId = intent.getStringExtra("command_id") ?: "fake_call_${System.currentTimeMillis()}"
        callerName = intent.getStringExtra("caller_name") ?: "N.E.X.A Assistant"
        callMessage = intent.getStringExtra("message") ?: "Tuan Faqih, ada pesan penting dari N.E.X.A!"
        val playRingtone = intent.getBooleanExtra("play_ringtone", true)

        // Register receiver for server live audio & reply events
        val filter = IntentFilter().apply {
            addAction("com.nexa.mobilebridge.CALL_REPLY_COMPLETE")
            addAction("com.nexa.mobilebridge.CALL_LIVE_EVENT")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callReplyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(callReplyReceiver, filter)
        }

        setContent {
            // Observe state for recomposition
            val state by callState
            val elapsedMs by recordingElapsedMs

            // Start ringtone exactly when UI first renders
            LaunchedEffect(Unit) {
                if (playRingtone) {
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

            // Auto-finish when state becomes FINISHED
            LaunchedEffect(state) {
                if (state == CallState.FINISHED) {
                    delay(400L) // Brief pause for any final animation
                    voiceStreamHandler.stopStreaming()
                    finish()
                }
            }

            FakeCallScreen(
                callerName = callerName,
                callMessage = callMessage,
                callState = state,
                recordingElapsedMs = elapsedMs,
                onAnswer = { handleAccept() },
                onReject = { handleReject() }
            )
        }
    }

    // ─── Accept Handler (Live Voice Streaming Mode) ──────────────────────────
    private fun handleAccept() {
        if (callState.value != CallState.RINGING) return
        callState.value = CallState.ACCEPTED_SPEAKING
        audioPlayerHandler.stopAll()

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

    // ─── Lifecycle ────────────────────────────────────────────────────────────
    private fun setupShowWhenLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
    recordingElapsedMs: Long,
    onAnswer: () -> Unit,
    onReject: () -> Unit
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
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SwipeButton(
                                icon = Icons.Default.Close,
                                tint = Color(0xFFFF003C),
                                containerColor = Color(0xFFFF003C).copy(alpha = 0.15f),
                                onThresholdReached = onReject
                            )
                            SwipeButton(
                                icon = Icons.Default.Phone,
                                tint = Color(0xFF64FFDA),
                                containerColor = Color(0xFF64FFDA).copy(alpha = 0.15f),
                                onThresholdReached = onAnswer
                            )
                        }
                    } else if (callState != CallState.FINISHED) {
                        // Ongoing call active: Show clear End Call button
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            val haptic = LocalHapticFeedback.current
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(Color(0xFFFF3366), Color(0xFFFF003C), Color(0xFF990024))
                                        )
                                    )
                                    .border(2.dp, Color(0xFFFF8099), CircleShape)
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
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Tutup Panggilan",
                                color = Color(0xFFFF8099),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
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

// ─── Swipe Button Composable ──────────────────────────────────────────────────
@Composable
fun SwipeButton(
    icon: ImageVector,
    tint: Color,
    containerColor: Color,
    onThresholdReached: () -> Unit
) {
    val density = LocalDensity.current
    val maxDragDistance = with(density) { -120.dp.toPx() }
    val threshold = with(density) { -80.dp.toPx() }

    var offsetY by remember { mutableFloatStateOf(0f) }
    var isTriggered by remember { mutableStateOf(false) }

    val animatedOffsetY by animateFloatAsState(
        targetValue = offsetY,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ), label = "swipeSpring"
    )

    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
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
                    if (offsetY <= threshold) {
                        isTriggered = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onThresholdReached()
                    }
                }
            }
            .size(80.dp)
            .background(containerColor, CircleShape)
            .border(2.dp, tint, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(36.dp)
        )
    }
}
