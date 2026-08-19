package com.nexa.mobilebridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.nexa.mobilebridge.NexaBridgeService
import com.nexa.mobilebridge.theme.NexaMobileBridgeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setupBankingShortcuts()

        // Auto-start NexaBridgeService foreground service upon activity launch
        try {
            val serviceIntent = Intent(this, NexaBridgeService::class.java)
            startForegroundService(serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        setContent {
            NexaMobileBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(
                        onToggleService = { start ->
                            val intent = Intent(this, NexaBridgeService::class.java)
                            if (start) {
                                startForegroundService(intent)
                            } else {
                                stopService(intent)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun setupBankingShortcuts() {
        val shortcuts = mutableListOf<ShortcutInfoCompat>()
        
        // 1. Livin' Mandiri
        val livinIntent = Intent(this, SafeBankingLauncherActivity::class.java).apply {
            action = "com.nexa.mobilebridge.ACTION_LAUNCH_SAFE_BANKING"
            putExtra("TARGET_PACKAGE", "id.bmri.livin")
        }
        val livinShortcut = ShortcutInfoCompat.Builder(this, "shortcut_livin_safe")
            .setShortLabel("Safe Livin'")
            .setLongLabel("Safe Livin'")
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_livin_logo))
            .setIntent(livinIntent)
            .build()
        shortcuts.add(livinShortcut)

        // 2. BCA Mobile
        val bcaIntent = Intent(this, SafeBankingLauncherActivity::class.java).apply {
            action = "com.nexa.mobilebridge.ACTION_LAUNCH_SAFE_BANKING"
            putExtra("TARGET_PACKAGE", "com.bca.mobile")
        }
        val bcaShortcut = ShortcutInfoCompat.Builder(this, "shortcut_bca_safe")
            .setShortLabel("Safe BCA")
            .setLongLabel("BCA Mobile (NEXA Shield)")
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(bcaIntent)
            .build()
        shortcuts.add(bcaShortcut)

        // 3. TARGET PENGUJIAN AMAN (Chrome)
        val testIntent = Intent(this, SafeBankingLauncherActivity::class.java).apply {
            action = "com.nexa.mobilebridge.ACTION_LAUNCH_SAFE_BANKING"
            putExtra("TARGET_PACKAGE", "com.android.chrome")
        }
        val testShortcut = ShortcutInfoCompat.Builder(this, "shortcut_test_safe")
            .setShortLabel("Test Shield")
            .setLongLabel("Test Shield (Chrome)")
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(testIntent)
            .build()
        shortcuts.add(testShortcut)

        // Push shortcuts to Launcher
        ShortcutManagerCompat.addDynamicShortcuts(this, shortcuts)
    }
}
