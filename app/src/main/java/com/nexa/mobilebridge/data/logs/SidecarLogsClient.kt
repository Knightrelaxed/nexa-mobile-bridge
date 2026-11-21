package com.nexa.mobilebridge.data.logs

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

enum class LogStreamStatus {
    IDLE_DISCONNECTED,
    AWAITING_BIOMETRIC,
    CONNECTING,
    LIVE,
    PAUSED,
    ERROR
}

/**
 * On-demand Connection B (/io/logs) WebSocket client (FR-LOG-01..13, FR-SEC-01, NFR-BAT-02):
 * - Uses dedicated @Named("sidecar") OkHttpClient (pingInterval = 0)
 * - Exists ONLY while the Logs tab is visible and screen is on (ON_START .. ON_STOP)
 * - Authenticates via HTTP Upgrade header "Authorization: Bearer <ed25519_ticket>"
 * - Sends binary OPEN frame on channel 0x03 and replenishes 32 KiB CREDIT atomically
 * - Performs 1x auto-retry on HTTP 502/503 pre-onOpen (sidecar 90s idle exit race, NFR-REL-04)
 */
@Singleton
class SidecarLogsClient @Inject constructor(
    @param:Named("sidecar") private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "SidecarLogsClient"
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val HEARTBEAT_PONG_TIMEOUT_MS = 35_000L
        private const val DEFAULT_TAIL_LINES = 100
        private const val SIDECAR_RETRY_DELAY_MS = 600L

        fun deriveLogsEndpointUrl(coreWsUrl: String): String {
            val trimmed = coreWsUrl.trim().trimEnd('/')
            return when {
                trimmed.endsWith("/ws", ignoreCase = true) ->
                    trimmed.dropLast(3) + "/io/logs"
                trimmed.contains("/io/logs", ignoreCase = true) ->
                    trimmed
                else -> "$trimmed/io/logs"
            }
        }
    }

    val logBuffer = CircularLogBuffer(capacity = 2000)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wsLock = Any()
    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var heartbeatJob: Job? = null
    @Volatile
    private var retryJob: Job? = null

    @Volatile
    private var currentFilter: LogFilterPreset = LogFilterPreset.ALL
    @Volatile
    private var isPaused: Boolean = false
    @Volatile
    private var pendingFilterResubscribe: Boolean = false
    @Volatile
    private var isDisconnectedExplicitly: Boolean = false
    private val consumedSinceLastCredit = AtomicInteger(0)
    private val lastPongAtMs = AtomicLong(0L)

    private val _status = MutableStateFlow(LogStreamStatus.IDLE_DISCONNECTED)
    val status: StateFlow<LogStreamStatus> = _status.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun setAwaitingBiometric() {
        _status.value = LogStreamStatus.AWAITING_BIOMETRIC
        _errorMessage.value = null
    }

    fun setError(message: String) {
        _status.value = LogStreamStatus.ERROR
        _errorMessage.value = message
    }

    fun connect(
        coreWsUrl: String,
        ticket: String,
        initialFilter: LogFilterPreset = currentFilter
    ) {
        isDisconnectedExplicitly = false
        pendingFilterResubscribe = false
        connectInternal(coreWsUrl, ticket, initialFilter, attempt = 0)
    }

    private fun isActiveSocket(candidate: WebSocket): Boolean =
        synchronized(wsLock) { webSocket === candidate }

    private fun connectInternal(
        coreWsUrl: String,
        ticket: String,
        initialFilter: LogFilterPreset,
        attempt: Int
    ) {
        retryJob?.cancel()
        retryJob = null
        disconnectSocketOnly()
        currentFilter = initialFilter
        isPaused = false
        consumedSinceLastCredit.set(0)
        _status.value = LogStreamStatus.CONNECTING
        _errorMessage.value = null

        val logsUrl = deriveLogsEndpointUrl(coreWsUrl)
        val request = Request.Builder()
            .url(logsUrl)
            .addHeader("Authorization", "Bearer $ticket")
            .build()

        val opened = AtomicBoolean(false)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isActiveSocket(webSocket)) return
                opened.set(true)
                lastPongAtMs.set(System.currentTimeMillis())
                Log.i(TAG, "Connection B (/io/logs) opened")
                _status.value = if (isPaused) LogStreamStatus.PAUSED else LogStreamStatus.LIVE
                sendOpenSubscription(currentFilter)
                if (isPaused) {
                    val zeroCredit = LogBinaryProtocol.encodeCreditFrame(
                        channel = LogBinaryProtocol.CH_LOGS,
                        bytesCredit = 0
                    )
                    webSocket.send(zeroCredit.toByteString())
                }
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isActiveSocket(webSocket)) return
                lastPongAtMs.set(System.currentTimeMillis())
                handleBinaryFrame(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isActiveSocket(webSocket)) return
                Log.i(TAG, "Connection B (/io/logs) closed ($code): $reason")
                heartbeatJob?.cancel()
                if (_status.value != LogStreamStatus.ERROR) {
                    _status.value = LogStreamStatus.IDLE_DISCONNECTED
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isActiveSocket(webSocket)) return
                val code = response?.code
                Log.w(TAG, "Connection B (/io/logs) failure (HTTP $code, attempt=$attempt): ${t.message}")
                heartbeatJob?.cancel()

                // A-H6 / NFR-REL-04: 1x auto-retry on HTTP 502/503 pre-onOpen (sidecar 90s idle exit race)
                if (!opened.get() && (code == 502 || code == 503) && attempt == 0 && !isDisconnectedExplicitly) {
                    Log.i(TAG, "Sidecar nexa-log restarting (HTTP $code); retrying once in ${SIDECAR_RETRY_DELAY_MS}ms...")
                    synchronized(wsLock) {
                        if (isDisconnectedExplicitly) return
                        retryJob = scope.launch {
                            delay(SIDECAR_RETRY_DELAY_MS)
                            if (!isDisconnectedExplicitly) {
                                connectInternal(coreWsUrl, ticket, currentFilter, attempt = 1)
                            }
                        }
                    }
                    return
                }

                _status.value = LogStreamStatus.ERROR
                _errorMessage.value = when (code) {
                    401, 403 -> "Log session ticket expired or rejected (HTTP $code). Tap Reconnect to verify again."
                    502, 503 -> "Log service is unavailable on server (HTTP $code)."
                    else -> "Log stream disconnected: ${t.message ?: "network error"}"
                }
            }
        }

        synchronized(wsLock) {
            webSocket = okHttpClient.newWebSocket(request, listener)
        }
    }

    fun updateFilterSubscription(preset: LogFilterPreset) {
        currentFilter = preset
        if (webSocket == null) return
        if (!isPaused) {
            pendingFilterResubscribe = false
            // Clear local ring before re-subscribing so server's fresh filtered tail never duplicates entries
            logBuffer.clear()
            consumedSinceLastCredit.set(0)
            sendOpenSubscription(preset)
        } else {
            pendingFilterResubscribe = true
        }
    }

    fun setPaused(paused: Boolean) {
        isPaused = paused
        val ws = webSocket
        if (ws != null && (_status.value == LogStreamStatus.LIVE || _status.value == LogStreamStatus.PAUSED)) {
            if (paused) {
                _status.value = LogStreamStatus.PAUSED
                val zeroCredit = LogBinaryProtocol.encodeCreditFrame(
                    channel = LogBinaryProtocol.CH_LOGS,
                    bytesCredit = 0
                )
                ws.send(zeroCredit.toByteString())
            } else {
                _status.value = LogStreamStatus.LIVE
                consumedSinceLastCredit.set(0)
                if (pendingFilterResubscribe) {
                    pendingFilterResubscribe = false
                    logBuffer.clear()
                    sendOpenSubscription(currentFilter)
                }
                // C3: Always replenish CREDIT on resume (even when resubscribing filter) so older/new sidecars never deadlock at credit=0
                val resumeCredit = LogBinaryProtocol.encodeCreditFrame(
                    channel = LogBinaryProtocol.CH_LOGS,
                    bytesCredit = LogBinaryProtocol.INITIAL_LOG_CREDIT_BYTES
                )
                ws.send(resumeCredit.toByteString())
            }
        }
    }

    fun clearLocalBuffer() {
        logBuffer.clear()
    }

    fun disconnectAndClear() {
        synchronized(wsLock) {
            isDisconnectedExplicitly = true
            retryJob?.cancel()
            retryJob = null
        }
        pendingFilterResubscribe = false
        disconnectSocketOnly()
        logBuffer.clear()
        _status.value = LogStreamStatus.IDLE_DISCONNECTED
        _errorMessage.value = null
    }

    private fun disconnectSocketOnly() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        val ws = synchronized(wsLock) {
            val prev = webSocket
            webSocket = null
            prev
        }
        if (ws != null) {
            try {
                val closeFrame = LogBinaryProtocol.encodeCloseFrame(
                    channel = LogBinaryProtocol.CH_LOGS,
                    reason = LogBinaryProtocol.CLOSE_CLIENT_EXIT
                )
                ws.send(closeFrame.toByteString())
                ws.close(1000, "Logs tab closed or screen off")
            } catch (_: Exception) {
                try {
                    ws.cancel()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun sendOpenSubscription(preset: LogFilterPreset) {
        val ws = webSocket ?: return
        val openFrame = LogBinaryProtocol.encodeLogOpenFrame(
            unitMask = preset.unitMask,
            minPrio = preset.minPrio,
            catMask = preset.catMask,
            tailLines = DEFAULT_TAIL_LINES
        )
        ws.send(openFrame.toByteString())
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        lastPongAtMs.set(System.currentTimeMillis())
        heartbeatJob = scope.launch {
            val pingBytes = LogBinaryProtocol.encodePingFrame().toByteString()
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val ws = webSocket ?: break
                val silentMs = System.currentTimeMillis() - lastPongAtMs.get()
                if (silentMs > HEARTBEAT_PONG_TIMEOUT_MS) {
                    Log.w(TAG, "Connection B (/io/logs) silent for ${silentMs}ms (> ${HEARTBEAT_PONG_TIMEOUT_MS}ms); cancelling dead socket.")
                    ws.cancel()
                    break
                }
                if (!ws.send(pingBytes)) break
            }
        }
    }

    private fun handleBinaryFrame(raw: ByteArray) {
        if (raw.size < 2) return
        val ch = raw[0].toInt() and 0xFF
        val op = raw[1].toInt() and 0xFF
        val payload = raw.copyOfRange(2, raw.size)

        when {
            ch == LogBinaryProtocol.CH_CONTROL && op == LogBinaryProtocol.OP_PONG -> {
                lastPongAtMs.set(System.currentTimeMillis())
            }

            ch == LogBinaryProtocol.CH_LOGS && op == LogBinaryProtocol.OP_DATA -> {
                try {
                    val records = LogBinaryProtocol.decodeLogRecords(payload)
                    logBuffer.pushBatch(records)
                } catch (e: Exception) {
                    Log.w(TAG, "Malformed log DATA frame: ${e.message}")
                } finally {
                    // A-M7: Always account for received payload bytes so malformed frames never leak flow-control credit
                    onBytesConsumed(payload.size)
                }
            }

            (ch == LogBinaryProtocol.CH_LOGS || ch == LogBinaryProtocol.CH_CONTROL) && op == LogBinaryProtocol.OP_ERR -> {
                val code = if (payload.isNotEmpty()) (payload[0].toInt() and 0xFF) else 0
                val msg = if (payload.size > 1) {
                    String(payload, 1, payload.size - 1, Charsets.UTF_8)
                } else {
                    "Log protocol error (#$code)"
                }
                _status.value = LogStreamStatus.ERROR
                _errorMessage.value = "Server rejected log stream (ERR #$code): $msg"
            }

            (ch == LogBinaryProtocol.CH_LOGS || ch == LogBinaryProtocol.CH_CONTROL) && op == LogBinaryProtocol.OP_CLOSE -> {
                val reason = if (payload.isNotEmpty()) (payload[0].toInt() and 0xFF) else 0
                _status.value = LogStreamStatus.IDLE_DISCONNECTED
                if (reason == LogBinaryProtocol.CLOSE_IDLE) {
                    _errorMessage.value = "Log stream closed automatically after being paused for over 5 minutes."
                }
            }
        }
    }

    private fun onBytesConsumed(bytesCount: Int) {
        if (isPaused) return
        val total = consumedSinceLastCredit.addAndGet(bytesCount)
        if (total >= LogBinaryProtocol.CREDIT_REPLENISH_THRESHOLD) {
            val toReplenish = consumedSinceLastCredit.getAndSet(0)
            if (toReplenish > 0) {
                val creditFrame = LogBinaryProtocol.encodeCreditFrame(
                    channel = LogBinaryProtocol.CH_LOGS,
                    bytesCredit = toReplenish
                )
                webSocket?.send(creditFrame.toByteString())
            }
        }
    }
}
