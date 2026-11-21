package com.nexa.mobilebridge.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay

/**
 * Manages the interactive Floating Overlay for Gemini Live Screen Sharing:
 * 1. Top-right capsule with [ ◱ ] expander button.
 * 2. Bottom Dock with Subtitle Card, 5 controls (Screen Share in Royal Blue),
 *    and audio visualizer capsule (Image 1).
 * 3. Smooth collapse to Floating Glowing Bubble at bottom-right on user screen touch / idle (Image 2).
 * 4. Tap bubble to smoothly re-expand to full dock.
 */
private const val BUBBLE_SIZE_DP = 56

class FloatingOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "FloatingOverlayManager"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lifecycleOwner: FloatingLifecycleOwner? = null
    private var pipContainer: FrameLayout? = null
    private var dockContainer: FrameLayout? = null

    // Layout params & coordinates for draggable bottom overlay bubble
    private var isCurrentlyCollapsed = true
    private val isCollapsedState = mutableStateOf(true)
    private var collapseRunnable: Runnable? = null
    private var snapAnimator: ValueAnimator? = null

    private var bubbleX = 0
    private var bubbleY = 0
    private var initialTouchRawX = 0f
    private var initialTouchRawY = 0f
    private var initialParamX = 0
    private var initialParamY = 0
    private var isDraggingBubble = false
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    fun show() {
        if (dockContainer != null) return

        val density = context.resources.displayMetrics.density
        val displayMetrics = context.resources.displayMetrics
        val sizePx = (BUBBLE_SIZE_DP * density).toInt()
        val margin = (16 * density).toInt()

        // Default initial position: Bottom-Right edge above navigation bar
        bubbleX = displayMetrics.widthPixels - sizePx - margin
        bubbleY = displayMetrics.heightPixels - sizePx - (100 * density).toInt()

        val owner = FloatingLifecycleOwner()
        owner.handleStart()
        lifecycleOwner = owner

        // Start collapsed as a sleek, non-intrusive floating bubble
        isCurrentlyCollapsed = true
        isCollapsedState.value = true
        showDockWindow(owner)
    }

    fun dismiss() {
        snapAnimator?.cancel()
        snapAnimator = null
        collapseRunnable?.let { mainHandler.removeCallbacks(it) }
        collapseRunnable = null
        mainHandler.removeCallbacksAndMessages(null)
        try {
            pipContainer?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing pipContainer: ${e.message}")
        }
        try {
            dockContainer?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing dockContainer: ${e.message}")
        }
        pipContainer = null
        dockContainer = null
        lifecycleOwner?.handleDestroy()
        lifecycleOwner = null
    }

    // ─── 1. Top-Right PiP Capsule Window ─────────────────────────────────────
    private fun showPipWindow(owner: FloatingLifecycleOwner) {
        if (pipContainer != null) return

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = context.resources.displayMetrics.density
        val pipParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * density).toInt()
            y = (48 * density).toInt() // Below system status bar
        }

        val container = FrameLayout(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val collapsed by isCollapsedState
                AnimatedVisibility(
                    visible = !collapsed,
                    enter = fadeIn(tween(250)) + scaleIn(tween(250)),
                    exit = fadeOut(tween(200)) + scaleOut(tween(200))
                ) {
                    PipExpanderCapsule(
                        onReturnToCall = {
                            ScreenCaptureService.onReturnToCallRequested?.invoke()
                        }
                    )
                }
            }
        }
        container.addView(composeView)
        try {
            windowManager.addView(container, pipParams)
            pipContainer = container
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add PiP overlay window: ${e.message}")
        }
    }

    // ─── 2. Bottom Dock / Bubble Window ──────────────────────────────────────
    private fun showDockWindow(owner: FloatingLifecycleOwner) {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Initialize as collapsed draggable bubble
        val dockParams = createCollapsedParams(overlayType)

        val container = object : FrameLayout(context) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (isCurrentlyCollapsed) {
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> {
                            snapAnimator?.cancel()
                            initialTouchRawX = ev.rawX
                            initialTouchRawY = ev.rawY
                            initialParamX = bubbleX
                            initialParamY = bubbleY
                            isDraggingBubble = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = ev.rawX - initialTouchRawX
                            val dy = ev.rawY - initialTouchRawY
                            if (!isDraggingBubble && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                                isDraggingBubble = true
                            }
                            if (isDraggingBubble) {
                                val density = context.resources.displayMetrics.density
                                val sizePx = (BUBBLE_SIZE_DP * density).toInt()
                                val displayMetrics = context.resources.displayMetrics
                                val maxBubbleX = displayMetrics.widthPixels - sizePx
                                val minBubbleY = (36 * density).toInt()
                                val maxBubbleY = displayMetrics.heightPixels - sizePx - (36 * density).toInt()

                                bubbleX = (initialParamX + dx.toInt()).coerceIn(0, maxBubbleX)
                                bubbleY = (initialParamY + dy.toInt()).coerceIn(minBubbleY, maxBubbleY)

                                val currentParams = layoutParams as? WindowManager.LayoutParams
                                if (currentParams != null) {
                                    currentParams.x = bubbleX
                                    currentParams.y = bubbleY
                                    windowManager.updateViewLayout(this, currentParams)
                                }
                                return true
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (isDraggingBubble) {
                                isDraggingBubble = false
                                val density = context.resources.displayMetrics.density
                                val sizePx = (BUBBLE_SIZE_DP * density).toInt()
                                val displayMetrics = context.resources.displayMetrics
                                val margin = (16 * density).toInt()
                                // Snap smoothly to closest edge (Left or Right)
                                val targetX = if (bubbleX + sizePx / 2 < displayMetrics.widthPixels / 2) {
                                    margin
                                } else {
                                    displayMetrics.widthPixels - sizePx - margin
                                }
                                snapBubbleToEdge(targetX)
                                return true
                            } else {
                                // Clean quick tap -> expand into full dock
                                expandToDock()
                                return true
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            if (isDraggingBubble) {
                                isDraggingBubble = false
                                return true
                            }
                        }
                    }
                } else {
                    if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                        // Screen touched outside dock -> trigger smooth collapse
                        collapseToBubble()
                        return false
                    }
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }

        val composeView = ComposeView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                FloatingDockOverlayScreen(
                    isCollapsed = isCollapsedState.value,
                    onExpandRequested = { expandToDock() },
                    onCollapseRequested = { collapseToBubble() },
                    onReturnToCall = { ScreenCaptureService.onReturnToCallRequested?.invoke() },
                    onToggleMicMute = { ScreenCaptureService.onToggleMicMuteRequested?.invoke() },
                    onInterrupt = { ScreenCaptureService.onInterruptRequested?.invoke() },
                    onEndCall = { ScreenCaptureService.onEndCallRequested?.invoke() },
                    onStopScreenShare = { ScreenCaptureService.stop(context) }
                )
            }
        }
        container.addView(composeView)

        try {
            windowManager.addView(container, dockParams)
            dockContainer = container
            isCurrentlyCollapsed = true
            isCollapsedState.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Dock overlay window: ${e.message}")
        }
    }

    private fun createExpandedParams(overlayType: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            x = 0
            y = 0
        }
    }

    private fun snapBubbleToEdge(targetX: Int) {
        snapAnimator?.cancel()
        val container = dockContainer ?: return
        val currentX = bubbleX
        snapAnimator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val animatedX = va.animatedValue as Int
                bubbleX = animatedX
                val params = container.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    params.x = animatedX
                    try {
                        windowManager.updateViewLayout(container, params)
                    } catch (_: Exception) {}
                }
            }
        }
        snapAnimator?.start()
    }

    private fun createCollapsedParams(overlayType: Int): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        val sizePx = (BUBBLE_SIZE_DP * density).toInt()
        return WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = bubbleY
        }
    }

    fun collapseToBubble() {
        if (isCurrentlyCollapsed) return
        isCurrentlyCollapsed = true
        isCollapsedState.value = true
        pipContainer?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            pipContainer = null
        }

        collapseRunnable?.let { mainHandler.removeCallbacks(it) }
        val container = dockContainer ?: return
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        try {
            windowManager.updateViewLayout(container, createCollapsedParams(overlayType))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update layout to collapsed: ${e.message}")
        }
    }

    fun expandToDock() {
        if (!isCurrentlyCollapsed) return
        collapseRunnable?.let { mainHandler.removeCallbacks(it) }
        collapseRunnable = null

        val container = dockContainer ?: return
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Expand WindowManager bounds first, then trigger Compose bloom animation
        try {
            windowManager.updateViewLayout(container, createExpandedParams(overlayType))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update layout to expanded: ${e.message}")
        }
        lifecycleOwner?.let { showPipWindow(it) }
        isCurrentlyCollapsed = false
        isCollapsedState.value = false
    }
}

// ─── COMPOSE COMPONENTS FOR FLOATING OVERLAY ─────────────────────────────────

/**
 * Top-Right PiP expander capsule button [ ◱ ]
 */
@Composable
fun PipExpanderCapsule(onReturnToCall: () -> Unit) {
    Box(
        modifier = Modifier
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xEE1E1E1E))
            .border(1.dp, Color(0x33FFFFFF), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onReturnToCall
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.PictureInPictureAlt,
            contentDescription = "Kembali ke Layar Penuh",
            tint = Color(0xFFE2E8F0),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Main floating overlay containing either:
 * - Expanded 5-button dock + Subtitle card (Image 1)
 * - Collapsed floating glowing orb in bottom-right corner (Image 2)
 */
@Composable
fun FloatingDockOverlayScreen(
    isCollapsed: Boolean,
    onExpandRequested: () -> Unit,
    onCollapseRequested: () -> Unit,
    onReturnToCall: () -> Unit,
    onToggleMicMute: () -> Unit,
    onInterrupt: () -> Unit,
    onEndCall: () -> Unit,
    onStopScreenShare: () -> Unit
) {
    val subtitleText by ScreenCaptureService.assistantSubtitle.collectAsState()
    val isMicMuted by ScreenCaptureService.isMicMuted.collectAsState()

    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }

    // Auto-collapse after 5 seconds of inactivity when expanded, reset on user interaction
    LaunchedEffect(isCollapsed, lastInteractionTime) {
        if (!isCollapsed) {
            delay(5000L)
            onCollapseRequested()
        }
    }

    Box(
        modifier = if (isCollapsed) {
            Modifier.size(BUBBLE_SIZE_DP.dp)
        } else {
            Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        },
        contentAlignment = Alignment.BottomEnd
    ) {
        // ── State 1: Expanded Dock (Image 1) ──
        AnimatedVisibility(
            visible = !isCollapsed,
            enter = slideInHorizontally(
                initialOffsetX = { it / 2 },
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 350f)
            ) + fadeIn(tween(250)),
            exit = slideOutHorizontally(
                targetOffsetX = { it / 2 },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(180))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Floating Subtitle Card ("Halo." or assistant response)
                FloatingSubtitleCard(text = subtitleText)

                // 5-Button Control Dock
                Floating5ButtonDock(
                    isMicMuted = isMicMuted,
                    onReturnToCall = {
                        lastInteractionTime = System.currentTimeMillis()
                        onReturnToCall()
                    },
                    onStopScreenShare = {
                        lastInteractionTime = System.currentTimeMillis()
                        onStopScreenShare()
                        onReturnToCall() // Restore full call screen
                    },
                    onInterrupt = {
                        lastInteractionTime = System.currentTimeMillis()
                        onInterrupt()
                    },
                    onToggleMicMute = {
                        lastInteractionTime = System.currentTimeMillis()
                        onToggleMicMute()
                    },
                    onEndCall = {
                        lastInteractionTime = System.currentTimeMillis()
                        onEndCall()
                    }
                )
            }
        }

        // ── State 2: Collapsed Glowing Orb (Image 2) ──
        AnimatedVisibility(
            visible = isCollapsed,
            enter = scaleIn(
                initialScale = 0.4f,
                animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f)
            ) + fadeIn(tween(200)),
            exit = scaleOut(
                targetScale = 0.4f,
                animationSpec = tween(180)
            ) + fadeOut(tween(180))
        ) {
            FloatingGlowingBubble(
                onClick = onExpandRequested
            )
        }
    }
}

/**
 * Floating subtitle capsule card above the dock (e.g. "Halo.")
 */
@Composable
fun FloatingSubtitleCard(text: String) {
    if (text.isBlank()) return

    Box(
        modifier = Modifier
            .shadow(16.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xEE1E1E1E))
            .border(1.dp, Color(0x25FFFFFF), RoundedCornerShape(22.dp))
            .padding(horizontal = 22.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Full 5-Button Dock with Screen Share highlighted in Royal Blue (Image 1)
 */
@Composable
fun Floating5ButtonDock(
    isMicMuted: Boolean,
    onReturnToCall: () -> Unit,
    onStopScreenShare: () -> Unit,
    onInterrupt: () -> Unit,
    onToggleMicMute: () -> Unit,
    onEndCall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Camera Button (Dark Circle)
        FloatingCircleButton(
            icon = Icons.Default.Videocam,
            backgroundColor = Color(0xDD2A2A2A),
            iconTint = Color(0xFFCBD5E1),
            contentDescription = "Buka Kamera / Panggilan",
            onClick = onReturnToCall
        )

        // 2. Screen Share Button (ROYAL BLUE - ACTIVE)
        FloatingCircleButton(
            icon = Icons.Default.Upload,
            backgroundColor = Color(0xFF2563EB), // Gemini Live Royal Blue
            iconTint = Color.White,
            contentDescription = "Hentikan Berbagi Layar",
            onClick = onStopScreenShare
        )

        // 3. Central Gemini Live Voice Visualizer Capsule
        FloatingLiveVoiceCapsule(
            onInterrupt = onInterrupt
        )

        // 4. Mic Mute Button (Dark Circle, Red if muted)
        FloatingCircleButton(
            icon = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
            backgroundColor = if (isMicMuted) Color(0xFFEF4444) else Color(0xDD2A2A2A),
            iconTint = Color.White,
            contentDescription = if (isMicMuted) "Nyalakan Mikrofon" else "Bisukan Mikrofon",
            onClick = onToggleMicMute
        )

        // 5. Close / End Call [ ✕ ] Button (Dark Circle)
        FloatingCircleButton(
            icon = Icons.Default.Close,
            backgroundColor = Color(0xDD2A2A2A),
            iconTint = Color(0xFFF87171),
            contentDescription = "Akhiri Panggilan",
            onClick = onEndCall
        )
    }
}

/**
 * Standard 48dp circular button used in dock
 */
@Composable
fun FloatingCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    backgroundColor: Color,
    iconTint: Color,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(1.dp, Color(0x22FFFFFF), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Center Voice Capsule with animated sound waves and blue bottom glow (Image 1)
 */
@Composable
fun FloatingLiveVoiceCapsule(
    onInterrupt: () -> Unit
) {
    val audioEnergy by ScreenCaptureService.audioEnergy.collectAsState()
    val isSpeaking by ScreenCaptureService.isAssistantSpeaking.collectAsState()
    val infiniteTransition = rememberInfiniteTransition(label = "VoiceWaveInfinite")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Box(
        modifier = Modifier
            .width(118.dp)
            .height(50.dp)
            .shadow(12.dp, RoundedCornerShape(25.dp))
            .clip(RoundedCornerShape(25.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1E1E1E),
                        Color(0xFF1E3A8A) // Electric blue bottom glow
                    )
                )
            )
            .border(1.dp, Color(0x3338BDF8), RoundedCornerShape(25.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onInterrupt
            ),
        contentAlignment = Alignment.Center
    ) {
        // 4 Animated Audio Visualizer Bars
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val baseEnergy = if (isSpeaking) 0.65f else (audioEnergy.coerceIn(0.05f, 1f))
            for (i in 0..3) {
                val waveFactor = kotlin.math.sin(phase + (i * 1.1f)).coerceIn(0.2f, 1f)
                val targetHeight = (10.dp + (22.dp * baseEnergy * waveFactor)).coerceIn(8.dp, 30.dp)
                val animatedHeight by animateFloatAsState(
                    targetValue = targetHeight.value,
                    animationSpec = spring(stiffness = 500f),
                    label = "barHeight$i"
                )

                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(animatedHeight.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF60A5FA))
                )
            }
        }
    }
}

/**
 * Collapsed Floating Glowing Orb (Gemini Live Dynamic Color Gradient)
 * Fluid, organic, and voice-reactive sphere shifting smoothly across:
 * - Cyan (#00E5FF / #38BDF8)
 * - Biru Elektrik (#2563EB / #1D4ED8)
 * - Hijau Emerald / Mint (#10B981 / #34D399)
 *
 * When speaking: Colors swirl, rotate, and pulse dynamically with Nexa's speech.
 * When listening/idle: Calm, soothing breathing glow waiting for user input.
 */
@Composable
fun FloatingGlowingBubble(
    onClick: () -> Unit
) {
    val isSpeaking by ScreenCaptureService.isAssistantSpeaking.collectAsState()
    val audioEnergy by ScreenCaptureService.audioEnergy.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "GeminiElectricOrb")

    // Dynamic rotation of the electric aurora swirl (3.0s idle, 1.5s speaking)
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSpeaking) 1500 else 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "electricRotation"
    )

    // Breathing & electric pulse phase
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSpeaking) 750 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "electricPulse"
    )

    // Voice & speech dynamic energy boost
    val dynamicEnergy = if (isSpeaking) {
        (0.70f + audioEnergy.coerceIn(0f, 1f) * 0.45f + pulsePhase * 0.15f).coerceIn(0.6f, 1.3f)
    } else {
        (0.40f + pulsePhase * 0.20f)
    }

    Box(
        modifier = Modifier
            .size(BUBBLE_SIZE_DP.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(BUBBLE_SIZE_DP.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = (size.minDimension / 2f) - 1.5.dp.toPx()

            // Clip all dynamic rendering strictly inside the sphere
            clipPath(Path().apply {
                addOval(Rect(center = center, radius = outerRadius))
            }) {
                // 1. Deep midnight obsidian background
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF090D16),
                            Color(0xFF0F1524)
                        ),
                        startY = center.y - outerRadius,
                        endY = center.y + outerRadius
                    )
                )

                // 2. Swirling 3D Electric Aurora Orbit (Cyan, Biru Elektrik, Hijau Mint)
                val rad = Math.toRadians(rotationAngle.toDouble())
                val orbitTiltY = outerRadius * 0.22f // Anchored in the lower hemisphere
                val orbitRadiusX = outerRadius * 0.48f
                val orbitRadiusY = outerRadius * 0.26f

                // Node 1: Neon Cyan (#00E5FF / #00F0FF)
                val cyanX = center.x + (cos(rad) * orbitRadiusX).toFloat()
                val cyanY = center.y + orbitTiltY + (sin(rad) * orbitRadiusY).toFloat()
                val cyanCenter = Offset(cyanX, cyanY)
                val cyanRadius = outerRadius * (0.85f + dynamicEnergy * 0.22f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF00F0FF).copy(alpha = if (isSpeaking) 0.95f else 0.82f),
                            Color(0xFF00B4D8).copy(alpha = if (isSpeaking) 0.65f else 0.48f),
                            Color(0xFF0077B6).copy(alpha = 0.22f),
                            Color.Transparent
                        ),
                        center = cyanCenter,
                        radius = cyanRadius
                    ),
                    center = cyanCenter,
                    radius = cyanRadius
                )

                // Node 2: Biru Elektrik (#2563EB / #1D4ED8) - 120° offset
                val radBlue = rad + (2.0 * Math.PI / 3.0)
                val blueX = center.x + (cos(radBlue) * orbitRadiusX).toFloat()
                val blueY = center.y + orbitTiltY + (sin(radBlue) * orbitRadiusY).toFloat()
                val blueCenter = Offset(blueX, blueY)
                val blueRadius = outerRadius * (0.88f + dynamicEnergy * 0.24f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF3B82F6).copy(alpha = if (isSpeaking) 0.92f else 0.78f),
                            Color(0xFF1D4ED8).copy(alpha = if (isSpeaking) 0.62f else 0.45f),
                            Color(0xFF1E3A8A).copy(alpha = 0.20f),
                            Color.Transparent
                        ),
                        center = blueCenter,
                        radius = blueRadius
                    ),
                    center = blueCenter,
                    radius = blueRadius
                )

                // Node 3: Hijau Emerald / Mint (#10B981 / #34D399) - 240° offset
                val radGreen = rad + (4.0 * Math.PI / 3.0)
                val greenX = center.x + (cos(radGreen) * orbitRadiusX).toFloat()
                val greenY = center.y + orbitTiltY + (sin(radGreen) * orbitRadiusY).toFloat()
                val greenCenter = Offset(greenX, greenY)
                val greenRadius = outerRadius * (0.80f + dynamicEnergy * 0.20f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF10B981).copy(alpha = if (isSpeaking) 0.88f else 0.72f),
                            Color(0xFF059669).copy(alpha = if (isSpeaking) 0.55f else 0.40f),
                            Color(0xFF064E3B).copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = greenCenter,
                        radius = greenRadius
                    ),
                    center = greenCenter,
                    radius = greenRadius
                )

                // 3. Central Luminous Plasma Shimmer (Pulsing glowing mist, shifting with rotation)
                val coreX = center.x + (cos(rad + Math.PI / 4) * (outerRadius * 0.14f)).toFloat()
                val coreY = center.y + orbitTiltY + (sin(rad + Math.PI / 4) * (outerRadius * 0.10f)).toFloat()
                val coreCenter = Offset(coreX, coreY)
                val coreRadius = outerRadius * (0.45f + dynamicEnergy * 0.22f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFFFFFF).copy(alpha = if (isSpeaking) 0.90f else 0.70f),
                            Color(0xFFE0F2FE).copy(alpha = if (isSpeaking) 0.55f else 0.38f),
                            Color(0xFF67E8F9).copy(alpha = 0.20f),
                            Color.Transparent
                        ),
                        center = coreCenter,
                        radius = coreRadius
                    ),
                    center = coreCenter,
                    radius = coreRadius
                )

                // 4. Cosmic Obsidian Top Shadow (Deepens upper hemisphere for true 3D sphere look)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xCC090D16),
                            Color(0x66090D16),
                            Color.Transparent
                        ),
                        startY = center.y - outerRadius,
                        endY = center.y + (outerRadius * 0.3f)
                    )
                )
            }

            // 5. Dynamic Shimmering Glass Rim
            val rimBrush = Brush.sweepGradient(
                colors = listOf(
                    Color(0x5500F0FF),
                    Color(0x552563EB),
                    Color(0x5510B981),
                    Color(0x5500F0FF)
                ),
                center = center
            )
            drawCircle(
                brush = rimBrush,
                radius = outerRadius,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
            drawCircle(
                color = Color(0x22FFFFFF),
                radius = outerRadius,
                center = center,
                style = Stroke(width = 0.8.dp.toPx())
            )
        }
    }
}
