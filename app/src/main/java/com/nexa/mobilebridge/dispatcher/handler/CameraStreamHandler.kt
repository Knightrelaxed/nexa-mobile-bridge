package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraStreamHandler — Real-time JPEG frame streaming engine for N.E.X.A Live Video.
 *
 * Binds CameraX ImageAnalysis to a background executor, throttles to ~1 FPS,
 * converts YUV->Bitmap->JPEG->Base64, and emits frames through an [onFrame] callback
 * so [FakeCallActivity] can forward them to the server via NexaBridgeService.
 *
 * Usage:
 *   val handler = CameraStreamHandler(context)
 *   handler.startCamera(lifecycleOwner, useBackCamera = true) { base64Jpeg -> ... }
 *   handler.stopCamera()
 */
class CameraStreamHandler(private val context: Context) {

    companion object {
        private const val TAG = "CameraStreamHandler"

        /** Target frame capture resolution — kept small to minimize bandwidth */
        private val TARGET_RESOLUTION = Size(640, 480)

        /** Minimum ms between frames sent to Gemini (~1 FPS = 1000ms) */
        private const val FRAME_THROTTLE_MS = 1000L

        /** JPEG compression quality — 65 balances fidelity and payload size */
        private const val JPEG_QUALITY = 65

        /** Max long dimension of bitmap before resize. Keep under 800px for fast encode. */
        private const val MAX_DIM = 800
    }

    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null

    @Volatile private var lastFrameTime = 0L
    @Volatile private var isActive = false

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Dynamically update the Preview SurfaceProvider (e.g. when Compose PreviewView becomes ready).
     */
    fun setPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider) {
        preview?.setSurfaceProvider(surfaceProvider)
    }

    /**
     * Start the camera pipeline and begin streaming JPEG frames to [onFrame].
     *
     * @param lifecycleOwner   Activity or Fragment whose lifecycle controls binding
     * @param useBackCamera    true = back camera (default), false = front camera
     * @param previewSurface   Optional: a [Preview.SurfaceProvider] for in-screen preview.
     *                         If null, a minimal headless surface provider is used so
     *                         Samsung HAL still delivers frames to ImageAnalysis.
     * @param onFrame          Callback invoked on camera thread — receives Base64 JPEG string
     */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        useBackCamera: Boolean = true,
        previewSurface: Preview.SurfaceProvider? = null,
        onFrame: (String) -> Unit
    ) {
        if (isActive) {
            if (previewSurface != null) {
                preview?.setSurfaceProvider(previewSurface)
            }
            return
        }
        isActive = true
        lastFrameTime = 0L
        cameraExecutor = Executors.newSingleThreadExecutor()

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val cameraSelector = if (useBackCamera) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }

                // Preview use case — required on Samsung Exynos devices for HAL to deliver frames
                val previewUseCase = Preview.Builder().build()
                this.preview = previewUseCase
                if (previewSurface != null) {
                    previewUseCase.setSurfaceProvider(previewSurface)
                }

                // ImageAnalysis use case — 1 FPS throttled frame capture
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(TARGET_RESOLUTION)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    processFrame(imageProxy, useBackCamera, onFrame)
                }

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, previewUseCase, imageAnalysis)
                Log.i(TAG, "Camera bound: ${if (useBackCamera) "BACK" else "FRONT"}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera: ${e.message}", e)
                isActive = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Switch camera lens without stopping/restarting the whole pipeline.
     * Unbinds the current camera and rebinds with the new [CameraSelector].
     *
     * @param lifecycleOwner  Same lifecycle owner as [startCamera]
     * @param useBackCamera   true = back, false = front
     * @param previewSurface  Same surface provider as [startCamera], if any
     * @param onFrame         Same callback as [startCamera]
     */
    fun switchCamera(
        lifecycleOwner: LifecycleOwner,
        useBackCamera: Boolean,
        previewSurface: Preview.SurfaceProvider? = null,
        onFrame: (String) -> Unit
    ) {
        stopCamera()
        // Brief pause so the HAL can release buffers cleanly before rebind
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startCamera(lifecycleOwner, useBackCamera, previewSurface, onFrame)
        }, 300L)
    }

    /**
     * Stop all camera use cases and release the executor.
     */
    fun stopCamera() {
        isActive = false
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "unbindAll error: ${e.message}")
        }
        preview = null
        cameraProvider = null
        cameraExecutor?.shutdownNow()
        cameraExecutor = null
        Log.i(TAG, "Camera stopped")
    }

    // ── Internal frame processing ─────────────────────────────────────────────

    private fun processFrame(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean,
        onFrame: (String) -> Unit
    ) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < FRAME_THROTTLE_MS) {
                return  // throttle — too soon
            }
            if (!isActive) return

            lastFrameTime = now

            val bitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val processed = rotateBitmap(bitmap, rotationDegrees, isFrontCamera)
            val jpeg = bitmapToJpegBase64(processed)
            if (processed != bitmap) {
                processed.recycle()
            }
            bitmap.recycle()
            if (jpeg.isNotEmpty()) {
                onFrame(jpeg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }


    /**
     * Rotate (and optionally mirror front camera) bitmap to correct upright orientation.
     * Also scales down to [MAX_DIM] if larger to keep payload small.
     */
    private fun rotateBitmap(source: Bitmap, rotationDegrees: Int, isFrontCamera: Boolean): Bitmap {
        val matrix = Matrix()
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }
        if (isFrontCamera) {
            // Mirror front camera so it matches what the user sees in the preview
            matrix.postScale(-1f, 1f)
        }

        var result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)

        // Scale down if needed
        if (result.width > MAX_DIM || result.height > MAX_DIM) {
            val ratio = result.width.toFloat() / result.height.toFloat()
            val (newW, newH) = if (ratio > 1) {
                Pair(MAX_DIM, (MAX_DIM / ratio).toInt())
            } else {
                Pair((MAX_DIM * ratio).toInt(), MAX_DIM)
            }
            val scaled = Bitmap.createScaledBitmap(result, newW, newH, true)
            result.recycle()
            result = scaled
        }
        return result
    }

    private fun bitmapToJpegBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
