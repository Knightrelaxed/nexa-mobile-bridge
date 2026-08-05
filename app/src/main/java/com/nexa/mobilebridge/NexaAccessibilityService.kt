package com.nexa.mobilebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.Display
import java.io.ByteArrayOutputStream

class NexaAccessibilityService : AccessibilityService() {

    companion object {
        var instance: NexaAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used, we only need the service for taking screenshots
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

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
                                // Convert to smaller bitmap for WebSocket transfer (prevent OOM and huge payload)
                                val resized = resizeBitmap(bitmap, 1280) // max width/height
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
