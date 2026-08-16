package com.nexa.mobilebridge.core.usage

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.nexa.mobilebridge.NexaBridgeService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Calendar

/**
 * ============================================================
 * N.E.X.A MOBILE BRIDGE — APP USAGE & DURATION TRACKER
 * Native Android 16 (API 36) UsageStatsManager & Accessibility Bridge
 * Tracks live session durations and daily accumulated usage.
 * ============================================================
 */
object AppUsageTracker {

    private const val TAG = "AppUsageTracker"

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

    /**
     * Dipanggil oleh NexaAccessibilityService saat mendeteksi pergantian window/app aktif.
     */
    fun onForegroundAppChanged(context: Context, newPackageName: String) {
        if (newPackageName == currentForegroundPackage || newPackageName.isEmpty()) return
        if (IGNORED_PACKAGES.contains(newPackageName)) {
            // Kembali ke Launcher atau System UI -> finalize sesi sebelumnya
            finalizeCurrentSession(context)
            currentForegroundPackage = null
            sessionStartTimeMs = 0
            lastReportedSessionMinute = 0
            return
        }

        // Finalize previous app session if any
        finalizeCurrentSession(context)

        // Start new session
        currentForegroundPackage = newPackageName
        sessionStartTimeMs = System.currentTimeMillis()
        lastReportedSessionMinute = 0

        Log.d(TAG, "📱 App switched to: $newPackageName")
        emitUsageTelemetry(context, newPackageName, sessionMinutes = 0)
    }

    /**
     * Dipanggil secara periodik (setiap 30-60 detik) dari NexaBridgeService
     * untuk memantau durasi sesi yang sedang aktif.
     */
    fun checkActiveSessionTick(context: Context) {
        val currentPkg = currentForegroundPackage ?: return
        if (sessionStartTimeMs == 0L || IGNORED_PACKAGES.contains(currentPkg)) return

        val elapsedMs = System.currentTimeMillis() - sessionStartTimeMs
        val elapsedMinutes = elapsedMs / (60 * 1000)

        // Kirim telemetri setiap penambahan menit baru jika aplikasi aktif >= 1 menit
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
            Log.i(TAG, "🏁 Session finalized for $currentPkg: ${totalSessionMinutes}m")
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

            val statsList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startOfDay,
                now
            )

            if (statsList.isNullOrEmpty()) return 0L

            val appStats = statsList.find { it.packageName == packageName }
            val foregroundTimeMs = appStats?.totalTimeInForeground ?: 0L
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
        } catch (_) {
            packageName.substringAfterLast('.')
        }
    }

    private fun emitUsageTelemetry(context: Context, packageName: String, sessionMinutes: Long) {
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

        Log.i(TAG, "📊 Emitting Usage Telemetry: $appLabel | Session=${sessionMinutes}m | Daily=${dailyTotalMinutes}m")
        NexaBridgeService.sendEvent(payload)
    }
}
