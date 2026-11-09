package com.dsh.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var debugImage: ImageView
    private lateinit var shutter: Button
    private lateinit var torch: Button
    private lateinit var filterButton: Button
    private var filter = ScanCapture.Filter.ENHANCE

    /** 结果页：斜纸扶正动画 + 全屏扫描件。 */
    private lateinit var rectifyView: RectifyView
    private lateinit var rescanButton: Button
    private lateinit var resultCaption: TextView

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var provider: ProcessCameraProvider? = null
    private var lastRotation = -1
    private var camera: Camera? = null
    private var torchOn = false

    private val detector = DocDetector()
    private val tracker = DocTracker()
    @Volatile private var modelDetector: DocModelDetector? = null
    @Volatile private var modelState = "未加载"
    private lateinit var motion: MotionGate

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var capturing = false

    /** 识别成功后置位：不再分析新帧，也不再触发拍摄，直到用户点"重新扫描"。 */
    @Volatile private var frozen = false

    @Volatile private var analysisW = 640
    @Volatile private var analysisH = 480
    @Volatile private var suppressStatus = false
    private var virtualMode = false

    private val analyzer: ScanAnalyzer by lazy {
        ScanAnalyzer(
            detector = detector,
            tracker = tracker,
            omega = { motion.angularSpeedDeg },
            model = { modelDetector },
            onFrame = ::onFrame,
        )
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                statusText.text = "需要相机权限才能扫描"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.statusText)
        debugImage = findViewById(R.id.debugImage)
        shutter = findViewById(R.id.shutterButton)
        torch = findViewById(R.id.torchButton)
        filterButton = findViewById(R.id.filterButton)
        rectifyView = findViewById(R.id.rectifyView)
        rescanButton = findViewById(R.id.rescanButton)
        resultCaption = findViewById(R.id.resultCaption)

        motion = MotionGate(this)
        virtualMode = intent.getBooleanExtra("virtual", false)

        if (!OpenCVLoader.initLocal()) {
            statusText.text = "OpenCV 初始化失败"
            Log.e(TAG, "OpenCV init failed")
        }

        shutter.setOnClickListener {
            if (!capturing && !frozen) capture()
        }
        filterButton.setOnClickListener {
            filter = when (filter) {
                ScanCapture.Filter.COLOR -> ScanCapture.Filter.ENHANCE
                ScanCapture.Filter.ENHANCE -> ScanCapture.Filter.GRAY
                ScanCapture.Filter.GRAY -> ScanCapture.Filter.COLOR
            }
            filterButton.text = filter.label
        }
        torch.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
        }
        rescanButton.setOnClickListener { rescan() }
        rectifyView.onFinished = {
            if (!virtualMode) {
                rescanButton.visibility = View.VISIBLE
                rescanButton.alpha = 0f
                rescanButton.animate().alpha(1f).setDuration(220L).start()
            }
        }

        if (!virtualMode) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startCamera()
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        } else {
            previewView.visibility = View.INVISIBLE
        }

        if (intent.getBooleanExtra("virtual", false)) {
            main.postDelayed({ startVirtualMode() }, 600)
        }
        if (intent.getBooleanExtra("selftest", false)) {
            main.postDelayed({ runSelfTest() }, 800)
        }
        if (intent.getBooleanExtra("forcecapture", false)) {
            main.postDelayed({ forceCaptureForTest() }, 3500)
        }
    }

    /** Debug hook: feeds the bundled image through the live loop to test auto-capture. */
    private fun startVirtualMode() {
        try {
            if (modelDetector == null) {
                modelDetector = DocModelDetector(applicationContext)
                Log.i(TAG, "model loaded for virtual mode")
            }
            val bytes = assets.open("selftest.jpg").readBytes()
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            debugImage.setImageBitmap(bmp)
            debugImage.visibility = View.VISIBLE
            suppressStatus = true
            analysisW = bmp.width
            analysisH = bmp.height

            val bgr = ImageOps.bitmapToMat(bmp)
            val gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            val outDir = File(getExternalFilesDir(null) ?: filesDir, "scans")

            analysisExecutor.execute {
                var last = SystemClock.elapsedRealtime()
                var done = false
                while (!done && !frozen) {
                    val now = SystemClock.elapsedRealtime()
                    val dt = (now - last).coerceIn(1L, 200L)
                    last = now
                    val tDet = SystemClock.elapsedRealtime()
                    var det = try { detector.detect(gray) } catch (t: Throwable) { null }
                    if (det == null) {
                        val m = modelDetector
                        if (m != null) {
                            val r = try { m.detect(bgr) } catch (t: Throwable) { null }
                            if (r != null) {
                                det = DocDetector.Detection(
                                    r.quad, r.confidence.toDouble(),
                                    (quadArea(r.quad) / (gray.cols() * gray.rows()).toFloat()).toDouble()
                                )
                            }
                        }
                    }
                    val detMs = (SystemClock.elapsedRealtime() - tDet).toFloat()
                    val rect = det?.quad?.bbox()?.toCvRect()
                    val sharp = ImageOps.laplacianVariance(gray, rect)
                    val snap = tracker.update(det, sharp, 0f, dt)
                    main.post {
                        if (frozen) return@post
                        overlay.update(snap, bmp.width, bmp.height, detMs)
                        statusText.text = String.format(
                            "虚拟模式 %s · 评分 %.2f · %s",
                            snap.state, snap.score,
                            if (snap.state == DocTracker.State.COUNTDOWN) "倒计时" else ""
                        )
                    }
                    if (snap.shouldCapture && !done) {
                        done = true
                        frozen = true
                        main.post { overlay.clear() }
                        try {
                            val res = ScanCapture.process(bgr, snap.quad!!, outDir, detector, filter)
                            main.post {
                                showResult(bmp, res.quad, res, keepSource = true)
                            }
                            Log.i(TAG, "virtual auto-capture -> " + res.file.absolutePath)
                        } catch (t: Throwable) {
                            Log.e(TAG, "virtual capture failed", t)
                            main.post { restartRecognition() }
                        }
                    }
                    Thread.sleep(66)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "virtual mode failed", t)
        }
    }

    /** Debug hook: exercises the real CameraX capture path without a detection. */
    private fun forceCaptureForTest() {
        suppressStatus = true
        val w = analysisW.toFloat()
        val h = analysisH.toFloat()
        val iw = w * 0.1f
        val ih = h * 0.1f
        val q: Quad = arrayOf(
            android.graphics.PointF(iw, ih),
            android.graphics.PointF(w - iw, ih),
            android.graphics.PointF(w - iw, h - ih),
            android.graphics.PointF(iw, h - ih),
        )
        Log.i(TAG, "force capture with synthetic quad")
        capture(q)
    }

    /** Runs the full offline path on a bundled image: detect -> refine -> warp -> save. */
    private fun runSelfTest() {
        suppressStatus = true
        try {
            val bytes = assets.open("selftest.jpg").readBytes()
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val mat = ImageOps.bitmapToMat(bmp)

            val t0 = android.os.SystemClock.elapsedRealtime()
            val det = detector.detect(mat)
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            if (det == null) {
                statusText.text = "自检：未检出纸张"
                Log.w(TAG, "selftest: no detection")
                return
            }

            val outDir = File(getExternalFilesDir(null) ?: filesDir, "scans")
            val result = ScanCapture.process(mat, det.quad, outDir, detector, filter)
            mat.release()

            showResult(bmp, result.quad, result, keepSource = false)
            statusText.text = String.format(
                "自检：检出 %d ms，支撑 %.2f，面积 %.2f\n%s",
                ms, det.support, det.areaRatio, result.file.name
            )
            Log.i(TAG, "selftest ok: ${det.support} ${det.areaRatio} ${result.file.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "selftest failed", t)
            statusText.text = "自检失败：${t.message}"
        }
    }

    override fun onResume() {
        super.onResume()
        motion.start()
    }

    override fun onPause() {
        super.onPause()
        motion.stop()
    }

    private fun selector(): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

    private fun startCamera() {
        // wait for the view to be attached: display.rotation is only correct then
        previewView.post { bindCamera() }
    }

    private fun bindCamera() {
        val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                val preview = Preview.Builder()
                    .setResolutionSelector(selector())
                    .setTargetRotation(rotation)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                imageCapture = ImageCapture.Builder()
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

                analysis.setAnalyzer(analysisExecutor, analyzer)
                imageAnalysis = analysis

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                    imageCapture,
                )
                statusText.text = "把试卷放入取景框"
                cameraExecutor.execute {
                    try {
                        val t0 = android.os.SystemClock.elapsedRealtime()
                        modelDetector = DocModelDetector(applicationContext)
                        val ms = android.os.SystemClock.elapsedRealtime() - t0
                        modelState = "模型已加载 ${ms}ms"
                        Log.i(TAG, "model loaded in $ms ms")
                    } catch (t: Throwable) {
                        modelState = "模型加载失败"
                        Log.e(TAG, "model load failed", t)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "camera start failed", t)
                statusText.text = "相机启动失败: ${t.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(snapshot: DocTracker.Snapshot, w: Int, h: Int, ms: Float) {
        if (frozen) return
        analysisW = w
        analysisH = h
        main.post {
            if (frozen) return@post
            overlay.update(snapshot, w, h, ms)
            if (!suppressStatus) {
                statusText.text = when (snapshot.state) {
                    DocTracker.State.SEARCH -> "把试卷放入取景框 · $modelState"
                    DocTracker.State.TRACKING -> "识别中，请放稳"
                    DocTracker.State.LOCKED -> "已锁定，即将自动拍摄"
                    DocTracker.State.COUNTDOWN -> "保持不动…"
                }
            }
        }
        if (snapshot.shouldCapture && !capturing && !frozen) {
            main.post { if (!capturing && !frozen) capture() }
        }
    }

    /** 识别成功：立刻停掉分析流，冻结所有后续识别/拍摄。 */
    private fun stopRecognition() {
        frozen = true
        imageAnalysis?.clearAnalyzer()
        overlay.clear()
    }

    /** 恢复识别（处理失败时回退用）。 */
    private fun restartRecognition() {
        frozen = false
        imageAnalysis?.setAnalyzer(analysisExecutor, analyzer)
        overlay.clear()
    }

    private fun capture(quadOverride: Quad? = null) {
        if (capturing) return
        val quad = quadOverride ?: tracker.currentQuad() ?: return
        val capture = imageCapture ?: return

        capturing = true
        stopRecognition()
        statusText.text = "拍摄中…"

        val file = File(cacheDir, "shot.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                try {
                    val bmp = decodeOriented(file) ?: error("decode failed")
                    Log.i(TAG, "still=" + bmp.width + "x" + bmp.height + " analysis=" + analysisW + "x" + analysisH)
                    val mat = ImageOps.bitmapToMat(bmp)
                    val sx = bmp.width.toFloat() / analysisW
                    val sy = bmp.height.toFloat() / analysisH
                    val quadFull = quad.scaled(sx, sy)

                    val outDir = File(getExternalFilesDir(null) ?: filesDir, "scans")
                    val result = ScanCapture.process(mat, quadFull, outDir, detector, filter)
                    mat.release()

                    main.post {
                        showResult(bmp, result.quad, result, keepSource = false)
                        statusText.text = "已保存：${result.file.name}"
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "scan processing failed", t)
                    main.post {
                        restartRecognition()
                        statusText.text = "处理失败：${t.message}"
                    }
                } finally {
                    capturing = false
                    file.delete()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "capture error", exception)
                capturing = false
                main.post {
                    restartRecognition()
                    statusText.text = "拍照失败"
                }
            }
        })
    }

    /**
     * 显示识别结果：先播放"透视矫正前 → 矫正后"的动画，动画结束停在扫描件上。
     *
     * @param source 原始图（含透视畸变）
     * @param quad   纸在 [source] 坐标下的四边形（通常取精修后的角点）
     * @param keepSource 为 true 时保留 source 位图（例如虚拟模式还要显示它）
     */
    private fun showResult(
        source: Bitmap,
        quad: Quad,
        result: ScanCapture.Result,
        keepSource: Boolean,
    ) {
        // 结果页一出现就代表这次识别结束：停分析、解绑相机（预览一起冻结）
        stopRecognition()
        stopCamera()

        val (still, stillQuad) = prepareStill(source, quad)
        if (!keepSource && still !== source) source.recycle()

        rectifyView.visibility = View.VISIBLE
        rectifyView.play(still, stillQuad, result.bitmap)

        resultCaption.text = getString(R.string.result_caption, result.file.name)
        resultCaption.visibility = View.VISIBLE
        rescanButton.visibility = View.GONE
        statusText.text = getString(R.string.status_done)
    }

    /** 动画用的源图缩小到 ~1400px，避免每帧透视绘制一张 12MP 大图。 */
    private fun prepareStill(source: Bitmap, quad: Quad, maxSide: Int = 1400): Pair<Bitmap, Quad> {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSide) return source to quad
        val k = maxSide.toFloat() / longest
        val bmp = Bitmap.createScaledBitmap(
            source,
            (source.width * k).toInt().coerceAtLeast(1),
            (source.height * k).toInt().coerceAtLeast(1),
            true,
        )
        return bmp to quad.scaled(k, k)
    }

    /** 点"重新扫描"：收起结果页，重新绑定相机继续识别。 */
    private fun rescan() {
        rectifyView.stop()
        rectifyView.visibility = View.GONE
        rescanButton.visibility = View.GONE
        resultCaption.visibility = View.GONE

        tracker.reset()
        overlay.clear()
        frozen = false
        capturing = false
        suppressStatus = false
        statusText.text = getString(R.string.status_idle)
        if (!virtualMode) startCamera()
    }

    /** 解绑所有 CameraX use case，预览与识别一起停下。 */
    private fun stopCamera() {
        try {
            provider?.unbindAll()
        } catch (t: Throwable) {
            Log.e(TAG, "unbind failed", t)
        }
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        imageCapture = null
        camera = null
        torchOn = false
    }

    private fun decodeOriented(file: File): Bitmap? {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    companion object {
        private const val TAG = "PaperScan"
    }
}
