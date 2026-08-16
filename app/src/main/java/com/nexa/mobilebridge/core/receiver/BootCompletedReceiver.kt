package com.nexa.mobilebridge.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.nexa.mobilebridge.NexaBridgeService

/**
 * Mencegat event ACTION_BOOT_COMPLETED untuk langsung menyalakan NexaBridgeService
 * begitu HP selesai restart. Ini membuat NEXA menjadi agen yang Always-Alive.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i("NexaBridge", "🚀 Boot Completed terdeteksi! Menghidupkan NexaBridgeService otomatis...")
            
            val serviceIntent = Intent(context, NexaBridgeService::class.java)
            
            // Pada Android 8.0 (API 26)+, Foreground Service harus dimulai menggunakan startForegroundService
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
