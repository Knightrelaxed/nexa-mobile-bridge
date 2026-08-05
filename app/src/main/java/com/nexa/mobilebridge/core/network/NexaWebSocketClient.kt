package com.nexa.mobilebridge.core.network

import android.util.Log
import com.nexa.mobilebridge.core.security.HmacVerifier
import com.nexa.mobilebridge.core.util.ConnectionState
import com.nexa.mobilebridge.protocol.NexaCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kotlin.math.pow

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
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private var currentUrl: String = ""
    private var currentToken: String = ""
    private var hmacSecret: String = ""

    private var reconnectAttempt = 0
    private val INITIAL_DELAY_MS = 1000L
    private val MAX_DELAY_MS = 30000L

    fun setHmacSecret(secret: String) {
        hmacSecret = secret
    }

    fun connect(url: String, token: String) {
        if (_connectionState.value is ConnectionState.Connected) return

        currentUrl = url
        currentToken = token
        reconnectAttempt = 0

        initiateConnection()
    }

    private fun initiateConnection() {
        if (currentUrl.isBlank()) return

        reconnectJob?.cancel()
        _connectionState.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url(currentUrl)
            .addHeader("Authorization", "Bearer $currentToken")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket Opened")
                reconnectAttempt = 0
                _connectionState.value = ConnectionState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val command = json.decodeFromString<NexaCommand>(text)
                    // Verify HMAC if signature is present (GodMode actions)
                    if (command.signature != null && hmacSecret.isNotBlank()) {
                        // Level is extracted from params if available, default 3 for GodMode
                        val level = command.params?.get("level")
                            ?.toString()?.toIntOrNull() ?: 3
                        val isValid = HmacVerifier.verify(command.timestamp, level, command.signature, hmacSecret)
                        if (!isValid) {
                            Log.e(TAG, "Invalid HMAC signature for command: ${command.command_id}")
                            return
                        }
                    }

                    scope.launch {
                        _incomingCommands.emit(command)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing incoming message: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket Closed: $reason")
                _connectionState.value = ConnectionState.Disconnected(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure: ${t.message}")
                _connectionState.value = ConnectionState.Disconnected(t.message)
                scheduleReconnect()
            }
        })
    }

    fun send(payload: String): Boolean {
        val ws = webSocket
        return if (ws != null && _connectionState.value is ConnectionState.Connected) {
            ws.send(payload)
        } else {
            Log.w(TAG, "Cannot send — WebSocket not connected.")
            false
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected("Disconnected by user")
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempt++
            // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s max
            val delayMs = (INITIAL_DELAY_MS * 2.0.pow((reconnectAttempt - 1).toDouble()))
                .toLong()
                .coerceAtMost(MAX_DELAY_MS)

            _connectionState.value = ConnectionState.Reconnecting(reconnectAttempt, delayMs)
            Log.i(TAG, "Reconnecting in ${delayMs}ms (Attempt $reconnectAttempt)")

            delay(delayMs)
            initiateConnection()
        }
    }
}
