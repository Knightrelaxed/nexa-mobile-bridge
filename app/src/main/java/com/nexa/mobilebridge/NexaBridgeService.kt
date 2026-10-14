package com.nexa.mobilebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexa.mobilebridge.core.context.ContextEngine
import com.nexa.mobilebridge.core.network.NexaWebSocketClient
import com.nexa.mobilebridge.core.network.PhaseFailureEvent
import com.nexa.mobilebridge.core.network.ReconnectErrorType
import com.nexa.mobilebridge.core.sensor.GeofenceManager
import com.nexa.mobilebridge.core.sensor.SensorObserverManager
import com.nexa.mobilebridge.dispatcher.DeviceCommandDispatcher
import com.nexa.mobilebridge.dispatcher.handler.BatteryHandler
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.ContextReport
import com.nexa.mobilebridge.protocol.NexaCommand
import com.nexa.mobilebridge.protocol.TelemetryReport
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class NexaBridgeService : Service() {

    companion object {
        /** Weak reference to the active WebSocket client for sending call events from Activities. */
        @Volatile private var activeWebSocketClient: com.nexa.mobilebridge.core.network.NexaWebSocketClient? = null
        @Volatile private var activeJson: kotlinx.serialization.json.Json? = null

        /**
         * Send a raw JSON payload to the N.E.X.A server via the active WebSocket connection.
         * Safe to call from any thread or Activity (e.g., FakeCallActivity).
         * Returns true if the message was enqueued successfully.
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

        fun encodeJson(): kotlinx.serialization.json.Json? = activeJson
    }

    @Inject lateinit var webSocketClient: com.nexa.mobilebridge.core.network.NexaWebSocketClient
    @Inject lateinit var dispatcher: DeviceCommandDispatcher
    @Inject lateinit var dataStore: DataStore<Preferences>
    @Inject lateinit var json: Json

    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    // ─── Notification Channels ────────────────────────────────────
    // Kanal layanan (persistensi foreground) — prioritas rendah agar tidak mengganggu
    private val CHANNEL_SERVICE_ID = "nexa_bridge_channel_01"
    // Kanal alert kegagalan koneksi — prioritas tinggi agar muncul sebagai heads-up
    private val CHANNEL_ALERT_ID   = "nexa_bridge_alert_01"
    private val NOTIF_SERVICE_ID   = 1001
    private val NOTIF_ALERT_ID     = 1002

    // ─── Alert TTS ────────────────────────────────────────────────
    // Instance TextToSpeech khusus untuk narasi kegagalan koneksi.
    // Menggunakan volume media AKTIF SAAT ITU tanpa memaksa perubahan volume.
    private var alertTts: TextToSpeech? = null
    private var alertTtsReady = false

    private var isServiceStarted = false
    private var sensorObserverManager: SensorObserverManager? = null
    private var contextEngine: ContextEngine? = null
    private var geofenceManager: GeofenceManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkDebounceJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var isScreenReceiverRegistered = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.i("NexaBridge", "📱 [EVENT] Layar Menyala (SCREEN_ON)")
                    val payload = """{"type":"SCREEN_ON","timestamp":${System.currentTimeMillis()}}"""
                    webSocketClient.send(payload)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i("NexaBridge", "📱 [EVENT] Layar Padam/Tidur (SCREEN_OFF)")
                    val payload = """{"type":"SCREEN_OFF","timestamp":${System.currentTimeMillis()}}"""
                    webSocketClient.send(payload)
                }
            }
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
            Log.i("NexaBridge", " ScreenStateReceiver registered successfully")
        } catch (e: Exception) {
            Log.e("NexaBridge", "Failed to register ScreenStateReceiver: ${e.message}")
        }
    }

    private fun unregisterScreenStateReceiver() {
        if (isScreenReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
                isScreenReceiverRegistered = false
                Log.i("NexaBridge", " ScreenStateReceiver unregistered")
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
        // Buat ulang scope setiap kali service dimulai agar coroutine sebelumnya
        // yang mungkin sudah dibatalkan tidak mempengaruhi sesi baru.
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        createNotificationChannels()
        acquireWakeLock()
        acquireWifiLock()
        initAlertTts()
        registerScreenStateReceiver()
        // Expose WebSocket client to companion object for cross-component access
        activeWebSocketClient = webSocketClient
        activeJson = json
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = buildServiceNotification()
            startForeground(NOTIF_SERVICE_ID, notification)
        } catch (e: Exception) {
            Log.e("NexaBridge", "Failed to start foreground notification: ${e.message}", e)
        }

        // Intercept action from SafeBankingLauncherActivity
        if (intent?.action == "com.nexa.mobilebridge.ACTION_START_USAGE_OBSERVER") {
            val targetPackage = intent.getStringExtra("WATCHING_PACKAGE")
            if (!targetPackage.isNullOrBlank()) {
                startUsageObserver(targetPackage)
            }
        }

        if (!isServiceStarted) {
            isServiceStarted = true
            Log.i("NexaBridge", "Service Dimulai di Foreground")

            serviceScope.launch {
                val config = loadConfig()
                webSocketClient.setHmacSecret(config.hmacSecret)

                // 1. Sambungkan WebSocket
                webSocketClient.connect(config.serverUrl, config.authToken)

                // 2. Mulai konsumer perintah dari server
                launchCommandConsumer()

                // 3. Mulai pelaporan telemetri periodik (setiap 60 detik)
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

                // 8. Mulai Network-Availability Watcher
                // Saat internet kembali setelah hilang, langsung reset orchestrator
                // dan coba reconnect dari awal — tanpa menunggu delay antar-fase.
                registerNetworkWatcher(config.serverUrl, config.authToken)

                // 9. Mulai Accessibility Self-Healing Watchdog
                // Memulihkan layanan jika status di Settings 'Not working' (unbound) secara otomatis
                launchAccessibilitySelfHealingWatchdog()
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

        // START_STICKY — Jika service dimatikan oleh OS karena kekurangan memori
        // atau user swipe dari Recent Apps, Android akan otomatis mencoba menghidupkannya lagi.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w("NexaBridge", " Aplikasi di-swipe dari Recent Apps! Mencoba menghidupkan kembali service via AlarmManager...")
        
        try {
            val restartServiceIntent = Intent(applicationContext, NexaBridgeService::class.java)
            val restartServicePendingIntent = android.app.PendingIntent.getService(
                applicationContext,
                1,
                restartServiceIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            val alarmService = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmService.set(
                android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 1000, // restart dalam 1 detik
                restartServicePendingIntent
            )
        } catch (e: Exception) {
            Log.e("NexaBridge", "Gagal menjadwalkan restart via AlarmManager: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Phase Failure Listener — Jantung dari sistem reconnect cerdas
    // ─────────────────────────────────────────────────────────────

    private fun launchPhaseFailureListener() = serviceScope.launch {
        webSocketClient.phaseFailureEvents.collect { event ->
            Log.w("NexaBridge", " PhaseFailureEvent: Fase ${event.phase}, Final: ${event.isFinal}")

            // 1. Bangun konten notifikasi dan pesan suara
            val (notifTitle, notifBody) = buildAlertNotifContent(event)
            val ttsMessage = buildAlertTtsMessage(event)

            // 2. Tampilkan notifikasi sistem di bilah notifikasi
            postAlertNotification(notifTitle, notifBody)

            // 3. Ucapkan pesan kegagalan menggunakan volume media AKTIF SAAT INI (tanpa ubah volume)
            speakAlert(ttsMessage)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Sensor & Context Engine Listener — Sensor Observers
    // ─────────────────────────────────────────────────────────────

    private fun launchSensorObserverListener() = serviceScope.launch {
        val manager = sensorObserverManager ?: return@launch
        val engine = contextEngine ?: return@launch

        // Pipe raw sensor events to ContextEngine
        launch {
            manager.sensorEvents.collect { sensorEvent ->
                engine.processSensorEvent(sensorEvent)
            }
        }

        // Collect synthesized ContextReports and send over WebSocket
        launch {
            engine.contextReports.collect { report ->
                val payload = json.encodeToString(ContextReport.serializer(), report)
                val sent = webSocketClient.send(payload)
                Log.d("NexaBridge", " ContextReport terkirim (success=$sent): ${report.event}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Network Watcher — Reset & reconnect saat internet kembali
    // ─────────────────────────────────────────────────────────────

    /**
     * Mendaftarkan NetworkCallback ke OS Android.
     * Menggunakan [registerDefaultNetworkCallback] agar memantau rute internet aktif secara langsung.
     * Dilengkapi multi-trigger ([onAvailable], [onCapabilitiesChanged], [onLinkPropertiesChanged])
     * sehingga saat ada koneksi baru (Wi-Fi/4G) yang masuk meski sinyal lemah,
     * sistem langsung membatalkan jeda tidur fase dan memicu reconnect instan.
     */
    private fun registerNetworkWatcher(serverUrl: String, authToken: String) {
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
                if (!force && webSocketClient.isConnected() && source != "onAvailable") {
                    return
                }
                networkDebounceJob?.cancel()
                networkDebounceJob = serviceScope.launch {
                    delay(800L) // 800ms debounce cepat untuk stabilisasi rute
                    Log.i("NexaBridge", "Sinyal internet masuk [$source]! Memicu reconnect instan dari Fase 1...")
                    webSocketClient.forceReconnect(serverUrl, authToken)
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(networkCallback!!)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, networkCallback!!)
            }
            Log.i("NexaBridge", " Modern NetworkWatcher (Default + Reactive Multi-Trigger) terdaftar.")
        } catch (e: Exception) {
            Log.e("NexaBridge", " Gagal mendaftarkan NetworkWatcher: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Alert Helpers
    // ─────────────────────────────────────────────────────────────

    private fun buildAlertNotifContent(event: PhaseFailureEvent): Pair<String, String> {
        val errorName = when (event.errorType) {
            ReconnectErrorType.NO_INTERNET  -> "Tidak Ada Internet / DNS Gagal"
            ReconnectErrorType.SERVER_DOWN  -> "Server Offline / Koneksi Ditolak"
            ReconnectErrorType.TIMEOUT      -> "Server Timeout (Tidak Merespons)"
            ReconnectErrorType.SSL_ERROR    -> "Kesalahan Sertifikat SSL"
            ReconnectErrorType.UNKNOWN      -> "Error: ${event.techMessage}"
        }

        val title = if (event.isFinal) {
            "N.E.X.A Bridge — Koneksi Dihentikan Total"
        } else {
            "N.E.X.A Bridge — Koneksi Gagal (Fase ${event.phase})"
        }

        val body = if (event.isFinal) {
            "$errorName\n${event.techMessage}\n\nSemua percobaan habis. Buka app untuk coba kembali."
        } else {
            "$errorName\n${event.techMessage}\n\nPercobaan berikutnya dalam ${event.nextRetryIn}."
        }

        return Pair(title, body)
    }

    private fun buildAlertTtsMessage(event: PhaseFailureEvent): String {
        val errorDesc = when (event.errorType) {
            ReconnectErrorType.NO_INTERNET  ->
                "H P tidak dapat menemukan server N E X A. Pastikan koneksi internet aktif"
            ReconnectErrorType.SERVER_DOWN  ->
                "Server N E X A tidak dapat dihubungi. Server kemungkinan sedang offline atau dalam pemeliharaan"
            ReconnectErrorType.TIMEOUT      ->
                "Koneksi ke server N E X A habis waktu tunggu. Server mungkin sedang kelebihan beban"
            ReconnectErrorType.SSL_ERROR    ->
                "Koneksi gagal karena masalah keamanan S S L. Periksa konfigurasi sertifikat server"
            ReconnectErrorType.UNKNOWN      ->
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

    // ─────────────────────────────────────────────────────────────
    // M-Banking Shield Mode (UsageStats Observer)
    // ─────────────────────────────────────────────────────────────
    
    private var usageObserverJob: kotlinx.coroutines.Job? = null
    
    private fun startUsageObserver(targetPackage: String) {
        usageObserverJob?.cancel() // Batalkan jika ada yang sedang berjalan
        Log.i("Nexa-MBankingShield", "Memulai pengamatan UsageStats untuk package: $targetPackage")
        
        usageObserverJob = serviceScope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            var isTargetStillForeground = true
            var hasTargetBeenForeground = false
            var cyclesWaitingForTarget = 0
            
            while (isActive && isTargetStillForeground) {
                val endTime = System.currentTimeMillis()
                val startTime = endTime - 5000 // Cek 5 detik terakhir
                
                val events = usageStatsManager.queryEvents(startTime, endTime)
                val event = android.app.usage.UsageEvents.Event()
                
                var latestForegroundPackage: String? = null
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                        latestForegroundPackage = event.packageName
                    }
                }
                
                if (!hasTargetBeenForeground) {
                    if (latestForegroundPackage == targetPackage) {
                        hasTargetBeenForeground = true
                        Log.i("Nexa-MBankingShield", "Target ($targetPackage) berhasil naik ke foreground.")
                    } else {
                        cyclesWaitingForTarget++
                        if (cyclesWaitingForTarget > 15) { // 15 detik batas tunggu sebelum timeout
                            Log.w("Nexa-MBankingShield", "Target ($targetPackage) tidak pernah muncul setelah 15 detik. Timeout.")
                            isTargetStillForeground = false
                        }
                    }
                } else {
                    // Target sudah masuk, awasi kalau tertutup
                    if (latestForegroundPackage != null && 
                        latestForegroundPackage != targetPackage &&
                        latestForegroundPackage != "com.nexa.mobilebridge" && // Jaga-jaga activity siluman
                        latestForegroundPackage != "com.android.systemui") {
                        
                        Log.i("Nexa-MBankingShield", "Target ditutup/bergeser ke background. Foreground saat ini: $latestForegroundPackage")
                        isTargetStillForeground = false
                    }
                }
                
                kotlinx.coroutines.delay(1000) // Polling setiap 1 detik
            }
            
            // Saat keluar dari loop, berarti M-Banking sudah tidak di foreground
            Log.i("Nexa-MBankingShield", "M-Banking tertutup atau timeout. Merestorasi izin Aksesibilitas N.E.X.A...")
            com.nexa.mobilebridge.core.security.MBankingShieldManager.enableNexaAccessibility(this@NexaBridgeService)
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
            // TIDAK ada perubahan volume — gunakan volume media yang sedang aktif saat ini
            alertTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nexa_alert_${System.currentTimeMillis()}")
            Log.d("NexaBridge", " Alert TTS: $text")
        } else {
            Log.w("NexaBridge", "Alert TTS belum siap — notifikasi tetap ditampilkan.")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Existing Core Loops
    // ─────────────────────────────────────────────────────────────

    private fun launchCommandConsumer() = serviceScope.launch {
        webSocketClient.incomingCommands.collect { command ->
            Log.i("NexaBridge", "Menerima perintah: ${command.action}")
            val result = dispatcher.dispatch(command)
            val payload = json.encodeToString(CommandResult.serializer(), result)
            webSocketClient.send(payload)
        }
    }

    private fun launchTelemetryReporter() = serviceScope.launch {
        val batteryHandler = BatteryHandler(this@NexaBridgeService)
        val stubCommand = NexaCommand(
            type = "INTERNAL",
            command_id = "telemetry_tick",
            action = "GET_BATTERY_STATUS"
        )

        while (true) {
            try {
                val batResult = batteryHandler.execute(stubCommand)
                val level    = (batResult.data?.get("battery_level") as? JsonPrimitive)?.intOrNull ?: 0
                val charging = (batResult.data?.get("charging") as? JsonPrimitive)?.booleanOrNull ?: false

                val telemetry = TelemetryReport(
                    battery_level    = level,
                    battery_charging = charging,
                    network_type     = "UNKNOWN",
                    network_strength = 0,
                    motion_state     = "IDLE",
                    screen_on        = isScreenInteractive()
                )

                val payload = json.encodeToString(TelemetryReport.serializer(), telemetry)
                webSocketClient.send(payload)
                Log.d("NexaBridge", "Telemetri terkirim: ${level}%, charging=$charging")

                // Periksa sesi aplikasi aktif secara periodik
                com.nexa.mobilebridge.core.usage.AppUsageTracker.checkActiveSessionTick(this@NexaBridgeService)
            } catch (e: Exception) {
                Log.e("NexaBridge", "Error telemetri: ${e.message}")
            }
            delay(30_000L)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Config & Lifecycle
    // ─────────────────────────────────────────────────────────────

    private suspend fun loadConfig(): NexaConfig {
        val urlKey   = stringPreferencesKey("server_ws_url")
        val tokenKey = stringPreferencesKey("auth_token")
        val hmacKey  = stringPreferencesKey("hmac_secret")

        val prefs = dataStore.data.first()
        return NexaConfig(
            serverUrl   = prefs[urlKey]   ?: "wss://nexa-asistant-nexa-core-server.hf.space/ws",
            authToken   = prefs[tokenKey] ?: "",
            hmacSecret  = prefs[hmacKey]  ?: ""
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

        // Kanal 1: Layanan Foreground (prioritas rendah — tidak mengganggu)
        NotificationChannel(CHANNEL_SERVICE_ID, "N.E.X.A Bridge", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Menjaga koneksi ke N.E.X.A Cloud Server"
            notifManager.createNotificationChannel(this)
        }

        // Kanal 2: Alert Kegagalan Koneksi (prioritas tinggi — heads-up notification)
        NotificationChannel(CHANNEL_ALERT_ID, "N.E.X.A Bridge — Alert Koneksi", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Notifikasi kegagalan koneksi ke server N.E.X.A"
            enableLights(true)
            enableVibration(true)
            notifManager.createNotificationChannel(this)
        }
    }

    /**
     * Accessibility Health Monitor
     * Memantau status keterikatan NexaAccessibilityService secara pasif tanpa melakukan
     * manipulasi siklus cabut-pasang Settings.Secure yang dapat memicu thrashing di Android OS.
     */
    private fun launchAccessibilitySelfHealingWatchdog() {
        serviceScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(60_000)
                try {
                    val cr = applicationContext.contentResolver
                    val enabledServices = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                    val isConfiguredOn = enabledServices.contains("com.nexa.mobilebridge")
                    val isServiceAlive = NexaAccessibilityService.instance != null

                    if (isConfiguredOn && !isServiceAlive) {
                        Log.d("NexaBridge", "Accessibility Monitor: Setting is ON, waiting for system binding.")
                    }
                } catch (e: Exception) {
                    Log.w("NexaBridge", "Accessibility Monitor check notice: ${e.message}")
                }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.w("NexaBridge", "Foreground service onTimeout signal received (type: $fgsType)")
    }

    private fun buildServiceNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setContentTitle("N.E.X.A Mobile Bridge")
            .setContentText("Listening to Neural Engine...")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NexaBridge::WakeLock"
        )
        @Suppress("WakelockTimeout")
        wakeLock?.acquire()
    }

    private fun acquireWifiLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(mode, "NexaBridge::WifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i("NexaBridge", " WifiLock acquired (mode: $mode)")
    }

    override fun onDestroy() {
        // Clear companion references before tearing down
        activeWebSocketClient = null
        activeJson = null
        super.onDestroy()
        networkDebounceJob?.cancel()
        networkDebounceJob = null
        sensorObserverManager?.stopObserving()
        sensorObserverManager = null
        geofenceManager?.stopGeofencing()
        geofenceManager = null
        contextEngine = null
        unregisterScreenStateReceiver()
        // Unregister network watcher agar tidak ada memory leak
        networkCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (e: Exception) { /* Diabaikan */ }
            networkCallback = null
        }
        webSocketClient.disconnect()
        serviceScope.cancel() // Batalkan semua coroutine milik service ini
        alertTts?.shutdown()
        alertTts = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        Log.i("NexaBridge", "Service Dihentikan")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

data class NexaConfig(
    val serverUrl: String,
    val authToken: String,
    val hmacSecret: String
)
