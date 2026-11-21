package com.nexa.mobilebridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexa.mobilebridge.core.context.ContextEngine
import com.nexa.mobilebridge.core.network.NexaWebSocketClient
import com.nexa.mobilebridge.core.network.PhaseFailureEvent
import com.nexa.mobilebridge.core.network.ReconnectErrorType
import com.nexa.mobilebridge.core.power.BridgePowerLockManager
import com.nexa.mobilebridge.core.security.SecretKeyStoreCipher
import com.nexa.mobilebridge.core.sensor.GeofenceManager
import com.nexa.mobilebridge.core.sensor.SensorObserverManager
import com.nexa.mobilebridge.core.usage.AppUsageTracker
import com.nexa.mobilebridge.data.local.NexaPreferencesDataStore
import com.nexa.mobilebridge.dispatcher.DeviceCommandDispatcher
import com.nexa.mobilebridge.dispatcher.handler.BatteryHandler
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.ContextReport
import com.nexa.mobilebridge.protocol.NexaCommand
import com.nexa.mobilebridge.protocol.ResultStatus
import com.nexa.mobilebridge.protocol.TelemetryReport
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class NexaBridgeService : Service() {

    companion object {
        const val NOTIF_SERVICE_ID = 1001

        /** Weak reference to the active WebSocket client for sending call events from Activities. */
        @Volatile var activeWebSocketClient: NexaWebSocketClient? = null
        @Volatile private var activeJson: Json? = null
        @Volatile var instance: NexaBridgeService? = null

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _isPowerLockActive = MutableStateFlow(false)
        val isPowerLockActive: StateFlow<Boolean> = _isPowerLockActive.asStateFlow()

        private val _latestTelemetry = MutableStateFlow<TelemetryReport?>(null)
        val latestTelemetry: StateFlow<TelemetryReport?> = _latestTelemetry.asStateFlow()

        private val _commandHistory = MutableStateFlow<List<CommandHistoryRecord>>(emptyList())
        val commandHistory: StateFlow<List<CommandHistoryRecord>> = _commandHistory.asStateFlow()

        internal fun recordCommandResult(
            command: NexaCommand,
            result: CommandResult,
            durationMs: Long
        ) {
            val upper = command.action.uppercase()
            val isSensitive = upper.contains("FINANCE") ||
                upper.contains("AUTH") ||
                upper.contains("OTP") ||
                upper.contains("CLIPBOARD") ||
                upper.contains("BANK")

            val summary = when {
                isSensitive -> "[Redacted]"
                result.status == ResultStatus.SUCCESS -> summarizeResultMessage(result.message)
                else -> "Failed"
            }

            val record = CommandHistoryRecord(
                id = command.command_id,
                action = command.action,
                summary = summary,
                isSuccess = result.status == ResultStatus.SUCCESS,
                durationMs = durationMs.coerceAtLeast(1L),
                timestamp = System.currentTimeMillis(),
                isSensitive = isSensitive
            )
            _commandHistory.update { curr -> (listOf(record) + curr).take(20) }
        }

        private fun summarizeResultMessage(message: String): String {
            val clean = message.trim()
            if (clean.isEmpty()) return "Done"
            return if (clean.length <= 18) clean else clean.take(15) + "..."
        }

        /**
         * Dynamically promote or demote this foreground service with FOREGROUND_SERVICE_TYPE_MICROPHONE.
         * Also acquires/releases the on-demand call power lock strictly for active voice calls (FR-CORE-04).
         */
        fun enableCallMicrophone(enable: Boolean) {
            val s = instance ?: return
            if (enable && ContextCompat.checkSelfPermission(s, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.w("NexaBridge", "Skipping MICROPHONE FGS promotion: RECORD_AUDIO permission missing.")
                return
            }
            val prevMicState = s.isCallMicActive
            s.isCallMicActive = enable
            try {
                val notif = s.buildServiceNotification()
                val types = s.resolveForegroundServiceTypes()
                s.startForeground(NOTIF_SERVICE_ID, notif, types)
                s.powerLockManager?.setCallLock(enable)
                Log.i("NexaBridge", "Foreground service microphone state updated: enable=$enable")
            } catch (e: Exception) {
                // Roll back state and power lock so failed promotion never leaks 30-min WakeLock/WifiLock
                s.isCallMicActive = prevMicState
                s.powerLockManager?.setCallLock(prevMicState)
                Log.w("NexaBridge", "Failed to update FGS microphone state (rolled back): ${e.message}")
            }
        }

        fun getWebSocketQueueSize(): Long = activeWebSocketClient?.getQueueSize() ?: 0L

        /**
         * Send a raw JSON payload to the N.E.X.A server via the active WebSocket connection.
         * Safe to call from any thread or Activity (e.g., FakeCallActivity).
         */
        fun sendEvent(payload: String): Boolean {
            return activeWebSocketClient?.send(payload) ?: false
        }

        fun isConnected(): Boolean {
            return activeWebSocketClient?.connectionState?.value is com.nexa.mobilebridge.core.util.ConnectionState.Connected
        }

        fun reconnect() {
            activeWebSocketClient?.forceReconnect()
        }

        fun encodeJson(): Json? = activeJson
    }

    @Inject lateinit var webSocketClient: NexaWebSocketClient
    @Inject lateinit var chatRepository: com.nexa.mobilebridge.data.chat.ChatRepository
    @Inject lateinit var chatNotificationManager: com.nexa.mobilebridge.notification.ChatNotificationManager
    @Inject lateinit var dispatcher: DeviceCommandDispatcher
    @Inject lateinit var dataStore: DataStore<Preferences>
    @Inject lateinit var json: Json

    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var powerLockManager: BridgePowerLockManager? = null

    // Notification Channels
    private val CHANNEL_SERVICE_ID = "nexa_bridge_channel_01"
    private val CHANNEL_ALERT_ID = "nexa_bridge_alert_01"
    private val NOTIF_ALERT_ID = 1002

    // Alert TTS
    private var alertTts: TextToSpeech? = null
    @Volatile
    private var alertTtsReady = false

    @Volatile
    private var isCallMicActive = false
    private var isServiceStarted = false
    private var sensorObserverManager: SensorObserverManager? = null
    private var contextEngine: ContextEngine? = null
    private var geofenceManager: GeofenceManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkDebounceJob: Job? = null
    private var isScreenReceiverRegistered = false

    private fun resolveForegroundServiceTypes(): Int {
        // A-H13: Preserve MICROPHONE FGS type across subsequent onStartCommand calls while a live call is active
        return if (isCallMicActive) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d("NexaBridge", "Screen broadcast received: $action (on-demand only, not pushed to WS)")
        }
    }

    private fun registerScreenStateReceiver() {
        if (isScreenReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenStateReceiver, filter)
            isScreenReceiverRegistered = true
            Log.i("NexaBridge", "ScreenStateReceiver registered successfully")
        } catch (e: Exception) {
            Log.e("NexaBridge", "Failed to register ScreenStateReceiver: ${e.message}")
        }
    }

    private fun unregisterScreenStateReceiver() {
        if (isScreenReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
                isScreenReceiverRegistered = false
                Log.i("NexaBridge", "ScreenStateReceiver unregistered")
            } catch (e: Exception) {
                Log.w("NexaBridge", "Error unregistering ScreenStateReceiver: ${e.message}")
            }
        }
    }

    private fun isScreenInteractive(): Boolean {
        return try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isInteractive ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        createNotificationChannels()

        // FR-CORE-04: On-demand power lock manager (NO permanent WakeLock/WifiLock held on startup)
        powerLockManager = BridgePowerLockManager.create(this).also { manager ->
            serviceScope.launch {
                manager.isLocked.collect { locked ->
                    _isPowerLockActive.value = locked
                }
            }
        }

        initAlertTts()
        registerScreenStateReceiver()
        activeWebSocketClient = webSocketClient
        activeJson = json
        instance = this
        _isServiceRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = buildServiceNotification()
            startForeground(NOTIF_SERVICE_ID, notification, resolveForegroundServiceTypes())
        } catch (e: Exception) {
            Log.e("NexaBridge", "Failed to start foreground notification: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isServiceStarted) {
            isServiceStarted = true
            _isServiceRunning.value = true
            Log.i("NexaBridge", "Service Dimulai di Foreground (Power Locks: On-Demand)")

            serviceScope.launch {
                val config = loadConfig()
                webSocketClient.setHmacSecret(config.hmacSecret)

                // 1. Sambungkan WebSocket
                webSocketClient.connect(config.serverUrl, config.authToken)

                // 2. Mulai konsumer perintah dari server
                launchCommandConsumer()

                // 3. Mulai pelaporan telemetri periodik (diff-driven + 3-menit heartbeat)
                launchTelemetryReporter()

                // 4. Mulai pendengar event kegagalan fase reconnect
                launchPhaseFailureListener()

                // 5. Inisialisasi SensorObserverManager & ContextEngine
                sensorObserverManager = SensorObserverManager(this@NexaBridgeService, serviceScope).apply {
                    startObserving()
                }
                contextEngine = ContextEngine(serviceScope)

                // 6. Inisialisasi GeofenceManager & muat semua geofence aktif
                geofenceManager = GeofenceManager(this@NexaBridgeService).apply {
                    loadAndRegisterAll()
                }

                // 7. Mulai pendengar event sensor & sintesis konteks
                launchSensorObserverListener()

                // 8. Mulai Network-Availability Watcher (selalu membaca config terbaru dari DataStore, A-H14)
                registerNetworkWatcher()
            }
        }

        // Handle Intent for Geofence
        if (intent?.action == "ACTION_GEOFENCE_EVENT") {
            val geofenceId = intent.getStringExtra("geofence_id")
            val transition = intent.getStringExtra("transition")
            if (geofenceId != null && transition != null) {
                contextEngine?.processGeofenceEvent(geofenceId, transition)
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // A-H13: Android 16 (API 36) forbids starting a background service via PendingIntent.getService().
        // START_STICKY + ForegroundService + BootCompletedReceiver handle persistence legally.
        Log.i("NexaBridge", "Aplikasi dihapus dari Recent Apps; FGS tetap aktif via START_STICKY.")
    }

    // Phase Failure Listener
    private fun launchPhaseFailureListener() = serviceScope.launch {
        webSocketClient.phaseFailureEvents.collect { event ->
            Log.w("NexaBridge", "PhaseFailureEvent: Fase ${event.phase}, Final: ${event.isFinal}")
            val (notifTitle, notifBody) = buildAlertNotifContent(event)
            val ttsMessage = buildAlertTtsMessage(event)
            postAlertNotification(notifTitle, notifBody)
            speakAlert(ttsMessage)
        }
    }

    // Sensor & Context Engine Listener
    private fun launchSensorObserverListener() = serviceScope.launch {
        val manager = sensorObserverManager ?: return@launch
        val engine = contextEngine ?: return@launch

        launch {
            manager.sensorEvents.collect { sensorEvent ->
                engine.processSensorEvent(sensorEvent)
            }
        }

        launch {
            engine.contextReports.collect { report ->
                if (report.event == "PHONE_PICKUP" || report.event == "PICKUP_MORNING" ||
                    report.event == "LIGHT_CHANGED" || report.event == "ROOM_DARK_NIGHT" ||
                    report.event == "WALKING_AT_NIGHT") {
                    Log.d("NexaBridge", "ContextReport [${report.event}] disaring (hemat daya, tidak dikirim ke WS).")
                    return@collect
                }
                val payload = json.encodeToString(ContextReport.serializer(), report)
                val sent = webSocketClient.send(payload)
                Log.d("NexaBridge", "ContextReport terkirim (success=$sent): ${report.event}")
            }
        }
    }

    // Network Watcher (A-H14: always reload fresh config from DataStore on reconnect)
    private fun registerNetworkWatcher() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                triggerInstantReconnect("onAvailable")
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    triggerInstantReconnect("onCapabilitiesChanged (Internet Ready)")
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                triggerInstantReconnect("onLinkPropertiesChanged (IP Route Assigned)")
            }

            override fun onLost(network: Network) {
                Log.w("NexaBridge", "Jaringan terputus (onLost). Memeriksa sisa jaringan...")
                val activeNetwork = cm.activeNetwork
                val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
                val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                if (!hasInternet) {
                    Log.w("NexaBridge", "Tidak ada koneksi internet aktif. Memutuskan socket.")
                    webSocketClient.onNetworkLost()
                } else {
                    Log.i("NexaBridge", "Jaringan beralih ke rute alternatif. Memicu reconnect...")
                    triggerInstantReconnect("onLost (Network Switch)", force = true)
                }
            }

            private fun triggerInstantReconnect(source: String, force: Boolean = false) {
                if (!force && webSocketClient.isConnected()) {
                    return
                }
                networkDebounceJob?.cancel()
                networkDebounceJob = serviceScope.launch {
                    delay(800L)
                    if (!force && webSocketClient.isConnected()) return@launch
                    val freshConfig = loadConfig()
                    webSocketClient.setHmacSecret(freshConfig.hmacSecret)
                    Log.i("NexaBridge", "Sinyal internet masuk [$source]! Memicu reconnect instan dari Fase 1...")
                    webSocketClient.forceReconnect(freshConfig.serverUrl, freshConfig.authToken)
                }
            }
        }

        try {
            cm.registerDefaultNetworkCallback(networkCallback!!)
            Log.i("NexaBridge", "Modern NetworkWatcher terdaftar.")
        } catch (e: Exception) {
            Log.e("NexaBridge", "Gagal mendaftarkan NetworkWatcher: ${e.message}")
        }
    }

    // Alert Helpers
    private fun buildAlertNotifContent(event: PhaseFailureEvent): Pair<String, String> {
        val errorName = when (event.errorType) {
            ReconnectErrorType.NO_INTERNET -> "No internet connection or DNS lookup failed"
            ReconnectErrorType.SERVER_DOWN -> "Server unreachable or connection refused"
            ReconnectErrorType.TIMEOUT -> "Server timed out"
            ReconnectErrorType.SSL_ERROR -> "SSL certificate error"
            ReconnectErrorType.UNKNOWN -> "Error: ${event.techMessage}"
        }

        val title = if (event.isFinal) {
            "N.E.X.A connection stopped"
        } else {
            "N.E.X.A connection interrupted"
        }

        val body = if (event.isFinal) {
            "$errorName\n${event.techMessage}\n\nAll retry attempts ended. Open N.E.X.A to reconnect."
        } else {
            "$errorName\n${event.techMessage}\n\nNext retry in ${event.nextRetryIn}."
        }

        return Pair(title, body)
    }

    private fun buildAlertTtsMessage(event: PhaseFailureEvent): String {
        val errorDesc = when (event.errorType) {
            ReconnectErrorType.NO_INTERNET ->
                "H P tidak dapat menemukan server N E X A. Pastikan koneksi internet aktif"
            ReconnectErrorType.SERVER_DOWN ->
                "Server N E X A tidak dapat dihubungi. Server kemungkinan sedang offline atau dalam pemeliharaan"
            ReconnectErrorType.TIMEOUT ->
                "Koneksi ke server N E X A habis waktu tunggu. Server mungkin sedang kelebihan beban"
            ReconnectErrorType.SSL_ERROR ->
                "Koneksi gagal karena masalah keamanan S S L. Periksa konfigurasi sertifikat server"
            ReconnectErrorType.UNKNOWN ->
                "Koneksi gagal dengan error: ${event.techMessage}"
        }

        return if (event.isFinal) {
            "Perhatian. N E X A Bridge. Semua percobaan koneksi telah habis. $errorDesc. " +
                "Silakan buka aplikasi untuk mencoba kembali secara manual."
        } else {
            "Perhatian. N E X A Bridge. Koneksi ke server gagal setelah fase ${event.phase}. " +
                "$errorDesc. Akan dicoba kembali dalam ${event.nextRetryIn}."
        }
    }

    private fun postAlertNotification(title: String, body: String) {
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notifManager.notify(NOTIF_ALERT_ID, notification)
    }

    private fun speakAlert(text: String) {
        if (alertTtsReady && alertTts != null) {
            alertTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nexa_alert_${System.currentTimeMillis()}")
            Log.d("NexaBridge", "Alert TTS: $text")
        } else {
            Log.w("NexaBridge", "Alert TTS belum siap - notifikasi tetap ditampilkan.")
        }
    }

    // Core Loops (A-L1: concurrent command execution so slow commands never block fast actions)
    private fun launchCommandConsumer() = serviceScope.launch {
        webSocketClient.incomingCommands.collect { command ->
            serviceScope.launch {
                Log.i("NexaBridge", "Menerima perintah: ${command.action}")
                val startElapsed = android.os.SystemClock.elapsedRealtime()
                val result: CommandResult = executeCommandWithLock(command)
                val durationMs = android.os.SystemClock.elapsedRealtime() - startElapsed
                recordCommandResult(command, result, durationMs)
            }
        }
    }

    private suspend fun executeCommandWithLock(command: NexaCommand): CommandResult {
        val executeWithTimeout: suspend () -> CommandResult = {
            val result = withTimeoutOrNull(BridgePowerLockManager.DEFAULT_TRANSFER_TIMEOUT_MS) {
                dispatcher.dispatch(command)
            } ?: CommandResult.failure(command.command_id, "Command '${command.action}' timed out after 15s")
            val payload = json.encodeToString(CommandResult.serializer(), result)
            webSocketClient.send(payload)
            result
        }
        val manager = powerLockManager
        return if (manager != null) {
            manager.withActiveTransferLock("cmd:${command.action}") {
                executeWithTimeout()
            }
        } else {
            executeWithTimeout()
        }
    }

    private fun launchTelemetryReporter() = serviceScope.launch {
        val batteryHandler = BatteryHandler(this@NexaBridgeService)
        val stubCommand = NexaCommand(
            type = "INTERNAL",
            command_id = "telemetry_tick",
            action = "GET_BATTERY_STATUS"
        )

        var lastBatteryLevel = -1
        var lastChargingState: Boolean? = null
        var lastReportTimeMs = 0L

        while (true) {
            try {
                val batResult = batteryHandler.execute(stubCommand)
                val level = (batResult.data?.get("battery_level") as? JsonPrimitive)?.intOrNull ?: 0
                val charging = (batResult.data?.get("charging") as? JsonPrimitive)?.booleanOrNull ?: false
                val screenOn = isScreenInteractive()
                val now = System.currentTimeMillis()

                val snapshot = TelemetryReport(
                    battery_level = level,
                    battery_charging = charging,
                    network_type = resolveNetworkType(),
                    network_strength = 0,
                    motion_state = "IDLE",
                    screen_on = screenOn
                )
                _latestTelemetry.value = snapshot

                val isBatteryChanged = (level != lastBatteryLevel) || (charging != lastChargingState)
                val isHeartbeatDue = (now - lastReportTimeMs) >= 180_000L // 3 menit

                // A-M1 / NFR-BAT-03: Drop non-critical telemetry when outbound WS queue is congested (>= 16 KiB)
                if ((isBatteryChanged || isHeartbeatDue) && webSocketClient.getQueueSize() < 16_384L) {
                    lastBatteryLevel = level
                    lastChargingState = charging
                    lastReportTimeMs = now

                    val payload = json.encodeToString(TelemetryReport.serializer(), snapshot)
                    webSocketClient.send(payload)
                    Log.d("NexaBridge", "Telemetri terkirim (diff/heartbeat): ${level}%, charging=$charging")
                } else if (webSocketClient.isConnected()) {
                    // Lightweight application-level heartbeat honoring configured screen-on/off intervals
                    webSocketClient.send("""{"type":"PING","ts":$now}""")
                }

                // Periksa sesi aplikasi aktif secara aman lewat UsageStatsManager (tanpa AccessibilityService)
                AppUsageTracker.checkActiveSessionTick(this@NexaBridgeService, isScreenOn = screenOn)
            } catch (e: Exception) {
                Log.e("NexaBridge", "Error telemetri: ${e.message}")
            }
            // A-M1 / NFR-BAT-01 / U-H12: Honor configured screen-on/screen-off intervals (defaults 60s / 120s)
            val sleepMs = try {
                val prefs = dataStore.data.first()
                if (isScreenInteractive()) {
                    val onSec = (prefs[intPreferencesKey("heartbeat_screen_on_sec")]
                        ?: NexaPreferencesDataStore.DEFAULT_HEARTBEAT_SCREEN_ON_SEC).coerceIn(30, 120)
                    onSec * 1_000L
                } else {
                    val offSec = (prefs[intPreferencesKey("heartbeat_screen_off_sec")]
                        ?: NexaPreferencesDataStore.DEFAULT_HEARTBEAT_SCREEN_OFF_SEC).coerceIn(60, 300)
                    offSec * 1_000L
                }
            } catch (_: Exception) {
                if (isScreenInteractive()) 60_000L else 120_000L
            }
            delay(sleepMs)
        }
    }

    private fun resolveNetworkType(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "OFFLINE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "OTHER"
            }
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }

    // Config & Lifecycle
    private suspend fun loadConfig(): NexaConfig {
        val urlKey = stringPreferencesKey("server_ws_url")
        val tokenKey = stringPreferencesKey("auth_token")
        val hmacKey = stringPreferencesKey("hmac_secret")

        val prefs = dataStore.data.first()
        return NexaConfig(
            serverUrl = prefs[urlKey] ?: NexaPreferencesDataStore.DEFAULT_WS_URL,
            authToken = SecretKeyStoreCipher.decrypt(prefs[tokenKey]),
            hmacSecret = SecretKeyStoreCipher.decrypt(prefs[hmacKey])
        )
    }

    private fun initAlertTts() {
        alertTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = alertTts?.setLanguage(Locale.forLanguageTag("id-ID"))
                alertTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                Log.i("NexaBridge", "Alert TTS siap: $alertTtsReady")
            } else {
                Log.w("NexaBridge", "Alert TTS gagal diinisialisasi (status=$status)")
            }
        }
    }

    private fun createNotificationChannels() {
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        NotificationChannel(CHANNEL_SERVICE_ID, "Background Service", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps N.E.X.A connected in the background"
            notifManager.createNotificationChannel(this)
        }

        NotificationChannel(CHANNEL_ALERT_ID, "Connection Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alerts when connection to N.E.X.A server fails"
            enableLights(true)
            enableVibration(true)
            notifManager.createNotificationChannel(this)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        // A-H13: Android 15/16 requires calling stopSelf() within seconds of onTimeout to prevent ForegroundServiceDidNotStopInTimeException
        Log.w("NexaBridge", "Foreground service onTimeout signal received (type: $fgsType); stopping gracefully.")
        stopSelf(startId)
    }

    private fun buildServiceNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle("N.E.X.A")
            .setContentText("Active in background · Power-save")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        _isServiceRunning.value = false
        _isPowerLockActive.value = false
        activeWebSocketClient = null
        activeJson = null
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
        networkDebounceJob?.cancel()
        networkDebounceJob = null
        sensorObserverManager?.stopObserving()
        sensorObserverManager = null
        geofenceManager?.stopGeofencing()
        geofenceManager = null
        contextEngine = null
        unregisterScreenStateReceiver()
        networkCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
            networkCallback = null
        }
        webSocketClient.disconnect()
        powerLockManager?.releaseAll()
        powerLockManager = null
        serviceScope.cancel()
        alertTts?.shutdown()
        alertTts = null
        Log.i("NexaBridge", "Service Dihentikan")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

data class NexaConfig(
    val serverUrl: String,
    val authToken: String,
    val hmacSecret: String
)

data class CommandHistoryRecord(
    val id: String,
    val action: String,
    val summary: String,
    val isSuccess: Boolean,
    val durationMs: Long,
    val timestamp: Long,
    val isSensitive: Boolean = false
)
