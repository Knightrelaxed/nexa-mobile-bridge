package com.nexa.mobilebridge

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.nexa.mobilebridge.data.logs.LogStreamStatus
import com.nexa.mobilebridge.data.logs.SidecarLogsClient
import com.nexa.mobilebridge.data.terminal.SidecarTerminalClient
import com.nexa.mobilebridge.data.terminal.TerminalSessionStatus
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NexaBridgeApp : Application() {

    @Inject
    lateinit var sidecarLogsClient: SidecarLogsClient

    @Inject
    lateinit var sidecarTerminalClient: SidecarTerminalClient

    override fun onCreate() {
        super.onCreate()
    }

    /**
     * A-H7 / PRD 13: Reclaim in-memory ring/scrollback buffers when UI moves to background
     * or OS signals memory pressure on 6 GB Exynos 1280.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            if (::sidecarLogsClient.isInitialized) {
                val logState = sidecarLogsClient.status.value
                if (logState == LogStreamStatus.IDLE_DISCONNECTED || logState == LogStreamStatus.ERROR) {
                    sidecarLogsClient.clearLocalBuffer()
                }
            }
            if (::sidecarTerminalClient.isInitialized) {
                val termState = sidecarTerminalClient.sessionState.value.status
                if (termState == TerminalSessionStatus.DISCONNECTED || termState == TerminalSessionStatus.ERROR) {
                    sidecarTerminalClient.emulator.reset()
                }
            }
            Log.d("NexaBridgeApp", "onTrimMemory(level=$level): trimmed inactive sidecar buffers.")
        }
    }
}
