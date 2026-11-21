package com.nexa.mobilebridge.core.network

import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexa.mobilebridge.core.security.HmacVerifier
import com.nexa.mobilebridge.core.util.ConnectionState
import com.nexa.mobilebridge.protocol.NexaCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

interface LiveCallEventListener {
    fun onAudioPlay(pcmChunk: String)
    fun onAudioInterrupted()
    fun onLiveReady()
    fun onStatusUpdate(status: String?, text: String?, userTranscript: String?)
    fun onCallReplyComplete(replyText: String?)
    fun onCallError(error: String?)
}

@Singleton
class NexaWebSocketClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    private val TAG = "NexaWS"

    companion object {
        private val CALL_EVENT_TYPES = setOf(
            "CALL_AUDIO_PLAY",
            "CALL_AUDIO_INTERRUPTED",
            "CALL_LIVE_READY",
            "CALL_STATUS_UPDATE",
            "CALL_REPLY_COMPLETE",
            "CALL_ERROR"
        )

        private val AUTH_EVENT_TYPES = setOf(
            "CHALLENGE",
            "TICKET",
            "AUTH_ERR",
            "ENROLL_OK",
            "ENROLL_ERR",
            "PONG"
        )

        private val CHAT_EVENT_TYPES = setOf(
            "CHAT_ACK",
            "CHAT_CHUNK",
            "CHAT_RESUME_MISS",
            "CHAT_PROACTIVE",
            "ACTION_PENDING",
            "ACTION_RESULT"
        )

        fun fastExtractPcmChunk(text: String): String? {
            val key = "\"pcm_chunk\":"
            val idx = text.indexOf(key)
            if (idx == -1) return null
            val quoteStart = text.indexOf('"', idx + key.length)
            if (quoteStart == -1) return null
            val quoteEnd = text.indexOf('"', quoteStart + 1)
            if (quoteEnd == -1) return null
            return text.substring(quoteStart + 1, quoteEnd)
        }
    }

    @Volatile
    var liveCallListener: LiveCallEventListener? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected(null))
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingCommands = MutableSharedFlow<NexaCommand>(extraBufferCapacity = 64)
    val incomingCommands: SharedFlow<NexaCommand> = _incomingCommands.asSharedFlow()

    private val _incomingAuthEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingAuthEvents: SharedFlow<String> = _incomingAuthEvents.asSharedFlow()

    private val _incomingChatEvents = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val incomingChatEvents: SharedFlow<String> = _incomingChatEvents.asSharedFlow()

    // A-H1: Guard socket assignment & identity checks with wsLock + @Volatile
    private val wsLock = Any()
    @Volatile
    private var webSocket: WebSocket? = null

    // A-C2: SupervisorJob prevents child failure from cancelling the entire WS scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A-C2: Strict FIFO single-consumer channels preserve exact wire order for CHAT_CHUNK seq 1..N & STREAM_FINAL
    private val chatDispatchChannel = Channel<String>(capacity = 512)
    private val authDispatchChannel = Channel<String>(capacity = 128)
    private val cmdDispatchChannel = Channel<NexaCommand>(capacity = 128)

    @Volatile
    private var currentUrl: String = ""
    @Volatile
    private var currentToken: String = ""
    @Volatile
    private var hmacSecret: String = ""

    init {
        scope.launch {
            for (raw in chatDispatchChannel) {
                _incomingChatEvents.emit(raw)
            }
        }
        scope.launch {
            for (raw in authDispatchChannel) {
                _incomingAuthEvents.emit(raw)
            }
        }
        scope.launch {
            for (cmd in cmdDispatchChannel) {
                _incomingCommands.emit(cmd)
            }
        }
    }

    private val orchestrator: ReconnectOrchestrator by lazy {
        ReconnectOrchestrator(scope) { initiateConnection() }
    }

    /** SharedFlow kegagalan fase yang di-subscribe oleh NexaBridgeService untuk notifikasi & TTS. */
    val phaseFailureEvents: SharedFlow<PhaseFailureEvent>
        get() = orchestrator.phaseFailureEvents

    fun setHmacSecret(secret: String) {
        hmacSecret = secret
    }

    fun forceReconnect(url: String = currentUrl, token: String = currentToken) {
        if (url.isNotBlank()) currentUrl = url
        if (token.isNotBlank()) currentToken = token
        Log.i(TAG, "forceReconnect dipanggil: mereset orchestrator dan memulai koneksi baru.")
        orchestrator.reset()
        initiateConnection()
    }

    fun onNetworkLost() {
        Log.w(TAG, "onNetworkLost: Membatalkan socket aktif dan menjeda retry karena jaringan terputus.")
        orchestrator.stop()
        val oldWs = synchronized(wsLock) {
            val prev = webSocket
            webSocket = null
            prev
        }
        try {
            oldWs?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling webSocket on network lost: ${e.message}")
        }
        _connectionState.value = ConnectionState.Disconnected("Jaringan terputus")
    }

    fun connect(url: String, token: String) {
        if (_connectionState.value is ConnectionState.Connected) return
        currentUrl = url
        currentToken = token
        orchestrator.reset()
        initiateConnection()
    }

    private fun isActiveSocket(candidate: WebSocket): Boolean =
        synchronized(wsLock) { webSocket === candidate }

    private fun initiateConnection() {
        if (orchestrator.isStopped) return
        val targetUrl = currentUrl
        val targetToken = currentToken
        if (targetUrl.isBlank()) return

        val prevWs = synchronized(wsLock) {
            val prev = webSocket
            webSocket = null
            prev
        }
        try {
            prevWs?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling previous webSocket: ${e.message}")
        }

        val trimmedUrl = targetUrl.trim()
        if (!trimmedUrl.startsWith("wss://", ignoreCase = true) && !trimmedUrl.startsWith("ws://", ignoreCase = true)) {
            Log.e(TAG, "URL WebSocket tidak valid (harus wss:// atau ws://): $trimmedUrl")
            _connectionState.value = ConnectionState.Disconnected("URL WebSocket tidak valid")
            return
        }

        _connectionState.value = ConnectionState.Connecting

        val request = try {
            Request.Builder()
                .url(trimmedUrl)
                .addHeader("Authorization", "Bearer $targetToken")
                .build()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Malformed WebSocket URL: ${e.message}")
            _connectionState.value = ConnectionState.Disconnected("URL server tidak valid")
            return
        }

        val listener = object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isActiveSocket(webSocket)) return
                Log.i(TAG, "WebSocket Terbuka")
                orchestrator.onConnectionSuccess()
                _connectionState.value = ConnectionState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isActiveSocket(webSocket)) return

                // A-C3 / A-H11: Fast-path ONLY for actual top-level CALL_AUDIO_PLAY frames;
                // otherwise parse top-level JSON "type" strictly so AI chat replies mentioning "CALL_ERROR" are never hijacked.
                val trimmed = text.trimStart()
                if (trimmed.startsWith("{\"type\":\"CALL_AUDIO_PLAY\"") ||
                    trimmed.startsWith("{\"type\": \"CALL_AUDIO_PLAY\"")
                ) {
                    val listenerNow = liveCallListener
                    if (listenerNow != null) {
                        val pcm = fastExtractPcmChunk(text)
                        if (pcm != null) listenerNow.onAudioPlay(pcm)
                    } else {
                        broadcastCallLiveEvent(text)
                    }
                    return
                }

                val jsonObj = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Gagal mem-parse pesan JSON masuk: ${e.message}")
                    return
                }
                val msgType = jsonObj.optString("type", "")

                // 1. Live Voice Call Events routed strictly by exact top-level type
                if (msgType in CALL_EVENT_TYPES) {
                    val currentListener = liveCallListener
                    if (currentListener != null) {
                        when (msgType) {
                            "CALL_AUDIO_PLAY" -> {
                                val pcm = jsonObj.optString("pcm_chunk", "").takeIf { it.isNotEmpty() }
                                    ?: fastExtractPcmChunk(text)
                                if (pcm != null) currentListener.onAudioPlay(pcm)
                            }
                            "CALL_AUDIO_INTERRUPTED" -> currentListener.onAudioInterrupted()
                            "CALL_LIVE_READY" -> currentListener.onLiveReady()
                            "CALL_STATUS_UPDATE" -> {
                                val status = if (jsonObj.has("status") && !jsonObj.isNull("status")) jsonObj.optString("status") else null
                                val txt = if (jsonObj.has("text") && !jsonObj.isNull("text")) jsonObj.optString("text") else null
                                val userTr = if (jsonObj.has("user_transcript") && !jsonObj.isNull("user_transcript")) jsonObj.optString("user_transcript") else null
                                currentListener.onStatusUpdate(status, txt, userTr)
                            }
                            "CALL_REPLY_COMPLETE" -> {
                                val replyText = if (jsonObj.has("reply_text") && !jsonObj.isNull("reply_text")) jsonObj.optString("reply_text") else null
                                currentListener.onCallReplyComplete(replyText)
                            }
                            "CALL_ERROR" -> {
                                val err = when {
                                    jsonObj.has("error") && !jsonObj.isNull("error") -> jsonObj.optString("error")
                                    jsonObj.has("message") && !jsonObj.isNull("message") -> jsonObj.optString("message")
                                    else -> null
                                }
                                currentListener.onCallError(err)
                            }
                        }
                    } else {
                        broadcastCallLiveEvent(text)
                    }
                    return
                }

                // 2. Hardware-Backed Auth / Enrollment / Ticket JSON Events (Connection A - FR-AUTH-01..05)
                if (msgType in AUTH_EVENT_TYPES) {
                    if (msgType != "PONG") {
                        if (!_incomingAuthEvents.tryEmit(text)) {
                            authDispatchChannel.trySend(text)
                        }
                    }
                    return
                }

                // 3. Native AI Chat & Finance Step-Up JSON Events (strict FIFO ordering, A-C2)
                if (msgType in CHAT_EVENT_TYPES) {
                    chatDispatchChannel.trySend(text)
                    return
                }

                // 4. Device Control Commands (EXECUTE_COMMAND)
                try {
                    val command = json.decodeFromString<NexaCommand>(text)
                    val secret = hmacSecret
                    if (secret.isNotBlank()) {
                        val level = command.params?.get("level")?.jsonPrimitive?.intOrNull ?: 3
                        val isValid = HmacVerifier.verifyCommand(
                            commandId = command.command_id,
                            action = command.action,
                            timestamp = command.timestamp,
                            level = level,
                            receivedSignature = command.signature,
                            secret = secret
                        )
                        if (!isValid) {
                            Log.e(TAG, "HMAC signature ditolak (fail-closed): ${command.command_id}")
                            return
                        }
                    }
                    cmdDispatchChannel.trySend(command)
                } catch (e: Exception) {
                    Log.e(TAG, "Gagal mem-parse NexaCommand masuk: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isActiveSocket(webSocket)) return
                Log.i(TAG, "WebSocket Ditutup ($code): $reason")
                _connectionState.value = ConnectionState.Disconnected(reason)
                if (!orchestrator.isStopped) {
                    orchestrator.onConnectionFailed(Exception("WebSocket ditutup oleh server ($code: $reason)"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isActiveSocket(webSocket)) {
                    Log.d(TAG, "Mengabaikan onFailure dari socket usang: ${t.message}")
                    return
                }
                val httpCode = response?.code
                if (httpCode == 401 || httpCode == 403) {
                    Log.e(TAG, "Autentikasi WebSocket ditolak (HTTP $httpCode). Menghentikan retry otomatis.")
                    orchestrator.stop()
                    _connectionState.value = ConnectionState.AuthFailed("Autentikasi ditolak server (HTTP $httpCode)")
                    return
                }
                Log.e(TAG, "WebSocket Gagal: ${t.javaClass.simpleName} (${t.message})")
                _connectionState.value = ConnectionState.Disconnected(t.message)
                orchestrator.onConnectionFailed(t)
            }
        }

        // A-H1: Assign webSocket inside wsLock so fast onOpen/onFailure never sees stale null reference
        synchronized(wsLock) {
            webSocket = okHttpClient.newWebSocket(request, listener)
        }
    }

    private fun broadcastCallLiveEvent(text: String) {
        val intent = Intent("com.nexa.mobilebridge.CALL_LIVE_EVENT").apply {
            putExtra("raw_json", text)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    fun send(payload: String): Boolean {
        val ws = webSocket
        return if (ws != null && _connectionState.value is ConnectionState.Connected) {
            ws.send(payload)
        } else {
            Log.w(TAG, "Tidak bisa kirim: WebSocket tidak terkoneksi.")
            false
        }
    }

    fun disconnect() {
        orchestrator.stop()
        val oldWs = synchronized(wsLock) {
            val prev = webSocket
            webSocket = null
            prev
        }
        try {
            oldWs?.cancel()
        } catch (_: Exception) {}
        _connectionState.value = ConnectionState.Disconnected("Disconnected by user")
    }

    fun isConnected(): Boolean = _connectionState.value is ConnectionState.Connected

    fun getQueueSize(): Long = webSocket?.queueSize() ?: 0L

    fun resetOrchestrator() {
        orchestrator.reset()
        Log.i(TAG, "Orchestrator direset oleh NetworkWatcher.")
    }
}
