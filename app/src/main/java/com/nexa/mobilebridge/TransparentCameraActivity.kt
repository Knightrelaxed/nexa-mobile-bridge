package com.nexa.mobilebridge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TransparentCameraActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var textureView: TextureView? = null
    private val isCaptured = AtomicBoolean(false)

    companion object {
        private const val TAG = "NexaCamera"
        var onPhotoTaken: ((String?, String?) -> Unit)? = null
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setupViewsAndStartCamera()
        } else {
            sendResult(null, "Izin kamera (Manifest.permission.CAMERA) ditolak di HP.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: TransparentCameraActivity started")

        // Ensure activity window does not steal user touches
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Camera permission already granted")
            setupViewsAndStartCamera()
        } else {
            Log.i(TAG, "Requesting camera permission")
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupViewsAndStartCamera() {
        Log.i(TAG, "setupViewsAndStartCamera: Creating TextureView")

        // TextureView MUST have real dimensions for Samsung HAL to create a valid surface
        val tv = TextureView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Make the TextureView invisible but still laid out (Samsung HAL needs the surface)
            alpha = 0.01f
        }
        textureView = tv
        setContentView(tv)

        // Wait for surface to be ready before binding camera
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "Surface available: ${width}x${height}")
                bindCameraUseCases()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        // If surface is already available (unlikely but defensive)
        if (tv.isAvailable) {
            Log.i(TAG, "Surface already available")
            bindCameraUseCases()
        }
    }

    private fun bindCameraUseCases() {
        Log.i(TAG, "bindCameraUseCases: Getting camera provider")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                val useFrontCamera = intent.getBooleanExtra("USE_FRONT_CAMERA", false)
                Log.i(TAG, "Camera facing: ${if (useFrontCamera) "FRONT" else "BACK"}")

                val cameraSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                // ══════════════════════════════════════════════════════
                // USE CASE 1: Preview — CRITICAL for Samsung HAL
                // Samsung Exynos camera HAL requires an active Preview
                // surface to start the repeating capture request.
                // Without this, ImageAnalysis never receives frames.
                // ══════════════════════════════════════════════════════
                val preview = Preview.Builder().build()

                preview.setSurfaceProvider { request ->
                    Log.i(TAG, "SurfaceProvider: Creating surface for preview (${request.resolution})")
                    val tv = textureView
                    if (tv != null && tv.isAvailable) {
                        val st = tv.surfaceTexture
                        if (st != null) {
                            st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                            val surface = Surface(st)
                            request.provideSurface(surface, cameraExecutor) { result ->
                                Log.d(TAG, "Preview surface released")
                                surface.release()
                            }
                        } else {
                            Log.w(TAG, "SurfaceTexture is null, creating standalone surface")
                            provideFallbackSurface(request)
                        }
                    } else {
                        Log.w(TAG, "TextureView not available, creating standalone surface")
                        provideFallbackSurface(request)
                    }
                }

                // ══════════════════════════════════════════════════════
                // USE CASE 2: ImageAnalysis — Frame capture engine
                // Uses default YUV format (most compatible).
                // ══════════════════════════════════════════════════════
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                var frameCount = 0
                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    frameCount++
                    Log.d(TAG, "Frame #$frameCount received (${imageProxy.width}x${imageProxy.height}, rotation=${imageProxy.imageInfo.rotationDegrees})")

                    // Skip first 3 frames for AE/AWB stabilization
                    if (frameCount >= 3 && !isCaptured.get()) {
                        try {
                            val bitmap = imageProxy.toBitmap()
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            val processed = processAndRotateBitmap(bitmap, rotation, useFrontCamera)

                            if (isCaptured.compareAndSet(false, true)) {
                                Log.i(TAG, "✅ Photo captured! Size: ${processed.width}x${processed.height}")
                                val base64 = bitmapToBase64(processed)
                                sendResult(base64, null)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Frame processing error: ${e.message}", e)
                            if (isCaptured.compareAndSet(false, true)) {
                                sendResult(null, "Gagal memproses frame kamera: ${e.message}")
                            }
                        }
                    }
                    imageProxy.close()
                }

                // ══════════════════════════════════════════════════════
                // BIND BOTH use cases — this starts the camera pipeline
                // ══════════════════════════════════════════════════════
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                Log.i(TAG, "✅ Camera bound successfully: Preview + ImageAnalysis")

                // Safety timeout: 6s (allow extra time for Samsung HAL initialization)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isCaptured.compareAndSet(false, true)) {
                        Log.e(TAG, "❌ Timeout: No frames received in 6 seconds")
                        sendResult(null, "Timeout kamera: Tidak ada frame yang diterima dari sensor.")
                    }
                }, 6000L)

            } catch (exc: Exception) {
                Log.e(TAG, "Camera init failed: ${exc.message}", exc)
                if (isCaptured.compareAndSet(false, true)) {
                    sendResult(null, "Inisialisasi kamera gagal: ${exc.message}")
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Fallback: create a standalone SurfaceTexture for Preview
     * when the TextureView's SurfaceTexture is unavailable.
     */
    private fun provideFallbackSurface(request: SurfaceRequest) {
        val st = SurfaceTexture(0)
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(st)
        request.provideSurface(surface, cameraExecutor) {
            surface.release()
            st.release()
        }
    }

    private fun processAndRotateBitmap(source: Bitmap, rotationDegrees: Int, isFrontCamera: Boolean): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        if (isFrontCamera) {
            // Mirror front camera horizontally
            matrix.postScale(-1f, 1f)
        }
        var rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)

        // Resize to maximum 1280px for fast transmission over WebSocket
        val maxDim = 1280
        if (rotated.width > maxDim || rotated.height > maxDim) {
            val ratio = rotated.width.toFloat() / rotated.height.toFloat()
            val newW: Int
            val newH: Int
            if (ratio > 1) {
                newW = maxDim
                newH = (maxDim / ratio).toInt()
            } else {
                newH = maxDim
                newW = (maxDim * ratio).toInt()
            }
            rotated = Bitmap.createScaledBitmap(rotated, newW, newH, true)
        }
        return rotated
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun sendResult(base64: String?, error: String?) {
        Log.i(TAG, "sendResult: error=${error != null}, base64Size=${base64?.length ?: 0}")
        runOnUiThread {
            onPhotoTaken?.invoke(base64, error)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy called")
        if (isCaptured.compareAndSet(false, true)) {
            onPhotoTaken?.invoke(null, "Aktivitas kamera ditutup.")
        }
        try {
            cameraExecutor.shutdown()
        } catch (_: Exception) {}
        onPhotoTaken = null
    }
}
