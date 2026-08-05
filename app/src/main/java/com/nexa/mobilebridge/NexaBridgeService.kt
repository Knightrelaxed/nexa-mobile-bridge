package com.nexa.mobilebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexa.mobilebridge.core.network.NexaWebSocketClient
import com.nexa.mobilebridge.dispatcher.DeviceCommandDispatcher
import com.nexa.mobilebridge.dispatcher.handler.BatteryHandler
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import com.nexa.mobilebridge.protocol.TelemetryReport
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import javax.inject.Inject

@AndroidEntryPoint
class NexaBridgeService : Service() {

    @Inject lateinit var webSocketClient: NexaWebSocketClient
    @Inject lateinit var dispatcher: DeviceCommandDispatcher
    @Inject lateinit var dataStore: DataStore<Preferences>
    @Inject lateinit var json: Json

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    private val CHANNEL_ID = "nexa_bridge_channel_01"
    private val NOTIF_ID = 1001

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIF_ID, notification)

        Log.i("NexaBridge", "Service Started in Foreground")

        serviceScope.launch {
            val config = loadConfig()
            webSocketClient.setHmacSecret(config.hmacSecret)

            // 1. Connect WebSocket
            webSocketClient.connect(config.serverUrl, config.authToken)

            // 2. Start Command Consumer Loop
            launchCommandConsumer()

            // 3. Start Telemetry Loop (every 60s)
            launchTelemetryReporter()
        }

        return START_STICKY
    }

    private fun launchCommandConsumer() = serviceScope.launch {
        webSocketClient.incomingCommands.collect { command ->
            Log.i("NexaBridge", "Received command: ${command.action}")
            val result = dispatcher.dispatch(command)
            // Use explicit serializer — Json inline reified cannot be used on injected instance
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
                // Safely extract values from the JsonObject using JsonPrimitive cast
                val level = (batResult.data?.get("battery_level") as? JsonPrimitive)?.intOrNull ?: 0
                val charging = (batResult.data?.get("charging") as? JsonPrimitive)?.booleanOrNull ?: false

                val telemetry = TelemetryReport(
                    battery_level = level,
                    battery_charging = charging,
                    network_type = "UNKNOWN", // Phase 2: will be populated by NetworkHandler
                    network_strength = 0,      // Phase 2
                    motion_state = "IDLE",     // Phase 2
                    screen_on = true           // Phase 2
                )

                val payload = json.encodeToString(TelemetryReport.serializer(), telemetry)
                webSocketClient.send(payload)
                Log.d("NexaBridge", "Telemetry sent: ${level}%, charging=$charging")
            } catch (e: Exception) {
                Log.e("NexaBridge", "Telemetry error: ${e.message}")
            }
            delay(60_000L)
        }
    }

    private suspend fun loadConfig(): NexaConfig {
        val urlKey = stringPreferencesKey("server_ws_url")
        val tokenKey = stringPreferencesKey("auth_token")
        val hmacKey = stringPreferencesKey("hmac_secret")

        val prefs = dataStore.data.first()
        return NexaConfig(
            serverUrl = prefs[urlKey] ?: "wss://nexa-asistant-nexa-core-server.hf.space/ws",
            authToken = prefs[tokenKey] ?: "",
            hmacSecret = prefs[hmacKey] ?: ""
        )
    }

    private fun createNotificationChannel() {
        val name = "N.E.X.A Bridge"
        val descriptionText = "Persists connection to N.E.X.A Cloud Server"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
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
        // Acquire without timeout — released explicitly in onDestroy
        @Suppress("WakelockTimeout")
        wakeLock?.acquire()
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketClient.disconnect()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        Log.i("NexaBridge", "Service Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

data class NexaConfig(
    val serverUrl: String,
    val authToken: String,
    val hmacSecret: String
)
