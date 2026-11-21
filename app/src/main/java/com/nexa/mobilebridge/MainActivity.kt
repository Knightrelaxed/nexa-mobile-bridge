package com.nexa.mobilebridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.mobilebridge.data.chat.ChatVisibilityTracker
import com.nexa.mobilebridge.data.local.AppearanceSettings
import com.nexa.mobilebridge.data.local.NexaPreferencesDataStore
import com.nexa.mobilebridge.notification.ChatNotificationManager
import com.nexa.mobilebridge.theme.NexaMobileBridgeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var preferencesDataStore: NexaPreferencesDataStore

    @Inject
    lateinit var chatVisibilityTracker: ChatVisibilityTracker

    private val requestNotifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Remove legacy M-Banking Shield shortcuts pointing to deleted SafeBankingLauncherActivity (FR-CORE-03)
        cleanupLegacyBankingShortcuts()
        handleDeepLinkIntent(intent)

        // Ensure POST_NOTIFICATIONS runtime permission is requested cleanly if missing
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

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
                        chatVisibilityTracker = chatVisibilityTracker,
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

    override fun onStart() {
        super.onStart()
        chatVisibilityTracker.setAppForeground(true)
    }

    override fun onStop() {
        chatVisibilityTracker.setAppForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val rawTab = intent?.getStringExtra(ChatNotificationManager.EXTRA_OPEN_TAB) ?: return
        val targetTab = NexaMainTab.entries.firstOrNull { it.name.equals(rawTab, ignoreCase = true) } ?: return
        chatVisibilityTracker.requestOpenTab(targetTab)
        intent.removeExtra(ChatNotificationManager.EXTRA_OPEN_TAB)
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
