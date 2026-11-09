package com.dsh.scanner

import android.graphics.PointF
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Page-edge detector: illumination normalisation -> Canny -> contours ->
 * 4-point convex candidates -> edge-support scoring -> sub-pixel line fit.
 *
 * This is the Kotlin port of scanner/pipeline.py (D1/D2 tiers).
 */
class DocDetector(
    private val lowContrast: Boolean = false,
    private val minAreaRatio: Double = 0.15,
    private val minSupport: Double = 0.40,
) {

    data class Detection(val quad: Quad, val support: Double, val areaRatio: Double)

    private var frames = 0
    private var lastStats = ""

    fun detect(gray: Mat): Detection? {
        val w = gray.cols()
        val h = gray.rows()
        if (w < 32 || h < 32) return null

        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        val bg = background(blurred)
        val norm = Mat()
        Core.divide(blurred, bg, norm, 128.0)
        bg.release()

        val edges = Mat()
        Imgproc.Canny(norm, edges, 35.0, 105.0)
        if (lowContrast) {
            val permissive = Mat()
            Imgproc.Canny(norm, permissive, 8.0, 24.0)
            Core.bitwise_or(edges, permissive, edges)
            permissive.release()
        }
        norm.release()

        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, k3)
        val d = if (lowContrast) 3.0 else 5.0
        val kd = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(d, d))
        val dilated = Mat()
        Imgproc.dilate(edges, dilated, kd)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        var nContours = 0
        var nFour = 0
        var nArea = 0
        var nSupport = 0
        var bestQuad: Quad? = null
        var bestSupport = 0.0
        var bestArea = 0.0
        var bestScore = 0.0

        for (c in contours) {
            nContours++
            if (Imgproc.contourArea(c) < minAreaRatio * w * h * 0.55) continue
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            c2f.release()
            if (approx.total() != 4L) continue
            nFour++

            val pts = approx.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) }
            val quad = orderQuad(pts)
            val ar = quadArea(quad) / (w * h).toFloat()
            if (ar < minAreaRatio) continue
            nArea++

            val sup = edgeSupport(quad, dilated)
            if (sup < minSupport) continue
            nSupport++

            val marginPx = minOf(
                quad.minOf { it.x }, quad.minOf { it.y },
                w - quad.maxOf { it.x }, h - quad.maxOf { it.y }
            ) / w
            var penalty = 1.0
            if (ar > 0.92f) penalty *= 0.15
            if (marginPx < 0.01f) penalty *= 0.25
            val score = (0.55 * sup + 0.45 * minOf(ar / 0.55, 1.0)) * penalty
            if (score > bestScore) {
                bestScore = score
                bestQuad = quad
                bestSupport = sup
                bestArea = ar.toDouble()
            }
        }
        blurred.release()
        edges.release()
        dilated.release()
        hierarchy.release()

        frames++
        if (frames % 30 == 0) {
            val mag = Mat()
            Imgproc.Sobel(gray, mag, org.opencv.core.CvType.CV_16S, 1, 0, 3)
            val mean = org.opencv.core.MatOfDouble()
            val std = org.opencv.core.MatOfDouble()
            Core.meanStdDev(mag, mean, std)
            mag.release()
            lastStats = "contours=$nContours fourPoint=$nFour areaOk=$nArea supportOk=$nSupport best=%.2f gradStd=%.1f"
                .format(bestScore, std.get(0, 0)[0])
            android.util.Log.i("PaperScan", "detect " + lastStats)
        }
        val coarse = bestQuad ?: return null
        val refined = refine(gray, coarse, band = 6).first
        return Detection(refined, bestSupport, bestArea)
    }

    /** Large-radius background via a pyramid; sigma-25 Gaussian is far too slow. */
    private fun background(src: Mat): Mat {
        val sw = maxOf(src.cols() / 4, 8)
        val sh = maxOf(src.rows() / 4, 8)
        val small = Mat()
        Imgproc.resize(src, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val blurred = Mat()
        Imgproc.blur(small, blurred, Size(21.0, 21.0))
        small.release()
        val up = Mat()
        Imgproc.resize(blurred, up, src.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        blurred.release()
        return up
    }

    private fun edgeSupport(q: Quad, edges: Mat, n: Int = 56): Double {
        var hit = 0
        var total = 0
        for (i in 0 until 4) {
            val p = q[i]
            val r = q[(i + 1) % 4]
            for (k in 0 until n) {
                val t = 0.04f + 0.92f * k / (n - 1)
                val x = (p.x + (r.x - p.x) * t).roundToInt()
                val y = (p.y + (r.y - p.y) * t).roundToInt()
                if (x in 0 until edges.cols() && y in 0 until edges.rows()) {
                    total++
                    if (edges.get(y, x)[0] > 0.0) hit++
                }
            }
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    /**
     * Snap a coarse quad to sub-pixel edges: scan each side along its normal for
     * the gradient ridge, fit a line (robust, two passes) and intersect.
     */
    fun refine(gray: Mat, quad: Quad, band: Int = 8, samples: Int = 90): Pair<Quad, DoubleArray> {
        val g = Mat()
        Imgproc.GaussianBlur(gray, g, Size(3.0, 3.0), 0.0)
        val gx = Mat()
        val gy = Mat()
        Imgproc.Sobel(g, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(g, gy, CvType.CV_32F, 0, 1, 3)
        g.release()
        val mag = Mat()
        Core.magnitude(gx, gy, mag)
        gx.release()
        gy.release()

        val h = mag.rows()
        val w = mag.cols()
        val lines = ArrayList<DoubleArray>(4)
        val support = DoubleArray(4)

        for (i in 0 until 4) {
            val p = quad[i]
            val q = quad[(i + 1) % 4]
            val dx = q.x - p.x
            val dy = q.y - p.y
            val len = hypot(dx.toDouble(), dy.toDouble())
            if (len < 16.0) return quad to DoubleArray(4)
            val ux = (dx / len).toFloat()
            val uy = (dy / len).toFloat()
            val nx = -uy
            val ny = ux

            val pts = ArrayList<Point>()
            var hits = 0
            for (k in 0 until samples) {
                val t = 0.05f + 0.90f * k / (samples - 1)
                val bx = p.x + dx * t
                val by = p.y + dy * t
                var bestVal = -1.0
                var bestOff = 0f
                for (off in -band..band) {
                    val x = (bx + nx * off).roundToInt()
                    val y = (by + ny * off).roundToInt()
                    if (x !in 0 until w || y !in 0 until h) continue
                    val v = mag.get(y, x)[0]
                    if (v > bestVal) {
                        bestVal = v
                        bestOff = off.toFloat()
                    }
                }
                if (bestVal < 8.0) continue
                hits++
                // parabolic interpolation around the discrete maximum
                val xm = (bx + nx * (bestOff - 1)).roundToInt()
                val ym = (by + ny * (bestOff - 1)).roundToInt()
                val xp = (bx + nx * (bestOff + 1)).roundToInt()
                val yp = (by + ny * (bestOff + 1)).roundToInt()
                var sub = bestOff
                if (xm in 0 until w && ym in 0 until h && xp in 0 until w && yp in 0 until h) {
                    val a = mag.get(ym, xm)[0]
                    val b = bestVal
                    val c = mag.get(yp, xp)[0]
                    val den = 2.0 * (a - 2 * b + c)
                    if (abs(den) > 1e-9) sub = bestOff + ((a - c) / den).toFloat()
                }
                pts.add(Point((bx + nx * sub).toDouble(), (by + ny * sub).toDouble()))
            }
            support[i] = hits.toDouble() / samples
            if (pts.size < 14) {
                mag.release()
                return quad to support
            }

            var line = fitLine(pts)
            // one robust pass: drop the worst 20% of the residuals, refit
            val residuals = pts.map { pt ->
                abs((pt.x - line[2]) * line[1] - (pt.y - line[3]) * line[0])
            }.sorted()
            val cut = residuals[(residuals.size * 0.8).toInt().coerceIn(0, residuals.size - 1)]
            val keep = pts.filterIndexed { idx, pt ->
                abs((pt.x - line[2]) * line[1] - (pt.y - line[3]) * line[0]) <= cut
            }
            if (keep.size >= 14) line = fitLine(keep)
            lines.add(line)
        }
        mag.release()

        val out = ArrayList<PointF>(4)
        for (i in 0 until 4) {
            val pt = intersect(lines[i], lines[(i + 1) % 4]) ?: return quad to support
            out.add(PointF(pt[0].toFloat(), pt[1].toFloat()))
        }
        val refined = orderQuad(out)
        if (quadArea(refined) <= 0f) return quad to support
        val ratio = quadArea(refined) / quadArea(quad)
        if (ratio < 0.7f || ratio > 1.3f) return quad to support
        if (quadMeanDistance(refined, quad) > 0.12f * quadDiagonal(quad)) return quad to support
        return refined to support
    }

    /** line = [vx, vy, x0, y0] */
    private fun fitLine(pts: List<Point>): DoubleArray {
        val m = MatOfPoint2f(*pts.toTypedArray())
        val line = Mat()
        Imgproc.fitLine(m, line, Imgproc.DIST_L2, 0.0, 0.01, 0.01)
        val out = doubleArrayOf(
            line.get(0, 0)[0], line.get(1, 0)[0], line.get(2, 0)[0], line.get(3, 0)[0]
        )
        m.release()
        line.release()
        return out
    }

    private fun intersect(a: DoubleArray, b: DoubleArray): DoubleArray? {
        val den = a[0] * b[1] - a[1] * b[0]
        if (abs(den) < 1e-8) return null
        val s = ((b[2] - a[2]) * b[1] - (b[3] - a[3]) * b[0]) / den
        return doubleArrayOf(a[2] + s * a[0], a[3] + s * a[1])
    }
}
