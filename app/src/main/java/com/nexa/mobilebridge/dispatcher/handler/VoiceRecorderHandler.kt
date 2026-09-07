package com.nexa.mobilebridge.dispatcher.handler

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * General-purpose microphone audio recorder for N.E.X.A.
 *
 * Records mono 16-bit PCM at 16kHz — the universal standard for STT APIs
 * (Whisper, Google STT, Azure, etc.) so this handler can be reused for:
 *
 * -  Call Interaction: 10s user voice reply after accepting SIMULATE_INCOMING_CALL
 * -  Voice Command: Short utterance capture for real-time voice control
 * -  Voice Memo: Any-duration field notes recording
 * -  Ambient Capture: Background noise/context sampling
 *
 * Usage:
 * ```kotlin
 * val recorder = VoiceRecorderHandler(context)
 * val base64Audio = recorder.record(durationMs = 5000) { elapsedMs ->
 *     // update UI timer
 * }
 * ```
 */
class VoiceRecorderHandler(private val context: Context) {

    private val TAG = "VoiceRecorderHandler"

    companion object {
        const val SAMPLE_RATE    = 16000                          // 16 kHz — STT optimal
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT
        const val TICK_MS        = 100L                           // Timer granularity
        const val AUDIO_FORMAT_NAME = "PCM_16BIT_16KHZ_MONO"     // For server metadata
    }

    /**
     * Records audio for [durationMs] milliseconds from the device microphone.
     *
     * @param durationMs  Total recording duration in milliseconds. Default: 10 seconds.
     * @param onTickMs    Callback every [TICK_MS] with elapsed milliseconds (0..durationMs).
     *                    Use this to drive UI countdown timers.
     * @return Base64-encoded PCM audio string ready for WebSocket/HTTP transmission,
     *         or null if permission is denied or hardware initialisation fails.
     */
    suspend fun record(
        durationMs: Long = 10_000L,
        onTickMs: (elapsedMs: Long) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {

        // ── Permission guard ────────────────────────────────────────────────
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted — aborting recording")
            return@withContext null
        }

        // ── AudioRecord setup ───────────────────────────────────────────────
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioRecord buffer size error: $minBuffer")
            return@withContext null
        }

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBuffer * 4    // 4× buffer for stability on low-end hardware
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException initialising AudioRecord: ${e.message}")
            return@withContext null
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise — hardware may be busy")
            audioRecord.release()
            return@withContext null
        }

        // ── Recording loop ──────────────────────────────────────────────────
        val out      = ByteArrayOutputStream()
        val buf      = ByteArray(minBuffer)
        var elapsed  = 0L

        return@withContext try {
            audioRecord.startRecording()
            Log.i(TAG, " Recording started — ${durationMs}ms")

            // Hard timeout = durationMs + 1s to guard against edge cases
            withTimeoutOrNull(durationMs + 1000L) {
                while (elapsed < durationMs) {
                    val bytesRead = audioRecord.read(buf, 0, buf.size)
                    if (bytesRead > 0) out.write(buf, 0, bytesRead)

                    elapsed = (elapsed + TICK_MS).coerceAtMost(durationMs)
                    onTickMs(elapsed)
                    delay(TICK_MS)
                }
            }

            audioRecord.stop()
            val bytes = out.toByteArray()
            Log.i(TAG, " Recording complete — ${bytes.size} bytes (${durationMs}ms)")

            Base64.encodeToString(bytes, Base64.NO_WRAP)

        } catch (e: Exception) {
            Log.e(TAG, "Recording error: ${e.message}", e)
            null
        } finally {
            audioRecord.release()
            out.close()
        }
    }
}
