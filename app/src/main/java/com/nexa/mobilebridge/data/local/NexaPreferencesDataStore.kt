package com.nexa.mobilebridge.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexa.mobilebridge.core.security.SecretKeyStoreCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM;

    companion object {
        fun fromStored(value: String?): ThemeMode = when (value?.uppercase()) {
            "LIGHT" -> LIGHT
            "SYSTEM" -> SYSTEM
            else -> DARK
        }
    }
}

enum class AccentPreset(val displayName: String, val badgeName: String) {
    CYAN("Cyan", "NEXA CYAN"),
    EMERALD("Hijau", "EMERALD"),
    AMBER("Amber", "AMBER"),
    VIOLET("Ungu", "VIOLET");

    companion object {
        fun fromStored(value: String?): AccentPreset = when (value?.uppercase()) {
            "EMERALD" -> EMERALD
            "AMBER" -> AMBER
            "VIOLET" -> VIOLET
            else -> CYAN
        }
    }
}

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentPreset: AccentPreset = AccentPreset.CYAN
)

data class NexaConfig(
    val serverUrl: String,
    val authToken: String,
    val hmacSecret: String,
    val ntfyTopic: String,
    val deviceName: String,
    val taskerWebhook: String,
    val autoStart: Boolean,
    val telemetryIntervalS: Int,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentPreset: AccentPreset = AccentPreset.CYAN,
    val ttsEnabled: Boolean = true,
    val hapticEnabled: Boolean = true,
    val terminalFontSizeSp: Int = 12,
    val enrollmentStatus: String = "NOT_ENROLLED",
    val enrolledAtEpochSec: Long = 0L,
    val enrolledFingerprint: String = "",
    val heartbeatScreenOnSec: Int = 60,
    val heartbeatScreenOffSec: Int = 120,
    val chatNotificationSoundUri: String = "",
    val chatNotificationSoundLabel: String = "Default Sistem"
)

@Singleton
class NexaPreferencesDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        const val DEFAULT_WS_URL = "wss://nexa-server.indonesiacentral.cloudapp.azure.com/ws"
        const val DEFAULT_WEBHOOK_URL = "https://nexa-server.indonesiacentral.cloudapp.azure.com/webhook/tasker"
        const val MIN_TERMINAL_FONT_SP = 8
        const val MAX_TERMINAL_FONT_SP = 16
        const val DEFAULT_TERMINAL_FONT_SP = 12
        const val DEFAULT_HEARTBEAT_SCREEN_ON_SEC = 60
        const val DEFAULT_HEARTBEAT_SCREEN_OFF_SEC = 120
        val HEARTBEAT_SCREEN_ON_OPTIONS = listOf(30, 45, 60, 90)
        val HEARTBEAT_SCREEN_OFF_OPTIONS = listOf(90, 120, 180)
    }

    private object Keys {
        val SERVER_WS_URL = stringPreferencesKey("server_ws_url")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val HMAC_SECRET = stringPreferencesKey("hmac_secret")
        val NTFY_TOPIC = stringPreferencesKey("ntfy_topic")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val TASKER_WEBHOOK = stringPreferencesKey("tasker_webhook_url")
        val AUTO_START = booleanPreferencesKey("auto_start_boot")
        val TELEMETRY_INTERVAL_S = intPreferencesKey("telemetry_interval_sec")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_PRESET = stringPreferencesKey("accent_preset")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
        val TERMINAL_FONT_SIZE_SP = intPreferencesKey("terminal_font_size_sp")
        val ENROLLMENT_STATUS = stringPreferencesKey("enrollment_status")
        val ENROLLED_AT_EPOCH_SEC = longPreferencesKey("enrolled_at_epoch_sec")
        val ENROLLED_FINGERPRINT = stringPreferencesKey("enrolled_fingerprint")
        val HEARTBEAT_SCREEN_ON_SEC = intPreferencesKey("heartbeat_screen_on_sec")
        val HEARTBEAT_SCREEN_OFF_SEC = intPreferencesKey("heartbeat_screen_off_sec")
        val CHAT_NOTIF_SOUND_URI = stringPreferencesKey("chat_notif_sound_uri")
        val CHAT_NOTIF_SOUND_LABEL = stringPreferencesKey("chat_notif_sound_label")
    }

    val appearanceFlow: Flow<AppearanceSettings> = dataStore.data.map { prefs ->
        AppearanceSettings(
            themeMode = ThemeMode.fromStored(prefs[Keys.THEME_MODE]),
            accentPreset = AccentPreset.fromStored(prefs[Keys.ACCENT_PRESET])
        )
    }

    val serverUrlFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.SERVER_WS_URL] ?: DEFAULT_WS_URL
    }

    suspend fun getConfig(): NexaConfig {
        val prefs = dataStore.data.first()
        val fontSize = (prefs[Keys.TERMINAL_FONT_SIZE_SP] ?: DEFAULT_TERMINAL_FONT_SP)
            .coerceIn(MIN_TERMINAL_FONT_SP, MAX_TERMINAL_FONT_SP)
        return NexaConfig(
            serverUrl = prefs[Keys.SERVER_WS_URL] ?: DEFAULT_WS_URL,
            authToken = SecretKeyStoreCipher.decrypt(prefs[Keys.AUTH_TOKEN]),
            hmacSecret = SecretKeyStoreCipher.decrypt(prefs[Keys.HMAC_SECRET]),
            ntfyTopic = prefs[Keys.NTFY_TOPIC] ?: "",
            deviceName = prefs[Keys.DEVICE_NAME] ?: "samsung-a33-tuan",
            taskerWebhook = prefs[Keys.TASKER_WEBHOOK] ?: DEFAULT_WEBHOOK_URL,
            autoStart = prefs[Keys.AUTO_START] ?: true,
            telemetryIntervalS = prefs[Keys.TELEMETRY_INTERVAL_S] ?: 60,
            themeMode = ThemeMode.fromStored(prefs[Keys.THEME_MODE]),
            accentPreset = AccentPreset.fromStored(prefs[Keys.ACCENT_PRESET]),
            ttsEnabled = prefs[Keys.TTS_ENABLED] ?: true,
            hapticEnabled = prefs[Keys.HAPTIC_ENABLED] ?: true,
            terminalFontSizeSp = fontSize,
            enrollmentStatus = prefs[Keys.ENROLLMENT_STATUS] ?: "NOT_ENROLLED",
            enrolledAtEpochSec = prefs[Keys.ENROLLED_AT_EPOCH_SEC] ?: 0L,
            enrolledFingerprint = prefs[Keys.ENROLLED_FINGERPRINT] ?: "",
            heartbeatScreenOnSec = (prefs[Keys.HEARTBEAT_SCREEN_ON_SEC] ?: DEFAULT_HEARTBEAT_SCREEN_ON_SEC)
                .coerceIn(30, 120),
            heartbeatScreenOffSec = (prefs[Keys.HEARTBEAT_SCREEN_OFF_SEC] ?: DEFAULT_HEARTBEAT_SCREEN_OFF_SEC)
                .coerceIn(60, 300),
            chatNotificationSoundUri = prefs[Keys.CHAT_NOTIF_SOUND_URI] ?: "",
            chatNotificationSoundLabel = prefs[Keys.CHAT_NOTIF_SOUND_LABEL]?.ifBlank { "Default Sistem" }
                ?: "Default Sistem"
        )
    }

    suspend fun saveEnrollmentState(status: String, enrolledAtEpochSec: Long, fingerprint: String) {
        dataStore.edit { prefs ->
            prefs[Keys.ENROLLMENT_STATUS] = status
            prefs[Keys.ENROLLED_AT_EPOCH_SEC] = enrolledAtEpochSec
            prefs[Keys.ENROLLED_FINGERPRINT] = fingerprint
        }
    }

    suspend fun saveConfig(url: String, token: String, secret: String) {
        dataStore.edit { prefs ->
            prefs[Keys.SERVER_WS_URL] = url
            prefs[Keys.AUTH_TOKEN] = SecretKeyStoreCipher.encrypt(token)
            prefs[Keys.HMAC_SECRET] = SecretKeyStoreCipher.encrypt(secret)
        }
    }

    suspend fun saveThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    suspend fun saveAccentPreset(preset: AccentPreset) {
        dataStore.edit { prefs ->
            prefs[Keys.ACCENT_PRESET] = preset.name
        }
    }

    suspend fun saveTtsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.TTS_ENABLED] = enabled
        }
    }

    suspend fun saveHapticEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.HAPTIC_ENABLED] = enabled
        }
    }

    suspend fun saveTerminalFontSizeSp(sizeSp: Int) {
        val clamped = sizeSp.coerceIn(MIN_TERMINAL_FONT_SP, MAX_TERMINAL_FONT_SP)
        dataStore.edit { prefs ->
            prefs[Keys.TERMINAL_FONT_SIZE_SP] = clamped
        }
    }

    suspend fun saveHeartbeatIntervals(screenOnSec: Int, screenOffSec: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.HEARTBEAT_SCREEN_ON_SEC] = screenOnSec.coerceIn(30, 120)
            prefs[Keys.HEARTBEAT_SCREEN_OFF_SEC] = screenOffSec.coerceIn(60, 300)
        }
    }

    suspend fun saveChatNotificationSound(uri: String, label: String) {
        dataStore.edit { prefs ->
            prefs[Keys.CHAT_NOTIF_SOUND_URI] = uri
            prefs[Keys.CHAT_NOTIF_SOUND_LABEL] = label.ifBlank { "Default Sistem" }
        }
    }
}
