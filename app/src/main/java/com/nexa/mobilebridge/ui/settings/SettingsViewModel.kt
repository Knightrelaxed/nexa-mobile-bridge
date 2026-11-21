package com.nexa.mobilebridge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobilebridge.NexaBridgeService
import com.nexa.mobilebridge.core.auth.EnrollmentOutcome
import com.nexa.mobilebridge.core.auth.TicketAuthRepository
import com.nexa.mobilebridge.core.network.NexaWebSocketClient
import com.nexa.mobilebridge.core.security.DeviceKeySnapshot
import com.nexa.mobilebridge.core.security.DeviceKeystoreManager
import com.nexa.mobilebridge.core.security.KeystoreKeyState
import com.nexa.mobilebridge.data.local.AccentPreset
import com.nexa.mobilebridge.data.local.NexaPreferencesDataStore
import com.nexa.mobilebridge.data.local.ThemeMode
import com.nexa.mobilebridge.data.terminal.SidecarTerminalClient
import com.nexa.mobilebridge.data.terminal.TerminalSessionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val deviceName: String = "samsung-a33-tuan",
    val serverUrl: String = "",
    val authToken: String = "",
    val hmacSecret: String = "",
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentPreset: AccentPreset = AccentPreset.CYAN,
    val hapticEnabled: Boolean = true,
    val terminalFontSizeSp: Int = 12,
    val heartbeatScreenOnSec: Int = NexaPreferencesDataStore.DEFAULT_HEARTBEAT_SCREEN_ON_SEC,
    val heartbeatScreenOffSec: Int = NexaPreferencesDataStore.DEFAULT_HEARTBEAT_SCREEN_OFF_SEC,
    val chatNotificationSoundUri: String = "",
    val chatNotificationSoundLabel: String = "Default Sistem",
    val killSwitchLabel: String = "NONAKTIF",
    val killSwitchSubtitle: String = "Nonaktif secara default di server (FR-TERM-18)",
    val isKillSwitchActive: Boolean = false,
    val isSaved: Boolean = false,
    // Hardware Identity & Enrollment (Tahap 1.5 / Audit A7)
    val keySnapshot: DeviceKeySnapshot = DeviceKeySnapshot(KeystoreKeyState.READY),
    val enrollmentStatus: String = "NOT_ENROLLED", // NOT_ENROLLED | ACTIVE | INVALIDATED
    val enrolledAtEpochSec: Long = 0L,
    val isDomainUnlocked: Boolean = false,
    val tokenRevealSecondsLeft: Int = 0,
    val isEnrolling: Boolean = false,
    val enrollError: String? = null,
    val lastEnrollSuccessTick: Int = 0,
    val statusNotice: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: NexaPreferencesDataStore,
    private val webSocketClient: NexaWebSocketClient,
    private val keystoreManager: DeviceKeystoreManager,
    private val ticketAuthRepository: TicketAuthRepository,
    private val sidecarTerminalClient: SidecarTerminalClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var tokenRevealJob: Job? = null

    init {
        loadInitialState()
        observeTerminalKillSwitch()
    }

    private fun observeTerminalKillSwitch() {
        viewModelScope.launch {
            sidecarTerminalClient.sessionState.collect { termState ->
                val (label, subtitle, active) = when (termState.status) {
                    TerminalSessionStatus.ACTIVE,
                    TerminalSessionStatus.PAUSED_SCREEN_OFF,
                    TerminalSessionStatus.DETACHED_RECONNECTING -> Triple(
                        "AKTIF",
                        "Sesi terminal sedang aktif di server (/io/term)",
                        true
                    )
                    TerminalSessionStatus.DISABLED_BY_SERVER -> Triple(
                        "NONAKTIF",
                        "Dinonaktifkan oleh kill switch server (FR-TERM-18)",
                        false
                    )
                    else -> Triple(
                        "NONAKTIF",
                        "Nonaktif secara default di server (verifikasi saat buka Shell)",
                        false
                    )
                }
                _uiState.update {
                    it.copy(
                        killSwitchLabel = label,
                        killSwitchSubtitle = subtitle,
                        isKillSwitchActive = active
                    )
                }
            }
        }
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            val config = dataStore.getConfig()
            val keySnap = keystoreManager.getOrCreateKeySnapshot()

            val effectiveStatus = when {
                keySnap.state == KeystoreKeyState.INVALIDATED -> "INVALIDATED"
                config.enrollmentStatus == "ACTIVE" &&
                    config.enrolledFingerprint.isNotBlank() &&
                    keySnap.sha256ColonHex.isNotBlank() &&
                    config.enrolledFingerprint != keySnap.sha256ColonHex -> "NOT_ENROLLED"
                else -> config.enrollmentStatus
            }

            _uiState.update {
                it.copy(
                    deviceName = config.deviceName,
                    serverUrl = config.serverUrl,
                    authToken = config.authToken,
                    hmacSecret = config.hmacSecret,
                    themeMode = config.themeMode,
                    accentPreset = config.accentPreset,
                    hapticEnabled = config.hapticEnabled,
                    terminalFontSizeSp = config.terminalFontSizeSp,
                    heartbeatScreenOnSec = config.heartbeatScreenOnSec,
                    heartbeatScreenOffSec = config.heartbeatScreenOffSec,
                    chatNotificationSoundUri = config.chatNotificationSoundUri,
                    chatNotificationSoundLabel = config.chatNotificationSoundLabel,
                    keySnapshot = keySnap,
                    enrollmentStatus = effectiveStatus,
                    enrolledAtEpochSec = config.enrolledAtEpochSec,
                    // Domain is editable initially before enrollment, locked once ACTIVE (Audit A7)
                    isDomainUnlocked = effectiveStatus != "ACTIVE"
                )
            }
        }
    }

    fun onServerUrlChanged(url: String) {
        _uiState.update { it.copy(serverUrl = url, isSaved = false) }
    }

    fun onAuthTokenChanged(token: String) {
        _uiState.update { it.copy(authToken = token, isSaved = false) }
    }

    fun onHmacSecretChanged(secret: String) {
        _uiState.update { it.copy(hmacSecret = secret, isSaved = false) }
    }

    fun onThemeModeSelected(mode: ThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
        viewModelScope.launch { dataStore.saveThemeMode(mode) }
    }

    fun onAccentPresetSelected(preset: AccentPreset) {
        _uiState.update { it.copy(accentPreset = preset) }
        viewModelScope.launch { dataStore.saveAccentPreset(preset) }
    }

    fun onHapticToggled(enabled: Boolean) {
        _uiState.update { it.copy(hapticEnabled = enabled) }
        viewModelScope.launch { dataStore.saveHapticEnabled(enabled) }
    }

    fun onTerminalFontSizePreview(sizeSp: Int) {
        val clamped = sizeSp.coerceIn(
            NexaPreferencesDataStore.MIN_TERMINAL_FONT_SP,
            NexaPreferencesDataStore.MAX_TERMINAL_FONT_SP
        )
        _uiState.update { it.copy(terminalFontSizeSp = clamped) }
    }

    fun onTerminalFontSizeCommitted() {
        val clamped = _uiState.value.terminalFontSizeSp.coerceIn(
            NexaPreferencesDataStore.MIN_TERMINAL_FONT_SP,
            NexaPreferencesDataStore.MAX_TERMINAL_FONT_SP
        )
        viewModelScope.launch { dataStore.saveTerminalFontSizeSp(clamped) }
    }

    fun onCycleHeartbeatScreenOn() {
        val options = NexaPreferencesDataStore.HEARTBEAT_SCREEN_ON_OPTIONS
        val current = _uiState.value.heartbeatScreenOnSec
        val next = options[(options.indexOf(current).let { if (it < 0) 0 else it + 1 }) % options.size]
        val screenOff = _uiState.value.heartbeatScreenOffSec
        _uiState.update { it.copy(heartbeatScreenOnSec = next) }
        viewModelScope.launch { dataStore.saveHeartbeatIntervals(next, screenOff) }
    }

    fun onCycleHeartbeatScreenOff() {
        val options = NexaPreferencesDataStore.HEARTBEAT_SCREEN_OFF_OPTIONS
        val current = _uiState.value.heartbeatScreenOffSec
        val next = options[(options.indexOf(current).let { if (it < 0) 0 else it + 1 }) % options.size]
        val screenOn = _uiState.value.heartbeatScreenOnSec
        _uiState.update { it.copy(heartbeatScreenOffSec = next) }
        viewModelScope.launch { dataStore.saveHeartbeatIntervals(screenOn, next) }
    }

    fun onChatNotificationSoundSelected(uri: String, label: String) {
        val safeLabel = label.ifBlank { "Default Sistem" }
        _uiState.update {
            it.copy(
                chatNotificationSoundUri = uri,
                chatNotificationSoundLabel = safeLabel
            )
        }
        viewModelScope.launch { dataStore.saveChatNotificationSound(uri, safeLabel) }
    }

    fun clearEnrollError() {
        _uiState.update { it.copy(enrollError = null) }
    }

    fun onEnterSettings() {
        loadInitialState()
    }

    fun onLeaveSettings() {
        hideTokenImmediately()
        _uiState.update { curr ->
            curr.copy(
                isDomainUnlocked = curr.enrollmentStatus != "ACTIVE",
                statusNotice = null,
                enrollError = null
            )
        }
    }

    fun unlockDomainAfterBiometric() {
        _uiState.update { it.copy(isDomainUnlocked = true, statusNotice = "Domain dibuka untuk pengeditan.") }
    }

    /**
     * Reveal bearer token for strictly 10 seconds after BIOMETRIC_STRONG confirmation (Audit A7).
     */
    fun startTokenReveal10Seconds() {
        tokenRevealJob?.cancel()
        tokenRevealJob = viewModelScope.launch {
            for (sec in 10 downTo 1) {
                _uiState.update { it.copy(tokenRevealSecondsLeft = sec) }
                delay(1_000L)
            }
            _uiState.update { it.copy(tokenRevealSecondsLeft = 0) }
        }
    }

    fun hideTokenImmediately() {
        tokenRevealJob?.cancel()
        _uiState.update { it.copy(tokenRevealSecondsLeft = 0) }
    }

    fun regenerateHardwareKey() {
        viewModelScope.launch {
            val newSnap = keystoreManager.regenerateKeyPair()
            dataStore.saveEnrollmentState("NOT_ENROLLED", 0L, "")
            _uiState.update {
                it.copy(
                    keySnapshot = newSnap,
                    enrollmentStatus = "NOT_ENROLLED",
                    enrolledAtEpochSec = 0L,
                    isDomainUnlocked = true,
                    statusNotice = if (newSnap.state == KeystoreKeyState.READY) {
                        "Kunci Keystore P-256 baru dibuat. Masukkan kode nexa-enroll re-pair dari server."
                    } else {
                        newSnap.errorMessage
                    }
                )
            }
        }
    }

    fun submitEnrollmentCode(code: String) {
        val clean = code.trim()
        if (clean.replace("-", "").length < 8) {
            _uiState.update { it.copy(enrollError = "Masukkan kode enrollment 8 karakter (contoh: ABCD-2345).") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isEnrolling = true, enrollError = null, statusNotice = null) }
            when (val res = ticketAuthRepository.submitEnrollment(_uiState.value.deviceName, clean)) {
                is EnrollmentOutcome.Success -> {
                    _uiState.update {
                        it.copy(
                            isEnrolling = false,
                            enrollError = null,
                            enrollmentStatus = "ACTIVE",
                            enrolledAtEpochSec = res.enrolledAtSec,
                            isDomainUnlocked = false,
                            lastEnrollSuccessTick = it.lastEnrollSuccessTick + 1,
                            statusNotice = "Perangkat terdaftar! Cocokkan SHA-256 32 oktet dengan CLI server."
                        )
                    }
                }
                is EnrollmentOutcome.Failure -> {
                    _uiState.update {
                        it.copy(
                            isEnrolling = false,
                            enrollError = "Gagal daftar (${res.code}): ${res.message}"
                        )
                    }
                }
            }
        }
    }

    fun clearStatusNotice() {
        _uiState.update { it.copy(statusNotice = null) }
    }

    fun setStatusNotice(msg: String) {
        _uiState.update { it.copy(statusNotice = msg) }
    }

    fun testConnection() {
        viewModelScope.launch {
            val state = _uiState.value
            val stored = dataStore.getConfig()
            val hasUnverifiedCredentialEdits = state.enrollmentStatus == "ACTIVE" &&
                (state.serverUrl.trim() != stored.serverUrl.trim() ||
                    state.authToken != stored.authToken ||
                    state.hmacSecret != stored.hmacSecret)
            if (hasUnverifiedCredentialEdits) {
                _uiState.update {
                    it.copy(statusNotice = "Simpan perubahan dengan sidik jari terlebih dahulu sebelum menguji koneksi baru.")
                }
                return@launch
            }
            val targetUrl = stored.serverUrl.trim()
            if (targetUrl.isNotBlank()) {
                webSocketClient.setHmacSecret(stored.hmacSecret)
                webSocketClient.forceReconnect(targetUrl, stored.authToken)
                _uiState.update { it.copy(statusNotice = "Menguji sambungan WebSocket ke $targetUrl...") }
            }
        }
    }

    fun saveSettings(onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            val state = _uiState.value
            val trimmedUrl = state.serverUrl.trim()
            dataStore.saveConfig(
                url = trimmedUrl,
                token = state.authToken,
                secret = state.hmacSecret
            )
            webSocketClient.setHmacSecret(state.hmacSecret)
            if (NexaBridgeService.isServiceRunning.value && trimmedUrl.isNotBlank()) {
                webSocketClient.forceReconnect(trimmedUrl, state.authToken)
            }
            _uiState.update {
                it.copy(
                    serverUrl = trimmedUrl,
                    isSaved = true,
                    isDomainUnlocked = it.enrollmentStatus != "ACTIVE"
                )
            }
            onSaved()
        }
    }

    override fun onCleared() {
        tokenRevealJob?.cancel()
        super.onCleared()
    }
}
