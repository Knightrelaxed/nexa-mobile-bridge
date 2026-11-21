package com.nexa.mobilebridge.data.terminal

import android.util.Log
import com.nexa.mobilebridge.data.logs.LogBinaryProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

enum class TerminalSessionStatus {
    DISCONNECTED,
    AWAITING_BIOMETRIC,
    CONNECTING,
    ACTIVE,
    PAUSED_SCREEN_OFF,
    DETACHED_RECONNECTING,
    DISABLED_BY_SERVER,
    ERROR
}

data class TerminalSessionState(
    val status: TerminalSessionStatus = TerminalSessionStatus.DISCONNECTED,
    val activeSid: String? = null,
    val receivedOffset: Long = 0L,
    val sessionExpiresAtMs: Long = 0L,
    val detachGraceEndsAtMs: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Dedicated Connection B (/io/term) WebSocket client (FR-TERM-04..18, FR-SEC-01, PRD §10.4):
 * - Uses dedicated @Named("sidecar") OkHttpClient (pingInterval = 0) so screen-off never wakes radio
 * - Authenticates via HTTP Upgrade header "Authorization: Bearer <ed25519_ticket>" (scope=term, sid required)
 * - Supports OPEN and seamless RESUME (op=0x09) using [sid: 16B][received_offset: u64 BE] + OFFSET_RESET sync
 * - Enforces 64 KiB CREDIT backpressure, stops heartbeat on screen-off (A-C5), and chunks stdin <= 4 KiB (A-H4)
 * - Performs 1x automatic retry on HTTP 502/503 pre-onOpen to handle systemd 90s idle-exit race (A-H6)
 */
@Singleton
class SidecarTerminalClient @Inject constructor(
    @param:Named("sidecar") private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "SidecarTermClient"
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val HEARTBEAT_PONG_TIMEOUT_MS = 35_000L
        private const val MAX_SESSION_AGE_MS = 4 * 3600_000L
        private const val DETACH_GRACE_MS = 120_000L
        private const val MAX_INPUT_CHUNK_BYTES = 4096
        private const val SIDECAR_RETRY_DELAY_MS = 600L

        fun deriveTerminalEndpointUrl(coreWsUrl: String): String {
            val trimmed = coreWsUrl.trim().trimEnd('/')
            return when {
                trimmed.endsWith("/ws", ignoreCase = true) ->
                    trimmed.dropLast(3) + "/io/term"
                trimmed.contains("/io/term", ignoreCase = true) ->
                    trimmed
                else -> "$trimmed/io/term"
            }
        }
    }

    val emulator = Vt100TerminalEmulator(initialRows = 24, initialCols = 80, maxScrollback = 1000)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wsLock = Any()
    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var heartbeatJob: Job? = null
    @Volatile
    private var retryJob: Job? = null
    @Volatile
    private var isClosedExplicitly: Boolean = false

    @Volatile
    private var isPausedByScreenOff: Boolean = false
    private val consumedSinceLastCredit = AtomicInteger(0)
    // Lock-free authoritative byte counter incremented on every OP_DATA (zero StateFlow recomposition storm)
    private val receivedOffset = AtomicLong(0L)
    private val lastPongAtMs = AtomicLong(0L)
    @Volatile
    private var lastRows: Int = 24
    @Volatile
    private var lastCols: Int = 80

    private val _sessionState = MutableStateFlow(TerminalSessionState())
    val sessionState: StateFlow<TerminalSessionState> = _sessionState.asStateFlow()

    fun setAwaitingBiometric() {
        _sessionState.update {
            it.copy(
                status = TerminalSessionStatus.AWAITING_BIOMETRIC,
                errorMessage = null
            )
        }
    }

    fun setError(message: String, disabledByServer: Boolean = false) {
        _sessionState.update {
            it.copy(
                status = if (disabledByServer) TerminalSessionStatus.DISABLED_BY_SERVER else TerminalSessionStatus.ERROR,
                errorMessage = message
            )
        }
    }

    fun connect(
        coreWsUrl: String,
        ticket: String,
        sid: String,
        rows: Int,
        cols: Int,
        isResume: Boolean
    ) {
        isClosedExplicitly = false
        connectInternal(coreWsUrl, ticket, sid, rows, cols, isResume, attempt = 0)
    }

    private fun isActiveSocket(candidate: WebSocket): Boolean =
        synchronized(wsLock) { webSocket === candidate }

    private fun connectInternal(
        coreWsUrl: String,
        ticket: String,
        sid: String,
        rows: Int,
        cols: Int,
        isResume: Boolean,
        attempt: Int
    ) {
        retryJob?.cancel()
        retryJob = null
        disconnectSocketOnly(sendClose = false)
        lastRows = rows.coerceIn(4, 200)
        lastCols = cols.coerceIn(10, 300)
        emulator.resize(lastRows, lastCols)

        if (!isResume && attempt == 0) {
            emulator.reset()
            receivedOffset.set(0L)
        }

        isPausedByScreenOff = false
        consumedSinceLastCredit.set(0)

        val now = System.currentTimeMillis()
        val prevState = _sessionState.value
        val resumeOffset = if (isResume) receivedOffset.get() else 0L
        val expMs = if (isResume && prevState.sessionExpiresAtMs > now) {
            prevState.sessionExpiresAtMs
        } else {
            now + MAX_SESSION_AGE_MS
        }

        _sessionState.value = TerminalSessionState(
            status = TerminalSessionStatus.CONNECTING,
            activeSid = sid,
            receivedOffset = resumeOffset,
            sessionExpiresAtMs = expMs,
            detachGraceEndsAtMs = 0L,
            errorMessage = null
        )

        val termUrl = deriveTerminalEndpointUrl(coreWsUrl)
        val request = Request.Builder()
            .url(termUrl)
            .addHeader("Authorization", "Bearer $ticket")
            .build()

        val sid16 = TerminalBinaryProtocol.sidTo16Bytes(sid)
        val opened = AtomicBoolean(false)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isActiveSocket(webSocket)) return
                opened.set(true)
                lastPongAtMs.set(System.currentTimeMillis())
                val currentOffset = receivedOffset.get()
                Log.i(TAG, "Connection B (/io/term) opened (resume=$isResume, sid=$sid, offset=$currentOffset)")
                _sessionState.update {
                    it.copy(
                        status = TerminalSessionStatus.ACTIVE,
                        receivedOffset = currentOffset,
                        detachGraceEndsAtMs = 0L,
                        errorMessage = null
                    )
                }

                val initFrame = if (isResume) {
                    TerminalBinaryProtocol.encodeResumeFrame(sid16, currentOffset)
                } else {
                    TerminalBinaryProtocol.encodeOpenFrame(lastRows, lastCols, sid16)
                }
                webSocket.send(initFrame.toByteString())
                if (isResume) {
                    webSocket.send(TerminalBinaryProtocol.encodeResizeFrame(lastRows, lastCols).toByteString())
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
                stopHeartbeat()
                val curr = _sessionState.value
                if (curr.status == TerminalSessionStatus.ACTIVE || curr.status == TerminalSessionStatus.PAUSED_SCREEN_OFF) {
                    markDetached("Koneksi terminal terputus ($code). Ketuk Pulihkan Sesi sebelum masa tenggang habis.")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isActiveSocket(webSocket)) return
                stopHeartbeat()
                val code = response?.code
                Log.w(TAG, "Connection B (/io/term) failure (HTTP $code, attempt=$attempt): ${t.message}")

                // A-H6 / NFR-REL-04: 1x auto-retry on HTTP 502/503 pre-onOpen (sidecar 90s idle exit race)
                if (!opened.get() && (code == 502 || code == 503) && attempt == 0 && !isClosedExplicitly) {
                    Log.i(TAG, "Sidecar nexa-term restarting (HTTP $code); retrying once in ${SIDECAR_RETRY_DELAY_MS}ms...")
                    synchronized(wsLock) {
                        if (isClosedExplicitly) return
                        retryJob = scope.launch {
                            delay(SIDECAR_RETRY_DELAY_MS)
                            if (!isClosedExplicitly) {
                                connectInternal(coreWsUrl, ticket, sid, lastRows, lastCols, isResume, attempt = 1)
                            }
                        }
                    }
                    return
                }

                when (code) {
                    403 -> {
                        _sessionState.update {
                            it.copy(
                                status = TerminalSessionStatus.DISABLED_BY_SERVER,
                                activeSid = null,
                                errorMessage = "Terminal dinonaktifkan di server (kill switch OFF)."
                            )
                        }
                    }
                    401 -> {
                        _sessionState.update {
                            it.copy(
                                status = TerminalSessionStatus.ERROR,
                                errorMessage = "Tiket terminal ditolak atau kedaluwarsa (HTTP 401)."
                            )
                        }
                    }
                    502, 503 -> {
                        _sessionState.update {
                            it.copy(
                                status = TerminalSessionStatus.ERROR,
                                errorMessage = "Daemon sidecar nexa-term belum aktif di server (HTTP $code)."
                            )
                        }
                    }
                    else -> {
                        val curr = _sessionState.value
                        val wasLiveSession = opened.get() &&
                            curr.activeSid != null &&
                            (curr.status == TerminalSessionStatus.ACTIVE || curr.status == TerminalSessionStatus.PAUSED_SCREEN_OFF)
                        if (wasLiveSession) {
                            markDetached("Koneksi terputus (${t.message ?: "jaringan"}). Sesi tetap hidup selama 120 detik.")
                        } else {
                            _sessionState.update {
                                it.copy(
                                    status = TerminalSessionStatus.ERROR,
                                    activeSid = if (!isResume) null else it.activeSid,
                                    errorMessage = "Gagal terhubung ke /io/term: ${t.message ?: "kesalahan jaringan"}"
                                )
                            }
                        }
                    }
                }
            }
        }

        synchronized(wsLock) {
            webSocket = okHttpClient.newWebSocket(request, listener)
        }
    }

    /**
     * A-H4: Chunk outbound stdin payloads to <= 4096 bytes so large pastes never breach
     * the Go sidecar MaxFramePayloadBytes (4 KiB) ceiling.
     */
    fun sendInputBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val ws = webSocket ?: return
        if (_sessionState.value.status != TerminalSessionStatus.ACTIVE) return
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + MAX_INPUT_CHUNK_BYTES, bytes.size)
            val slice = if (offset == 0 && end == bytes.size) bytes else bytes.copyOfRange(offset, end)
            val frame = TerminalBinaryProtocol.encodeDataFrame(slice)
            if (!ws.send(frame.toByteString())) break
            offset = end
        }
    }

    fun sendResize(rows: Int, cols: Int) {
        val r = rows.coerceIn(4, 200)
        val c = cols.coerceIn(10, 300)
        if (r == lastRows && c == lastCols) return
        lastRows = r
        lastCols = c
        emulator.resize(r, c)
        val ws = webSocket ?: return
        if (_sessionState.value.status == TerminalSessionStatus.ACTIVE) {
            ws.send(TerminalBinaryProtocol.encodeResizeFrame(r, c).toByteString())
        }
    }

    /**
     * A-C5 / FR-TERM-13 / NFR-BAT-02: Screen turned off -> stop 15s heartbeat immediately
     * and withhold CREDIT (CREDIT=0) so radio sleeps and Dead-Man Switch (30s detach + 120s grace)
     * works cleanly without draining battery all night.
     */
    fun pauseForScreenOff() {
        val ws = webSocket ?: return
        if (_sessionState.value.status != TerminalSessionStatus.ACTIVE) return
        isPausedByScreenOff = true
        stopHeartbeat()
        _sessionState.update { it.copy(status = TerminalSessionStatus.PAUSED_SCREEN_OFF) }
        ws.send(TerminalBinaryProtocol.encodeCreditFrame(0).toByteString())
    }

    /**
     * Resume output and restart heartbeat when screen wakes up while socket is still alive.
     */
    fun resumeFromScreenOn() {
        val ws = webSocket ?: return
        if (_sessionState.value.status != TerminalSessionStatus.PAUSED_SCREEN_OFF) return
        isPausedByScreenOff = false
        consumedSinceLastCredit.set(0)
        _sessionState.update { it.copy(status = TerminalSessionStatus.ACTIVE) }
        ws.send(TerminalBinaryProtocol.encodeCreditFrame(TerminalBinaryProtocol.INITIAL_TERM_CREDIT_BYTES).toByteString())
        startHeartbeat()
    }

    /**
     * FR-TERM-12: Explicitly terminate remote bash process group (SIGHUP -> SIGKILL) and clear local session.
     */
    fun closeSessionExplicitly() {
        synchronized(wsLock) {
            isClosedExplicitly = true
            retryJob?.cancel()
            retryJob = null
        }
        disconnectSocketOnly(sendClose = true)
        receivedOffset.set(0L)
        emulator.reset()
        _sessionState.value = TerminalSessionState(status = TerminalSessionStatus.DISCONNECTED)
    }

    private fun markDetached(msg: String) {
        val now = System.currentTimeMillis()
        _sessionState.update {
            it.copy(
                status = TerminalSessionStatus.DETACHED_RECONNECTING,
                receivedOffset = receivedOffset.get(),
                detachGraceEndsAtMs = now + DETACH_GRACE_MS,
                errorMessage = msg
            )
        }
    }

    private fun disconnectSocketOnly(sendClose: Boolean) {
        stopHeartbeat()
        val ws = synchronized(wsLock) {
            val prev = webSocket
            webSocket = null
            prev
        }
        if (ws != null) {
            try {
                if (sendClose) {
                    ws.send(TerminalBinaryProtocol.encodeCloseFrame(LogBinaryProtocol.CLOSE_CLIENT_EXIT).toByteString())
                }
                ws.close(1000, "Terminal closed")
            } catch (_: Exception) {
                try {
                    ws.cancel()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        lastPongAtMs.set(System.currentTimeMillis())
        heartbeatJob = scope.launch {
            val pingBytes = TerminalBinaryProtocol.encodePingFrame().toByteString()
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (isPausedByScreenOff) break
                val ws = webSocket ?: break
                val silentMs = System.currentTimeMillis() - lastPongAtMs.get()
                if (silentMs > HEARTBEAT_PONG_TIMEOUT_MS) {
                    Log.w(TAG, "Connection B (/io/term) silent for ${silentMs}ms (> ${HEARTBEAT_PONG_TIMEOUT_MS}ms); cancelling dead socket.")
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

            ch >= TerminalBinaryProtocol.CH_TERM && op == LogBinaryProtocol.OP_DATA -> {
                receivedOffset.addAndGet(payload.size.toLong())
                emulator.feed(payload)
                // Avoid mutating StateFlow on every PTY chunk so MainShell/Hud/Settings never suffer recomposition storms
                onBytesConsumed(payload.size)
            }

            op == LogBinaryProtocol.OP_ERR -> {
                val code = if (payload.isNotEmpty()) (payload[0].toInt() and 0xFF) else 0
                val msg = if (payload.size > 1) {
                    String(payload, 1, payload.size - 1, Charsets.UTF_8)
                } else {
                    "Kesalahan terminal (#$code)"
                }
                if (code == 5) { // ERR_FEATURE_OFF
                    receivedOffset.set(0L)
                    _sessionState.update {
                        it.copy(
                            status = TerminalSessionStatus.DISABLED_BY_SERVER,
                            activeSid = null,
                            receivedOffset = 0L,
                            errorMessage = "Terminal dinonaktifkan di server."
                        )
                    }
                } else if (code == 7 && msg.startsWith("OFFSET_RESET:")) {
                    // A-H5 / G-H5 / C1: Server ring overflowed during detach; synchronize exact offset & reset emulator
                    val authoritativeOffset = msg.removePrefix("OFFSET_RESET:").trim().toLongOrNull() ?: 0L
                    receivedOffset.set(authoritativeOffset)
                    emulator.reset()
                    consumedSinceLastCredit.set(0)
                    _sessionState.update {
                        it.copy(
                            receivedOffset = authoritativeOffset,
                            errorMessage = null
                        )
                    }
                } else if (code == 7) { // ERR_RESUME_FAILED (session expired/missing)
                    receivedOffset.set(0L)
                    _sessionState.update {
                        it.copy(
                            status = TerminalSessionStatus.ERROR,
                            activeSid = null,
                            receivedOffset = 0L,
                            errorMessage = "Sesi sebelumnya sudah berakhir ($msg). Mulai sesi baru."
                        )
                    }
                } else {
                    _sessionState.update {
                        it.copy(
                            status = TerminalSessionStatus.ERROR,
                            activeSid = if (receivedOffset.get() == 0L) null else it.activeSid,
                            errorMessage = "ERR #$code: $msg"
                        )
                    }
                }
            }

            op == LogBinaryProtocol.OP_CLOSE -> {
                val reason = if (payload.isNotEmpty()) (payload[0].toInt() and 0xFF) else 0
                stopHeartbeat()
                receivedOffset.set(0L)
                val notice = when (reason) {
                    3 -> "Batas durasi sesi 4 jam tercapai. Lakukan otentikasi sidik jari ulang."
                    5 -> "Server sidecar sedang dihentikan atau dimulai ulang."
                    6 -> "Masa tenggang resume 120 detik berakhir. Proses bash telah dibersihkan."
                    else -> "Sesi terminal ditutup oleh server."
                }
                _sessionState.value = TerminalSessionState(
                    status = TerminalSessionStatus.DISCONNECTED,
                    activeSid = null,
                    receivedOffset = 0L,
                    errorMessage = notice
                )
            }
        }
    }

    private fun onBytesConsumed(bytesCount: Int) {
        if (isPausedByScreenOff) return
        val total = consumedSinceLastCredit.addAndGet(bytesCount)
        if (total >= TerminalBinaryProtocol.CREDIT_REPLENISH_THRESHOLD) {
            val replenish = consumedSinceLastCredit.getAndSet(0)
            if (replenish > 0) {
                webSocket?.send(TerminalBinaryProtocol.encodeCreditFrame(replenish).toByteString())
            }
        }
    }
}
