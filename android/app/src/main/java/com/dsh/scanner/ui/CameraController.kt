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
    /** 应用 Context：获取 CameraProvider、写缓存照片。 */
    private val appContext: Context,

    /** 相机绑定的生命周期宿主。 */
    private val lifecycleOwner: LifecycleOwner,

    /** 预览用的 PreviewView。 */
    private val previewView: PreviewView,

    /** ViewModel 提供的分析器；绑定后挂到 ImageAnalysis。 */
    private val analyzer: ImageAnalysis.Analyzer,

    /** 绑定/拍照结果回调。 */
    private val listener: Listener,
) {

    interface Listener {
        /** CameraX 绑定成功，可以开始接收分析帧。 */
        fun onCameraBound()

        /** 绑定或启动失败；message 用于状态栏。 */
        fun onCameraError(message: String)

        /** 拍照、解码和 EXIF 旋转完成，位图所有权交给回调方。 */
        fun onPhotoReady(bitmap: Bitmap)

        /** 拍照或解码失败。 */
        fun onPhotoError(message: String)
    }

    /** 单线程分析器执行器，CameraX 分析流串行。 */
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** 单线程拍照/解码执行器，避免阻塞主线程。 */
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** 主线程执行器，用于 CameraProvider future 回调。 */
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    /** CameraX provider；unbind 后置空，下次 bind 重新获取。 */
    private var provider: ProcessCameraProvider? = null

    /** 全分辨率拍照 use case。 */
    private var imageCapture: ImageCapture? = null

    /** 实时分析 use case。 */
    private var imageAnalysis: ImageAnalysis? = null

    /** 已绑定的相机，用于手电筒控制。 */
    private var camera: Camera? = null

    /** 期望的绑定状态；异步回调回来时若已 unbind 就丢弃。 */
    private var wanted = false

    /** ImageAnalysis 当前是否挂着 [analyzer]。 */
    private var analyzing = false

    /** 期望的手电筒状态；重新绑定后恢复。 */
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
