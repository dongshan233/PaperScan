package com.dsh.scanner.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Model layer for the scanner: one place that owns detection, temporal tracking,
 * the optional corner model and the full-resolution capture pipeline.
 *
 * CameraX frames arrive through [analyze]; the virtual-camera debug hook uses
 * [analyzeBitmap]. All public operations are serialized so the tracker/model
 * state is never touched from two threads at once.
 */
class ScanEngine(
    /** 应用 Context，仅用于读取 assets 和创建输出目录。 */
    private val appContext: Context,
) {

    /** 经典边缘检测器（低对比度兜底未开启）。 */
    private val detector = DocDetector()

    /** 时序平滑、评分和拍摄状态机。 */
    private val tracker = DocTracker()

    /** 陀螺仪角速度来源，用于判断手持是否稳定。 */
    private val motion = MotionGate(appContext)

    /** DocAligner 角点模型；null 表示尚未加载或加载失败。 */
    @Volatile
    private var modelDetector: DocModelDetector? = null

    /** 上一帧时间戳，用来计算帧间隔 dt。 */
    private var lastTs = 0L

    /** 检测耗时的 EMA（ms），显示在 HUD 上。 */
    private var emaMs = 33f

    /** 已分析的帧数，用于控制角点模型每 3 帧跑一次。 */
    private var frames = 0

    /** 首帧尺寸日志只打一次。 */
    private var logged = false

    /** 最近一次模型推理耗时（ms）。 */
    private var lastModelMs = 0f

    /** 上次模型输出的四边形，供中间帧复用。 */
    private var cachedModelQuad: Quad? = null

    /** 上次模型输出的置信度。 */
    private var cachedModelConf = 0f

    /** 上次模型结果对应的帧号。 */
    private var cachedModelFrame = -100

    /** 最新平滑四边形；放在引擎锁外，保证按快门不必等模型推理。 */
    @Volatile
    private var latestQuad: Quad? = null

    fun startMotion() = motion.start()

    fun stopMotion() = motion.stop()

    /** CameraX adapter entry point. Always closes [image]. */
    @Synchronized
    fun analyze(image: ImageProxy): FrameResult? {
        val now = SystemClock.elapsedRealtime()
        val dt = nextDelta(now)
        frames++

        var rgba: Mat? = null
        var bgr: Mat? = null
        var gray: Mat? = null
        return try {
            rgba = ImageOps.imageToRgba(image)
            bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)

            if (!logged) {
                logged = true
                Log.i(
                    TAG,
                    "analysis src=${image.width}x${image.height} rot=${image.imageInfo.rotationDegrees}" +
                        " -> gray=${gray.cols()}x${gray.rows()}",
                )
            }
            processFrame(bgr, gray, dt, motion.angularSpeedDeg)
        } catch (t: Throwable) {
            Log.e(TAG, "frame analysis failed", t)
            null
        } finally {
            rgba?.release()
            bgr?.release()
            gray?.release()
            image.close()
        }
    }

    /**
     * Feeds a still bitmap through the same detection/tracking path as a camera
     * frame. Used by the `--ez virtual true` debug hook.
     */
    @Synchronized
    fun analyzeBitmap(bitmap: Bitmap, omegaDeg: Float = 0f): FrameResult? {
        val now = SystemClock.elapsedRealtime()
        val dt = nextDelta(now)
        frames++

        var bgr: Mat? = null
        var gray: Mat? = null
        return try {
            bgr = ImageOps.bitmapToBgr(bitmap)
            gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            processFrame(bgr, gray, dt, omegaDeg)
        } catch (t: Throwable) {
            Log.e(TAG, "virtual frame analysis failed", t)
            null
        } finally {
            bgr?.release()
            gray?.release()
        }
    }

    private fun nextDelta(now: Long): Long {
        val dt = if (lastTs == 0L) 33L else (now - lastTs).coerceIn(1L, 200L)
        lastTs = now
        return dt
    }

    private fun processFrame(bgr: Mat, gray: Mat, dt: Long, omegaDeg: Float): FrameResult {
        val t0 = SystemClock.elapsedRealtime()
        var det = try {
            detector.detect(gray)
        } catch (t: Throwable) {
            Log.w(TAG, "edge detection failed", t)
            null
        }
        var source = if (det != null) "edge" else "none"

        if (det == null) {
            val model = modelDetector
            // The model costs ~150 ms: run it every third frame and coast on the
            // previous result in between so the box stays stable.
            if (model != null && frames % 3 == 0) {
                val tModel = SystemClock.elapsedRealtime()
                val result = try {
                    val previous = if (frames - cachedModelFrame <= 9) cachedModelQuad else null
                    model.detect(bgr, previous)
                } catch (t: Throwable) {
                    Log.w(TAG, "model detection failed", t)
                    null
                }
                lastModelMs = (SystemClock.elapsedRealtime() - tModel).toFloat()
                if (result != null) {
                    val area = quadArea(result.quad) / (gray.cols() * gray.rows()).toFloat()
                    det = DocDetector.Detection(
                        result.quad,
                        result.confidence.toDouble().coerceIn(0.0, 1.0),
                        area.toDouble(),
                    )
                    source = "model"
                    cachedModelQuad = result.quad
                    cachedModelConf = result.confidence
                    cachedModelFrame = frames
                }
            } else if (model != null && cachedModelQuad != null &&
                frames - cachedModelFrame <= 6
            ) {
                val quad = cachedModelQuad!!
                det = DocDetector.Detection(
                    quad,
                    cachedModelConf.toDouble(),
                    (quadArea(quad) / (gray.cols() * gray.rows()).toFloat()).toDouble(),
                )
                source = "model-cached"
            }
        }

        val detectionMs = (SystemClock.elapsedRealtime() - t0).toFloat()
        emaMs = 0.9f * emaMs + 0.1f * detectionMs

        val rect = det?.quad?.bbox()?.toCvRect()
        val sharpness = ImageOps.laplacianVariance(gray, rect)
        if (frames % 30 == 0) {
            Log.i(
                TAG,
                "frame src=$source model=${lastModelMs.toInt()}ms ema=${emaMs.toInt()}ms",
            )
        }

        val snapshot = tracker.update(det, sharpness, omegaDeg, dt)
        latestQuad = snapshot.quad
        return FrameResult(snapshot, gray.cols(), gray.rows(), emaMs)
    }

    /**
     * Loads the DocAligner ONNX model. Blocking; call from a worker thread.
     * Subsequent calls are cheap and return the cached result.
     */
    @Synchronized
    fun loadModel(): ModelLoadResult {
        modelDetector?.let { return ModelLoadResult(true, 0L) }

        val t0 = SystemClock.elapsedRealtime()
        return try {
            val loaded = DocModelDetector(appContext)
            modelDetector = loaded
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "model loaded in $ms ms")
            ModelLoadResult(true, ms)
        } catch (t: Throwable) {
            val ms = SystemClock.elapsedRealtime() - t0
            Log.e(TAG, "model load failed", t)
            ModelLoadResult(false, ms, t.message ?: t.javaClass.simpleName)
        }
    }

    /** Full-resolution path: refine -> warp -> shadow removal -> JPEG. Blocking. */
    @Synchronized
    fun processCapture(source: Bitmap, quad: Quad, filter: ScanCapture.Filter): ScanCapture.Result {
        var bgr: Mat? = null
        return try {
            bgr = ImageOps.bitmapToBgr(source)
            ScanCapture.process(bgr, quad, outputDir(), detector, filter)
        } finally {
            bgr?.release()
        }
    }

    /** Offline check on the bundled image: detect -> refine -> warp -> save. Blocking. */
    @Synchronized
    fun runSelfTest(filter: ScanCapture.Filter): SelfTestResult {
        val source = loadAssetBitmap("selftest.jpg")
            ?: return SelfTestResult.Failed("内置图片读取失败")

        var bgr: Mat? = null
        var gray: Mat? = null
        var sourceTransferred = false
        return try {
            bgr = ImageOps.bitmapToBgr(source)
            gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)

            val t0 = SystemClock.elapsedRealtime()
            val detection = detector.detect(gray)
            val detectionMs = SystemClock.elapsedRealtime() - t0
            if (detection == null) {
                SelfTestResult.NoPaper(detectionMs)
            } else {
                val scan = ScanCapture.process(bgr, detection.quad, outputDir(), detector, filter)
                Log.i(
                    TAG,
                    "selftest ok: support=${detection.support} area=${detection.areaRatio}" +
                        " file=${scan.file.absolutePath}",
                )
                sourceTransferred = true
                SelfTestResult.Detected(
                    source = source,
                    quad = detection.quad,
                    scan = scan,
                    detectionMs = detectionMs,
                    support = detection.support.toFloat(),
                    areaRatio = detection.areaRatio.toFloat(),
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "selftest failed", t)
            SelfTestResult.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            bgr?.release()
            gray?.release()
            if (!sourceTransferred && !source.isRecycled) source.recycle()
        }
    }

    fun loadAssetBitmap(name: String): Bitmap? = try {
        appContext.assets.open(name).use { BitmapFactory.decodeStream(it) }
    } catch (t: Throwable) {
        Log.e(TAG, "asset $name failed to load", t)
        null
    }

    fun outputDir(): File =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "scans")

    @Synchronized
    fun reset() {
        tracker.reset()
        latestQuad = null
        cachedModelQuad = null
        cachedModelConf = 0f
        cachedModelFrame = -100
        lastTs = 0L
    }

    fun currentQuad(): Quad? = latestQuad?.copyOf()

    @Synchronized
    fun close() {
        modelDetector?.close()
        modelDetector = null
    }

    private companion object {
        const val TAG = "PaperScan"
    }
}
