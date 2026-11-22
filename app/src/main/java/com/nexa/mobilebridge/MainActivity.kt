package com.nexa.mobilebridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.nexa.mobilebridge.data.chat.ChatRepository
import com.nexa.mobilebridge.data.chat.ChatVisibilityTracker
import com.nexa.mobilebridge.data.local.AppearanceSettings
import com.nexa.mobilebridge.data.local.NexaPreferencesDataStore
import com.nexa.mobilebridge.notification.ChatNotificationManager
import com.nexa.mobilebridge.theme.NexaMobileBridgeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val HOME_STANDBY_HIBERNATE_MS = 10_000L
        private const val SCREEN_OFF_HIBERNATE_MS = 4_000L
        private const val TERMINAL_RESUME_GRACE_MS = 130_000L

        @Volatile
        private var lastExternalPickerLaunchedAtMs: Long = 0L

        fun markExternalPickerLaunched() {
            lastExternalPickerLaunchedAtMs = System.currentTimeMillis()
        }

        fun clearExternalPickerLaunched() {
            lastExternalPickerLaunchedAtMs = 0L
        }

        internal fun trimWindowManagerGraphicsCache(level: Int) {
            runCatching {
                val clazz = Class.forName("android.view.WindowManagerGlobal")
                val getInstance = clazz.getDeclaredMethod("getInstance")
                val instance = getInstance.invoke(null)
                val trimMemory = clazz.getDeclaredMethod("trimMemory", Int::class.javaPrimitiveType)
                trimMemory.invoke(instance, level)
            }
        }
    }

    @Inject
    lateinit var preferencesDataStore: NexaPreferencesDataStore

    @Inject
    lateinit var chatVisibilityTracker: ChatVisibilityTracker

    @Inject
    lateinit var chatRepository: ChatRepository

    private var standbyHibernationJob: Job? = null
    private var isScreenOffReceiverRegistered = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                trimWindowManagerGraphicsCache(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                scheduleStandbyUiHibernation(isScreenOff = true)
            }
        }
    }

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
                Log.w(TAG, "Failed auto-starting NexaBridgeService: ${e.message}")
            }
        }
        registerScreenOffReceiverIfNeeded()

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
        standbyHibernationJob?.cancel()
        standbyHibernationJob = null
        chatVisibilityTracker.setAppForeground(true)
    }

    override fun onStop() {
        chatVisibilityTracker.setAppForeground(false)
        super.onStop()
        if (!isChangingConfigurations) {
            // Immediately release HardwareRenderer / EGL / GL mtrack caches while hidden
            trimWindowManagerGraphicsCache(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val screenOff = pm?.isInteractive == false
            scheduleStandbyUiHibernation(isScreenOff = screenOff)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        trimWindowManagerGraphicsCache(level)
    }

    override fun onDestroy() {
        standbyHibernationJob?.cancel()
        standbyHibernationJob = null
        unregisterScreenOffReceiverIfNeeded()
        super.onDestroy()
        if (!isChangingConfigurations) {
            // Final graphics & ART heap trim once Activity ViewRootImpl is detached
            CoroutineScope(Dispatchers.Default).launch {
                delay(350L)
                trimWindowManagerGraphicsCache(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                trimWindowManagerGraphicsCache(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                Runtime.getRuntime().gc()
            }
        }
    }

    private fun scheduleStandbyUiHibernation(isScreenOff: Boolean) {
        standbyHibernationJob?.cancel()
        val isTerminalVisible = chatVisibilityTracker.visibleTab.value == NexaMainTab.SHELL
        val externalAgeMs = System.currentTimeMillis() - lastExternalPickerLaunchedAtMs
        if (externalAgeMs in 0..90_000L && !isScreenOff) {
            // Do not auto-finish while user is picking a ringtone or toggling system notification settings
            return
        }
        val waitMs = when {
            isTerminalVisible -> TERMINAL_RESUME_GRACE_MS
            isScreenOff -> SCREEN_OFF_HIBERNATE_MS
            else -> HOME_STANDBY_HIBERNATE_MS
        }
        standbyHibernationJob = lifecycleScope.launch {
            delay(waitMs)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            if (isFinishing || isDestroyed || isChangingConfigurations) return@launch
            if (chatRepository.pendingStepUp.value != null) return@launch
            if (chatRepository.streamState.value.activeClientMsgId != null) return@launch

            Log.i(TAG, "Standby UI hibernation: releasing MainActivity (Activities -> 0) while NexaBridgeService stays active.")
            finish()
        }
    }

    private fun registerScreenOffReceiverIfNeeded() {
        if (isScreenOffReceiverRegistered) return
        runCatching {
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            isScreenOffReceiverRegistered = true
        }
    }

    private fun unregisterScreenOffReceiverIfNeeded() {
        if (!isScreenOffReceiverRegistered) return
        runCatching {
            unregisterReceiver(screenOffReceiver)
        }
        isScreenOffReceiverRegistered = false
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
