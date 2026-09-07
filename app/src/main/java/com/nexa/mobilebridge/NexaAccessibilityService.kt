package com.nexa.mobilebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nexa.mobilebridge.ui.call.FakeCallActivity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream

class NexaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NexaAccessibility"
        var instance: NexaAccessibilityService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        try {
            val info = serviceInfo
            if (info != null) {
                info.flags = info.flags or
                        android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                        android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                serviceInfo = info
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error configuring serviceInfo flags: ${e.message}")
        }

        // Ensure companion NexaBridgeService is active in foreground
        ensureBridgeServiceRunning()

        Log.i(TAG, "NexaAccessibilityService connected with Key Event Filtering active.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrEmpty()) {
                    serviceScope.launch(Dispatchers.IO) {
                        com.nexa.mobilebridge.core.usage.AppUsageTracker.onForegroundAppChanged(applicationContext, pkg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onAccessibilityEvent error: ${e.message}")
        }
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "NexaAccessibilityService unbind called.")
        return true // Request onRebind when system reconnects
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        instance = this
        ensureBridgeServiceRunning()
        Log.i(TAG, "NexaAccessibilityService rebound successfully.")
    }

    override fun onDestroy() {
        instance = null
        try {
            serviceScope.cancel()
        } catch (_: Exception) {}
        super.onDestroy()
        Log.i(TAG, "NexaAccessibilityService destroyed.")
    }

    // ─────────────────────────────────────────────────────────────
    // 0. Physical Hardware Button Intercept (Chord Press: Vol Up + Vol Down)
    // ─────────────────────────────────────────────────────────────
    private var isVolumeUpHeld = false
    private var isVolumeDownHeld = false
    private var lastVolumeUpPressTime: Long = 0L
    private var lastVolumeDownPressTime: Long = 0L
    private var lastChordTriggerTime: Long = 0L
    private val CHORD_WINDOW_MS: Long = 750L // Expanded window for natural chord pressing
    private val CHORD_DEBOUNCE_MS: Long = 2000L

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)

        try {
            val now = System.currentTimeMillis()

            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        isVolumeUpHeld = true
                        lastVolumeUpPressTime = now
                        val diffDown = now - lastVolumeDownPressTime

                        Log.d(TAG, "Volume Up DOWN: isVolDownHeld=$isVolumeDownHeld, diffDown=${diffDown}ms")

                        // Check if Volume Down is currently held or was pressed within CHORD_WINDOW_MS
                        if (isVolumeDownHeld || (diffDown in 1..CHORD_WINDOW_MS)) {
                            if (now - lastChordTriggerTime > CHORD_DEBOUNCE_MS) {
                                lastChordTriggerTime = now
                                isVolumeUpHeld = false
                                isVolumeDownHeld = false
                                Log.i(TAG, "Volume Chord (Up + Down) terdeteksi. Memeriksa konektivitas...")
                                serviceScope.launch(Dispatchers.IO) {
                                    handleVolumeChordTriggered()
                                }
                                return true
                            }
                        }
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        isVolumeUpHeld = false
                    }
                }

                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        isVolumeDownHeld = true
                        lastVolumeDownPressTime = now
                        val diffUp = now - lastVolumeUpPressTime

                        Log.d(TAG, "Volume Down DOWN: isVolUpHeld=$isVolumeUpHeld, diffUp=${diffUp}ms")

                        // Check if Volume Up is currently held or was pressed within CHORD_WINDOW_MS
                        if (isVolumeUpHeld || (diffUp in 1..CHORD_WINDOW_MS)) {
                            if (now - lastChordTriggerTime > CHORD_DEBOUNCE_MS) {
                                lastChordTriggerTime = now
                                isVolumeUpHeld = false
                                isVolumeDownHeld = false
                                Log.i(TAG, "Volume Chord (Down + Up) terdeteksi. Memeriksa konektivitas...")
                                serviceScope.launch(Dispatchers.IO) {
                                    handleVolumeChordTriggered()
                                }
                                return true
                            }
                        }
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        isVolumeDownHeld = false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onKeyEvent: ${e.message}", e)
        }

        return super.onKeyEvent(event)
    }

    private fun isInternetAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun handleVolumeChordTriggered() {
        val hasInternet = isInternetAvailable()
        val isBridgeConnected = NexaBridgeService.isConnected()

        if (!hasInternet) {
            Log.w(TAG, "Panggilan keluar dibatalkan: Tidak ada koneksi internet.")
            triggerErrorVibration()
            showCallFailureNotification(
                title = "Panggilan N.E.X.A Gagal",
                message = "Tidak ada koneksi internet. Sambungkan perangkat ke Wi-Fi atau data seluler untuk melakukan panggilan."
            )
            showToast("Panggilan N.E.X.A Gagal: Tidak ada koneksi internet.")
            ensureBridgeServiceRunning()
            return
        }

        if (!isBridgeConnected) {
            Log.w(TAG, "Panggilan keluar dibatalkan: NexaBridgeService belum terhubung ke server.")
            triggerErrorVibration()
            showCallFailureNotification(
                title = "Panggilan N.E.X.A Gagal",
                message = "Koneksi ke server N.E.X.A belum terhubung. Sedang mencoba menghubungkan ulang..."
            )
            showToast("Panggilan N.E.X.A Gagal: Sedang menghubungkan ulang ke server...")
            NexaBridgeService.reconnect()
            ensureBridgeServiceRunning()
            return
        }

        // Keduanya valid (internet aktif & bridge terhubung)
        Log.i(TAG, "Koneksi terverifikasi -> Menjalankan Panggilan N.E.X.A")
        triggerDoubleVibration()
        launchOutgoingNexaCall()
    }

    private fun ensureBridgeServiceRunning() {
        try {
            val serviceIntent = Intent(this, NexaBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal memastikan NexaBridgeService berjalan: ${e.message}")
        }
    }

    private fun triggerDoubleVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                val effect = VibrationEffect.createWaveform(
                    longArrayOf(0, 60, 70, 90),
                    intArrayOf(0, 255, 0, 255),
                    -1
                )
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 60, 70, 90), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration feedback error: ${e.message}")
        }
    }

    private fun triggerErrorVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                val effect = VibrationEffect.createWaveform(
                    longArrayOf(0, 120, 100, 120, 100, 120),
                    intArrayOf(0, 255, 0, 255, 0, 255),
                    -1
                )
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 120, 100, 120, 100, 120), -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error vibration feedback error: ${e.message}")
        }
    }

    private fun showCallFailureNotification(title: String, message: String) {
        try {
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "nexa_call_alert_channel"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "N.E.X.A Bridge — Notifikasi Panggilan",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Pemberitahuan status panggilan N.E.X.A"
                    enableLights(true)
                    enableVibration(true)
                }
                notifManager.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notifManager.notify(2001, notif)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show call failure notification: ${e.message}", e)
        }
    }

    private fun showToast(text: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    private fun launchOutgoingNexaCall() {
        try {
            val intent = Intent(this, FakeCallActivity::class.java).apply {
                action = "com.nexa.mobilebridge.ACTION_CALL_NEXA"
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra("IS_OUTGOING_CALL", true)
                putExtra("CALLER_NAME", "N.E.X.A Voice Bridge")
                putExtra("CALL_MESSAGE", "Halo Tuan Faqih, saya mendengarkan.")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch outgoing Nexa call: ${e.message}", e)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 1. UI Hierarchy Inspector (Dump Tree)
    // ─────────────────────────────────────────────────────────────

    fun dumpUiHierarchy(): JsonArray {
        val rootNode = rootInActiveWindow ?: return JsonArray(emptyList())
        val nodesList = mutableListOf<JsonObject>()
        traverseNode(rootNode, nodesList, maxDepth = 10, currentDepth = 0)
        return JsonArray(nodesList)
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo?,
        result: MutableList<JsonObject>,
        maxDepth: Int,
        currentDepth: Int
    ) {
        if (node == null || currentDepth > maxDepth || result.size > 150) return

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""
        
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Only include nodes that have meaningful info (text, desc, id, or clickable/editable)
        if (text.isNotEmpty() || desc.isNotEmpty() || viewId.isNotEmpty() || node.isClickable || node.isEditable) {
            val nodeObj = buildJsonObject {
                put("text", JsonPrimitive(text))
                put("desc", JsonPrimitive(desc))
                put("id", JsonPrimitive(viewId))
                put("class", JsonPrimitive(className.substringAfterLast('.')))
                put("clickable", JsonPrimitive(node.isClickable))
                put("editable", JsonPrimitive(node.isEditable))
                put("bounds", buildJsonObject {
                    put("left", JsonPrimitive(bounds.left))
                    put("top", JsonPrimitive(bounds.top))
                    put("right", JsonPrimitive(bounds.right))
                    put("bottom", JsonPrimitive(bounds.bottom))
                })
            }
            result.add(nodeObj)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, result, maxDepth, currentDepth + 1)
            child.recycle() // Release native reference to prevent memory leak
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. Gesture & Click Actions
    // ─────────────────────────────────────────────────────────────

    fun clickCoordinates(x: Float, y: Float, callback: (Boolean, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            callback(false, "Coordinates click requires Android 7.0 (API 24) or higher")
            return
        }

        val path = Path().apply {
            moveTo(x, y)
            // Android requires a non-zero-length path for gesture dispatch.
            // A tiny lineTo ensures the stroke has measurable length.
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback(true, null)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback(false, "Click gesture was cancelled by system")
            }
        }, null)

        if (!dispatched) {
            callback(false, "System refused to dispatch gesture. Ensure android:canPerformGestures=\"true\" is in config.")
        }
    }

    fun clickNode(targetIdOrText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        
        // 1. Search by Resource ID only if it looks like a fully-qualified resource ID
        //    (e.g. "com.example.app:id/submit_button")
        if (targetIdOrText.contains(":id/") || targetIdOrText.contains("/")) {
            val nodesById = root.findAccessibilityNodeInfosByViewId(targetIdOrText)
            if (!nodesById.isNullOrEmpty()) {
                for (node in nodesById) {
                    if (performClickOnNode(node)) return true
                }
            }
        }

        // 2. Search by visible text (partial match)
        val nodesByText = root.findAccessibilityNodeInfosByText(targetIdOrText)
        if (!nodesByText.isNullOrEmpty()) {
            for (node in nodesByText) {
                if (performClickOnNode(node)) return true
            }
        }

        return false
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────
    // 3. Input Text Action
    // ─────────────────────────────────────────────────────────────

    fun inputText(text: String, targetIdOrText: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        var targetNode: AccessibilityNodeInfo? = null

        if (!targetIdOrText.isNullOrEmpty()) {
            val byId = root.findAccessibilityNodeInfosByViewId(targetIdOrText)
            if (!byId.isNullOrEmpty()) targetNode = byId[0]
            
            if (targetNode == null) {
                val byText = root.findAccessibilityNodeInfosByText(targetIdOrText)
                if (!byText.isNullOrEmpty()) targetNode = byText[0]
            }
        }

        if (targetNode == null) {
            targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        if (targetNode != null) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        return false
    }

    // ─────────────────────────────────────────────────────────────
    // 4. Scroll Action
    // ─────────────────────────────────────────────────────────────

    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (direction.uppercase() == "UP" || direction.uppercase() == "BACKWARD") {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        
        return performScrollOnNode(root, action)
    }

    private fun performScrollOnNode(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable) {
            if (node.performAction(action)) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (performScrollOnNode(child, action)) return true
        }

        return false
    }

    // ─────────────────────────────────────────────────────────────
    // 5. Screenshot Action (Existing)
    // ─────────────────────────────────────────────────────────────

    fun takeScreenshotBase64(callback: (String?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            if (bitmap != null) {
                                val resized = resizeBitmap(bitmap, 1280)
                                val outputStream = ByteArrayOutputStream()
                                resized.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
                                val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                                callback(base64, null)
                            } else {
                                callback(null, "Failed to wrap hardware buffer into Bitmap")
                            }
                        } catch (e: Exception) {
                            callback(null, e.message)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        callback(null, "Screenshot failed with error code: $errorCode")
                    }
                }
            )
        } else {
            callback(null, "Screenshot requires Android 11 (API 30) or higher")
        }
    }
    
    private fun resizeBitmap(image: Bitmap, maxSize: Int): Bitmap {
        var width = image.width
        var height = image.height
        val bitmapRatio = width.toFloat() / height.toFloat()
        if (bitmapRatio > 1) {
            width = maxSize
            height = (width / bitmapRatio).toInt()
        } else {
            height = maxSize
            width = (height * bitmapRatio).toInt()
        }
        return Bitmap.createScaledBitmap(image, width, height, true)
    }
}
