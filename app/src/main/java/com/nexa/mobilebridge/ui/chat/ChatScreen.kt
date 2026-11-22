package com.nexa.mobilebridge.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.nexa.mobilebridge.core.security.findActivity
import com.nexa.mobilebridge.data.chat.ChatReplyMetadata
import com.nexa.mobilebridge.data.chat.ChatReplyTarget
import com.nexa.mobilebridge.data.chat.VoiceNoteMetadata
import com.nexa.mobilebridge.data.chat.VoiceNoteWaveform
import com.nexa.mobilebridge.data.chat.VoicePlaybackState
import com.nexa.mobilebridge.data.local.chat.ChatMessageEntity
import com.nexa.mobilebridge.data.local.chat.ChatRole
import com.nexa.mobilebridge.data.local.chat.ChatStatus
import com.nexa.mobilebridge.theme.LocalNexaColors
import com.nexa.mobilebridge.theme.NexaMonoTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Native AI Chat Screen matching Stitch Blueprint v2 (01_chat_ai_native_v2.png)
 * and PRD FR-CHAT-01..19, FR-AUTH-07/08:
 * - LazyColumn(reverseLayout = true) + Paging 3 (maxSize = 100)
 * - 33ms token batching overlay on active STREAMING row (zero per-token Room writes)
 * - Incremental Markdown & Rupiah highlight renderer (memoizes completed paragraphs)
 * - Telegram-style bubble gestures: single tap opens action menu, long press enters multi-select mode
 * - Idempotent "Kirim ulang" retry button on FAILED user bubbles
 * - Docked FinanceStepUpBottomSheet with ref-counted FLAG_SECURE & locked input bar
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel()
) {
    val pagedItems: LazyPagingItems<ChatMessageEntity> = viewModel.pagedMessages.collectAsLazyPagingItems()
    val streamState by viewModel.streamState.collectAsStateWithLifecycle()
    val pendingStepUp by viewModel.pendingStepUp.collectAsStateWithLifecycle()
    val submittingCardIds by viewModel.submittingCardIds.collectAsStateWithLifecycle()
    val cardErrors by viewModel.cardErrors.collectAsStateWithLifecycle()
    val chatPrefs by viewModel.chatPrefs.collectAsStateWithLifecycle()
    val voiceRecordingState by viewModel.voiceRecordingState.collectAsStateWithLifecycle()
    val voicePlaybackState by viewModel.voicePlaybackState.collectAsStateWithLifecycle()
    val replyTarget by viewModel.replyTarget.collectAsStateWithLifecycle()
    val selectionState by viewModel.selectionState.collectAsStateWithLifecycle()
    val pendingDeleteIds by viewModel.pendingDeleteIds.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val nexaColors = LocalNexaColors.current
    var draftText by rememberSaveable { mutableStateOf("") }
    var isInputFocused by remember { mutableStateOf(false) }
    var isExpandedComposer by rememberSaveable { mutableStateOf(false) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var activeMenuAnchor by remember { mutableStateOf<MessageMenuAnchor?>(null) }
    var activeCategoryPickerCard by remember { mutableStateOf<com.nexa.mobilebridge.data.chat.InteractiveCardData?>(null) }
    var activeAccountPickerCard by remember { mutableStateOf<com.nexa.mobilebridge.data.chat.InteractiveCardData?>(null) }
    var activeMethodPickerCard by remember { mutableStateOf<com.nexa.mobilebridge.data.chat.InteractiveCardData?>(null) }
    var activeFinanceEditCard by remember { mutableStateOf<com.nexa.mobilebridge.data.chat.InteractiveCardData?>(null) }
    var activeVaultEditCard by remember { mutableStateOf<com.nexa.mobilebridge.data.chat.InteractiveCardData?>(null) }
    val filteredSlashCommands = remember(draftText) { filterSlashCommands(draftText) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (!granted) {
            viewModel.showVoiceHint("Microphone permission is required to record voice notes")
        } else {
            viewModel.showVoiceHint("Hold mic to record")
        }
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val headMessage = if (pagedItems.itemCount > 0) pagedItems.peek(0) else null
    val headMessageId = headMessage?.id
    val headMessageRole = headMessage?.role

    var lastSeenHeadId by remember { mutableStateOf<String?>(null) }
    var isPinnedToBottom by remember { mutableStateOf(true) }
    var pendingForceScroll by remember { mutableStateOf(false) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }
    var unreadWhileScrolledUp by remember { mutableIntStateOf(0) }

    val showScrollToBottomButton by remember {
        derivedStateOf {
            pagedItems.itemCount > 0 &&
                (listState.firstVisibleItemIndex >= 1 || listState.firstVisibleItemScrollOffset > 180)
        }
    }

    suspend fun scrollToLatest(animated: Boolean) {
        if (pagedItems.itemCount <= 0) return
        isProgrammaticScroll = true
        try {
            if (animated && listState.firstVisibleItemIndex <= 12) {
                listState.animateScrollToItem(0, 0)
            } else {
                listState.scrollToItem(0, 0)
            }
        } finally {
            isProgrammaticScroll = false
        }
    }

    fun requestScrollToLatestOnSend() {
        pendingForceScroll = true
        isPinnedToBottom = true
        unreadWhileScrolledUp = 0
        if (pagedItems.itemCount > 0 && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) {
            coroutineScope.launch { scrollToLatest(animated = true) }
        }
    }

    BackHandler(enabled = activeMenuAnchor != null || selectionState.isActive) {
        if (activeMenuAnchor != null) {
            activeMenuAnchor = null
        } else if (selectionState.isActive) {
            viewModel.clearSelection()
        }
    }

    LaunchedEffect(selectionState.isActive) {
        if (selectionState.isActive) {
            activeMenuAnchor = null
            isExpandedComposer = false
            focusManager.clearFocus(force = true)
            viewModel.cancelVoiceRecording()
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && activeMenuAnchor != null) {
            activeMenuAnchor = null
        }
    }

    // Track whether the user manually scrolled away from the latest message.
    // Ignores Compose key-anchor shifts (0 -> 1) on new inserts because isScrollInProgress is false.
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }.collect { (scrolling, index, offset) ->
            if (index == 0 && offset <= 48) {
                isPinnedToBottom = true
                unreadWhileScrolledUp = 0
            } else if (scrolling && !isProgrammaticScroll && (index >= 1 || offset > 96)) {
                isPinnedToBottom = false
            }
        }
    }

    // Auto-scroll to latest ONLY AFTER Room + Paging 3 emits the new head row into LazyColumn.
    // Waiting 1 frame (withFrameNanos) lets LazyColumn finish key-anchor shift (0 -> 1) first so scrollToItem(0) is never a no-op.
    LaunchedEffect(headMessageId) {
        val currentHead = headMessageId ?: return@LaunchedEffect
        val previousHead = lastSeenHeadId
        lastSeenHeadId = currentHead

        if (previousHead == null) {
            if (pendingForceScroll) {
                withFrameNanos { }
                scrollToLatest(animated = false)
                pendingForceScroll = false
            }
            return@LaunchedEffect
        }

        if (currentHead != previousHead) {
            val shouldFollow = pendingForceScroll ||
                headMessageRole == ChatRole.USER ||
                isPinnedToBottom

            if (shouldFollow) {
                withFrameNanos { }
                if (!listState.isScrollInProgress || isProgrammaticScroll) {
                    isPinnedToBottom = true
                    unreadWhileScrolledUp = 0
                    scrollToLatest(animated = true)
                }
                pendingForceScroll = false
            } else {
                unreadWhileScrolledUp = (unreadWhileScrolledUp + 1).coerceAtMost(99)
            }
        }
    }

    // Keep the bottom of an actively growing streamed reply visible while pinned to bottom
    LaunchedEffect(streamState.activeAssistantRowId, streamState.streamingText.length) {
        if (isPinnedToBottom &&
            !listState.isScrollInProgress &&
            streamState.activeAssistantRowId != null &&
            pagedItems.itemCount > 0 &&
            (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
        ) {
            scrollToLatest(animated = false)
        }
    }

    // Keep latest message visible above the keyboard when focusing input while pinned to bottom
    LaunchedEffect(isInputFocused) {
        if (isInputFocused && isPinnedToBottom && pagedItems.itemCount > 0) {
            withFrameNanos { }
            if (!listState.isScrollInProgress &&
                (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
            ) {
                scrollToLatest(animated = true)
            }
        }
    }

    LaunchedEffect(isInputFocused, draftText, pendingStepUp, isExpandedComposer) {
        viewModel.setInputListening(
            pendingStepUp == null && (isInputFocused || isExpandedComposer || draftText.isNotBlank())
        )
        if (pendingStepUp != null) {
            isExpandedComposer = false
            activeMenuAnchor = null
            viewModel.clearSelection()
            viewModel.cancelVoiceRecording()
        }
    }

    // Keep screen awake during active voice recording (HOLDING/LOCKED) so OS sleep never deletes a long voice note
    DisposableEffect(view, voiceRecordingState.phase) {
        val keepAwake = voiceRecordingState.phase != VoiceRecordingPhase.IDLE
        view.keepScreenOn = keepAwake
        onDispose {
            if (keepAwake) {
                view.keepScreenOn = false
            }
        }
    }

    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId != null) {
            delay(1_150L)
            highlightedMessageId = null
        }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshPreferences()
        hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        onPauseOrDispose {
            activeMenuAnchor = null
            viewModel.stopVoicePlayback()
            viewModel.cancelVoiceRecording()
            viewModel.setInputListening(false)
        }
    }

    fun triggerHapticTick() {
        if (chatPrefs.hapticEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun triggerHapticConfirm() {
        if (chatPrefs.hapticEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.US) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
        ) {
        // Optional transient error banner
        if (!streamState.lastError.isNullOrBlank()) {
            Surface(
                color = if (nexaColors.isDark) Color(0xFF281519) else Color(0xFFFEE2E2),
                border = BorderStroke(1.dp, nexaColors.crimson.copy(alpha = 0.7f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = streamState.lastError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (nexaColors.isDark) Color(0xFFFECACA) else nexaColors.crimson,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.clearError() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss alert",
                            tint = nexaColors.crimson,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Main Chat Area (reverseLayout = true so newest message sits at bottom above input/sheet)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (pagedItems.itemCount == 0) {
                ChatEmptyWelcomeState(
                    onQuickPrompt = { prompt ->
                        triggerHapticTick()
                        requestScrollToLatestOnSend()
                        viewModel.sendMessage(prompt)
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.Top
                ) {
                    items(
                        count = pagedItems.itemCount,
                        key = pagedItems.itemKey { it.id }
                    ) { index ->
                        val msg = pagedItems[index] ?: return@items
                        val aboveRole = if (index + 1 < pagedItems.itemCount) {
                            pagedItems.peek(index + 1)?.role
                        } else {
                            null
                        }
                        val belowRole = if (index - 1 >= 0) {
                            pagedItems.peek(index - 1)?.role
                        } else {
                            null
                        }
                        val topGap = if (aboveRole == msg.role) 1.5.dp else 4.dp
                        val bottomGap = if (belowRole == msg.role) 1.5.dp else 2.5.dp
                        val formattedMsgTime = timeFormat.format(Date(msg.timestamp))
                        val isLiveStreamingRow =
                            msg.id == streamState.activeAssistantRowId && streamState.streamingText.isNotEmpty()
                        val effectiveContent = if (isLiveStreamingRow) {
                            streamState.streamingText
                        } else {
                            msg.content
                        }
                        val isStreamingOrSending =
                            msg.status == ChatStatus.STREAMING ||
                                msg.status == ChatStatus.SENDING ||
                                isLiveStreamingRow
                        val isInteractive = !isStreamingOrSending
                        val isSelected = msg.id in selectionState.selectedIds
                        val isVoiceMsg = remember(msg.executionMetadata) {
                            VoiceNoteMetadata.fromJson(msg.executionMetadata) != null
                        }

                        SwipeToReplyContainer(
                            messageKey = msg.id,
                            isHighlighted = highlightedMessageId == msg.id,
                            isSelected = isSelected,
                            isSelectionMode = selectionState.isActive,
                            swipeEnabled = !selectionState.isActive,
                            topPadding = topGap,
                            bottomPadding = bottomGap,
                            onTriggerHaptic = { triggerHapticTick() },
                            onSwipeReply = {
                                viewModel.selectReplyTarget(msg, effectiveContent)
                            },
                            onRowTapInSelectionMode = {
                                if (isInteractive) {
                                    triggerHapticTick()
                                    viewModel.toggleSelectMessage(msg)
                                }
                            }
                        ) {
                            if (msg.role == ChatRole.USER) {
                                UserChatBubble(
                                    message = msg,
                                    formattedTime = formattedMsgTime,
                                    playbackState = voicePlaybackState,
                                    isSelected = isSelected,
                                    isSelectionMode = selectionState.isActive,
                                    isInteractive = isInteractive,
                                    onTapBubble = { tapOffset ->
                                        if (selectionState.isActive) {
                                            triggerHapticTick()
                                            viewModel.toggleSelectMessage(msg)
                                        } else {
                                            triggerHapticTick()
                                            activeMenuAnchor = MessageMenuAnchor(
                                                message = msg,
                                                displayContent = effectiveContent,
                                                isVoice = isVoiceMsg,
                                                tapWindowOffset = tapOffset
                                            )
                                        }
                                    },
                                    onLongPressBubble = {
                                        activeMenuAnchor = null
                                        triggerHapticConfirm()
                                        if (selectionState.isActive) {
                                            viewModel.toggleSelectMessage(msg)
                                        } else {
                                            viewModel.selectMessage(msg)
                                        }
                                    },
                                    onToggleVoicePlay = { audioPath, durationMs ->
                                        triggerHapticTick()
                                        viewModel.toggleVoicePlayback(msg.id, audioPath, durationMs)
                                    },
                                    onSeekVoice = { audioPath, durationMs, fraction ->
                                        viewModel.seekVoicePlayback(msg.id, audioPath, durationMs, fraction)
                                    },
                                    onJumpToReply = { targetId ->
                                        triggerHapticTick()
                                        for (i in 0 until pagedItems.itemCount) {
                                            if (pagedItems.peek(i)?.id == targetId) {
                                                highlightedMessageId = targetId
                                                isPinnedToBottom = (i == 0)
                                                coroutineScope.launch { listState.animateScrollToItem(i) }
                                                break
                                            }
                                        }
                                    },
                                    onRetry = {
                                        triggerHapticTick()
                                        viewModel.retryMessage(msg.clientMsgId)
                                    }
                                )
                            } else {
                                AssistantChatBubble(
                                    message = msg,
                                    formattedTime = formattedMsgTime,
                                    displayContent = effectiveContent,
                                    isStreaming = msg.status == ChatStatus.STREAMING || isLiveStreamingRow,
                                    isSelected = isSelected,
                                    isSelectionMode = selectionState.isActive,
                                    isInteractive = isInteractive,
                                    submittingCardIds = submittingCardIds,
                                    cardErrors = cardErrors,
                                    hapticEnabled = chatPrefs.hapticEnabled,
                                    onSubmitCardAction = { cardId, cardType, actionId, payload ->
                                        viewModel.submitCardAction(cardId, cardType, actionId, payload)
                                    },
                                    onOpenCategoryPicker = { card -> activeCategoryPickerCard = card },
                                    onOpenAccountPicker = { card -> activeAccountPickerCard = card },
                                    onOpenMethodPicker = { card -> activeMethodPickerCard = card },
                                    onOpenFinanceEdit = { card -> activeFinanceEditCard = card },
                                    onOpenVaultEdit = { card -> activeVaultEditCard = card },
                                    onQuickPrompt = { prompt ->
                                        triggerHapticTick()
                                        requestScrollToLatestOnSend()
                                        viewModel.sendMessage(prompt)
                                    },
                                    onTapBubble = { tapOffset ->
                                        if (selectionState.isActive) {
                                            triggerHapticTick()
                                            viewModel.toggleSelectMessage(msg)
                                        } else {
                                            triggerHapticTick()
                                            activeMenuAnchor = MessageMenuAnchor(
                                                message = msg,
                                                displayContent = effectiveContent,
                                                isVoice = false,
                                                tapWindowOffset = tapOffset
                                            )
                                        }
                                    },
                                    onLongPressBubble = {
                                        activeMenuAnchor = null
                                        triggerHapticConfirm()
                                        if (selectionState.isActive) {
                                            viewModel.toggleSelectMessage(msg)
                                        } else {
                                            viewModel.selectMessage(msg)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Floating "Jump to Latest" button when user scrolls up into chat history
            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollToBottomButton && !isExpandedComposer,
                enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.82f, animationSpec = tween(140)),
                exit = fadeOut(tween(110)) + scaleOut(targetScale = 0.82f, animationSpec = tween(110)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 12.dp)
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Surface(
                        color = if (nexaColors.isDark) Color(0xFF15202E) else MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, nexaColors.cardBorder),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .size(42.dp)
                            .clickable(role = Role.Button) {
                                triggerHapticTick()
                                isPinnedToBottom = true
                                unreadWhileScrolledUp = 0
                                coroutineScope.launch { scrollToLatest(animated = true) }
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Jump to latest message",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    if (unreadWhileScrolledUp > 0) {
                        val badgeLabel = if (unreadWhileScrolledUp > 99) "99+" else unreadWhileScrolledUp.toString()
                        Box(
                            modifier = Modifier
                                .offset(x = 3.dp, y = (-5).dp)
                                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape)
                                .padding(horizontal = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = badgeLabel,
                                color = nexaColors.onPrimaryButtonFill,
                                fontSize = 10.sp,
                                lineHeight = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Bottom Dock: Either Finance Step-Up Modal Bottom Sheet OR Chat Input Bar
        val activeStepUp = pendingStepUp
        if (activeStepUp != null) {
            FinanceStepUpBottomSheet(
                stepUp = activeStepUp,
                onConfirmBiometric = { stepUpReq ->
                    triggerHapticTick()
                    val activity = context.findActivity() as? FragmentActivity
                    if (activity != null) {
                        viewModel.confirmFinanceStepUpWithBiometric(activity, stepUpReq)
                    } else {
                        // U-H3: Fail-closed when FragmentActivity is unavailable
                        viewModel.reportStepUpMissingActivity(stepUpReq.actionId)
                    }
                },
                onCancel = { actionId ->
                    triggerHapticTick()
                    viewModel.cancelFinanceStepUp(actionId)
                },
                onExpired = { actionId ->
                    viewModel.expireFinanceStepUp(actionId)
                }
            )
        } else {
            AnimatedVisibility(
                visible = voiceRecordingState.phase == VoiceRecordingPhase.IDLE && filteredSlashCommands.isNotEmpty(),
                enter = fadeIn(tween(120)) + slideInVertically(initialOffsetY = { it / 3 }, animationSpec = tween(150)),
                exit = fadeOut(tween(100)) + slideOutVertically(targetOffsetY = { it / 3 }, animationSpec = tween(120))
            ) {
                SlashCommandPaletteCard(
                    commands = filteredSlashCommands,
                    onSelectCommand = { spec ->
                        triggerHapticTick()
                        if (spec.requiresArgument) {
                            draftText = "${spec.command} "
                        } else {
                            draftText = ""
                            isExpandedComposer = false
                            requestScrollToLatestOnSend()
                            viewModel.sendMessage(spec.command)
                        }
                    }
                )
            }

            ChatInputBar(
                draftText = draftText,
                onDraftChange = { draftText = it },
                onFocusChanged = { focused -> isInputFocused = focused },
                onOpenExpanded = {
                    triggerHapticTick()
                    isExpandedComposer = true
                },
                replyTarget = replyTarget,
                onClearReplyTarget = {
                    triggerHapticTick()
                    viewModel.clearReplyTarget()
                },
                isStreaming = streamState.activeClientMsgId != null,
                voiceState = voiceRecordingState,
                hasMicPermission = hasMicPermission,
                onRequestMicPermission = {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStartVoiceRecording = {
                    val started = viewModel.startVoiceRecording()
                    if (started) triggerHapticTick()
                    started
                },
                onLockVoiceRecording = {
                    triggerHapticConfirm()
                    viewModel.lockVoiceRecording()
                },
                onCancelVoiceRecording = {
                    triggerHapticTick()
                    viewModel.cancelVoiceRecording()
                },
                onFinishVoiceRecording = {
                    triggerHapticConfirm()
                    requestScrollToLatestOnSend()
                    viewModel.finishAndSendVoiceRecording()
                },
                onSend = {
                    val text = draftText.trim()
                    if (text.isNotEmpty()) {
                        triggerHapticTick()
                        draftText = ""
                        isExpandedComposer = false
                        requestScrollToLatestOnSend()
                        viewModel.sendMessage(text)
                    }
                },
                onStopStream = {
                    triggerHapticTick()
                    viewModel.stopActiveStream()
                }
            )
        }
    }

        // Full-height Telegram-style expanded message composer overlay
        androidx.compose.animation.AnimatedVisibility(
            visible = isExpandedComposer && pendingStepUp == null,
            enter = fadeIn(tween(140)) + slideInVertically(initialOffsetY = { it / 6 }, animationSpec = tween(180)),
            exit = fadeOut(tween(120)) + slideOutVertically(targetOffsetY = { it / 6 }, animationSpec = tween(140))
        ) {
            ChatExpandedComposerOverlay(
                draftText = draftText,
                onDraftChange = { draftText = it },
                replyTarget = replyTarget,
                onClearReplyTarget = {
                    triggerHapticTick()
                    viewModel.clearReplyTarget()
                },
                isStreaming = streamState.activeClientMsgId != null,
                onCollapse = {
                    triggerHapticTick()
                    isExpandedComposer = false
                },
                onSend = {
                    val text = draftText.trim()
                    if (text.isNotEmpty()) {
                        triggerHapticTick()
                        draftText = ""
                        isExpandedComposer = false
                        requestScrollToLatestOnSend()
                        viewModel.sendMessage(text)
                    }
                },
                onStopStream = {
                    triggerHapticTick()
                    viewModel.stopActiveStream()
                }
            )
        }

        // Single screen-level Telegram-style tap menu for any interactive bubble
        val menuAnchor = activeMenuAnchor
        if (menuAnchor != null && !selectionState.isActive && pendingStepUp == null) {
            MessageActionMenuPopup(
                anchor = menuAnchor,
                onDismiss = { activeMenuAnchor = null },
                onReply = {
                    activeMenuAnchor = null
                    triggerHapticTick()
                    viewModel.selectReplyTarget(menuAnchor.message, menuAnchor.displayContent)
                },
                onCopy = {
                    activeMenuAnchor = null
                    triggerHapticTick()
                    viewModel.copyToClipboard(menuAnchor.displayContent)
                },
                onDelete = {
                    activeMenuAnchor = null
                    triggerHapticTick()
                    viewModel.requestDeleteMessages(setOf(menuAnchor.message.id))
                }
            )
        }

        if (pendingDeleteIds.isNotEmpty()) {
            DeleteMessagesConfirmDialog(
                count = pendingDeleteIds.size,
                onConfirm = {
                    triggerHapticConfirm()
                    viewModel.confirmPendingDelete()
                },
                onDismiss = { viewModel.dismissPendingDelete() }
            )
        }

        val catCard = activeCategoryPickerCard
        if (catCard != null) {
            FinanceCategoryPickerSheet(
                card = catCard,
                onSelectCategory = { selectedCategory ->
                    activeCategoryPickerCard = null
                    triggerHapticTick()
                    val payload = org.json.JSONObject().apply {
                        put("category", selectedCategory)
                        val key = catCard.metaString("composite_key")
                        if (key.isNotEmpty()) put("composite_key", key)
                    }
                    viewModel.submitCardAction(catCard.cardId, catCard.cardType, "update_category", payload)
                },
                onDismiss = { activeCategoryPickerCard = null }
            )
        }

        val accCard = activeAccountPickerCard
        if (accCard != null) {
            FinanceAccountPickerSheet(
                card = accCard,
                onSelectAccount = { selectedAccount ->
                    activeAccountPickerCard = null
                    triggerHapticTick()
                    val payload = org.json.JSONObject().apply {
                        put("account", selectedAccount)
                        val key = accCard.metaString("composite_key")
                        if (key.isNotEmpty()) put("composite_key", key)
                    }
                    viewModel.submitCardAction(accCard.cardId, accCard.cardType, "update_account", payload)
                },
                onDismiss = { activeAccountPickerCard = null }
            )
        }

        val methodCard = activeMethodPickerCard
        if (methodCard != null) {
            FinancePaymentMethodPickerSheet(
                card = methodCard,
                onSelectMethod = { selectedMethod ->
                    activeMethodPickerCard = null
                    triggerHapticTick()
                    val payload = org.json.JSONObject().apply {
                        put("payment_method", selectedMethod)
                        val key = methodCard.metaString("composite_key")
                        if (key.isNotEmpty()) put("composite_key", key)
                    }
                    viewModel.submitCardAction(methodCard.cardId, methodCard.cardType, "update_payment_method", payload)
                },
                onDismiss = { activeMethodPickerCard = null }
            )
        }

        val editCard = activeFinanceEditCard
        if (editCard != null) {
            FinanceQuickEditDialog(
                card = editCard,
                onSave = { note, nominal, dateText ->
                    activeFinanceEditCard = null
                    triggerHapticConfirm()
                    val payload = org.json.JSONObject().apply {
                        if (note.isNotEmpty()) put("note", note)
                        if (nominal.isNotEmpty()) put("nominal", nominal)
                        if (dateText.isNotEmpty()) put("date_text", dateText)
                        val key = editCard.metaString("composite_key")
                        if (key.isNotEmpty()) put("composite_key", key)
                    }
                    viewModel.submitCardAction(editCard.cardId, editCard.cardType, "update_fields", payload)
                },
                onDismiss = { activeFinanceEditCard = null }
            )
        }

        val vaultCard = activeVaultEditCard
        if (vaultCard != null) {
            VaultMetadataEditDialog(
                card = vaultCard,
                onSave = { category, edits ->
                    activeVaultEditCard = null
                    triggerHapticConfirm()
                    val editsObj = org.json.JSONObject()
                    edits.forEach { (k, v) -> editsObj.put(k, v) }
                    val payload = org.json.JSONObject().apply {
                        put("vault_row_id", vaultCard.metaLong("vault_row_id", 0L))
                        put("category", category)
                        put("edits", editsObj)
                        val status = vaultCard.metaString("vault_status")
                        if (status.isNotEmpty()) put("vault_status", status)
                    }
                    viewModel.submitCardAction(vaultCard.cardId, vaultCard.cardType, "update_fields", payload)
                },
                onDismiss = { activeVaultEditCard = null }
            )
        }
    }
}

/**
 * Telegram/WhatsApp-style bidirectional horizontal swipe-to-reply wrapper for any chat bubble
 * (user or N.E.X.A). Yields cleanly to vertical LazyColumn scroll and snaps back with spring physics.
 */
@Composable
private fun SwipeToReplyContainer(
    messageKey: String,
    isHighlighted: Boolean,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    swipeEnabled: Boolean,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onTriggerHaptic: () -> Unit,
    onSwipeReply: () -> Unit,
    onRowTapInSelectionMode: () -> Unit,
    content: @Composable () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember(messageKey) { Animatable(0f) }
    val triggerThresholdPx = with(density) { 52.dp.toPx() }
    val maxDragPx = with(density) { 68.dp.toPx() }
    var thresholdReached by remember(messageKey) { mutableStateOf(false) }

    val latestOnHaptic by rememberUpdatedState(onTriggerHaptic)
    val latestOnReply by rememberUpdatedState(onSwipeReply)

    LaunchedEffect(swipeEnabled) {
        if (!swipeEnabled && offsetX.value != 0f) {
            thresholdReached = false
            offsetX.snapTo(0f)
        }
    }

    val highlightAlpha by animateFloatAsState(
        targetValue = if (isSelected || isHighlighted) 0.16f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "rowHighlightTint"
    )

    val currentOffset = offsetX.value
    val swipeProgress = (abs(currentOffset) / triggerThresholdPx).coerceIn(0f, 1f)
    val isActiveThreshold = swipeProgress >= 1f

    val dragModifier = if (swipeEnabled) {
        Modifier.pointerInput(messageKey) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    val shouldReply = thresholdReached || abs(offsetX.value) >= triggerThresholdPx
                    thresholdReached = false
                    if (shouldReply) {
                        latestOnReply()
                    }
                    coroutineScope.launch {
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.72f, stiffness = 480f)
                        )
                    }
                },
                onDragCancel = {
                    thresholdReached = false
                    coroutineScope.launch {
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.72f, stiffness = 480f)
                        )
                    }
                },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    val cur = offsetX.value
                    val resistance = 1f - ((abs(cur) / maxDragPx).coerceIn(0f, 1f) * 0.52f)
                    val next = (cur + dragAmount * resistance).coerceIn(-maxDragPx, maxDragPx)
                    coroutineScope.launch {
                        offsetX.snapTo(next)
                    }
                    val reachedNow = abs(next) >= triggerThresholdPx
                    if (reachedNow && !thresholdReached) {
                        thresholdReached = true
                        latestOnHaptic()
                    } else if (!reachedNow && abs(next) < triggerThresholdPx * 0.85f) {
                        thresholdReached = false
                    }
                }
            )
        }
    } else {
        Modifier
    }

    val selectionRowClickModifier = if (isSelectionMode) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onRowTapInSelectionMode
        )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = highlightAlpha))
            .then(dragModifier)
            .then(selectionRowClickModifier)
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = topPadding,
                bottom = bottomPadding
            )
    ) {
        if (swipeEnabled && abs(currentOffset) > 4f) {
            val iconAlignment = if (currentOffset > 0f) Alignment.CenterStart else Alignment.CenterEnd
            val iconBg = if (isActiveThreshold) {
                nexaColors.primaryButtonFill
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            }
            val iconTint = if (isActiveThreshold) {
                nexaColors.onPrimaryButtonFill
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            val iconScale = 0.72f + (swipeProgress * 0.34f)

            Surface(
                color = iconBg,
                shape = CircleShape,
                border = BorderStroke(
                    1.dp,
                    if (isActiveThreshold) MaterialTheme.colorScheme.primary else nexaColors.cardBorder
                ),
                modifier = Modifier
                    .align(iconAlignment)
                    .padding(horizontal = 4.dp)
                    .size(34.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                        alpha = swipeProgress.coerceIn(0.25f, 1f)
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Reply,
                        contentDescription = "Reply to message",
                        tint = iconTint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(currentOffset.roundToInt(), 0) }
        ) {
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = expandHorizontally(tween(150)) + fadeIn(tween(150)),
                exit = shrinkHorizontally(tween(150)) + fadeOut(tween(150))
            ) {
                SelectionCheckCircle(
                    isSelected = isSelected,
                    modifier = Modifier.padding(end = 10.dp, bottom = 6.dp)
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                content()
            }
        }
    }
}

/**
 * Right-aligned User Message Bubble (text or Telegram-style OGG/Opus voice note) + optional quoted reply + footer.
 */
@Composable
private fun UserChatBubble(
    message: ChatMessageEntity,
    formattedTime: String,
    playbackState: VoicePlaybackState,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isInteractive: Boolean,
    onTapBubble: (windowOffset: IntOffset) -> Unit,
    onLongPressBubble: () -> Unit,
    onToggleVoicePlay: (audioPath: String, durationMs: Long) -> Unit,
    onSeekVoice: (audioPath: String, durationMs: Long, fraction: Float) -> Unit,
    onJumpToReply: (targetMessageId: String) -> Unit,
    onRetry: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val baseBg = if (nexaColors.isDark) Color(0xFF1A2232) else MaterialTheme.colorScheme.surfaceVariant
    val bubbleBg = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f).compositeOver(baseBg)
    } else {
        baseBg
    }
    val bubbleBorder = if (isSelected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, nexaColors.cardBorder)
    }
    var bubbleWindowPos by remember(message.id) { mutableStateOf(Offset.Zero) }
    val latestOnTapBubble by rememberUpdatedState(onTapBubble)
    val latestOnLongPressBubble by rememberUpdatedState(onLongPressBubble)

    val voiceMeta = remember(message.executionMetadata) {
        VoiceNoteMetadata.fromJson(message.executionMetadata)
    }
    val replyTarget = remember(message.executionMetadata) {
        ChatReplyMetadata.fromJson(message.executionMetadata)
    }

    val gestureModifier = if (isInteractive) {
        Modifier
            .onGloballyPositioned { coords -> bubbleWindowPos = coords.positionInWindow() }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Message options") {
                        latestOnTapBubble(IntOffset(bubbleWindowPos.x.roundToInt(), bubbleWindowPos.y.roundToInt()))
                        true
                    },
                    CustomAccessibilityAction("Select message") {
                        latestOnLongPressBubble()
                        true
                    }
                )
            }
            .pointerInput(message.id, isInteractive) {
                detectTapGestures(
                    onTap = { localOffset ->
                        val winOffset = IntOffset(
                            (bubbleWindowPos.x + localOffset.x).roundToInt(),
                            (bubbleWindowPos.y + localOffset.y).roundToInt()
                        )
                        latestOnTapBubble(winOffset)
                    },
                    onLongPress = {
                        latestOnLongPressBubble()
                    }
                )
            }
    } else {
        Modifier
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            color = bubbleBg,
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = 14.dp,
                bottomEnd = 4.dp
            ),
            border = bubbleBorder,
            modifier = Modifier
                .widthIn(max = 312.dp)
                .then(gestureModifier)
        ) {
            Column {
                if (replyTarget != null) {
                    QuotedReplyBox(
                        replyTarget = replyTarget,
                        onClick = {
                            if (isSelectionMode && isInteractive) {
                                latestOnTapBubble(IntOffset(bubbleWindowPos.x.roundToInt(), bubbleWindowPos.y.roundToInt()))
                            } else {
                                onJumpToReply(replyTarget.messageId)
                            }
                        },
                        modifier = Modifier
                            .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                    )
                }

                if (voiceMeta != null) {
                    val isThisActive = playbackState.activeMessageId == message.id
                    val isPlaying = isThisActive && playbackState.isPlaying
                    val progress = if (isThisActive) playbackState.progress else 0f
                    val displayTime = if (isThisActive && (isPlaying || playbackState.positionMs > 0L)) {
                        "${VoiceNoteWaveform.formatDuration(playbackState.positionMs)} / ${VoiceNoteWaveform.formatDuration(voiceMeta.durationMs)}"
                    } else {
                        VoiceNoteWaveform.formatDuration(voiceMeta.durationMs)
                    }

                    UserVoiceNoteContent(
                        waveform = voiceMeta.waveform,
                        isPlaying = isPlaying,
                        progress = progress,
                        durationLabel = displayTime,
                        formattedTime = formattedTime,
                        status = message.status,
                        isSelectionMode = isSelectionMode,
                        isInteractive = isInteractive,
                        onToggleSelect = {
                            latestOnTapBubble(IntOffset(bubbleWindowPos.x.roundToInt(), bubbleWindowPos.y.roundToInt()))
                        },
                        onLongPress = { latestOnLongPressBubble() },
                        onTogglePlay = { onToggleVoicePlay(voiceMeta.audioPath, voiceMeta.durationMs) },
                        onSeek = { frac -> onSeekVoice(voiceMeta.audioPath, voiceMeta.durationMs, frac) }
                    )
                } else {
                    val isShortSingleLine = replyTarget == null &&
                        !message.content.contains('\n') &&
                        message.content.length <= 24

                    if (isShortSingleLine) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(
                                start = 12.dp,
                                end = 10.dp,
                                top = 8.dp,
                                bottom = 7.dp
                            )
                        ) {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            UserBubbleTimestampMeta(
                                formattedTime = formattedTime,
                                status = message.status,
                                modifier = Modifier.padding(bottom = 1.dp)
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.padding(
                                start = 12.dp,
                                end = 12.dp,
                                top = if (replyTarget != null) 6.dp else 8.dp,
                                bottom = 6.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            UserBubbleTimestampMeta(
                                formattedTime = formattedTime,
                                status = message.status,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }
            }
        }

        if (message.status == ChatStatus.FAILED) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Failed to send",
                    style = MaterialTheme.typography.labelSmall,
                    color = nexaColors.crimson
                )
                Surface(
                    color = nexaColors.crimson.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, nexaColors.crimson.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .clickable(role = Role.Button, onClick = onRetry)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry sending message",
                            tint = nexaColors.crimson,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Retry",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = nexaColors.crimson
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubbleTimestampMeta(
    formattedTime: String,
    status: String,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    val metaColor = if (status == ChatStatus.FAILED) {
        nexaColors.crimson
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    }
    val statusIcon = when (status) {
        ChatStatus.SENDING -> Icons.Default.Schedule
        ChatStatus.FAILED, ChatStatus.INTERRUPTED -> Icons.Default.ErrorOutline
        else -> Icons.Default.DoneAll
    }
    val iconTint = when (status) {
        ChatStatus.SENT -> MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
        ChatStatus.FAILED -> nexaColors.crimson
        ChatStatus.INTERRUPTED -> nexaColors.amber
        else -> metaColor
    }
    val statusDescription = when (status) {
        ChatStatus.SENDING -> "Sending"
        ChatStatus.FAILED -> "Failed"
        ChatStatus.INTERRUPTED -> "Interrupted"
        else -> "Sent"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
    ) {
        Text(
            text = formattedTime,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = metaColor
        )
        Icon(
            imageVector = statusIcon,
            contentDescription = statusDescription,
            tint = iconTint,
            modifier = Modifier.size(13.dp)
        )
    }
}

@Composable
private fun QuotedReplyBox(
    replyTarget: ChatReplyTarget,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    val quoteBg = if (nexaColors.isDark) {
        Color(0xFF101826)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
    }
    val senderName = ChatReplyMetadata.senderLabel(replyTarget.role)

    Surface(
        color = quoteBg,
        shape = RoundedCornerShape(9.dp),
        modifier = modifier
            .widthIn(min = 148.dp, max = 304.dp)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
            Column(
                modifier = Modifier.padding(start = 9.dp, top = 6.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (replyTarget.isVoice) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    Text(
                        text = replyTarget.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ReplyPreviewStrip(
    replyTarget: ChatReplyTarget,
    onDismiss: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val senderName = ChatReplyMetadata.senderLabel(replyTarget.role)

    Surface(
        color = if (nexaColors.isDark) Color(0xFF0E1520) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = "Replying to $senderName",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (replyTarget.isVoice) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    Text(
                        text = replyTarget.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel reply",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Clean Telegram-style voice note bubble content (Play/Pause + interactive 32-bar waveform + duration).
 */
@Composable
private fun UserVoiceNoteContent(
    waveform: List<Int>,
    isPlaying: Boolean,
    progress: Float,
    durationLabel: String,
    formattedTime: String,
    status: String,
    isSelectionMode: Boolean,
    isInteractive: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val activeBarColor = MaterialTheme.colorScheme.primary
    val inactiveBarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.36f)
    val bars = remember(waveform) {
        if (waveform.isEmpty()) List(VoiceNoteMetadata.TARGET_WAVEFORM_BARS) { 16 } else waveform
    }
    val latestSelectionMode by rememberUpdatedState(isSelectionMode)
    val latestInteractive by rememberUpdatedState(isInteractive)
    val latestOnToggleSelect by rememberUpdatedState(onToggleSelect)
    val latestOnLongPress by rememberUpdatedState(onLongPress)
    val latestOnTogglePlay by rememberUpdatedState(onTogglePlay)
    val latestOnSeek by rememberUpdatedState(onSeek)

    Row(
        modifier = Modifier
            .widthIn(min = 216.dp, max = 276.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            color = nexaColors.primaryButtonFill,
            shape = CircleShape,
            modifier = Modifier
                .size(40.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (latestSelectionMode && latestInteractive) {
                                latestOnToggleSelect()
                            } else {
                                latestOnTogglePlay()
                            }
                        },
                        onLongPress = {
                            if (latestInteractive) latestOnLongPress()
                        }
                    )
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause voice message" else "Play voice message",
                    tint = nexaColors.onPrimaryButtonFill,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .pointerInput(bars) {
                        detectTapGestures(
                            onTap = { tapOffset ->
                                if (latestSelectionMode && latestInteractive) {
                                    latestOnToggleSelect()
                                } else if (size.width > 0) {
                                    val fraction = (tapOffset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    latestOnSeek(fraction)
                                }
                            },
                            onLongPress = {
                                if (latestInteractive) latestOnLongPress()
                            }
                        )
                    }
            ) {
                val count = bars.size.coerceAtLeast(1)
                val step = size.width / count.toFloat()
                val strokeWidth = (step * 0.56f).coerceIn(2.dp.toPx(), 4.dp.toPx())
                val centerY = size.height / 2f
                val maxHalfHeight = (size.height / 2f) - (strokeWidth / 2f)

                for (i in 0 until count) {
                    val norm = (bars[i].coerceIn(8, 100) / 100f)
                    val halfH = (maxHalfHeight * norm).coerceAtLeast(2.dp.toPx())
                    val x = (i + 0.5f) * step
                    val barFrac = (i + 1).toFloat() / count.toFloat()
                    val color = if (progress > 0f && barFrac <= progress) activeBarColor else inactiveBarColor
                    drawLine(
                        color = color,
                        start = Offset(x, centerY - halfH),
                        end = Offset(x, centerY + halfH),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isPlaying) activeBarColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    )
                    Text(
                        text = durationLabel,
                        style = NexaMonoTypography.codeLine.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                UserBubbleTimestampMeta(
                    formattedTime = formattedTime,
                    status = status
                )
            }
        }
    }
}

/**
 * Left-aligned N.E.X.A Assistant Bubble with Incremental Markdown & Rupiah highlight (FR-CHAT-11).
 * Compact Telegram-style bubble: tap for contextual action menu, long press for selection mode.
 */
@Composable
private fun AssistantChatBubble(
    message: ChatMessageEntity,
    formattedTime: String,
    displayContent: String,
    isStreaming: Boolean,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isInteractive: Boolean,
    submittingCardIds: Set<String> = emptySet(),
    cardErrors: Map<String, String> = emptyMap(),
    hapticEnabled: Boolean = true,
    onSubmitCardAction: (cardId: String, cardType: String, actionId: String, payload: org.json.JSONObject?) -> Unit = { _, _, _, _ -> },
    onOpenCategoryPicker: (com.nexa.mobilebridge.data.chat.InteractiveCardData) -> Unit = {},
    onOpenAccountPicker: (com.nexa.mobilebridge.data.chat.InteractiveCardData) -> Unit = {},
    onOpenMethodPicker: (com.nexa.mobilebridge.data.chat.InteractiveCardData) -> Unit = {},
    onOpenFinanceEdit: (com.nexa.mobilebridge.data.chat.InteractiveCardData) -> Unit = {},
    onOpenVaultEdit: (com.nexa.mobilebridge.data.chat.InteractiveCardData) -> Unit = {},
    onQuickPrompt: (String) -> Unit = {},
    onTapBubble: (windowOffset: IntOffset) -> Unit,
    onLongPressBubble: () -> Unit
) {
    val interactiveCard = remember(message.executionMetadata) {
        com.nexa.mobilebridge.data.chat.InteractiveCardCodec.parseFromExecutionMetadata(message.executionMetadata)
    }
    val nexaColors = LocalNexaColors.current
    val baseBg = if (nexaColors.isDark) Color(0xFF111720) else MaterialTheme.colorScheme.surface
    val bubbleBg = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f).compositeOver(baseBg)
    } else {
        baseBg
    }
    val bubbleBorder = if (isSelected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, nexaColors.cardBorder)
    }
    var bubbleWindowPos by remember(message.id) { mutableStateOf(Offset.Zero) }
    val latestOnTapBubble by rememberUpdatedState(onTapBubble)
    val latestOnLongPressBubble by rememberUpdatedState(onLongPressBubble)

    val gestureModifier = if (isInteractive) {
        Modifier
            .onGloballyPositioned { coords -> bubbleWindowPos = coords.positionInWindow() }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Message options") {
                        latestOnTapBubble(IntOffset(bubbleWindowPos.x.roundToInt(), bubbleWindowPos.y.roundToInt()))
                        true
                    },
                    CustomAccessibilityAction("Select message") {
                        latestOnLongPressBubble()
                        true
                    }
                )
            }
            .pointerInput(message.id, isInteractive, isSelectionMode) {
                detectTapGestures(
                    onTap = { localOffset ->
                        val winOffset = IntOffset(
                            (bubbleWindowPos.x + localOffset.x).roundToInt(),
                            (bubbleWindowPos.y + localOffset.y).roundToInt()
                        )
                        latestOnTapBubble(winOffset)
                    },
                    onLongPress = {
                        latestOnLongPressBubble()
                    }
                )
            }
    } else {
        Modifier
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            color = bubbleBg,
            shape = RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 14.dp,
                bottomStart = 14.dp,
                bottomEnd = 14.dp
            ),
            border = bubbleBorder,
            modifier = Modifier
                .widthIn(max = 336.dp)
                .then(gestureModifier)
        ) {
            val isRichCardBubble = interactiveCard != null &&
                (interactiveCard.presentation == "CARD" || interactiveCard.presentation == "SUMMARY_CARD")
            Column(
                modifier = Modifier.padding(
                    start = if (isRichCardBubble) 14.dp else 12.dp,
                    end = if (isRichCardBubble) 14.dp else 12.dp,
                    top = if (isRichCardBubble) 12.dp else 8.dp,
                    bottom = if (isRichCardBubble) 8.dp else 6.dp
                ),
                verticalArrangement = Arrangement.spacedBy(if (interactiveCard?.presentation == "SUMMARY_CARD") 10.dp else 6.dp)
            ) {
                if (interactiveCard != null && interactiveCard.presentation == "CARD") {
                    NexaInteractiveCard(
                        card = interactiveCard,
                        isSubmitting = interactiveCard.cardId in submittingCardIds,
                        errorMessage = cardErrors[interactiveCard.cardId],
                        hapticEnabled = hapticEnabled,
                        onSubmitAction = onSubmitCardAction,
                        onOpenCategoryPicker = onOpenCategoryPicker,
                        onOpenAccountPicker = onOpenAccountPicker,
                        onOpenMethodPicker = onOpenMethodPicker,
                        onOpenFinanceEdit = onOpenFinanceEdit,
                        onOpenVaultEdit = onOpenVaultEdit,
                        onQuickPrompt = onQuickPrompt,
                        framed = false
                    )
                } else if (interactiveCard != null && interactiveCard.presentation == "SUMMARY_CARD") {
                    NexaSummaryCard(
                        card = interactiveCard,
                        hapticEnabled = hapticEnabled,
                        onQuickPrompt = onQuickPrompt,
                        framed = false,
                        showActions = false
                    )

                    val cleanSummaryBody = remember(displayContent) {
                        stripCardEmojisPreserveLines(displayContent)
                    }
                    if (cleanSummaryBody.isNotEmpty()) {
                        HorizontalDivider(
                            thickness = 0.6.dp,
                            color = nexaColors.cardBorder.copy(alpha = 0.55f)
                        )
                        IncrementalMarkdownText(
                            rawText = cleanSummaryBody,
                            highlightColor = MaterialTheme.colorScheme.primary
                        )
                    }

                    NexaSummaryActionBar(
                        card = interactiveCard,
                        hapticEnabled = hapticEnabled,
                        onQuickPrompt = onQuickPrompt
                    )
                } else {
                    if (displayContent.isEmpty() && isStreaming) {
                        Text(
                            text = "Thinking...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (displayContent.isNotEmpty()) {
                        IncrementalMarkdownText(
                            rawText = displayContent,
                            highlightColor = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (interactiveCard != null) {
                        if (interactiveCard.presentation == "CHIPS") {
                            NexaQuickActionChips(
                                card = interactiveCard,
                                isSubmitting = interactiveCard.cardId in submittingCardIds,
                                errorMessage = cardErrors[interactiveCard.cardId],
                                hapticEnabled = hapticEnabled,
                                onSubmitAction = onSubmitCardAction
                            )
                        } else {
                            NexaInteractiveCard(
                                card = interactiveCard,
                                isSubmitting = interactiveCard.cardId in submittingCardIds,
                                errorMessage = cardErrors[interactiveCard.cardId],
                                hapticEnabled = hapticEnabled,
                                onSubmitAction = onSubmitCardAction,
                                onOpenCategoryPicker = onOpenCategoryPicker,
                                onOpenAccountPicker = onOpenAccountPicker,
                                onOpenMethodPicker = onOpenMethodPicker,
                                onOpenFinanceEdit = onOpenFinanceEdit,
                                onOpenVaultEdit = onOpenVaultEdit,
                                onQuickPrompt = onQuickPrompt
                            )
                        }
                    }
                }

                if (message.status == ChatStatus.INTERRUPTED && !isStreaming) {
                    Text(
                        text = "Response interrupted",
                        style = MaterialTheme.typography.labelSmall,
                        color = nexaColors.amber
                    )
                }

                if (!isStreaming || displayContent.isNotEmpty() || interactiveCard != null) {
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}

private sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class FencedCode(val language: String, val code: String) : MarkdownBlock
}

private fun parseMarkdownBlocks(rawText: String): List<MarkdownBlock> {
    if (!rawText.contains("```")) {
        return listOf(MarkdownBlock.Paragraph(rawText))
    }
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = rawText.split("\n")
    val paragraphBuf = StringBuilder()
    val codeBuf = StringBuilder()
    var inFence = false
    var fenceLang = ""

    fun flushParagraph() {
        val text = paragraphBuf.toString().trimEnd('\n')
        if (text.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(text))
        }
        paragraphBuf.setLength(0)
    }

    for (line in lines) {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (!inFence) {
                flushParagraph()
                inFence = true
                fenceLang = trimmed.removePrefix("```").trim()
                codeBuf.setLength(0)
            } else {
                blocks.add(MarkdownBlock.FencedCode(fenceLang, codeBuf.toString().trimEnd('\n')))
                inFence = false
                fenceLang = ""
                codeBuf.setLength(0)
            }
        } else if (inFence) {
            if (codeBuf.isNotEmpty()) codeBuf.append('\n')
            codeBuf.append(line)
        } else {
            if (paragraphBuf.isNotEmpty()) paragraphBuf.append('\n')
            paragraphBuf.append(line)
        }
    }

    if (inFence) {
        // Streaming incomplete fenced code block: still render cleanly in code surface
        blocks.add(MarkdownBlock.FencedCode(fenceLang, codeBuf.toString().trimEnd('\n')))
    } else {
        flushParagraph()
    }
    return blocks
}

/**
 * FR-CHAT-11 / U-H5: Block-level Incremental Markdown & Currency Highlighter.
 * Supports fenced code blocks (```lang ... ```), inline code, bold, and Rupiah highlights
 * while memoizing completed paragraph prefixes during token streaming.
 */
@Composable
private fun IncrementalMarkdownText(
    rawText: String,
    highlightColor: Color
) {
    val nexaColors = LocalNexaColors.current
    val blocks = remember(rawText) { parseMarkdownBlocks(rawText) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> {
                    IncrementalParagraphText(
                        rawText = block.text,
                        highlightColor = highlightColor
                    )
                }

                is MarkdownBlock.FencedCode -> {
                    Surface(
                        color = if (nexaColors.isDark) Color(0xFF070B10) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, nexaColors.cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (block.language.isNotBlank()) {
                                Text(
                                    text = block.language.uppercase(Locale.US),
                                    style = NexaMonoTypography.badge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = block.code,
                                style = NexaMonoTypography.codeLine,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IncrementalParagraphText(
    rawText: String,
    highlightColor: Color
) {
    val splitIdx = rawText.lastIndexOf("\n\n")
    val completedPrefix = if (splitIdx > 0) rawText.substring(0, splitIdx + 2) else ""
    val trailingBlock = if (splitIdx > 0) rawText.substring(splitIdx + 2) else rawText

    val memoizedPrefix = remember(completedPrefix, highlightColor) {
        if (completedPrefix.isEmpty()) AnnotatedString("") else parseMarkdownSegment(completedPrefix, highlightColor)
    }
    val parsedTrailing = remember(trailingBlock, highlightColor) {
        if (trailingBlock.isEmpty()) AnnotatedString("") else parseMarkdownSegment(trailingBlock, highlightColor)
    }

    val combined = remember(memoizedPrefix, parsedTrailing) {
        buildAnnotatedString {
            append(memoizedPrefix)
            append(parsedTrailing)
        }
    }

    Text(
        text = combined,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
}

private val INLINE_TOKEN_REGEX = Regex(
    "(\\*\\*[^*]+\\*\\*|`[^`]+`|Rp\\s?[0-9]{1,3}(?:\\.[0-9]{3})+(?:,[0-9]{1,2})?)"
)

private fun parseMarkdownSegment(segment: String, highlightColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        for (match in INLINE_TOKEN_REGEX.findAll(segment)) {
            if (match.range.first > cursor) {
                append(segment.substring(cursor, match.range.first))
            }
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") && token.length > 4 -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(token.substring(2, token.length - 2))
                    }
                }
                token.startsWith("`") && token.endsWith("`") && token.length > 2 -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = highlightColor
                        )
                    ) {
                        append(token.substring(1, token.length - 1))
                    }
                }
                token.startsWith("Rp", ignoreCase = true) -> {
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            color = highlightColor
                        )
                    ) {
                        append(token)
                    }
                }
                else -> append(token)
            }
            cursor = match.range.last + 1
        }
        if (cursor < segment.length) {
            append(segment.substring(cursor))
        }
    }
}

private enum class InputActionMode {
    STOP,
    SEND,
    MIC
}

@Composable
private fun ChatInputBar(
    draftText: String,
    onDraftChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onOpenExpanded: () -> Unit,
    replyTarget: ChatReplyTarget?,
    onClearReplyTarget: () -> Unit,
    isStreaming: Boolean,
    voiceState: VoiceRecordingUiState,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onStartVoiceRecording: () -> Boolean,
    onLockVoiceRecording: () -> Unit,
    onCancelVoiceRecording: () -> Unit,
    onFinishVoiceRecording: () -> Unit,
    onSend: () -> Unit,
    onStopStream: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { 84.dp.toPx() }
    val lockThresholdPx = with(density) { 72.dp.toPx() }

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var visualLineCount by remember { mutableIntStateOf(1) }

    val latestHasMicPermission by rememberUpdatedState(hasMicPermission)
    val latestOnRequestMicPermission by rememberUpdatedState(onRequestMicPermission)
    val latestOnStartVoiceRecording by rememberUpdatedState(onStartVoiceRecording)
    val latestOnLockVoiceRecording by rememberUpdatedState(onLockVoiceRecording)
    val latestOnCancelVoiceRecording by rememberUpdatedState(onCancelVoiceRecording)
    val latestOnFinishVoiceRecording by rememberUpdatedState(onFinishVoiceRecording)

    LaunchedEffect(voiceState.phase) {
        if (voiceState.phase != VoiceRecordingPhase.HOLDING) {
            dragOffsetX = 0f
            dragOffsetY = 0f
        }
    }

    LaunchedEffect(draftText) {
        if (draftText.isEmpty()) {
            visualLineCount = 1
        }
    }

    val isHolding = voiceState.phase == VoiceRecordingPhase.HOLDING
    val isLockedRecording = voiceState.phase == VoiceRecordingPhase.LOCKED
    val canSendText = draftText.isNotBlank()
    val showExpandButton = visualLineCount >= 4 && draftText.isNotBlank() && !isHolding && !isLockedRecording
    val actionMode = when {
        isStreaming && !isHolding && !isLockedRecording -> InputActionMode.STOP
        canSendText && !isHolding && !isLockedRecording -> InputActionMode.SEND
        else -> InputActionMode.MIC
    }

    val cancelProgress = if (isHolding) ((-dragOffsetX) / cancelThresholdPx).coerceIn(0f, 1f) else 0f
    val lockProgress = if (isHolding) ((-dragOffsetY) / lockThresholdPx).coerceIn(0f, 1f) else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        // Floating Telegram-style hint tooltip ("Tahan untuk merekam")
        AnimatedVisibility(
            visible = voiceState.hintMessage != null && voiceState.phase == VoiceRecordingPhase.IDLE,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(180))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Surface(
                    color = if (nexaColors.isDark) Color(0xFF1A2433) else MaterialTheme.colorScheme.inverseSurface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, nexaColors.cardBorder)
                ) {
                    Text(
                        text = voiceState.hintMessage ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (nexaColors.isDark) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }

        // Floating Slide-Up Lock pill above Mic button while holding
        AnimatedVisibility(
            visible = isHolding,
            enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.85f, animationSpec = tween(140)),
            exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.85f, animationSpec = tween(100))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp, bottom = 8.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                val lockBg = if (lockProgress > 0.65f) {
                    nexaColors.primaryButtonFill
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
                val lockTint = if (lockProgress > 0.65f) {
                    nexaColors.onPrimaryButtonFill
                } else {
                    MaterialTheme.colorScheme.primary
                }
                Surface(
                    color = lockBg,
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .width(48.dp)
                        .offset { IntOffset(0, (-12f * lockProgress).roundToInt()) }
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Slide up to lock recording",
                            tint = lockTint,
                            modifier = Modifier.size(16.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = lockTint.copy(alpha = 0.75f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                HorizontalDivider(thickness = 1.dp, color = nexaColors.cardBorder)

                AnimatedVisibility(
                    visible = replyTarget != null,
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(100))
                ) {
                    replyTarget?.let { target ->
                        Column {
                            ReplyPreviewStrip(
                                replyTarget = target,
                                onDismiss = onClearReplyTarget
                            )
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = nexaColors.cardBorder.copy(alpha = 0.55f)
                            )
                        }
                    }
                }

                if (isLockedRecording) {
                    LockedVoiceRecordingBar(
                        voiceState = voiceState,
                        onCancel = onCancelVoiceRecording,
                        onSend = onFinishVoiceRecording
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isHolding) {
                            HoldingVoiceRecordingStrip(
                                elapsedMs = voiceState.elapsedMs,
                                cancelProgress = cancelProgress,
                                dragOffsetX = dragOffsetX,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.background,
                                shape = RoundedCornerShape(22.dp),
                                border = BorderStroke(1.dp, nexaColors.cardBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                ) {
                                    BasicTextField(
                                        value = draftText,
                                        onValueChange = onDraftChange,
                                        maxLines = 6,
                                        onTextLayout = { result -> visualLineCount = result.lineCount },
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            lineHeight = 20.sp
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        keyboardOptions = KeyboardOptions(
                                            capitalization = KeyboardCapitalization.Sentences,
                                            imeAction = ImeAction.Send
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onSend = { if (draftText.isNotBlank()) onSend() }
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.CenterStart)
                                            .onFocusChanged { focusState -> onFocusChanged(focusState.isFocused) }
                                            .padding(
                                                start = 16.dp,
                                                top = 11.dp,
                                                bottom = 11.dp,
                                                end = if (showExpandButton) 42.dp else 16.dp
                                            ),
                                        decorationBox = { innerTextField ->
                                            if (draftText.isEmpty()) {
                                                Text(
                                                    text = "Message N.E.X.A...",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            innerTextField()
                                        }
                                    )

                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = showExpandButton,
                                        enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.8f, animationSpec = tween(120)),
                                        exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.8f, animationSpec = tween(100)),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 4.dp, end = 4.dp)
                                    ) {
                                        IconButton(
                                            onClick = onOpenExpanded,
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.OpenInFull,
                                                contentDescription = "Expand composer",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Action Button: stays mounted across IDLE <-> HOLDING so pointer gesture never drops
                        val micScale by animateFloatAsState(
                            targetValue = if (isHolding) 1.18f + (voiceState.liveLevel * 0.14f) else 1f,
                            animationSpec = tween(durationMillis = 90),
                            label = "micHoldScale"
                        )
                        val buttonColor = when {
                            isHolding -> nexaColors.primaryButtonFill
                            actionMode == InputActionMode.STOP -> nexaColors.amber
                            actionMode == InputActionMode.SEND -> nexaColors.primaryButtonFill
                            else -> if (nexaColors.isDark) Color(0xFF162332) else MaterialTheme.colorScheme.primaryContainer
                        }
                        val iconTint = when {
                            isHolding -> nexaColors.onPrimaryButtonFill
                            actionMode == InputActionMode.STOP -> nexaColors.onPrimaryButtonFill
                            actionMode == InputActionMode.SEND -> nexaColors.onPrimaryButtonFill
                            else -> MaterialTheme.colorScheme.primary
                        }

                        val interactionModifier = if (actionMode == InputActionMode.MIC) {
                            Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (!latestHasMicPermission) {
                                        latestOnRequestMicPermission()
                                        return@awaitEachGesture
                                    }

                                    val started = latestOnStartVoiceRecording()
                                    if (!started) return@awaitEachGesture

                                    var accumX = 0f
                                    var accumY = 0f
                                    var resolved = false

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: event.changes.firstOrNull()
                                        if (change == null || !change.pressed) {
                                            break
                                        }
                                        val delta = change.position - change.previousPosition
                                        accumX = (accumX + delta.x).coerceIn(-cancelThresholdPx * 1.4f, 0f)
                                        accumY = (accumY + delta.y).coerceIn(-lockThresholdPx * 1.4f, 0f)
                                        dragOffsetX = accumX
                                        dragOffsetY = accumY
                                        change.consume()

                                        if (accumX <= -cancelThresholdPx) {
                                            resolved = true
                                            latestOnCancelVoiceRecording()
                                            break
                                        }
                                        if (accumY <= -lockThresholdPx) {
                                            resolved = true
                                            latestOnLockVoiceRecording()
                                            break
                                        }
                                    }

                                    dragOffsetX = 0f
                                    dragOffsetY = 0f
                                    if (!resolved) {
                                        // Match the visual "Release to cancel" prompt shown past 65% cancel progress
                                        if (accumX <= -(cancelThresholdPx * 0.65f)) {
                                            latestOnCancelVoiceRecording()
                                        } else {
                                            latestOnFinishVoiceRecording()
                                        }
                                    }
                                }
                            }
                        } else {
                            Modifier.clickable(
                                enabled = actionMode == InputActionMode.STOP || canSendText,
                                role = Role.Button,
                                onClick = {
                                    if (actionMode == InputActionMode.STOP) onStopStream() else onSend()
                                }
                            )
                        }

                        // Attach gesture modifier to the unscaled Box so pulsing micScale never distorts drag deltas
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .then(interactionModifier)
                        ) {
                            if (isHolding) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .graphicsLayer {
                                            val haloScale = 1.25f + (voiceState.liveLevel * 0.42f)
                                            scaleX = haloScale
                                            scaleY = haloScale
                                        }
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                                )
                            }

                            Surface(
                                color = buttonColor,
                                shape = CircleShape,
                                border = BorderStroke(
                                    1.dp,
                                    if (isHolding || actionMode == InputActionMode.MIC) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                    } else {
                                        nexaColors.cardBorder
                                    }
                                ),
                                modifier = Modifier
                                    .size(48.dp)
                                    .graphicsLayer {
                                        scaleX = micScale
                                        scaleY = micScale
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    AnimatedContent(
                                        targetState = actionMode,
                                        transitionSpec = {
                                            (fadeIn(tween(140)) + scaleIn(initialScale = 0.72f, animationSpec = tween(140)))
                                                .togetherWith(fadeOut(tween(100)) + scaleOut(targetScale = 0.72f, animationSpec = tween(100)))
                                        },
                                        label = "chatActionIconMorph"
                                    ) { mode ->
                                        Icon(
                                            imageVector = when (mode) {
                                                InputActionMode.STOP -> Icons.Default.Stop
                                                InputActionMode.SEND -> Icons.AutoMirrored.Filled.Send
                                                InputActionMode.MIC -> Icons.Default.Mic
                                            },
                                            contentDescription = when (mode) {
                                                InputActionMode.STOP -> "Stop response"
                                                InputActionMode.SEND -> "Send message"
                                                InputActionMode.MIC -> "Hold to record voice message"
                                            },
                                            tint = iconTint,
                                            modifier = Modifier.size(21.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldingVoiceRecordingStrip(
    elapsedMs: Long,
    cancelProgress: Float,
    dragOffsetX: Float,
    modifier: Modifier = Modifier
) {
    val nexaColors = LocalNexaColors.current
    val infiniteTransition = rememberInfiniteTransition(label = "recPulseHold")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "recDotAlpha"
    )
    val nearLimit = elapsedMs >= (ChatViewModel.MAX_VOICE_DURATION_MS - 15_000L)

    Surface(
        color = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(
            1.dp,
            if (cancelProgress > 0.6f) nexaColors.crimson else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        ),
        modifier = modifier.height(48.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .alpha(dotAlpha)
                        .clip(CircleShape)
                        .background(nexaColors.crimson)
                )
                Text(
                    text = VoiceNoteWaveform.formatDuration(elapsedMs),
                    style = NexaMonoTypography.codeLine.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    color = if (nearLimit) nexaColors.amber else MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = if (cancelProgress > 0.65f) "Release to cancel" else "< Slide to cancel",
                style = MaterialTheme.typography.bodySmall,
                color = if (cancelProgress > 0.65f) nexaColors.crimson else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .offset { IntOffset((dragOffsetX * 0.35f).roundToInt(), 0) }
                    .alpha((1f - cancelProgress * 0.45f).coerceIn(0.45f, 1f))
            )
        }
    }
}

@Composable
private fun LockedVoiceRecordingBar(
    voiceState: VoiceRecordingUiState,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val infiniteTransition = rememberInfiniteTransition(label = "recPulseLocked")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "recLockedDotAlpha"
    )
    val barColor = MaterialTheme.colorScheme.primary
    val nearLimit = voiceState.elapsedMs >= (ChatViewModel.MAX_VOICE_DURATION_MS - 15_000L)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = nexaColors.crimson.copy(alpha = 0.14f),
            shape = CircleShape,
            border = BorderStroke(1.dp, nexaColors.crimson.copy(alpha = 0.55f)),
            modifier = Modifier
                .size(48.dp)
                .clickable(role = Role.Button, onClick = onCancel)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Discard voice recording",
                    tint = nexaColors.crimson,
                    modifier = Modifier.size(21.dp)
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .alpha(dotAlpha)
                        .clip(CircleShape)
                        .background(nexaColors.crimson)
                )

                Text(
                    text = VoiceNoteWaveform.formatDuration(voiceState.elapsedMs),
                    style = NexaMonoTypography.codeLine.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    color = if (nearLimit) nexaColors.amber else MaterialTheme.colorScheme.onSurface
                )

                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp)
                ) {
                    val bars = voiceState.liveBars
                    val slotCount = 18
                    val step = size.width / slotCount.toFloat()
                    val strokeW = (step * 0.52f).coerceIn(2.dp.toPx(), 3.5.dp.toPx())
                    val centerY = size.height / 2f
                    val maxHalfH = (size.height / 2f) - (strokeW / 2f)

                    for (i in 0 until slotCount) {
                        val srcIdx = bars.size - slotCount + i
                        val level = if (srcIdx >= 0 && srcIdx < bars.size) bars[srcIdx] else 0.08f
                        val halfH = (maxHalfH * level.coerceIn(0.12f, 1f)).coerceAtLeast(2.dp.toPx())
                        val x = (i + 0.5f) * step
                        drawLine(
                            color = if (srcIdx >= 0) barColor else barColor.copy(alpha = 0.25f),
                            start = Offset(x, centerY - halfH),
                            end = Offset(x, centerY + halfH),
                            strokeWidth = strokeW,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }

        Surface(
            color = nexaColors.primaryButtonFill,
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
            modifier = Modifier
                .size(48.dp)
                .clickable(role = Role.Button, onClick = onSend)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send voice message",
                    tint = nexaColors.onPrimaryButtonFill,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

@Composable
private fun ChatExpandedComposerOverlay(
    draftText: String,
    onDraftChange: (String) -> Unit,
    replyTarget: ChatReplyTarget?,
    onClearReplyTarget: () -> Unit,
    isStreaming: Boolean,
    onCollapse: () -> Unit,
    onSend: () -> Unit,
    onStopStream: () -> Unit
) {
    val nexaColors = LocalNexaColors.current
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    BackHandler(onBack = onCollapse)

    LaunchedEffect(Unit) {
        delay(60L)
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            HorizontalDivider(thickness = 1.dp, color = nexaColors.cardBorder)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Compose",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${draftText.length} characters",
                        style = NexaMonoTypography.codeLine.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloseFullscreen,
                        contentDescription = "Collapse composer",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (replyTarget != null) {
                ReplyPreviewStrip(
                    replyTarget = replyTarget,
                    onDismiss = onClearReplyTarget
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Surface(
                color = if (nexaColors.isDark) Color(0xFF0D131B) else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, nexaColors.cardBorder),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                val emptyAreaInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = emptyAreaInteraction,
                            indication = null
                        ) {
                            runCatching { focusRequester.requestFocus() }
                        }
                        .verticalScroll(scrollState)
                        .padding(14.dp)
                ) {
                    BasicTextField(
                        value = draftText,
                        onValueChange = onDraftChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 22.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(focusRequester),
                        decorationBox = { innerTextField ->
                            if (draftText.isEmpty()) {
                                Text(
                                    text = "Message N.E.X.A...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (draftText.isNotEmpty()) {
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .clickable(role = Role.Button) { onDraftChange("") }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = "Clear",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                val canSend = draftText.isNotBlank()
                Surface(
                    color = when {
                        isStreaming -> nexaColors.amber
                        canSend -> nexaColors.primaryButtonFill
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = CircleShape,
                    border = BorderStroke(1.dp, nexaColors.cardBorder),
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            enabled = isStreaming || canSend,
                            role = Role.Button,
                            onClick = { if (isStreaming) onStopStream() else onSend() }
                        )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isStreaming) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (isStreaming) "Stop response" else "Send message",
                            tint = if (isStreaming || canSend) {
                                nexaColors.onPrimaryButtonFill
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatEmptyWelcomeState(
    onQuickPrompt: (String) -> Unit
) {
    val nexaColors = LocalNexaColors.current
    // Safe read-only quick prompts (no accidental financial transfer on tap)
    val suggestions = remember {
        listOf(
            "Check battery & device status",
            "Today's spending summary",
            "Check today's schedule",
            "Check server health & services"
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, nexaColors.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "N.E.X.A",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Ready for your schedule, finances, and device control. Sensitive actions always require fingerprint approval.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    suggestions.forEach { prompt ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, nexaColors.cardBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { onQuickPrompt(prompt) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = prompt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
