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
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrEmpty()) {
                com.nexa.mobilebridge.core.usage.AppUsageTracker.onForegroundAppChanged(this, pkg)
            }
        }
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
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
