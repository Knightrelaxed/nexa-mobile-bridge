package com.nexa.mobilebridge.core.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.math.pow

// ─────────────────────────────────────────────
// Data Models
// ─────────────────────────────────────────────

enum class ReconnectErrorType {
    NO_INTERNET,   // UnknownHostException — tidak ada internet / DNS gagal
    SERVER_DOWN,   // ConnectException — server offline / koneksi ditolak
    TIMEOUT,       // SocketTimeoutException — server tidak merespons
    SSL_ERROR,     // SSLException — sertifikat atau keamanan bermasalah
    UNKNOWN        // Error lain yang tidak terklasifikasi
}

/**
 * Event yang diemisikan saat seluruh percobaan pada satu fase gagal.
 *
 * @param phase        Fase ke berapa (2, 3, atau 4). Fase 1 tidak menghasilkan notifikasi.
 * @param errorType    Jenis error yang terdeteksi.
 * @param techMessage  Pesan teknis asli dari Throwable.
 * @param isFinal      True jika ini adalah kegagalan terakhir (Fase 4) — tidak ada percobaan lagi.
 * @param nextRetryIn  Keterangan waktu percobaan berikutnya ("6 jam", "24 jam", atau "" jika final).
 */
data class PhaseFailureEvent(
    val phase: Int,
    val errorType: ReconnectErrorType,
    val techMessage: String,
    val isFinal: Boolean,
    val nextRetryIn: String
)

// ─────────────────────────────────────────────
// ReconnectOrchestrator
// ─────────────────────────────────────────────

/**
 * Mengelola logika percobaan ulang koneksi (reconnect) secara bertingkat (4 fase).
 *
 * FASE 1 — Segera: 10 percobaan dengan exponential backoff (maks 30 detik/percobaan).
 *          Jika gagal semua → tunggu 5 menit.
 * FASE 2 — +5 Menit: 10 percobaan lagi.
 *          Jika gagal semua → kirim [PhaseFailureEvent] → tunggu 30 menit.
 * FASE 3 — +30 Menit: 10 percobaan lagi.
 *          Jika gagal semua → kirim [PhaseFailureEvent] → tunggu 2 jam.
 * FASE 4 — +2 Jam: 10 percobaan terakhir.
 *          Jika gagal semua → kirim [PhaseFailureEvent] (isFinal=true) → BERHENTI TOTAL.
 *
 * @param scope          CoroutineScope tempat semua coroutine reconnect dijalankan.
 * @param triggerConnect Lambda yang akan dipanggil untuk memulai satu upaya koneksi.
 */
class ReconnectOrchestrator(
    private val scope: CoroutineScope,
    private val triggerConnect: () -> Unit
) {
    private val TAG = "ReconnectOrchestrator"

    private val _phaseFailureEvents = MutableSharedFlow<PhaseFailureEvent>(extraBufferCapacity = 8)
    val phaseFailureEvents: SharedFlow<PhaseFailureEvent> = _phaseFailureEvents.asSharedFlow()

    private var currentPhase = 1
    private var attemptInPhase = 0

    @Volatile
    var isStopped = false
        private set

    private var reconnectJob: Job? = null

    companion object {
        const val MAX_ATTEMPTS      = 10
        const val INITIAL_DELAY_MS  = 2_000L
        const val MAX_BACKOFF_MS    = 30_000L

        // Durasi tunggu antar-fase (jauh lebih singkat & realistis)
        val WAIT_PHASE_2_MS =  5L * 60 * 1_000     // 5 menit
        val WAIT_PHASE_3_MS = 30L * 60 * 1_000     // 30 menit
        val WAIT_PHASE_4_MS =  2L * 60 * 60 * 1_000 // 2 jam
    }

    /** Dipanggil ketika koneksi WebSocket berhasil dibuka. Mereset semua state fase. */
    fun onConnectionSuccess() {
        reconnectJob?.cancel()
        currentPhase = 1
        attemptInPhase = 0
        isStopped = false
        Log.i(TAG, "✅ Koneksi berhasil. Fase & percobaan direset ke awal.")
    }

    /** Dipanggil ketika user secara eksplisit menghentikan service. Membatalkan semua jadwal. */
    fun stop() {
        isStopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        currentPhase = 1
        attemptInPhase = 0
        Log.i(TAG, "🛑 Reconnect dihentikan oleh user.")
    }

    /**
     * Mereset seluruh state orchestrator untuk memulai sesi koneksi baru.
     * Wajib dipanggil sebelum [onConnectionFailed] bisa bekerja kembali setelah [stop].
     */
    fun reset() {
        isStopped = false
        currentPhase = 1
        attemptInPhase = 0
        reconnectJob?.cancel()
        reconnectJob = null
        Log.i(TAG, "🔄 Orchestrator direset untuk sesi koneksi baru.")
    }

    /** Dipanggil ketika koneksi WebSocket gagal (dari onFailure). */
    fun onConnectionFailed(error: Throwable) {
        if (isStopped) {
            Log.d(TAG, "Reconnect diabaikan — service sudah dihentikan oleh user.")
            return
        }
        scheduleNextAttempt(error)
    }

    // ─────────────────────────────────────────────
    // Private Logic
    // ─────────────────────────────────────────────

    private fun scheduleNextAttempt(error: Throwable) {
        if (isStopped) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            attemptInPhase++

            if (attemptInPhase <= MAX_ATTEMPTS) {
                // Masih dalam batas percobaan fase saat ini — gunakan exponential backoff
                val backoffMs = (INITIAL_DELAY_MS * 2.0.pow((attemptInPhase - 1).toDouble()))
                    .toLong()
                    .coerceAtMost(MAX_BACKOFF_MS)

                Log.i(
                    TAG,
                    "🔄 Fase $currentPhase | Percobaan $attemptInPhase/$MAX_ATTEMPTS | " +
                    "Tunggu ${backoffMs / 1000}s | Error: ${error.javaClass.simpleName}"
                )
                delay(backoffMs)
                if (!isStopped) triggerConnect()

            } else {
                // Semua 20 percobaan pada fase ini habis — tentukan langkah selanjutnya
                attemptInPhase = 0
                val errorType = classifyError(error)
                val techMsg   = error.message ?: error.javaClass.simpleName

                when (currentPhase) {
                    1 -> {
                        // Fase 1 habis → tunggu 5 menit → mulai Fase 2 (tanpa notifikasi)
                        Log.w(TAG, "⏳ Fase 1 habis (${MAX_ATTEMPTS}x). Menunggu 5 menit sebelum Fase 2...")
                        currentPhase = 2
                        delay(WAIT_PHASE_2_MS)
                        if (!isStopped) triggerConnect()
                    }
                    2 -> {
                        // Fase 2 habis → NOTIFIKASI → tunggu 30 menit → mulai Fase 3
                        Log.w(TAG, "⚠️ Fase 2 habis (${MAX_ATTEMPTS}x). Memberitahu user. Tunggu 30 menit...")
                        _phaseFailureEvents.emit(
                            PhaseFailureEvent(2, errorType, techMsg, false, "30 menit")
                        )
                        currentPhase = 3
                        delay(WAIT_PHASE_3_MS)
                        if (!isStopped) triggerConnect()
                    }
                    3 -> {
                        // Fase 3 habis → NOTIFIKASI → tunggu 2 jam → mulai Fase 4
                        Log.w(TAG, "⚠️ Fase 3 habis (${MAX_ATTEMPTS}x). Memberitahu user. Tunggu 2 jam...")
                        _phaseFailureEvents.emit(
                            PhaseFailureEvent(3, errorType, techMsg, false, "2 jam")
                        )
                        currentPhase = 4
                        delay(WAIT_PHASE_4_MS)
                        if (!isStopped) triggerConnect()
                    }
                    4 -> {
                        // Fase 4 habis → NOTIFIKASI FINAL → BERHENTI TOTAL
                        Log.e(TAG, "❌ Fase 4 habis (${MAX_ATTEMPTS}x). Semua percobaan gagal. Berhenti total.")
                        _phaseFailureEvents.emit(
                            PhaseFailureEvent(4, errorType, techMsg, true, "")
                        )
                        isStopped = true
                    }
                    else -> {
                        Log.e(TAG, "Fase tidak dikenal ($currentPhase). Berhenti.")
                        isStopped = true
                    }
                }
            }
        }
    }

    /** Mengklasifikasikan jenis error dari Throwable ke enum [ReconnectErrorType]. */
    private fun classifyError(t: Throwable): ReconnectErrorType = when (t) {
        is UnknownHostException -> ReconnectErrorType.NO_INTERNET
        is ConnectException     -> ReconnectErrorType.SERVER_DOWN
        is SocketTimeoutException -> ReconnectErrorType.TIMEOUT
        is SSLException         -> ReconnectErrorType.SSL_ERROR
        else                    -> ReconnectErrorType.UNKNOWN
    }
}
