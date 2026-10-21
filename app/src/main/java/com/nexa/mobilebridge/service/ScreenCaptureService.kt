package com.nexa.mobilebridge.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.nexa.mobilebridge.NexaBridgeService
import com.nexa.mobilebridge.R
import com.nexa.mobilebridge.ui.call.FakeCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

/**
 * Foreground Service for Real-Time Screen Capture & Floating Overlay during Gemini Live calls.
 * - Captures screen at 1 FPS (540x1200) via MediaProjection & ImageReader (Zero-Accessibility).
 * - Enforces M-Banking safety via native Android FLAG_SECURE.
 * - Streams JPEG 50 frames to Google Gemini Live API via NexaBridgeService ("CALL_VIDEO_FRAME").
 * - Hosts FloatingOverlayManager (Interactive Dock & Glowing Orb).
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val CHANNEL_ID = "nexa_screen_share_channel"
        const val NOTIFICATION_ID = 9021

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_COMMAND_ID = "extra_command_id"

        const val ACTION_STOP_SCREEN_SHARE = "com.nexa.mobilebridge.ACTION_STOP_SCREEN_SHARE"

        // Scale resolution: 540x1200 (exact 50% of 1080x2400 Galaxy A33 5G)
        const val CAPTURE_WIDTH = 540
        const val CAPTURE_HEIGHT = 1200
        const val CAPTURE_DPI = 200

        @Volatile
        var isRunning: Boolean = false
            private set

        // Shared Reactive States for Floating Overlay & FakeCallActivity
        val isSharingActive = MutableStateFlow(false)
        val assistantSubtitle = MutableStateFlow("")
        val isMicMuted = MutableStateFlow(false)
        val audioEnergy = MutableStateFlow(0f)
        val isAssistantSpeaking = MutableStateFlow(false)

        // Callbacks from Overlay to FakeCallActivity
        var onEndCallRequested: (() -> Unit)? = null
        var onToggleMicMuteRequested: (() -> Unit)? = null
        var onInterruptRequested: (() -> Unit)? = null
        var onReturnToCallRequested: (() -> Unit)? = null

        fun start(context: Context, resultCode: Int, data: Intent, commandId: String) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_COMMAND_ID, commandId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureJob: Job? = null

    private var overlayManager: FloatingOverlayManager? = null
    private var commandId: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ScreenCaptureService created")
        createNotificationChannel()

        handlerThread = HandlerThread("ScreenCaptureBg").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)

        overlayManager = FloatingOverlayManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SCREEN_SHARE) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (intent != null) {
            IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        } else null

        commandId = intent?.getStringExtra(EXTRA_COMMAND_ID) ?: ""

        if (isRunning && virtualDisplay != null) {
            Log.i(TAG, "ScreenCaptureService already running, updated commandId=$commandId")
            return START_STICKY
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "Cannot start ScreenCaptureService: Invalid resultCode=$resultCode or resultData is null")
            stopSelf()
            return START_NOT_STICKY
        }

        // 1. Mandatory Android 14+ Foreground Service start BEFORE calling getMediaProjection()
        // Coupled with FOREGROUND_SERVICE_TYPE_MICROPHONE so AudioRecord is not silenced when FakeCallActivity moves to back.
        val notification = buildOngoingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasMicPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPermission) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }

            startForeground(
                NOTIFICATION_ID,
                notification,
                serviceType
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        isSharingActive.value = true

        // 2. Initialize MediaProjection pipeline
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            val mp = mpManager.getMediaProjection(resultCode, resultData)
            if (mp == null) {
                Log.e(TAG, "MediaProjectionManager returned null projection")
                stopSelf()
                return START_NOT_STICKY
            }
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system/user")
                    stopSelf()
                }
            }, backgroundHandler)
            mediaProjection = mp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain MediaProjection: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // 3. Setup VirtualDisplay and ImageReader
        setupVirtualDisplay()

        // 4. Start 1 FPS capture coroutine
        startCaptureLoop()

        // 5. Show Floating Overlay (PiP button + 5-button dock / collapsed bubble)
        try {
            overlayManager?.show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing floating overlay: ${e.message}", e)
        }

        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        val mp = mediaProjection ?: return

        imageReader = ImageReader.newInstance(
            CAPTURE_WIDTH,
            CAPTURE_HEIGHT,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mp.createVirtualDisplay(
            "NexaScreenShareDisplay",
            CAPTURE_WIDTH,
            CAPTURE_HEIGHT,
            CAPTURE_DPI,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            backgroundHandler
        )
        Log.i(TAG, "VirtualDisplay created: ${CAPTURE_WIDTH}x${CAPTURE_HEIGHT} @ ${CAPTURE_DPI}dpi")
    }

    private fun startCaptureLoop() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            Log.i(TAG, "Screen capture loop active (1 FPS throttle)")
            while (isActive && isRunning) {
                try {
                    // Conserve bandwidth & thermal energy: skip frame extraction while assistant speaks
                    if (!isAssistantSpeaking.value) {
                        captureAndSendFrame()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error capturing screen frame: ${e.message}")
                }
                delay(1000L) // Exact 1 FPS cadence
            }
        }
    }

    private fun captureAndSendFrame() {
        val reader = imageReader ?: return
        val image = reader.acquireLatestImage() ?: return

        // Prevent uplink backpressure HOL blocking for voice chunks
        if (NexaBridgeService.getWebSocketQueueSize() > 64 * 1024) {
            image.close()
            return
        }

        var rawBitmap: Bitmap? = null
        var cleanBitmap: Bitmap? = null

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * CAPTURE_WIDTH

            // Allocate bitmap considering row padding alignment
            rawBitmap = Bitmap.createBitmap(
                CAPTURE_WIDTH + (rowPadding / pixelStride),
                CAPTURE_HEIGHT,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)

            // Crop excess GPU row padding if present
            cleanBitmap = if (rowPadding > 0) {
                Bitmap.createBitmap(rawBitmap, 0, 0, CAPTURE_WIDTH, CAPTURE_HEIGHT)
            } else {
                rawBitmap
            }

            // Compress to JPEG Quality 50
            val outputStream = ByteArrayOutputStream()
            cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)

            val jpegBytes = outputStream.toByteArray()
            val base64Frame = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

            // Relay directly to Google Gemini Multimodal Live API
            val payload = buildJsonObject {
                put("type", "CALL_VIDEO_FRAME")
                put("command_id", commandId)
                put("image_chunk", base64Frame)
            }.toString()

            NexaBridgeService.sendEvent(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process image buffer: ${e.message}")
        } finally {
            try {
                if (cleanBitmap != null && cleanBitmap != rawBitmap) {
                    cleanBitmap.recycle()
                }
                rawBitmap?.recycle()
            } catch (_: Exception) {}
            image.close() // Mandatory cleanup to prevent buffer pool starvation
        }
    }

    private fun buildOngoingNotification(): Notification {
        // PendingIntent to return to FakeCallActivity
        val returnIntent = Intent(this, FakeCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val returnPendingIntent = PendingIntent.getActivity(
            this,
            0,
            returnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // PendingIntent to stop screen share
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP_SCREEN_SHARE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("N.E.X.A Berbagi Layar Aktif")
            .setContentText("Layar ponsel dipantau real-time oleh Google Gemini Multimodal")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(returnPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hentikan", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "N.E.X.A Berbagi Layar",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi status transmisi layar real-time N.E.X.A Assistant"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ScreenCaptureService destroying")
        isRunning = false
        isSharingActive.value = false

        captureJob?.cancel()
        serviceScope.cancel()

        try {
            overlayManager?.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing overlay: ${e.message}")
        }
        overlayManager = null

        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up projection: ${e.message}")
        }

        handlerThread?.quitSafely()
        handlerThread = null
    }
}
