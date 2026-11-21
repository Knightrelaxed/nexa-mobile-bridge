package com.nexa.mobilebridge.ui.chat

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import java.util.Locale
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
    val ttsEnabled: Boolean = true,
    val hapticEnabled: Boolean = true
)

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

    // FR-CHAT-13 / U-H4: Surface LISTENING visor state while user focuses, types, or records voice note
    val effectiveAvatarState: StateFlow<NexaAvatarState> = combine(
        streamState,
        _isInputListening,
        _voiceRecordingState
    ) { state, listening, voiceState ->
        if (voiceState.phase != VoiceRecordingPhase.IDLE) {
            NexaAvatarState.LISTENING
        } else if (state.avatarState == NexaAvatarState.IDLE && listening) {
            NexaAvatarState.LISTENING
        } else {
            state.avatarState
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NexaAvatarState.IDLE
    )

    private val _chatPrefs = MutableStateFlow(ChatUiPreferences())
    val chatPrefs: StateFlow<ChatUiPreferences> = _chatPrefs.asStateFlow()

    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

    private var ttsEngine: TextToSpeech? = null
    @Volatile
    private var isTtsReady: Boolean = false

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
                ttsEnabled = cfg.ttsEnabled,
                hapticEnabled = cfg.hapticEnabled
            )
        }
    }

    fun selectReplyTarget(message: ChatMessageEntity, resolvedContent: String = message.content) {
        val voiceMeta = VoiceNoteMetadata.fromJson(message.executionMetadata)
        val preview = if (voiceMeta != null) {
            "Pesan suara (${VoiceNoteWaveform.formatDuration(voiceMeta.durationMs)})"
        } else {
            ChatReplyMetadata.sanitizePreview(resolvedContent).ifBlank { "Pesan" }
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
            showVoiceHint("Mikrofon sedang dipakai Panggilan Suara")
            return false
        }

        // Stop any active local TTS or voice playback to avoid feedback/echo
        stopSpeaking()
        voiceNotePlayer.stop()

        val clientMsgId = UUID.randomUUID().toString()
        val started = voiceNoteRecorder.start(clientMsgId)
        if (!started) {
            showVoiceHint("Gagal mengakses mikrofon perangkat")
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
            showVoiceHint("Tahan untuk merekam")
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
                    showVoiceHint("Gagal menyimpan rekaman suara")
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
        stopSpeaking()
        voiceNotePlayer.togglePlay(messageId, audioPath, durationMs)
    }

    fun seekVoicePlayback(messageId: String, audioPath: String, durationMs: Long, fraction: Float) {
        stopSpeaking()
        voiceNotePlayer.seekToFraction(messageId, audioPath, durationMs, fraction)
    }

    fun stopVoicePlayback() {
        voiceNotePlayer.stop()
    }

    /**
     * FR-CHAT-16: Offline-capable local Android TextToSpeech per N.E.X.A assistant bubble.
     */
    fun toggleSpeakMessage(messageId: String, rawText: String) {
        if (_speakingMessageId.value == messageId) {
            stopSpeaking()
            return
        }

        val cleanSpeech = sanitizeForSpeech(rawText)
        if (cleanSpeech.isBlank()) return

        // Stop any active voice note playback to prevent overlapping audio streams
        voiceNotePlayer.stop()

        if (ttsEngine == null) {
            ttsEngine = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val indonesian = Locale.forLanguageTag("id-ID")
                    ttsEngine?.language = indonesian
                    ttsEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _speakingMessageId.value = utteranceId
                        }

                        override fun onDone(utteranceId: String?) {
                            _speakingMessageId.update { curr -> if (curr == utteranceId) null else curr }
                        }

                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onError(utteranceId: String?) {
                            _speakingMessageId.update { curr -> if (curr == utteranceId) null else curr }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            _speakingMessageId.update { curr -> if (curr == utteranceId) null else curr }
                        }
                    })
                    isTtsReady = true
                    speakNow(messageId, cleanSpeech)
                } else {
                    Log.w(TAG, "Local TextToSpeech initialization failed with status=$status")
                    // U-M8: Reset failed engine reference so future taps retry cleanly
                    try {
                        ttsEngine?.shutdown()
                    } catch (_: Exception) {
                    }
                    ttsEngine = null
                    isTtsReady = false
                    _speakingMessageId.value = null
                }
            }
        } else if (isTtsReady) {
            speakNow(messageId, cleanSpeech)
        }
    }

    fun stopSpeaking() {
        try {
            ttsEngine?.stop()
        } catch (_: Exception) {
        }
        _speakingMessageId.value = null
    }

    private fun speakNow(messageId: String, cleanSpeech: String) {
        _speakingMessageId.value = messageId
        ttsEngine?.speak(
            cleanSpeech,
            TextToSpeech.QUEUE_FLUSH,
            null,
            messageId
        )
    }

    private fun sanitizeForSpeech(text: String): String {
        return text
            .replace(Regex("```[\\s\\S]*?```"), " blok kode ")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("N\\.?E\\.?X\\.?A\\.?", RegexOption.IGNORE_CASE), "Nexa")
            .trim()
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
                "Hash integritas transaksi tidak cocok dengan detail di layar. Otorisasi dibatalkan demi keamanan."
            )
            return
        }

        if (!BiometricAuthHelper.isStrongBiometricAvailable(activity)) {
            chatRepository.setStepUpError(
                stepUp.actionId,
                "Sensor sidik jari kuat (BIOMETRIC_STRONG) belum aktif di perangkat ini."
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
                "Kunci perangkat gugur karena perubahan sidik jari Android. Buka Pengaturan -> Panduan Daftar Ulang (nexa-enroll re-pair)."
            )
            return
        } catch (e: Exception) {
            chatRepository.setStepUpError(
                stepUp.actionId,
                "Gagal menyiapkan kunci Android Keystore: ${e.message}"
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
            title = "Konfirmasi ${stepUp.formattedAmount}",
            subtitle = "${stepUp.recipientFull} (${stepUp.bank} •••• ${stepUp.acctLast4})",
            negativeButtonText = "Batalkan",
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
            "Activity biometrik tidak tersedia. Buka ulang layar chat lalu ulangi konfirmasi sidik jari."
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
        try {
            ttsEngine?.stop()
            ttsEngine?.shutdown()
        } catch (_: Exception) {
        }
        ttsEngine = null
    }
}
