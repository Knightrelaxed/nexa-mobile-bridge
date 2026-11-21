package com.nexa.mobilebridge.core.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.fragment.app.FragmentActivity
import java.util.WeakHashMap

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findActivity()
    else -> null
}

fun Context.findFragmentActivity(): FragmentActivity? = findActivity() as? FragmentActivity

/**
 * Reference-counted FLAG_SECURE manager per Window (FR-SEC-06 / A-H2):
 * - Guards underflow on release()
 * - Defers clearFlags(FLAG_SECURE) via decorView.post so navigating directly between
 *   two secure tabs (e.g. LOGS -> SHELL) never exposes a 1-frame unencrypted window gap.
 */
object WindowSecureManager {
    private val lock = Any()
    private val counts = WeakHashMap<Window, Int>()

    fun acquire(window: Window) {
        synchronized(lock) {
            val next = (counts[window] ?: 0) + 1
            counts[window] = next
            if (next == 1) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    fun release(window: Window) {
        val shouldScheduleClear = synchronized(lock) {
            val current = counts[window] ?: return
            if (current <= 1) {
                counts.remove(window)
                true
            } else {
                counts[window] = current - 1
                false
            }
        }
        if (shouldScheduleClear) {
            val decor = runCatching { window.decorView }.getOrNull()
            if (decor != null) {
                decor.post {
                    synchronized(lock) {
                        if ((counts[window] ?: 0) <= 0) {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
                }
            } else {
                synchronized(lock) {
                    if ((counts[window] ?: 0) <= 0) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
    }
}

@Composable
fun SecureScreenEffect(allowInDebugBuild: Boolean = false) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (allowInDebugBuild && isDebuggable) return

    DisposableEffect(context) {
        val window = context.findActivity()?.window
        // A-H2: Fail closed in runtime if sensitive screen cannot resolve its host Window (FR-SEC-06)
        if (!isPreview) {
            checkNotNull(window) { "SecureScreenEffect requires an Activity Window to enforce FLAG_SECURE." }
        }
        if (window != null) {
            WindowSecureManager.acquire(window)
        }
        onDispose {
            if (window != null) {
                WindowSecureManager.release(window)
            }
        }
    }
}
