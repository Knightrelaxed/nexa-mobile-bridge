package com.nexa.mobilebridge.core.power

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-demand reference-counted WakeLock and WifiLock manager (FR-CORE-04).
 *
 * Unlike legacy permanent locks held from Service.onCreate(), locks are acquired
 * strictly during active command/transfer or live call execution and released
 * immediately once idle so Android Doze and battery life remain optimal.
 */
class BridgePowerLockManager(
    private val acquirePlatformLocks: (timeoutMs: Long) -> Unit,
    private val releasePlatformLocks: () -> Unit,
    private val onLog: (String) -> Unit = { Log.d(TAG, it) },
    private val nowElapsedMs: () -> Long = {
        runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.nanoTime() / 1_000_000L }
    }
) {
    companion object {
        private const val TAG = "BridgePowerLock"
        const val DEFAULT_TRANSFER_TIMEOUT_MS = 15_000L
        const val MAX_CALL_TIMEOUT_MS = 30 * 60_000L // 30-minute safety cap per call acquire

        fun create(context: Context): BridgePowerLockManager {
            val appCtx = context.applicationContext
            val powerManager = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wifiManager = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager

            val wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NexaBridge::ActiveTransferWakeLock"
            )?.apply {
                setReferenceCounted(false)
            }

            val wifiLock = wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                "NexaBridge::ActiveTransferWifiLock"
            )?.apply {
                setReferenceCounted(false)
            }

            return BridgePowerLockManager(
                acquirePlatformLocks = { timeoutMs ->
                    try {
                        wakeLock?.let { wl ->
                            wl.acquire(timeoutMs)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
                    }
                    try {
                        wifiLock?.let { wl ->
                            if (!wl.isHeld) wl.acquire()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to acquire WifiLock: ${e.message}")
                    }
                },
                releasePlatformLocks = {
                    try {
                        wakeLock?.let { wl ->
                            if (wl.isHeld) wl.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to release WakeLock: ${e.message}")
                    }
                    try {
                        wifiLock?.let { wl ->
                            if (wl.isHeld) wl.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to release WifiLock: ${e.message}")
                    }
                }
            )
        }
    }

    private val lock = Any()
    private val activeRefs = AtomicInteger(0)
    private var callLockHeld = false
    private var lockDeadlineElapsedMs = 0L

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    val activeRefCount: Int
        get() = activeRefs.get()

    fun acquire(reason: String, timeoutMs: Long = DEFAULT_TRANSFER_TIMEOUT_MS) {
        synchronized(lock) {
            val refs = activeRefs.incrementAndGet()
            val newDeadline = nowElapsedMs() + timeoutMs
            // A-H12: Re-acquire platform WakeLock whenever a nested/overlapping acquire has a longer deadline
            if (refs == 1 || newDeadline > lockDeadlineElapsedMs) {
                lockDeadlineElapsedMs = newDeadline
                acquirePlatformLocks(timeoutMs)
                _isLocked.value = true
                onLog("Locks acquired/extended (reason=$reason, refs=$refs, timeoutMs=$timeoutMs)")
            }
        }
    }

    fun release(reason: String) {
        synchronized(lock) {
            val current = activeRefs.get()
            if (current <= 0) {
                activeRefs.set(0)
                lockDeadlineElapsedMs = 0L
                if (_isLocked.value) {
                    releasePlatformLocks()
                    _isLocked.value = false
                }
                return
            }
            val remaining = activeRefs.decrementAndGet()
            if (remaining == 0) {
                lockDeadlineElapsedMs = 0L
                releasePlatformLocks()
                _isLocked.value = false
                onLog("Locks released -> IDLE (reason=$reason)")
            }
        }
    }

    fun setCallLock(active: Boolean) {
        synchronized(lock) {
            if (active && !callLockHeld) {
                callLockHeld = true
                acquire("live_call", MAX_CALL_TIMEOUT_MS)
            } else if (!active && callLockHeld) {
                callLockHeld = false
                release("live_call")
            }
        }
    }

    suspend inline fun <T> withActiveTransferLock(
        reason: String,
        timeoutMs: Long = DEFAULT_TRANSFER_TIMEOUT_MS,
        block: suspend () -> T
    ): T {
        acquire(reason, timeoutMs)
        return try {
            block()
        } finally {
            release(reason)
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            callLockHeld = false
            activeRefs.set(0)
            releasePlatformLocks()
            _isLocked.value = false
            onLog("All power locks force-released")
        }
    }
}
