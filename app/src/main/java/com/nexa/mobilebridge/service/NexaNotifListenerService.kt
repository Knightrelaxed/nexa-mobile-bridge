package com.nexa.mobilebridge.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.nexa.mobilebridge.core.network.NexaWebSocketClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

@AndroidEntryPoint
class NexaNotifListenerService : NotificationListenerService() {

    @Inject
    lateinit var webSocketClient: NexaWebSocketClient

    private val TAG = "NexaNotif"

    private val TARGET_PACKAGES = setOf(
        "com.bca",                          // BCA Mobile
        "com.gojek.app",                    // GoPay
        "ovo.id",                           // OVO
        "com.shopee.id",                    // ShopeePay
        "com.bankmandiri.mandirionline",    // Mandiri
        "com.bni.android",                  // BNI
        "com.sec.android.app.clockpackage", // Samsung Clock (Alarm)
        "com.google.android.deskclock"      // Google Clock (Alarm)
    )

    // Regex to match finance related keywords
    private val moneyPattern = Regex("(Rp|debit|kredit|transfer|masuk|keluar|bayar|berhasil)", RegexOption.IGNORE_CASE)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName !in TARGET_PACKAGES) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        // Filter out non-financial notifications
        if (!moneyPattern.containsMatchIn(text) && !moneyPattern.containsMatchIn(title)) return

        Log.i(TAG, "Finance Notification Captured from \$packageName: \$title")

        // Construct JSON payload
        val payloadObj = buildJsonObject {
            put("type", "NOTIFICATION_EVENT")
            put("source_package", packageName)
            put("title", title)
            put("text", text)
            put("timestamp", System.currentTimeMillis())
        }

        val payload = payloadObj.toString()
        webSocketClient.send(payload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        
        // Detect if the removed notification is from a Clock/Alarm app
        if (packageName == "com.sec.android.app.clockpackage" || packageName == "com.google.android.deskclock") {
            Log.i(TAG, "Alarm Notification Dismissed from $packageName")
            
            val payloadObj = buildJsonObject {
                put("type", "ALARM_DISMISSED")
                put("source_package", packageName)
                put("timestamp", System.currentTimeMillis())
            }
            
            webSocketClient.send(payloadObj.toString())
        }
    }
}
