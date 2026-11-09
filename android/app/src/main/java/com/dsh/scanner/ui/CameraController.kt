package com.dsh.scanner.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * View-layer CameraX adapter. Owns the use cases and the photo file, but never
 * scans pages or decides when to capture: those decisions live in the ViewModel.
 */
class CameraController(
    private val appContext: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val analyzer: ImageAnalysis.Analyzer,
    private val listener: Listener,
) {

    interface Listener {
        fun onCameraBound()
        fun onCameraError(message: String)
        fun onPhotoReady(bitmap: Bitmap)
        fun onPhotoError(message: String)
    }

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var wanted = false
    private var analyzing = false
    private var torchOn = false

    /** Binds the back camera once; repeated calls while bound are no-ops. */
    fun bind() {
        if (wanted) return
        wanted = true
        // Wait for the view to be attached: display.rotation is only right then.
        previewView.post { bindCamera() }
    }

    private fun bindCamera() {
        if (!wanted) return
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            if (!wanted) return@addListener
            try {
                val cameraProvider = future.get()

                val preview = Preview.Builder()
                    .setResolutionSelector(selector())
                    .setTargetRotation(rotation)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(selector())
                    .setTargetRotation(rotation)
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector())
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer) }

                cameraProvider.unbindAll()
                val boundCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                    capture,
                )

                provider = cameraProvider
                imageCapture = capture
                imageAnalysis = analysis
                camera = boundCamera
                analyzing = true
                boundCamera.cameraControl.enableTorch(torchOn)
                listener.onCameraBound()
            } catch (t: Throwable) {
                Log.e(TAG, "camera start failed", t)
                wanted = false
                listener.onCameraError(t.message ?: t.javaClass.simpleName)
            }
        }, mainExecutor)
    }

    /** Detaches all use cases; the preview and the analyzer stop together. */
    fun unbind() {
        wanted = false
        try {
            provider?.unbindAll()
        } catch (t: Throwable) {
            Log.e(TAG, "unbind failed", t)
        }
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        imageCapture = null
        camera = null
        provider = null
        analyzing = false
    }

    fun setTorch(on: Boolean) {
        if (on == torchOn) return
        torchOn = on
        camera?.cameraControl?.enableTorch(on)
    }

    /** Pause/resume analysis without unbinding (used while a still is captured). */
    fun setAnalyzing(active: Boolean) {
        val analysis = imageAnalysis ?: return
        if (active == analyzing) return
        if (active) {
            analysis.setAnalyzer(analysisExecutor, analyzer)
            analyzing = true
        } else {
            analysis.clearAnalyzer()
            analyzing = false
        }
    }

    /** Captures a full-resolution JPEG, decodes it upright and hands it over. */
    fun takePhoto() {
        val capture = imageCapture
        if (capture == null) {
            listener.onPhotoError("相机未就绪")
            return
        }
        setAnalyzing(false)

        val file = File(appContext.cacheDir, "shot.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            captureExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    try {
                        val bitmap = decodeOriented(file) ?: error("decode failed")
                        Log.i(TAG, "still=${bitmap.width}x${bitmap.height}")
                        listener.onPhotoReady(bitmap)
                    } catch (t: Throwable) {
                        Log.e(TAG, "still decode failed", t)
                        listener.onPhotoError(t.message ?: "decode failed")
                    } finally {
                        file.delete()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "capture error", exception)
                    file.delete()
                    listener.onPhotoError(exception.message ?: "capture failed")
                }
            },
        )
    }

    fun release() {
        unbind()
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    private fun selector(): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

    private fun decodeOriented(file: File): Bitmap? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private companion object {
        const val TAG = "PaperScan"
    }
}
