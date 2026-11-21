package com.nexa.mobilebridge.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.nexa.mobilebridge.core.security.BiometricAuthHelper
import com.nexa.mobilebridge.core.security.DeviceKeystoreManager
import com.nexa.mobilebridge.data.chat.ActiveChatStreamState
import com.nexa.mobilebridge.data.chat.ChatCopyText
import com.nexa.mobilebridge.data.chat.ChatReplyMetadata
import com.nexa.mobilebridge.data.chat.ChatReplyTarget
import com.nexa.mobilebridge.data.chat.ChatRepository
import com.nexa.mobilebridge.data.chat.NexaAvatarState
import com.nexa.mobilebridge.data.chat.PendingFinanceStepUp
import com.nexa.mobilebridge.data.chat.VoiceNoteMetadata
import com.nexa.mobilebridge.data.chat.VoiceNotePlayer
import com.nexa.mobilebridge.data.chat.VoiceNoteRecorder
import com.nexa.mobilebridge.data.chat.VoiceNoteWaveform
import com.nexa.mobilebridge.data.chat.VoicePlaybackState
import com.nexa.mobilebridge.data.local.NexaPreferencesDataStore
import com.nexa.mobilebridge.data.local.chat.ChatMessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

enum class VoiceRecordingPhase {
    IDLE,
    HOLDING,
    LOCKED
}

data class VoiceRecordingUiState(
    val phase: VoiceRecordingPhase = VoiceRecordingPhase.IDLE,
    val elapsedMs: Long = 0L,
    val liveLevel: Float = 0f,
    val liveBars: List<Float> = emptyList(),
    val hintMessage: String? = null
)

data class ChatUiPreferences(
    val deviceName: String = "samsung-a33-tuan",
    val hapticEnabled: Boolean = true
)

data class ChatSelectionUiState(
    val selectedCopyableById: Map<String, Boolean> = emptyMap()
) {
    val selectedIds: Set<String> get() = selectedCopyableById.keys
    val count: Int get() = selectedCopyableById.size
    val isActive: Boolean get() = selectedCopyableById.isNotEmpty()
    val canReply: Boolean get() = selectedCopyableById.size == 1
    val canCopy: Boolean get() = selectedCopyableById.values.any { it }
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val chatRepository: ChatRepository,
    private val dataStore: NexaPreferencesDataStore,
    private val keystoreManager: DeviceKeystoreManager,
    private val voiceNoteRecorder: VoiceNoteRecorder,
    private val voiceNotePlayer: VoiceNotePlayer
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
        const val MIN_VOICE_DURATION_MS = 600L
        const val MAX_VOICE_DURATION_MS = 180_000L // 3 menit
        private const val VOICE_TICK_MS = 65L
        private const val LIVE_BAR_HISTORY = 20
        private const val HINT_AUTO_DISMISS_MS = 2_200L
    }

    val pagedMessages: Flow<PagingData<ChatMessageEntity>> =
        chatRepository.getPagedMessages().cachedIn(viewModelScope)

    val streamState: StateFlow<ActiveChatStreamState> = chatRepository.streamState
    val pendingStepUp: StateFlow<PendingFinanceStepUp?> = chatRepository.pendingStepUp
    val voicePlaybackState: StateFlow<VoicePlaybackState> = voiceNotePlayer.state

    private val _voiceRecordingState = MutableStateFlow(VoiceRecordingUiState())
    val voiceRecordingState: StateFlow<VoiceRecordingUiState> = _voiceRecordingState.asStateFlow()

    private val _replyTarget = MutableStateFlow<ChatReplyTarget?>(null)
    val replyTarget: StateFlow<ChatReplyTarget?> = _replyTarget.asStateFlow()

    private val _isInputListening = MutableStateFlow(false)

    // FR-CHAT-13 / U-H4: Surface STEP_UP / LISTENING visor state in priority order
    val effectiveAvatarState: StateFlow<NexaAvatarState> = combine(
        streamState,
        pendingStepUp,
        _isInputListening,
        _voiceRecordingState
    ) { state, stepUp, listening, voiceState ->
        when {
            stepUp != null -> NexaAvatarState.STEP_UP
            voiceState.phase != VoiceRecordingPhase.IDLE -> NexaAvatarState.LISTENING
            state.avatarState == NexaAvatarState.IDLE && listening -> NexaAvatarState.LISTENING
            else -> state.avatarState
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NexaAvatarState.IDLE
    )

    val voiceVisorLevel: StateFlow<Float> = combine(_voiceRecordingState) { arr ->
        val voiceState = arr[0]
        if (voiceState.phase != VoiceRecordingPhase.IDLE) voiceState.liveLevel.coerceIn(0f, 1f) else 0f
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0f
    )

    private val _chatPrefs = MutableStateFlow(ChatUiPreferences())
    val chatPrefs: StateFlow<ChatUiPreferences> = _chatPrefs.asStateFlow()

    private val _selectionState = MutableStateFlow(ChatSelectionUiState())
    val selectionState: StateFlow<ChatSelectionUiState> = _selectionState.asStateFlow()

    private val _pendingDeleteIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingDeleteIds: StateFlow<Set<String>> = _pendingDeleteIds.asStateFlow()

    private var activeVoiceClientMsgId: String? = null
    private var voiceStartedAtMs: Long = 0L
    private var voiceTickJob: Job? = null
    private var hintDismissJob: Job? = null
    private val rawAmplitudeSamples = mutableListOf<Int>()
    @Volatile
    private var isFinalizingVoice: Boolean = false
    private var activeBiometricPrompt: BiometricPrompt? = null

    init {
        refreshPreferences()
    }

    fun setInputListening(listening: Boolean) {
        _isInputListening.value = listening
    }

    fun refreshPreferences() {
        viewModelScope.launch {
            val cfg = dataStore.getConfig()
            _chatPrefs.value = ChatUiPreferences(
                deviceName = cfg.deviceName.ifBlank { "samsung-a33-tuan" },
                hapticEnabled = cfg.hapticEnabled
            )
        }
    }

    fun selectReplyTarget(message: ChatMessageEntity, resolvedContent: String = message.content) {
        val voiceMeta = VoiceNoteMetadata.fromJson(message.executionMetadata)
        val preview = if (voiceMeta != null) {
            "Voice message (${VoiceNoteWaveform.formatDuration(voiceMeta.durationMs)})"
        } else {
            ChatReplyMetadata.sanitizePreview(resolvedContent).ifBlank { "Message" }
        }
        _replyTarget.value = ChatReplyTarget(
            messageId = message.id,
            role = message.role,
            preview = preview,
            isVoice = voiceMeta != null
        )
    }

    fun clearReplyTarget() {
        _replyTarget.value = null
    }

    fun sendMessage(content: String) {
        val clean = content.trim()
        if (clean.isEmpty()) return
        val reply = _replyTarget.value
        _replyTarget.value = null
        viewModelScope.launch {
            chatRepository.sendMessage(clean, replyTo = reply)
        }
    }

    fun retryMessage(clientMsgId: String) {
        viewModelScope.launch {
            chatRepository.retryMessage(clientMsgId)
        }
    }

    fun stopActiveStream() {
        chatRepository.stopActiveStream()
    }

    fun clearError() {
        chatRepository.clearError()
    }

    fun showVoiceHint(message: String) {
        hintDismissJob?.cancel()
        _voiceRecordingState.update { it.copy(hintMessage = message) }
        hintDismissJob = viewModelScope.launch {
            delay(HINT_AUTO_DISMISS_MS)
            _voiceRecordingState.update { curr ->
                if (curr.hintMessage == message) curr.copy(hintMessage = null) else curr
            }
        }
    }

    /**
     * Begin Telegram-style hold-to-record OGG/Opus voice note capture.
     * Returns true if recording started.
     */
    fun startVoiceRecording(): Boolean {
        if (isFinalizingVoice || _voiceRecordingState.value.phase != VoiceRecordingPhase.IDLE) return false
        if (chatRepository.isLiveCallActive()) {
            showVoiceHint("Microphone is in use by Voice Call")
            return false
        }

        // Stop any active voice playback to avoid feedback/echo
        voiceNotePlayer.stop()

        val clientMsgId = UUID.randomUUID().toString()
        val started = voiceNoteRecorder.start(clientMsgId)
        if (!started) {
            showVoiceHint("Unable to access microphone")
            return false
        }

        activeVoiceClientMsgId = clientMsgId
        voiceStartedAtMs = System.currentTimeMillis()
        rawAmplitudeSamples.clear()
        hintDismissJob?.cancel()

        _voiceRecordingState.value = VoiceRecordingUiState(
            phase = VoiceRecordingPhase.HOLDING,
            elapsedMs = 0L,
            liveLevel = 0f,
            liveBars = emptyList(),
            hintMessage = null
        )

        voiceTickJob?.cancel()
        voiceTickJob = viewModelScope.launch {
            while (isActive) {
                delay(VOICE_TICK_MS)
                val elapsed = (System.currentTimeMillis() - voiceStartedAtMs).coerceAtLeast(0L)
                val amp = voiceNoteRecorder.sampleAmplitude()
                rawAmplitudeSamples.add(amp)
                val level = VoiceNoteWaveform.normalizeLiveLevel(amp)

                _voiceRecordingState.update { curr ->
                    if (curr.phase == VoiceRecordingPhase.IDLE) {
                        curr
                    } else {
                        val nextBars = (curr.liveBars + level).takeLast(LIVE_BAR_HISTORY)
                        curr.copy(
                            elapsedMs = elapsed.coerceAtMost(MAX_VOICE_DURATION_MS),
                            liveLevel = level,
                            liveBars = nextBars
                        )
                    }
                }

                if (elapsed >= MAX_VOICE_DURATION_MS) {
                    finishAndSendVoiceRecording(autoStoppedAtLimit = true)
                    break
                }
            }
        }
        return true
    }

    fun lockVoiceRecording() {
        _voiceRecordingState.update { curr ->
            if (curr.phase == VoiceRecordingPhase.HOLDING) {
                curr.copy(phase = VoiceRecordingPhase.LOCKED)
            } else {
                curr
            }
        }
    }

    fun cancelVoiceRecording() {
        if (_voiceRecordingState.value.phase == VoiceRecordingPhase.IDLE) return
        voiceTickJob?.cancel()
        voiceTickJob = null
        activeVoiceClientMsgId = null
        rawAmplitudeSamples.clear()
        voiceNoteRecorder.cancelAndDelete()
        _voiceRecordingState.value = VoiceRecordingUiState(phase = VoiceRecordingPhase.IDLE)
    }

    fun finishAndSendVoiceRecording(autoStoppedAtLimit: Boolean = false) {
        if (_voiceRecordingState.value.phase == VoiceRecordingPhase.IDLE) return
        val clientMsgId = activeVoiceClientMsgId
        val elapsedMs = (System.currentTimeMillis() - voiceStartedAtMs).coerceAtLeast(0L)
        val capturedSamples = rawAmplitudeSamples.toList()

        voiceTickJob?.cancel()
        voiceTickJob = null
        activeVoiceClientMsgId = null
        rawAmplitudeSamples.clear()

        if (!autoStoppedAtLimit && elapsedMs < MIN_VOICE_DURATION_MS) {
            voiceNoteRecorder.cancelAndDelete()
            _voiceRecordingState.value = VoiceRecordingUiState(phase = VoiceRecordingPhase.IDLE)
            showVoiceHint("Hold to record")
            return
        }

        _voiceRecordingState.value = VoiceRecordingUiState(phase = VoiceRecordingPhase.IDLE)
        if (clientMsgId == null) {
            voiceNoteRecorder.cancelAndDelete()
            return
        }

        val reply = _replyTarget.value
        isFinalizingVoice = true

        viewModelScope.launch {
            try {
                val recorded = withContext(Dispatchers.IO) {
                    voiceNoteRecorder.stopAndFinalize()
                }
                if (recorded == null) {
                    showVoiceHint("Failed to save voice recording")
                    return@launch
                }
                if (_replyTarget.value?.messageId == reply?.messageId) {
                    _replyTarget.value = null
                }
                val effectiveDuration = recorded.durationMs.coerceIn(MIN_VOICE_DURATION_MS, MAX_VOICE_DURATION_MS)
                val waveform = VoiceNoteWaveform.normalize(capturedSamples)
                val metadata = VoiceNoteMetadata(
                    audioPath = recorded.file.absolutePath,
                    durationMs = effectiveDuration,
                    waveform = waveform,
                    mimeType = recorded.mimeType,
                    replyTo = reply
                )
                chatRepository.sendVoiceMessage(clientMsgId, metadata)
            } finally {
                isFinalizingVoice = false
            }
        }
    }

    fun toggleVoicePlayback(messageId: String, audioPath: String, durationMs: Long) {
        voiceNotePlayer.togglePlay(messageId, audioPath, durationMs)
    }

    fun seekVoicePlayback(messageId: String, audioPath: String, durationMs: Long, fraction: Float) {
        voiceNotePlayer.seekToFraction(messageId, audioPath, durationMs, fraction)
    }

    fun stopVoicePlayback() {
        voiceNotePlayer.stop()
    }

    // ------------------------------------------------------------------
    // Message selection mode (long press) and per-message actions (tap menu)
    // ------------------------------------------------------------------

    fun selectMessage(message: ChatMessageEntity) {
        val copyable = ChatCopyText.isCopyable(message)
        _selectionState.update { curr ->
            curr.copy(selectedCopyableById = curr.selectedCopyableById + (message.id to copyable))
        }
    }

    fun toggleSelectMessage(message: ChatMessageEntity) {
        val copyable = ChatCopyText.isCopyable(message)
        _selectionState.update { curr ->
            val next = if (message.id in curr.selectedCopyableById) {
                curr.selectedCopyableById - message.id
            } else {
                curr.selectedCopyableById + (message.id to copyable)
            }
            curr.copy(selectedCopyableById = next)
        }
    }

    fun clearSelection() {
        if (_selectionState.value.isActive) {
            _selectionState.value = ChatSelectionUiState()
        }
    }

    fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("N.E.X.A Message", text))
    }

    fun copySelected() {
        val ids = _selectionState.value.selectedIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val text = ChatCopyText.build(chatRepository.getMessagesByIds(ids))
            copyToClipboard(text)
            clearSelection()
        }
    }

    fun replyToSelected() {
        val id = _selectionState.value.selectedIds.singleOrNull() ?: return
        viewModelScope.launch {
            val row = chatRepository.getMessagesByIds(listOf(id)).firstOrNull()
            if (row != null) selectReplyTarget(row)
            clearSelection()
        }
    }

    fun requestDeleteMessages(ids: Set<String>) {
        if (ids.isEmpty()) return
        _pendingDeleteIds.value = ids
    }

    fun requestDeleteSelected() {
        requestDeleteMessages(_selectionState.value.selectedIds)
    }

    fun dismissPendingDelete() {
        _pendingDeleteIds.value = emptySet()
    }

    fun confirmPendingDelete() {
        val ids = _pendingDeleteIds.value
        _pendingDeleteIds.value = emptySet()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val deleted = chatRepository.deleteMessages(ids)
            if (voiceNotePlayer.state.value.activeMessageId in deleted) {
                voiceNotePlayer.stop()
            }
            if (_replyTarget.value?.messageId in deleted) {
                _replyTarget.value = null
            }
            _selectionState.update { curr ->
                curr.copy(selectedCopyableById = curr.selectedCopyableById - ids)
            }
        }
    }

    /**
     * FR-AUTH-07: Hardware-Biometric CryptoObject signing for Finance Step-Up Modal Bottom Sheet.
     * System BiometricPrompt title and subtitle explicitly carry the nominal and last 4 digits.
     */
    fun confirmFinanceStepUpWithBiometric(
        activity: FragmentActivity,
        stepUp: PendingFinanceStepUp
    ) {
        if (stepUp.isSubmitting) return

        // Verify canonical_hash locally against displayed transaction fields before signing
        val expectedHash = DeviceKeystoreManager.computeCanonicalActionHash(
            actionId = stepUp.actionId,
            actionLabel = stepUp.actionLabel,
            amount = stepUp.amount,
            recipientFull = stepUp.recipientFull,
            bank = stepUp.bank,
            acctLast4 = stepUp.acctLast4,
            whitelisted = stepUp.whitelisted
        )
        if (!expectedHash.equals(stepUp.canonicalHash, ignoreCase = true)) {
            Log.e(TAG, "Finance Step-Up canonical_hash mismatch for actionId=${stepUp.actionId}")
            chatRepository.setStepUpError(
                stepUp.actionId,
                "Transaction integrity check failed. Approval was cancelled for your safety."
            )
            return
        }

        if (!BiometricAuthHelper.isStrongBiometricAvailable(activity)) {
            chatRepository.setStepUpError(
                stepUp.actionId,
                "Strong fingerprint unlock is not enabled on this device."
            )
            return
        }

        val signature = try {
            keystoreManager.prepareSignatureForBiometric()
        } catch (_: KeyPermanentlyInvalidatedException) {
            viewModelScope.launch {
                dataStore.saveEnrollmentState("INVALIDATED", 0L, "")
            }
            chatRepository.setStepUpError(
                stepUp.actionId,
                "Device key was invalidated after a fingerprint change. Open Settings to re-pair this device."
            )
            return
        } catch (e: Exception) {
            chatRepository.setStepUpError(
                stepUp.actionId,
                "Failed to prepare hardware key: ${e.message}"
            )
            return
        }

        val payloadToSign = DeviceKeystoreManager.buildCanonicalActionMessage(
            nonce = stepUp.nonce,
            actionId = stepUp.actionId,
            canonicalHash = stepUp.canonicalHash
        )

        cancelActiveBiometricPrompt()
        activeBiometricPrompt = BiometricAuthHelper.authenticateAndSign(
            activity = activity,
            title = "Approve ${stepUp.formattedAmount}",
            subtitle = "${stepUp.recipientFull} (${stepUp.bank} •••• ${stepUp.acctLast4})",
            negativeButtonText = "Cancel",
            signature = signature,
            payloadToSign = payloadToSign,
            onSigned = { sigDerBytes ->
                activeBiometricPrompt = null
                chatRepository.submitActionAuth(
                    actionId = stepUp.actionId,
                    nonce = stepUp.nonce,
                    deviceId = _chatPrefs.value.deviceName,
                    signatureDerBytes = sigDerBytes
                )
            },
            onError = { _, errMsg ->
                activeBiometricPrompt = null
                chatRepository.setStepUpError(stepUp.actionId, errMsg)
            }
        )
    }

    private fun cancelActiveBiometricPrompt() {
        val prompt = activeBiometricPrompt ?: return
        activeBiometricPrompt = null
        try {
            prompt.cancelAuthentication()
        } catch (_: Exception) {
        }
    }

    fun reportStepUpMissingActivity(actionId: String) {
        chatRepository.setStepUpError(
            actionId,
            "Unable to open fingerprint prompt. Reopen Chat and try again."
        )
    }

    fun cancelFinanceStepUp(actionId: String) {
        cancelActiveBiometricPrompt()
        chatRepository.cancelPendingAction(actionId, _chatPrefs.value.deviceName)
    }

    fun expireFinanceStepUp(actionId: String) {
        cancelActiveBiometricPrompt()
        chatRepository.expirePendingActionLocally(actionId, _chatPrefs.value.deviceName)
    }

    override fun onCleared() {
        super.onCleared()
        cancelActiveBiometricPrompt()
        cancelVoiceRecording()
        voiceNotePlayer.stop()
        hintDismissJob?.cancel()
    }
}
