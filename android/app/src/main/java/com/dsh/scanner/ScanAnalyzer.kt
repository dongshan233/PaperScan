package com.dsh.scanner

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Runs detection + tracking on the analysis stream (background thread).
 *
 * Detection order: classical edges first (3-8 ms), and only when that finds
 * nothing, the DocAligner corner model (every other frame, tens of ms).
 */
class ScanAnalyzer(
    private val detector: DocDetector,
    private val tracker: DocTracker,
    private val omega: () -> Float,
    private val model: () -> DocModelDetector?,
    private val onFrame: (DocTracker.Snapshot, Int, Int, Float) -> Unit,
) : ImageAnalysis.Analyzer {

    private var lastTs = 0L
    private var emaMs = 33f
    private var logged = false
    private var frames = 0
    private var lastModelMs = 0f
    private var lastConvertMs = 0f
    private var lastSource = "edge"
    private var cachedModelQuad: Quad? = null
    private var cachedModelConf = 0f
    private var cachedModelFrame = -100

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastTs == 0L) 33L else (now - lastTs).coerceIn(1L, 200L)
        lastTs = now
        frames++

        try {
            val rgba = ImageOps.imageToRgba(image)
            val bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            rgba.release()
            val gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            if (!logged) {
                logged = true
                android.util.Log.i(
                    "PaperScan",
                    "analysis src=" + image.width + "x" + image.height +
                        " rot=" + image.imageInfo.rotationDegrees +
                        " -> gray=" + gray.cols() + "x" + gray.rows()
                )
            }

            val t0 = SystemClock.elapsedRealtime()
            var det = try {
                detector.detect(gray)
            } catch (t: Throwable) {
                null
            }
            var source = if (det != null) "edge" else "none"

            if (det == null) {
                val m = model()
                // the model costs ~200 ms on this SoC: run it every 3rd frame and
                // coast on the previous result in between so the box stays stable
                if (m != null && frames % 3 == 0) {
                    lastConvertMs = 0f
                    if (true) {
                        val tM = SystemClock.elapsedRealtime()
                        val r = try {
                            val prev = if (frames - cachedModelFrame <= 9) cachedModelQuad else null
                            m.detect(bgr, prev)
                        } catch (t: Throwable) {
                            null
                        }
                        lastModelMs = (SystemClock.elapsedRealtime() - tM).toFloat()
                        if (r != null) {
                            val area = quadArea(r.quad) / (gray.cols() * gray.rows()).toFloat()
                            det = DocDetector.Detection(
                                r.quad,
                                r.confidence.toDouble().coerceIn(0.0, 1.0),
                                area.toDouble(),
                            )
                            source = "model"
                            cachedModelQuad = r.quad
                            cachedModelConf = r.confidence
                            cachedModelFrame = frames
                        }
                    }
                } else if (m != null && cachedModelQuad != null &&
                    frames - cachedModelFrame <= 6 && det == null
                ) {
                    val q = cachedModelQuad!!
                    det = DocDetector.Detection(
                        q, cachedModelConf.toDouble(),
                        (quadArea(q) / (gray.cols() * gray.rows()).toFloat()).toDouble(),
                    )
                    source = "model-cached"
                }
            }

            val ms = (SystemClock.elapsedRealtime() - t0).toFloat()
            emaMs = 0.9f * emaMs + 0.1f * ms
            lastSource = source

            val rect = det?.quad?.bbox()?.toCvRect()
            val sharp = ImageOps.laplacianVariance(gray, rect)
            if (frames % 30 == 0) {
                android.util.Log.i(
                    "PaperScan",
                    "frame src=$source conv=${lastConvertMs.toInt()}ms model=${lastModelMs.toInt()}ms ema=${emaMs.toInt()}ms"
                )
            }
            val snapshot = tracker.update(det, sharp, omega(), dt)
            onFrame(snapshot, gray.cols(), gray.rows(), emaMs)
            gray.release()
            bgr.release()
        } catch (t: Throwable) {
            // never let a single bad frame kill the session
        } finally {
            image.close()
        }
    }

    fun source(): String = lastSource
    fun modelMs(): Float = lastModelMs
}
