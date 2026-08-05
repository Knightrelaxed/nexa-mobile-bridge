package com.nexa.mobilebridge.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class NexaConfig(
    val serverUrl: String,
    val authToken: String,
    val hmacSecret: String,
    val ntfyTopic: String,
    val deviceName: String,
    val taskerWebhook: String,
    val autoStart: Boolean,
    val telemetryIntervalS: Int
)

@Singleton
class NexaPreferencesDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val SERVER_WS_URL = stringPreferencesKey("server_ws_url")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val HMAC_SECRET = stringPreferencesKey("hmac_secret")
        val NTFY_TOPIC = stringPreferencesKey("ntfy_topic")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val TASKER_WEBHOOK = stringPreferencesKey("tasker_webhook_url")
        val AUTO_START = booleanPreferencesKey("auto_start_boot")
        val TELEMETRY_INTERVAL_S = intPreferencesKey("telemetry_interval_sec")
    }

    suspend fun getConfig(): NexaConfig {
        val prefs = dataStore.data.first()
        return NexaConfig(
            serverUrl = prefs[Keys.SERVER_WS_URL] ?: "wss://nexa-asistant-nexa-core-server.hf.space/ws",
            authToken = prefs[Keys.AUTH_TOKEN] ?: "",
            hmacSecret = prefs[Keys.HMAC_SECRET] ?: "",
            ntfyTopic = prefs[Keys.NTFY_TOPIC] ?: "",
            deviceName = prefs[Keys.DEVICE_NAME] ?: "Samsung_A33_5G",
            taskerWebhook = prefs[Keys.TASKER_WEBHOOK] ?: "https://nexa-asistant-nexa-core-server.hf.space/webhook/tasker",
            autoStart = prefs[Keys.AUTO_START] ?: true,
            telemetryIntervalS = prefs[Keys.TELEMETRY_INTERVAL_S] ?: 60
        )
    }

    suspend fun saveConfig(url: String, token: String, secret: String) {
        dataStore.edit { prefs ->
            prefs[Keys.SERVER_WS_URL] = url
            prefs[Keys.AUTH_TOKEN] = token
            prefs[Keys.HMAC_SECRET] = secret
        }
    }
}
