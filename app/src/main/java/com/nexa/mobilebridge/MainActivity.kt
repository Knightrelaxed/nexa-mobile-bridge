package com.nexa.mobilebridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.mobilebridge.data.local.AppearanceSettings
import com.nexa.mobilebridge.data.local.NexaPreferencesDataStore
import com.nexa.mobilebridge.theme.NexaMobileBridgeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var preferencesDataStore: NexaPreferencesDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Remove legacy M-Banking Shield shortcuts pointing to deleted SafeBankingLauncherActivity (FR-CORE-03)
        cleanupLegacyBankingShortcuts()

        // Auto-start NexaBridgeService only on fresh Activity launch (avoid restarting stopped service on rotation)
        if (savedInstanceState == null) {
            try {
                val serviceIntent = Intent(this, NexaBridgeService::class.java)
                startForegroundService(serviceIntent)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Failed auto-starting NexaBridgeService: ${e.message}")
            }
        }

        setContent {
            val appearance by preferencesDataStore.appearanceFlow
                .collectAsStateWithLifecycle(initialValue = AppearanceSettings())

            NexaMobileBridgeTheme(
                themeMode = appearance.themeMode,
                accentPreset = appearance.accentPreset
            ) {
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

    private fun cleanupLegacyBankingShortcuts() {
        try {
            ShortcutManagerCompat.removeDynamicShortcuts(
                this,
                listOf("shortcut_livin_safe", "shortcut_bca_safe", "shortcut_test_safe")
            )
        } catch (_: Exception) {
            // Safe no-op on launchers without dynamic shortcuts
        }
    }
}
