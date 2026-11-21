package com.nexa.mobilebridge.ui.terminal

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobilebridge.core.auth.TicketAuthRepository
import com.nexa.mobilebridge.core.auth.TicketOutcome
import com.nexa.mobilebridge.data.local.NexaPreferencesDataStore
import com.nexa.mobilebridge.data.terminal.ModifierState
import com.nexa.mobilebridge.data.terminal.SidecarTerminalClient
import com.nexa.mobilebridge.data.terminal.TerminalSessionState
import com.nexa.mobilebridge.data.terminal.TerminalSessionStatus
import com.nexa.mobilebridge.data.terminal.Vt100TerminalEmulator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val sidecarTerminalClient: SidecarTerminalClient,
    private val ticketAuthRepository: TicketAuthRepository,
    private val dataStore: NexaPreferencesDataStore
) : ViewModel() {

    // FR-TERM-07: Single shared ModifierState instance used by both AccessoryBar and InputSink
    val modifierState = ModifierState()

    val emulator: Vt100TerminalEmulator = sidecarTerminalClient.emulator
    // Coalesce high-frequency PTY bursts to ~30 fps while keeping single-keystroke latency at 0 ms
    val emulatorVersion: StateFlow<Long> = flow {
        sidecarTerminalClient.emulator.version.collect { version ->
            emit(version)
            delay(33L)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = sidecarTerminalClient.emulator.version.value
    )
    val sessionState: StateFlow<TerminalSessionState> = sidecarTerminalClient.sessionState

    private val _fontSizeSp = MutableStateFlow(NexaPreferencesDataStore.DEFAULT_TERMINAL_FONT_SP)
    val fontSizeSp: StateFlow<Int> = _fontSizeSp.asStateFlow()

    private val _hapticEnabled = MutableStateFlow(true)
    val hapticEnabled: StateFlow<Boolean> = _hapticEnabled.asStateFlow()

    private val _enrollmentStatus = MutableStateFlow("NOT_ENROLLED")
    val enrollmentStatus: StateFlow<String> = _enrollmentStatus.asStateFlow()

    private val _isRow2Expanded = MutableStateFlow(false)
    val isRow2Expanded: StateFlow<Boolean> = _isRow2Expanded.asStateFlow()

    private var connectJob: Job? = null
    private var resizeDebounceJob: Job? = null
    private val isConnecting = AtomicBoolean(false)

    @Volatile
    private var latestRows: Int = 24
    @Volatile
    private var latestCols: Int = 80

    init {
        refreshPreferences()
    }

    fun refreshPreferences() {
        viewModelScope.launch {
            val cfg = dataStore.getConfig()
            _fontSizeSp.value = cfg.terminalFontSizeSp
            _hapticEnabled.value = cfg.hapticEnabled
            _enrollmentStatus.value = cfg.enrollmentStatus
        }
    }

    fun toggleRow2Expanded() {
        _isRow2Expanded.value = !_isRow2Expanded.value
    }

    fun adjustFontSize(deltaSp: Int) {
        val next = (_fontSizeSp.value + deltaSp).coerceIn(
            NexaPreferencesDataStore.MIN_TERMINAL_FONT_SP,
            NexaPreferencesDataStore.MAX_TERMINAL_FONT_SP
        )
        _fontSizeSp.value = next
        viewModelScope.launch {
            dataStore.saveTerminalFontSizeSp(next)
        }
    }

    /**
     * FR-TERM-05: Debounce viewport resize events (~150 ms) before emitting RESIZE (SIGWINCH).
     */
    fun onViewportMeasured(rows: Int, cols: Int) {
        val r = rows.coerceIn(4, 200)
        val c = cols.coerceIn(10, 300)
        if (r == latestRows && c == latestCols) return
        latestRows = r
        latestCols = c

        resizeDebounceJob?.cancel()
        resizeDebounceJob = viewModelScope.launch {
            delay(150L)
            sidecarTerminalClient.sendResize(r, c)
        }
    }

    /**
     * Open a new inspection terminal session or resume a detached session via BiometricPrompt + Ed25519 ticket.
     */
    fun startOrResumeSession(activity: FragmentActivity?, forceNew: Boolean = false) {
        if (!isConnecting.compareAndSet(false, true)) return

        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            try {
                val cfg = dataStore.getConfig()
                _enrollmentStatus.value = cfg.enrollmentStatus
                _hapticEnabled.value = cfg.hapticEnabled
                _fontSizeSp.value = cfg.terminalFontSizeSp

                if (cfg.enrollmentStatus != "ACTIVE") {
                    val msg = if (cfg.enrollmentStatus == "INVALIDATED") {
                        "Kunci perangkat gugur karena sidik jari baru ditambahkan. Buka Pengaturan -> Panduan Daftar Ulang (nexa-enroll re-pair)."
                    } else {
                        "Perangkat belum terdaftar untuk Terminal Inspeksi. Buka Pengaturan & Keamanan lalu daftar dengan CLI nexa-enroll."
                    }
                    sidecarTerminalClient.setError(msg)
                    return@launch
                }

                if (activity == null) {
                    sidecarTerminalClient.setError("Activity tidak tersedia untuk menampilkan BiometricPrompt.")
                    return@launch
                }

                val currentState = sessionState.value
                val candidateSid = if (!forceNew && currentState.activeSid != null &&
                    currentState.status == TerminalSessionStatus.DETACHED_RECONNECTING
                ) {
                    currentState.activeSid
                } else {
                    ""
                }

                sidecarTerminalClient.setAwaitingBiometric()
                when (
                    val outcome = ticketAuthRepository.requestTicket(
                        activity = activity,
                        scope = "term",
                        resumeSid = candidateSid,
                        promptTitle = if (candidateSid.isNotBlank()) {
                            "Pulihkan Sesi Terminal"
                        } else {
                            "Buka Terminal Inspeksi VPS"
                        },
                        promptSubtitle = "Verifikasi sidik jari untuk tiket Ed25519 (scope=term)"
                    )
                ) {
                    is TicketOutcome.Success -> {
                        val isResume = candidateSid.isNotBlank() && outcome.ticket.sid == candidateSid
                        sidecarTerminalClient.connect(
                            coreWsUrl = cfg.serverUrl,
                            ticket = outcome.ticket.ticket,
                            sid = outcome.ticket.sid,
                            rows = latestRows,
                            cols = latestCols,
                            isResume = isResume
                        )
                    }

                    is TicketOutcome.KeyInvalidated -> {
                        _enrollmentStatus.value = "INVALIDATED"
                        sidecarTerminalClient.setError(outcome.message)
                    }

                    is TicketOutcome.Failure -> {
                        sidecarTerminalClient.setError(outcome.message)
                    }
                }
            } finally {
                isConnecting.set(false)
            }
        }
    }

    fun sendInputBytes(bytes: ByteArray) {
        sidecarTerminalClient.sendInputBytes(bytes)
    }

    /**
     * FR-TERM-13: Screen turned off -> pause output (CREDIT=0) instead of killing bash.
     */
    fun onScreenPaused() {
        sidecarTerminalClient.pauseForScreenOff()
    }

    /**
     * FR-TERM-13: Screen woke up -> resume output if socket is still alive.
     */
    fun onScreenResumed() {
        sidecarTerminalClient.resumeFromScreenOn()
    }

    /**
     * FR-TERM-12 / PRD §13: Deliberately closing the Terminal tab terminates the remote
     * bash process group immediately (SIGHUP -> 2s SIGKILL, 0 zombies) and clears buffers.
     */
    fun onTabClosed() {
        disconnectSession()
    }

    /**
     * FR-TERM-12: Explicitly disconnect and terminate remote bash process group (0 zombies).
     */
    fun disconnectSession() {
        connectJob?.cancel()
        connectJob = null
        resizeDebounceJob?.cancel()
        resizeDebounceJob = null
        isConnecting.set(false)
        modifierState.resetAll()
        sidecarTerminalClient.closeSessionExplicitly()
    }

    override fun onCleared() {
        super.onCleared()
        disconnectSession()
    }
}
