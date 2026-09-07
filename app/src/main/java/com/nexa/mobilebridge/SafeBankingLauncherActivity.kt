package com.nexa.mobilebridge
                
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.nexa.mobilebridge.core.security.MBankingShieldManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context

class SafeBankingLauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val targetPackage = intent.getStringExtra("TARGET_PACKAGE")
        if (targetPackage.isNullOrBlank()) {
            Toast.makeText(this, "SafeBanking: No target package provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Log.i("Nexa-SafeBanking", "Initiating Safe Launch for: $targetPackage")
        
        lifecycleScope.launch {
            // 1. PRE-KILL (Matikan Aksesibilitas)
            val disabled = MBankingShieldManager.disableNexaAccessibility(this@SafeBankingLauncherActivity)
            if (!disabled) {
                Toast.makeText(this@SafeBankingLauncherActivity, "N.E.X.A Shield Failed! Did you grant WRITE_SECURE_SETTINGS via ADB?", Toast.LENGTH_LONG).show()
            }
            
            // 2. SMART WAIT (Tunggu Sistem OS Android Beneran Mereset Memori)
            // Sistem Android butuh waktu untuk mencabut SEMUA service dari memori aktif
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            var isTrulyDead = false
            for (i in 1..50) { // Tunggu maksimal 5 detik (50 * 100ms)
                val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                
                if (enabledServices.isEmpty()) {
                    isTrulyDead = true
                    Log.i("Nexa-SafeBanking", "OS AccessibilityManager confirmed ALL services are DEAD in ${i * 100}ms.")
                    break
                } else {
                    val remaining = enabledServices.map { it.resolveInfo.serviceInfo.packageName }
                    Log.d("Nexa-SafeBanking", "Still waiting... Active services: $remaining")
                }
                delay(100)
            }

            if (!isTrulyDead) {
                Log.w("Nexa-SafeBanking", "Peringatan: Sistem Android belum mencabut semua service tepat waktu.")
            }

            // Jeda aman solid (600ms) sesuai arahan Tuan Faqih: Menjamin DexGuard/Promon tidak membaca sisa-sisa cache
            Log.i("Nexa-SafeBanking", "Holding 600ms safety buffer before launching banking app...")
            delay(600)

            // 3. LAUNCH TARGET (Buka M-Banking)
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(launchIntent)
                    
                    // 4. SIGNAL BACKGROUND OBSERVER
                    // Beri tahu NexaBridgeService untuk mulai memantau kapan Livin ditutup
                    val serviceIntent = Intent(this@SafeBankingLauncherActivity, NexaBridgeService::class.java).apply {
                        action = "com.nexa.mobilebridge.ACTION_START_USAGE_OBSERVER"
                        putExtra("WATCHING_PACKAGE", targetPackage)
                    }
                    startService(serviceIntent)
                } else {
                    Toast.makeText(this@SafeBankingLauncherActivity, "App not installed: $targetPackage", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("Nexa-SafeBanking", "Failed to launch $targetPackage", e)
                Toast.makeText(this@SafeBankingLauncherActivity, "Error launching app.", Toast.LENGTH_SHORT).show()
            }
            
            // 5. SELESAI (Hancurkan peluncur ini)
            finish()
        }
    }
}
