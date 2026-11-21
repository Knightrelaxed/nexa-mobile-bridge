package com.nexa.mobilebridge.ui.logs

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobilebridge.core.auth.TicketAuthRepository
import com.nexa.mobilebridge.core.auth.TicketOutcome
import com.nexa.mobilebridge.data.local.NexaPreferencesDataStore
import com.nexa.mobilebridge.data.logs.CircularLogBuffer
import com.nexa.mobilebridge.data.logs.LogEntry
import com.nexa.mobilebridge.data.logs.LogFilterPreset
import com.nexa.mobilebridge.data.logs.LogStreamStatus
import com.nexa.mobilebridge.data.logs.SidecarLogsClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class LogSnapshotState(
    val visibleEntries: List<LogEntry> = emptyList(),
    val totalBufferedCount: Int = 0
)

@HiltViewModel
class LogViewerViewModel @Inject constructor(
    private val sidecarLogsClient: SidecarLogsClient,
    private val ticketAuthRepository: TicketAuthRepository,
    private val dataStore: NexaPreferencesDataStore
) : ViewModel() {

    val logBuffer: CircularLogBuffer = sidecarLogsClient.logBuffer
    val bufferVersion: StateFlow<Long> = sidecarLogsClient.logBuffer.version
    val skippedLinesCount: StateFlow<Int> = sidecarLogsClient.logBuffer.skippedLinesCount
    val streamStatus: StateFlow<LogStreamStatus> = sidecarLogsClient.status
    val errorMessage: StateFlow<String?> = sidecarLogsClient.errorMessage

    private val _activeFilter = MutableStateFlow(LogFilterPreset.ALL)
    val activeFilter: StateFlow<LogFilterPreset> = _activeFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Compute log snapshot + subsystem filter + instant local keyword search off Main Thread (~30 fps)
    val logSnapshot: StateFlow<LogSnapshotState> = flow {
        combine(
            sidecarLogsClient.logBuffer.version,
            _activeFilter,
            _searchQuery
        ) { _, filter, rawQuery ->
            val all = sidecarLogsClient.logBuffer.snapshot()
            val q = rawQuery.trim()
            val filtered = all.filter { entry ->
                if (!entry.matchesFilter(filter)) return@filter false
                if (q.isEmpty()) return@filter true
                entry.message.contains(q, ignoreCase = true) ||
                    entry.unitName.contains(q, ignoreCase = true) ||
                    entry.priorityBadge.contains(q, ignoreCase = true) ||
                    (entry.categoryBadge?.contains(q, ignoreCase = true) == true)
            }
            LogSnapshotState(
                visibleEntries = filtered,
                totalBufferedCount = all.size
            )
        }.flowOn(Dispatchers.Default).conflate().collect { snap ->
            emit(snap)
            delay(33L)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = LogSnapshotState()
    )

    private val _autoScrollEnabled = MutableStateFlow(true)
    val autoScrollEnabled: StateFlow<Boolean> = _autoScrollEnabled.asStateFlow()

    private val _enrollmentStatus = MutableStateFlow("NOT_ENROLLED")
    val enrollmentStatus: StateFlow<String> = _enrollmentStatus.asStateFlow()

    private val _hapticEnabled = MutableStateFlow(true)
    val hapticEnabled: StateFlow<Boolean> = _hapticEnabled.asStateFlow()

    private var startupJob: Job? = null
    private val isSessionActiveOrStarting = AtomicBoolean(false)

    /**
     * FR-LOG-01 / FR-LOG-03 / FR-LOG-13: Called from LifecycleStartEffect (ON_START).
     * Prevents duplicate subscriptions and requests a fresh single-use Ed25519 ticket (scope=logs).
     */
    fun onLogsTabStarted(activity: FragmentActivity?) {
        if (!isSessionActiveOrStarting.compareAndSet(false, true)) {
            return
        }

        startupJob?.cancel()
        startupJob = viewModelScope.launch {
            val cfg = dataStore.getConfig()
            _enrollmentStatus.value = cfg.enrollmentStatus
            _hapticEnabled.value = cfg.hapticEnabled

            if (cfg.enrollmentStatus != "ACTIVE") {
                val msg = if (cfg.enrollmentStatus == "INVALIDATED") {
                    "Kunci Keystore perangkat gugur karena perubahan sidik jari Android. Buka Pengaturan -> Panduan Daftar Ulang (nexa-enroll re-pair)."
                } else {
                    "Perangkat belum terdaftar untuk akses Log Sidecar. Buka Pengaturan & Keamanan lalu daftarkan kunci perangkat dengan CLI nexa-enroll."
                }
                sidecarLogsClient.setError(msg)
                isSessionActiveOrStarting.set(false)
                return@launch
            }

            if (activity == null) {
                sidecarLogsClient.setError("Activity tidak tersedia untuk menampilkan BiometricPrompt.")
                isSessionActiveOrStarting.set(false)
                return@launch
            }

            sidecarLogsClient.setAwaitingBiometric()
            when (
                val outcome = ticketAuthRepository.requestTicket(
                    activity = activity,
                    scope = "logs",
                    promptTitle = "Buka Server Log Monitor",
                    promptSubtitle = "Verifikasi sidik jari untuk tiket Ed25519 (scope=logs)"
                )
            ) {
                is TicketOutcome.Success -> {
                    sidecarLogsClient.connect(
                        coreWsUrl = cfg.serverUrl,
                        ticket = outcome.ticket.ticket,
                        initialFilter = _activeFilter.value
                    )
                }

                is TicketOutcome.KeyInvalidated -> {
                    _enrollmentStatus.value = "INVALIDATED"
                    sidecarLogsClient.setError(outcome.message)
                    isSessionActiveOrStarting.set(false)
                }

                is TicketOutcome.Failure -> {
                    sidecarLogsClient.setError(outcome.message)
                    isSessionActiveOrStarting.set(false)
                }
            }
        }
    }

    /**
     * FR-LOG-03 / PRD §13: Called from LifecycleStartEffect onStopOrDispose (ON_STOP or tab exit).
     * Immediately sends CLOSE, closes Connection B (/io/logs), and clears the 2,000-entry ring buffer.
     */
    fun onLogsTabStopped() {
        startupJob?.cancel()
        startupJob = null
        isSessionActiveOrStarting.set(false)
        _searchQuery.value = ""
        sidecarLogsClient.disconnectAndClear()
    }

    fun retryConnection(activity: FragmentActivity?) {
        onLogsTabStopped()
        onLogsTabStarted(activity)
    }

    /**
     * FR-LOG-05 / FR-LOG-06: Change server-side filter subscription and local view filter.
     */
    fun selectFilter(preset: LogFilterPreset) {
        _activeFilter.value = preset
        _autoScrollEnabled.value = true
        sidecarLogsClient.updateFilterSubscription(preset)
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearSearchQuery() {
        _searchQuery.value = ""
    }

    /**
     * FR-LOG-10: Protocol-level Pause/Resume (withholds CREDIT when paused).
     */
    fun togglePause() {
        val currentlyPaused = streamStatus.value == LogStreamStatus.PAUSED
        sidecarLogsClient.setPaused(!currentlyPaused)
    }

    /**
     * FR-LOG-11: Update auto-scroll state to newest log entry.
     */
    fun setAutoScroll(enabled: Boolean) {
        _autoScrollEnabled.value = enabled
    }

    /**
     * FR-LOG-11: Clear local ring buffer only (does not affect server journald).
     */
    fun clearLocalBuffer() {
        _autoScrollEnabled.value = true
        sidecarLogsClient.clearLocalBuffer()
    }

    override fun onCleared() {
        super.onCleared()
        onLogsTabStopped()
    }
}
