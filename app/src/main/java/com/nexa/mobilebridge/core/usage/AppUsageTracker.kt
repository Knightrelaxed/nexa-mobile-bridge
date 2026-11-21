package com.nexa.mobilebridge.core.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import com.nexa.mobilebridge.NexaBridgeService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Calendar

/**
 * ============================================================
 * N.E.X.A MOBILE BRIDGE - APP USAGE & DURATION TRACKER
 * Native Android 16 (API 36) UsageStatsManager (Banking-Safe, No AccessibilityService)
 * Tracks live session durations and daily accumulated usage.
 * ============================================================
 */
object AppUsageTracker {

    private const val TAG = "AppUsageTracker"
    private const val USAGE_POLL_WINDOW_MS = 65_000L

    // Ignore Android system UI & launchers from duration breach enforcement
    private val IGNORED_PACKAGES = setOf(
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.android.systemui",
        "com.android.settings",
        "com.nexa.mobilebridge"
    )

    @Volatile private var currentForegroundPackage: String? = null
    @Volatile private var sessionStartTimeMs: Long = 0
    @Volatile private var lastReportedSessionMinute: Long = 0
    @Volatile private var lastEventTimestampMs: Long = 0L

    /**
     * Memeriksa apakah izin PACKAGE_USAGE_STATS telah diberikan oleh pemilik perangkat.
     */
    @Suppress("DEPRECATION")
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check UsageStats permission: ${e.message}")
            false
        }
    }

    /**
     * Memeriksa perubahan aplikasi foreground melalui UsageStatsManager.queryEvents()
     * (pengganti NexaAccessibilityService yang aman untuk aplikasi m-banking / FR-CORE-03).
     * Sebaiknya dipanggil hanya saat layar menyala.
     */
    fun pollForegroundAppFromUsageEvents(context: Context) {
        if (!hasUsageStatsPermission(context)) return
        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return
            val endTime = System.currentTimeMillis()
            val startTime = if (lastEventTimestampMs > 0L) {
                maxOf(lastEventTimestampMs + 1L, endTime - USAGE_POLL_WINDOW_MS)
            } else {
                endTime - USAGE_POLL_WINDOW_MS
            }
            if (startTime >= endTime) return
            val events = usageStatsManager.queryEvents(startTime, endTime) ?: return
            val event = UsageEvents.Event()
            var latestResumedPackage: String? = null
            var latestPausedPackage: String? = null
            var latestResumedTs = 0L
            var latestPausedTs = 0L
            var maxSeenTs = lastEventTimestampMs

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.timeStamp > maxSeenTs) maxSeenTs = event.timeStamp
                val pkg = event.packageName
                if (pkg.isNullOrBlank()) continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (event.timeStamp >= latestResumedTs) {
                            latestResumedTs = event.timeStamp
                            latestResumedPackage = pkg
                        }
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        if (event.timeStamp >= latestPausedTs) {
                            latestPausedTs = event.timeStamp
                            latestPausedPackage = pkg
                        }
                    }
                }
            }
            lastEventTimestampMs = maxSeenTs

            // A-M8: Only switch to resumed app if not subsequently paused/stopped without a newer resume
            if (latestResumedPackage != null && latestResumedTs >= latestPausedTs) {
                onForegroundAppChanged(context, latestResumedPackage)
            } else if (latestPausedPackage != null && latestPausedPackage == currentForegroundPackage && latestPausedTs > latestResumedTs) {
                finalizeCurrentSession(context)
                currentForegroundPackage = null
                sessionStartTimeMs = 0
                lastReportedSessionMinute = 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "UsageEvents poll failed: ${e.message}")
        }
    }

    /**
     * Dipanggil ketika pergantian aplikasi foreground terdeteksi via UsageStatsManager.
     */
    fun onForegroundAppChanged(context: Context, newPackageName: String) {
        if (newPackageName == currentForegroundPackage || newPackageName.isEmpty()) return
        if (IGNORED_PACKAGES.contains(newPackageName)) {
            finalizeCurrentSession(context)
            currentForegroundPackage = null
            sessionStartTimeMs = 0
            lastReportedSessionMinute = 0
            return
        }

        finalizeCurrentSession(context)

        currentForegroundPackage = newPackageName
        sessionStartTimeMs = System.currentTimeMillis()
        lastReportedSessionMinute = 0

        Log.d(TAG, "App switched to: $newPackageName")
        emitUsageTelemetry(context, newPackageName, sessionMinutes = 0)
    }

    /**
     * Dipanggil secara periodik (setiap 30 detik) dari NexaBridgeService
     * untuk memantau durasi sesi yang sedang aktif.
     */
    fun checkActiveSessionTick(context: Context, isScreenOn: Boolean = true) {
        if (!isScreenOn) {
            // Layar mati -> akhiri sesi aktif agar durasi tidak melambung saat standby
            if (currentForegroundPackage != null) {
                finalizeCurrentSession(context)
                currentForegroundPackage = null
                sessionStartTimeMs = 0
                lastReportedSessionMinute = 0
            }
            return
        }

        pollForegroundAppFromUsageEvents(context)

        val currentPkg = currentForegroundPackage ?: return
        if (sessionStartTimeMs == 0L || IGNORED_PACKAGES.contains(currentPkg)) return

        val elapsedMs = System.currentTimeMillis() - sessionStartTimeMs
        val elapsedMinutes = elapsedMs / (60 * 1000)

        if (elapsedMinutes >= 1 && elapsedMinutes != lastReportedSessionMinute) {
            lastReportedSessionMinute = elapsedMinutes
            emitUsageTelemetry(context, currentPkg, elapsedMinutes)
        }
    }

    private fun finalizeCurrentSession(context: Context) {
        val currentPkg = currentForegroundPackage ?: return
        if (sessionStartTimeMs == 0L) return

        val totalSessionMs = System.currentTimeMillis() - sessionStartTimeMs
        val totalSessionMinutes = totalSessionMs / (60 * 1000)

        if (totalSessionMinutes >= 1) {
            Log.i(TAG, "Session finalized for $currentPkg: ${totalSessionMinutes}m")
            emitUsageTelemetry(context, currentPkg, totalSessionMinutes)
        }
    }

    /**
     * Menghitung total waktu penggunaan hari ini (akumulasi) via UsageStatsManager.
     */
    fun getDailyUsageMinutes(context: Context, packageName: String): Long {
        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return 0L

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfDay = calendar.timeInMillis
            val now = System.currentTimeMillis()

            // A-M8: Use queryAndAggregateUsageStats so multi-bucket daily usage is summed accurately
            val aggregated = usageStatsManager.queryAndAggregateUsageStats(startOfDay, now)
            val foregroundTimeMs = aggregated?.get(packageName)?.totalTimeInForeground ?: 0L
            foregroundTimeMs / (60 * 1000)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query UsageStatsManager: ${e.message}")
            0L
        }
    }

    private fun getAppLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    private fun emitUsageTelemetry(context: Context, packageName: String, sessionMinutes: Long) {
        // A-M1 / NFR-BAT-03: Drop non-critical telemetry when outbound WS queue is congested
        if (NexaBridgeService.getWebSocketQueueSize() >= 16_384L) return
        val dailyTotalMinutes = getDailyUsageMinutes(context, packageName)
        val appLabel = getAppLabel(context, packageName)

        val payload = buildJsonObject {
            put("type", "APP_USAGE_TELEMETRY")
            put("package_name", packageName)
            put("app_name", appLabel)
            put("session_minutes", sessionMinutes)
            put("daily_total_minutes", dailyTotalMinutes)
            put("timestamp", System.currentTimeMillis().toString())
        }.toString()

        Log.i(TAG, "Emitting Usage Telemetry: $appLabel | Session=${sessionMinutes}m | Daily=${dailyTotalMinutes}m")
        NexaBridgeService.sendEvent(payload)
    }
}
