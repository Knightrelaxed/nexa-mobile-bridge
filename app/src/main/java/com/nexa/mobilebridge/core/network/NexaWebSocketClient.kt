package com.nexa.mobilebridge.core.network

import android.util.Log
import com.nexa.mobilebridge.core.security.HmacVerifier
import com.nexa.mobilebridge.core.util.ConnectionState
import com.nexa.mobilebridge.protocol.NexaCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NexaWebSocketClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    private val TAG = "NexaWS"

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected(null))
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingCommands = MutableSharedFlow<NexaCommand>(extraBufferCapacity = 64)
    val incomingCommands: SharedFlow<NexaCommand> = _incomingCommands.asSharedFlow()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private var currentUrl: String = ""
    private var currentToken: String = ""
    private var hmacSecret: String = ""

    // ─────────────────────────────────────────────
    // ReconnectOrchestrator — delegasi semua logika retry ke sini
    // ─────────────────────────────────────────────
    private val orchestrator: ReconnectOrchestrator by lazy {
        ReconnectOrchestrator(scope) { initiateConnection() }
    }

    /** SharedFlow kegagalan fase — di-subscribe oleh NexaBridgeService untuk notifikasi & TTS. */
    val phaseFailureEvents: SharedFlow<PhaseFailureEvent>
        get() = orchestrator.phaseFailureEvents

    fun setHmacSecret(secret: String) {
        hmacSecret = secret
    }

    fun connect(url: String, token: String) {
        if (_connectionState.value is ConnectionState.Connected) return

        currentUrl = url
        currentToken = token
        // Reset orchestrator agar sesi koneksi baru bisa berjalan,
        // terutama penting setelah service di-restart oleh user.
        orchestrator.reset()
        initiateConnection()
    }

    private fun initiateConnection() {
        if (orchestrator.isStopped) return
        if (currentUrl.isBlank()) return

        // 1. Explicitly cancel previous socket instance to prevent ghost socket race conditions
        try {
            webSocket?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling previous webSocket: ${e.message}")
        }
        webSocket = null

        _connectionState.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url(currentUrl)
            .addHeader("Authorization", "Bearer $currentToken")
            .build()

        val listener = object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== this@NexaWebSocketClient.webSocket) return // Ignore stale socket
                Log.i(TAG, "✅ WebSocket Terbuka")
                orchestrator.onConnectionSuccess()
                _connectionState.value = ConnectionState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== this@NexaWebSocketClient.webSocket) return // Ignore stale socket
                try {
                    val command = json.decodeFromString<NexaCommand>(text)

                    // Verifikasi HMAC jika signature hadir (GodMode)
                    if (command.signature != null && hmacSecret.isNotBlank()) {
                        val level = command.params?.get("level")
                            ?.toString()?.toIntOrNull() ?: 3
                        val isValid = HmacVerifier.verify(
                            command.timestamp, level, command.signature, hmacSecret
                        )
                        if (!isValid) {
                            Log.e(TAG, "❌ HMAC signature tidak valid: ${command.command_id}")
                            return
                        }
                    }

                    scope.launch { _incomingCommands.emit(command) }

                } catch (e: Exception) {
                    Log.e(TAG, "Gagal mem-parse pesan masuk: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@NexaWebSocketClient.webSocket) return // Ignore stale socket
                Log.i(TAG, "WebSocket Ditutup ($code): $reason")
                _connectionState.value = ConnectionState.Disconnected(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== this@NexaWebSocketClient.webSocket) {
                    Log.d(TAG, "Mengabaikan onFailure dari socket usang: ${t.message}")
                    return
                }
                Log.e(TAG, "❌ WebSocket Gagal: ${t.javaClass.simpleName} — ${t.message}")
                _connectionState.value = ConnectionState.Disconnected(t.message)
                // Delegasikan ke orchestrator untuk menentukan kapan dan apakah harus reconnect
                orchestrator.onConnectionFailed(t)
            }
        }

        webSocket = okHttpClient.newWebSocket(request, listener)
    }

    fun send(payload: String): Boolean {
        val ws = webSocket
        return if (ws != null && _connectionState.value is ConnectionState.Connected) {
            ws.send(payload)
        } else {
            Log.w(TAG, "Tidak bisa kirim — WebSocket tidak terkoneksi.")
            false
        }
    }

    fun disconnect() {
        orchestrator.stop()
        try {
            webSocket?.cancel()
        } catch (_: Exception) {}
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected("Disconnected by user")
    }

    /** Mengembalikan true jika WebSocket saat ini dalam status Connected. */
    fun isConnected(): Boolean = _connectionState.value is ConnectionState.Connected

    /**
     * Mereset ReconnectOrchestrator ke fase 1 tanpa mengubah konfigurasi koneksi.
     * Digunakan oleh NetworkWatcher saat jaringan kembali tersedia — agar
     * orchestrator tidak melanjutkan delay fase yang sedang berjalan.
     */
    fun resetOrchestrator() {
        orchestrator.reset()
        Log.i(TAG, "🔄 Orchestrator direset oleh NetworkWatcher.")
    }
}
