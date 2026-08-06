package com.nexa.mobilebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class NexaBridgeService : Service() {

    @Inject lateinit var webSocketClient: NexaWebSocketClient
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

    override fun onCreate() {
        super.onCreate()
        // Buat ulang scope setiap kali service dimulai agar coroutine sebelumnya
        // yang mungkin sudah dibatalkan tidak mempengaruhi sesi baru.
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        createNotificationChannels()
        acquireWakeLock()
        initAlertTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildServiceNotification()
        startForeground(NOTIF_SERVICE_ID, notification)

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

                // 6. Inisialisasi GeofenceManager
                geofenceManager = GeofenceManager(this@NexaBridgeService).apply {
                    startGeofencing()
                }

                // 7. Mulai pendengar event sensor & sintesis konteks
                launchSensorObserverListener()
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

        // START_NOT_STICKY — service TIDAK akan otomatis dihidupkan kembali oleh OS
        // saat dihentikan oleh user. Harus dinyalakan secara eksplisit.
        return START_NOT_STICKY
    }

    // ─────────────────────────────────────────────────────────────
    // Phase Failure Listener — Jantung dari sistem reconnect cerdas
    // ─────────────────────────────────────────────────────────────

    private fun launchPhaseFailureListener() = serviceScope.launch {
        webSocketClient.phaseFailureEvents.collect { event ->
            Log.w("NexaBridge", "⚠️ PhaseFailureEvent: Fase ${event.phase}, Final: ${event.isFinal}")

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
                Log.d("NexaBridge", "📡 ContextReport terkirim (success=$sent): ${report.event}")
            }
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
            "❌ N.E.X.A Bridge — Koneksi Dihentikan Total"
        } else {
            "⚠️ N.E.X.A Bridge — Koneksi Gagal (Fase ${event.phase})"
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
            Log.d("NexaBridge", "🎙️ Alert TTS: $text")
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
                    screen_on        = true
                )

                val payload = json.encodeToString(TelemetryReport.serializer(), telemetry)
                webSocketClient.send(payload)
                Log.d("NexaBridge", "Telemetri terkirim: ${level}%, charging=$charging")
            } catch (e: Exception) {
                Log.e("NexaBridge", "Error telemetri: ${e.message}")
            }
            delay(60_000L)
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

    override fun onDestroy() {
        super.onDestroy()
        sensorObserverManager?.stopObserving()
        sensorObserverManager = null
        geofenceManager?.stopGeofencing()
        geofenceManager = null
        contextEngine = null
        webSocketClient.disconnect()
        serviceScope.cancel() // Batalkan semua coroutine milik service ini
        alertTts?.shutdown()
        alertTts = null
        wakeLock?.let { if (it.isHeld) it.release() }
        Log.i("NexaBridge", "Service Dihentikan")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

data class NexaConfig(
    val serverUrl: String,
    val authToken: String,
    val hmacSecret: String
)
