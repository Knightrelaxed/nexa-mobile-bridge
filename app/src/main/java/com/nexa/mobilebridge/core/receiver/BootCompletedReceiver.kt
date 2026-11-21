package com.nexa.mobilebridge.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexa.mobilebridge.NexaBridgeService

/**
 * Mencegat event ACTION_BOOT_COMPLETED untuk langsung menyalakan NexaBridgeService
 * begitu HP selesai restart.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            try {
                Log.i("NexaBridge", "Boot Completed terdeteksi! Menghidupkan NexaBridgeService otomatis...")
                val serviceIntent = Intent(context, NexaBridgeService::class.java)
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.w("NexaBridge", "Gagal menghidupkan NexaBridgeService saat boot: ${e.message}")
            }
        }
    }
}
