package com.nexa.mobilebridge.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

enum class LightState { DARK, NORMAL, BRIGHT }
enum class MotionState { STATIONARY, MOVING }

data class SensorObservedEvent(
    val eventName: String,
    val summary: String,
    val data: Map<String, Any>
)

// ─────────────────────────────────────────────────────────────────────────────
// Hysteresis Thresholds for Light Sensor
//
// Menggunakan model "Dual-Threshold" agar sensor tidak bergetar (jitter) di
// batas nilai. State baru hanya dikonfirmasi jika melampaui threshold KELUAR
// dari state saat ini, bukan masuk ke state baru.
//
//   DARK zone  : lux < DARK_UPPER  (3 lux)
//   NORMAL zone: lux >= DARK_UPPER && lux < BRIGHT_LOWER (3..80 lux)
//   BRIGHT zone: lux >= BRIGHT_LOWER (80 lux)
//
// + Debounce 600ms: state tidak dikonfirmasi sampai nilai stabil selama 600ms,
//   mencegah event spam saat kondisi cahaya berubah cepat (kipas/kedipan lampu).
// ─────────────────────────────────────────────────────────────────────────────
private const val DARK_UPPER_LUX     = 3.0f   // Masuk DARK jika lux < 3
private const val DARK_HYSTERESIS    = 6.0f   // Keluar DARK jika lux >= 6
private const val BRIGHT_LOWER_LUX  = 80.0f  // Masuk BRIGHT jika lux >= 80
private const val BRIGHT_HYSTERESIS  = 50.0f  // Keluar BRIGHT jika lux < 50
private const val LIGHT_DEBOUNCE_MS  = 600L   // Harus stabil selama 600ms

class SensorObserverManager(
    private val context: Context,
    private val scope: CoroutineScope
) : SensorEventListener {

    private val TAG = "SensorObserver"
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _sensorEvents = MutableSharedFlow<SensorObservedEvent>(extraBufferCapacity = 16)
    val sensorEvents: SharedFlow<SensorObservedEvent> = _sensorEvents.asSharedFlow()

    // Sensors
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val stepCounterSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    @Suppress("DEPRECATION")
    private val pickupSensor: Sensor? = sensorManager.getDefaultSensor(25) // Sensor.TYPE_PICK_UP_GESTURE
    private val sigMotionSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    // ─── Light Sensor State ───────────────────────────────────────────────────
    // currentLightState : state yang sudah dikonfirmasi & dikirim ke server
    // pendingLightState : state kandidat yang menunggu dikonfirmasi oleh debounce
    // lightDebounceJob  : Job coroutine debounce yang sedang aktif
    private var currentLightState: LightState? = null
    private var pendingLightState: LightState? = null
    private var lightDebounceJob: Job? = null

    // ─── Step Counter State ───────────────────────────────────────────────────
    private var lastRecordedStepCount: Int = -1
    private var lastMilestoneStepCount: Int = 0

    @Volatile
    var isListening = false
        private set

    // ─────────────────────────────────────────────────────────────────────────
    // TriggerEventListener for One-Shot sensors (Pickup & Significant Motion)
    // ─────────────────────────────────────────────────────────────────────────

    private val pickupTriggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            Log.i(TAG, " PHONE_PICKUP Gesture Detected!")
            emitEvent(
                eventName = "PHONE_PICKUP",
                summary = "HP baru saja diangkat pengguna",
                data = mapOf("timestamp" to System.currentTimeMillis())
            )
            // Re-register karena sensor ini bersifat one-shot (auto-cancel setelah trigger)
            registerPickupTrigger()
        }
    }

    private val sigMotionTriggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            Log.i(TAG, " SIGNIFICANT_MOTION Detected!")
            emitEvent(
                eventName = "MOTION_DETECTED",
                summary = "Terdeteksi pergerakan signifikan",
                data = mapOf("motion_state" to "MOVING")
            )
            registerSigMotionTrigger()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    fun startObserving() {
        if (isListening) return
        isListening = true
        Log.i(TAG, " Memulai SensorObserverManager...")

        // 1. Light Sensor (NONAKTIF STREAMING BACKGROUND)
        // Sesuai instruksi Tuan Faqih: Sensor cahaya tidak dikirim terus-menerus via WebSocket.
        // Pembacaan kini dilakukan secara on-demand via LightSensorHandler saat diminta oleh server.
        /*
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Light Sensor terdaftar.")
        } ?: Log.w(TAG, "Light Sensor tidak tersedia di perangkat ini.")
        */

        // 2. Register Step Counter
        stepCounterSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            Log.d(TAG, "Step Counter Sensor terdaftar.")
        } ?: Log.w(TAG, "Step Counter Sensor tidak tersedia di perangkat ini.")

        // 3. Register One-Shot Triggers
        // NONAKTIF SEMENTARA: Sesuai instruksi Tuan Faqih, sensor HP diangkat dinonaktifkan untuk hemat baterai.
        // registerPickupTrigger()
        registerSigMotionTrigger()
    }

    fun stopObserving() {
        if (!isListening) return
        isListening = false
        Log.i(TAG, " Menghentikan SensorObserverManager...")

        // Batalkan job debounce yang mungkin masih menunggu
        lightDebounceJob?.cancel()
        lightDebounceJob = null

        sensorManager.unregisterListener(this)
        pickupSensor?.let { sensorManager.cancelTriggerSensor(pickupTriggerListener, it) }
        sigMotionSensor?.let { sensorManager.cancelTriggerSensor(sigMotionTriggerListener, it) }

        // Reset state
        currentLightState = null
        pendingLightState = null
    }

    private fun registerPickupTrigger() {
        // NONAKTIF SEMENTARA: Sesuai instruksi Tuan Faqih (jangan dihapus, cukup nonaktifkan)
        /*
        if (pickupSensor != null && isListening) {
            sensorManager.requestTriggerSensor(pickupTriggerListener, pickupSensor)
        }
        */
    }

    private fun registerSigMotionTrigger() {
        if (sigMotionSensor != null && isListening) {
            sensorManager.requestTriggerSensor(sigMotionTriggerListener, sigMotionSensor)
        }
    }

    // ─────────────────────────────────────────────
    // SensorEventListener (Light & Step Counter)
    // ─────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (!isListening) return

        when (event.sensor.type) {
            Sensor.TYPE_LIGHT        -> handleLightSensor(event.values[0])
            Sensor.TYPE_STEP_COUNTER -> handleStepCounter(event.values[0].toInt())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Tidak diperlukan untuk logika threshold
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Light Sensor Handler — Dual-Threshold Hysteresis + Debounce
    //
    // Strategi:
    //   1. Tentukan kandidat state BARU berdasarkan hysteresis relatif terhadap
    //      state SAAT INI (bukan fixed threshold sederhana).
    //   2. Jika kandidat sama dengan state saat ini → abaikan, tidak ada perubahan.
    //   3. Jika kandidat BERBEDA dari state saat ini:
    //      a. Jika kandidat sama dengan pendingLightState → biarkan job berjalan.
    //      b. Jika kandidat BERBEDA dari pendingLightState → batalkan job lama,
    //         simpan kandidat baru, mulai debounce timer 600ms.
    //   4. Setelah 600ms stabil tanpa interupsi → konfirmasi state & kirim event.
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleLightSensor(lux: Float) {
        val candidate = classifyLuxWithHysteresis(lux)

        // Tidak ada perubahan → skip sepenuhnya
        if (candidate == currentLightState) {
            if (pendingLightState != null) {
                // Kembali ke state stabil sebelum debounce selesai → batalkan
                lightDebounceJob?.cancel()
                lightDebounceJob = null
                pendingLightState = null
            }
            return
        }

        // Kandidat baru berbeda dari state saat ini
        if (candidate == pendingLightState) {
            // Masih menunggu konfirmasi untuk kandidat yang sama → biarkan timer berjalan
            return
        }

        // Kandidat berbeda dari pending → ganti kandidat & restart timer
        lightDebounceJob?.cancel()
        pendingLightState = candidate

        lightDebounceJob = scope.launch {
            delay(LIGHT_DEBOUNCE_MS)

            // Jika masih listening setelah 600ms → state sudah stabil, konfirmasi
            if (!isListening) return@launch
            if (pendingLightState != candidate) return@launch // State berubah lagi sebelum timer selesai

            currentLightState = candidate
            pendingLightState = null

            val stateName = candidate.name
            val desc = when (candidate) {
                LightState.DARK   -> "Cahaya redup/gelap ($lux lux)"
                LightState.NORMAL -> "Cahaya normal ($lux lux)"
                LightState.BRIGHT -> "Cahaya terang ($lux lux)"
            }

            Log.i(TAG, " Light State Confirmed  $stateName ($lux lux) [setelah debounce ${LIGHT_DEBOUNCE_MS}ms]")
            emitEvent(
                eventName = "LIGHT_CHANGED",
                summary = desc,
                data = mapOf("state" to stateName, "lux" to lux)
            )
        }
    }

    /**
     * Mengklasifikasikan nilai lux menggunakan thresholds yang bergantung pada state saat ini.
     * Hysteresis mencegah osilasi di batas antara dua state.
     */
    private fun classifyLuxWithHysteresis(lux: Float): LightState {
        return when (currentLightState) {
            LightState.DARK -> when {
                lux >= DARK_HYSTERESIS  -> if (lux >= BRIGHT_LOWER_LUX) LightState.BRIGHT else LightState.NORMAL
                else                   -> LightState.DARK
            }
            LightState.BRIGHT -> when {
                lux < BRIGHT_HYSTERESIS -> if (lux < DARK_UPPER_LUX) LightState.DARK else LightState.NORMAL
                else                   -> LightState.BRIGHT
            }
            else -> when { // NORMAL atau null (initial read)
                lux < DARK_UPPER_LUX   -> LightState.DARK
                lux >= BRIGHT_LOWER_LUX -> LightState.BRIGHT
                else                   -> LightState.NORMAL
            }
        }
    }

    // ─────────────────────────────────────────────
    // Step Counter Handler
    // ─────────────────────────────────────────────

    private fun handleStepCounter(totalSteps: Int) {
        if (lastRecordedStepCount == -1) {
            // Sesi baru: catat baseline langkah saat ini tanpa emit event
            lastRecordedStepCount = totalSteps
            lastMilestoneStepCount = (totalSteps / 2500) * 2500
            Log.d(TAG, " Step Counter baseline: $totalSteps")
            return
        }

        lastRecordedStepCount = totalSteps

        // Kirim event hanya jika melewati milestone 2.500 langkah
        if (totalSteps >= lastMilestoneStepCount + 2500) {
            lastMilestoneStepCount = (totalSteps / 2500) * 2500
            Log.i(TAG, " Step Milestone Reached: $lastMilestoneStepCount steps")

            emitEvent(
                eventName = "STEP_MILESTONE",
                summary = "Pencapaian $lastMilestoneStepCount langkah kaki tercapai",
                data = mapOf(
                    "total_steps" to totalSteps,
                    "milestone"   to lastMilestoneStepCount
                )
            )
        }
    }

    // ─────────────────────────────────────────────
    // Emitter
    // ─────────────────────────────────────────────

    private fun emitEvent(eventName: String, summary: String, data: Map<String, Any>) {
        scope.launch {
            _sensorEvents.emit(SensorObservedEvent(eventName, summary, data))
        }
    }
}
