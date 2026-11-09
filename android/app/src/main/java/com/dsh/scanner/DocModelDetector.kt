package com.dsh.scanner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.PointF
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer

/**
 * DocAligner fastvit_t8 corner model (ONNX, Apache-2.0).
 *
 * Input : 256x256 BGR, scaled to [0,1], NCHW, **no mean/std normalisation**
 * Output: 4 x 128x128 corner heatmaps
 * Decode: resize each heatmap back, threshold at 0.3, take the centroid of the
 *         largest blob -> corner in input-image coordinates
 *
 * It is used as the fallback when the classical edge detector finds nothing
 * (white paper on a white desk). Its corners are only accurate to a few pixels
 * at 256 resolution, so they are always fed through DocDetector.refine().
 */
class DocModelDetector(context: Context) {

    companion object {
        const val INPUT = 256
        const val HEAT = 128
        const val THRESHOLD = 0.15f
        private const val ASSET = "docaligner_fastvit_t8.onnx"
    }

    private var runs = 0

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val bytes = context.assets.open(ASSET).readBytes()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(4)
        // CPU beats NNAPI on this SoC (147 ms vs 628 ms); keep NNAPI opt-in
        session = env.createSession(bytes, opts)
        android.util.Log.i("PaperScan", "model on CPU")
    }

    data class Result(val quad: Quad, val confidence: Float)

    fun detect(bgr: Mat, roi: Quad? = null): Result? {
        val w = bgr.cols()
        val h = bgr.rows()
        if (w < 32 || h < 32) return null

        // Square crop around the previous detection (page fills the 256 input,
        // which is what makes the model confident), else centre crop.
        var side = minOf(w, h)
        var ox = (w - side) / 2
        var oy = (h - side) / 2
        if (roi != null) {
            val r = roi.bbox()
            val want = (maxOf(r.width(), r.height()) * 1.25f).toInt()
            side = want.coerceIn(64, minOf(w, h))
            ox = (r.centerX() - side / 2f).toInt().coerceIn(0, w - side)
            oy = (r.centerY() - side / 2f).toInt().coerceIn(0, h - side)
        }
        val s = side
        val roiMat = Mat(bgr, Rect(ox, oy, s, s))
        val small = Mat()
        Imgproc.resize(roiMat, small, Size(INPUT.toDouble(), INPUT.toDouble()))
        roiMat.release()

        val pixels = ByteArray(INPUT * INPUT * 3)
        small.get(0, 0, pixels)
        small.release()

        val plane = INPUT * INPUT
        val chw = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val o = i * 3
            chw[i] = (pixels[o].toInt() and 0xFF) / 255f
            chw[plane + i] = (pixels[o + 1].toInt() and 0xFF) / 255f
            chw[2 * plane + i] = (pixels[o + 2].toInt() and 0xFF) / 255f
        }

        var sum = 0.0
        var mn = 1f
        var mx = 0f
        for (v in chw) { sum += v; if (v < mn) mn = v; if (v > mx) mx = v }
        android.util.Log.i("PaperScan", "model input mean=%.4f min=%.3f max=%.3f".format(sum / chw.size, mn, mx))

        val tensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(chw),
            longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())
        )
        val tRun = android.os.SystemClock.elapsedRealtime()
        val output = try {
            session.run(mapOf("img" to tensor))
        } catch (t: Throwable) {
            android.util.Log.e("PaperScan", "model inference failed", t)
            tensor.close()
            return null
        }
        tensor.close()
        val inferMs = android.os.SystemClock.elapsedRealtime() - tRun

        val heat = (output.get(0) as OnnxTensor).floatBuffer
        android.util.Log.i("PaperScan", "heat capacity=" + heat.capacity() + " limit=" + heat.limit() + " remaining=" + heat.remaining())
        val pts = arrayOfNulls<PointF>(4)
        var confidence = 0f
        var accepted = 0
        val peaks = FloatArray(4)
        val t0 = android.os.SystemClock.elapsedRealtime()
        val scale = s.toFloat() / HEAT

        for (c in 0 until 4) {
            val arr = FloatArray(HEAT * HEAT)
            heat.position(c * HEAT * HEAT)
            heat.get(arr)
            val pk = arr.max()
            confidence += pk
            peaks[c] = pk

            // peak + weighted centroid in a small window (cheaper than contours,
            // same result to sub-pixel on the 128x128 heatmap)
            var maxV = -1f
            var mx = 0
            var my = 0
            for (y in 0 until HEAT) {
                val row = y * HEAT
                for (x in 0 until HEAT) {
                    val v = arr[row + x]
                    if (v > maxV) {
                        maxV = v
                        mx = x
                        my = y
                    }
                }
            }
            if (maxV < THRESHOLD) continue
            accepted++
            confidence += maxV

            var sw = 0f
            var sx = 0f
            var sy = 0f
            val cut = THRESHOLD * 0.5f
            for (dy in -3..3) {
                val y = my + dy
                if (y !in 0 until HEAT) continue
                for (dx in -3..3) {
                    val x = mx + dx
                    if (x !in 0 until HEAT) continue
                    val v = arr[y * HEAT + x]
                    if (v >= cut) {
                        sw += v
                        sx += v * x
                        sy += v * y
                    }
                }
            }
            if (sw <= 1e-6f) continue
            pts[c] = PointF(sx / sw * scale + ox, sy / sw * scale + oy)
        }
        output.close()

        if (runs++ % 10 == 0) {
            android.util.Log.i(
                "PaperScan",
                "model peaks=" + peaks.joinToString(",") { "%.2f".format(it) } +
                    " corners=" + pts.count { it != null } + " infer=" + inferMs + "ms"
            )
        }
        val found = pts.count { it != null }
        if (found < 3) return null
        // one corner missing: a page is ~a parallelogram, so p_i = p_i+1 + p_i+3 - p_i+2
        if (found == 3) {
            val miss = pts.indexOfFirst { it == null }
            val a = pts[(miss + 1) % 4]!!
            val b = pts[(miss + 3) % 4]!!
            val c = pts[(miss + 2) % 4]!!
            pts[miss] = PointF(a.x + b.x - c.x, a.y + b.y - c.y)
            android.util.Log.i("PaperScan", "model: corner $miss inferred")
        }
        return Result(orderQuad(pts.map { it!! }), (confidence / accepted).coerceIn(0f, 1f))
    }

    fun close() {
        session.close()
    }
}
