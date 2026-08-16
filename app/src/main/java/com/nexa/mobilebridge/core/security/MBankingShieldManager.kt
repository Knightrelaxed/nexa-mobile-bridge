package com.nexa.mobilebridge.core.security
                
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * M-Banking Shield Manager v2
 * 
 * Strategi: Menyimpan dan menghapus SELURUH daftar accessibility service yang aktif,
 * bukan hanya N.E.X.A. Ini diperlukan karena DexGuard/Promon pada M-Banking
 * menggunakan multi-layer detection yang menolak APAPUN accessibility service
 * pihak ketiga, bukan hanya satu service tertentu.
 * 
 * Flow:
 * 1. disableNexaAccessibility() → Simpan daftar lengkap ke SharedPreferences → Kosongkan
 * 2. enableNexaAccessibility() → Baca dari SharedPreferences → Tulis kembali
 */
object MBankingShieldManager {
    private const val TAG = "Nexa-MBankingShield"
    private const val PREFS_NAME = "nexa_shield_prefs"
    private const val KEY_SAVED_SERVICES = "saved_accessibility_services"
    private const val NEXA_ACCESSIBILITY_COMPONENT = "com.nexa.mobilebridge/com.nexa.mobilebridge.NexaAccessibilityService"

    /**
     * FASE 1: PRE-KILL — Simpan seluruh daftar accessibility service, lalu kosongkan semuanya.
     * DexGuard tidak akan menemukan satupun service pihak ketiga yang aktif.
     */
    fun disableNexaAccessibility(context: Context): Boolean {
        try {
            val contentResolver = context.contentResolver
            val currentEnabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            
            // Jika sudah kosong, tidak perlu lakukan apa-apa
            if (currentEnabled.isBlank()) {
                Log.d(TAG, "All Accessibility Services already disabled.")
                return true
            }

            // Simpan daftar lengkap ke SharedPreferences sebelum dihapus
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SAVED_SERVICES, currentEnabled).apply()
            Log.i(TAG, "Saved ${currentEnabled.split(":").size} services to restore later: $currentEnabled")

            // Kosongkan SELURUH daftar accessibility service
            Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
            
            // Matikan flag master accessibility — ini KRITIS karena DexGuard juga mengecek
            // AccessibilityManager.isEnabled() yang membaca flag ini
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            
            Log.i(TAG, "Shield Active: ALL Accessibility Services Disabled.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable Accessibility via Secure Settings. Did you grant WRITE_SECURE_SETTINGS via ADB?", e)
            return false
        }
    }

    /**
     * FASE 2: POST-RESTORE — Baca daftar yang disimpan, lalu tulis kembali ke Settings.Secure.
     * Semua service (termasuk N.E.X.A dan Spaceship TextCopy) akan kembali aktif.
     */
    fun enableNexaAccessibility(context: Context): Boolean {
        try {
            val contentResolver = context.contentResolver
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedServices = prefs.getString(KEY_SAVED_SERVICES, null)
            
            if (savedServices.isNullOrBlank()) {
                // Fallback: Jika tidak ada data tersimpan, coba restore hanya N.E.X.A
                Log.w(TAG, "No saved services found in SharedPreferences. Falling back to NEXA-only restore.")
                val currentEnabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                if (currentEnabled.contains(NEXA_ACCESSIBILITY_COMPONENT)) {
                    Log.d(TAG, "N.E.X.A Accessibility is already enabled.")
                    return true
                }
                val updatedList = if (currentEnabled.isBlank()) {
                    NEXA_ACCESSIBILITY_COMPONENT
                } else {
                    "$currentEnabled:$NEXA_ACCESSIBILITY_COMPONENT"
                }
                Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updatedList)
                Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.i(TAG, "Shield Deactivated: N.E.X.A Accessibility Restored (fallback).")
                return true
            }

            // Restore seluruh daftar yang tersimpan
            Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, savedServices)
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            
            // Bersihkan data tersimpan agar tidak stale
            prefs.edit().remove(KEY_SAVED_SERVICES).apply()
            
            Log.i(TAG, "Shield Deactivated: ALL ${savedServices.split(":").size} Accessibility Services Restored.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable Accessibility via Secure Settings. Did you grant WRITE_SECURE_SETTINGS via ADB?", e)
            return false
        }
    }
}
