package com.dsh.scanner.core

import android.graphics.Bitmap
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot

/**
 * Full-resolution capture pipeline: sub-pixel refinement -> perspective warp ->
 * shadow removal -> JPEG.
 */
object ScanCapture {

    /** 横版 A4 输出长边（150 dpi 左右）。 */
    const val A4_LONG = 1754

    /** 竖版 A4 输出短边。 */
    const val A4_SHORT = 1240

    /** 扫描件的色彩模式；点按滤镜按钮按 COLOR → ENHANCE → GRAY 循环。 */
    enum class Filter {
        /** 原彩：只做透视矫正，不调色。 */
        COLOR,

        /** 增强：LAB 只压平 L 通道，保留 a/b 色度，并轻微锐化。 */
        ENHANCE,

        /** 黑白：灰度 + 背景除法 + 锐化，传统扫描件观感。 */
        GRAY;

        /** 返回循环中的下一个模式。 */
        fun next(): Filter = when (this) {
            COLOR -> ENHANCE
            ENHANCE -> GRAY
            GRAY -> COLOR
        }
    }

    data class Result(
        /** 矫正并应用滤镜后的位图，交给结果页显示。 */
        val bitmap: Bitmap,

        /** 已写盘的 JPEG 文件。 */
        val file: File,

        /** 全分辨率下精修后的纸张四边形。 */
        val quad: Quad,
    )

    /**
     * 全分辨率处理：直线精修 → 透视变换到 A4 → 去阴影/锐化 → 保存 JPEG。
     *
     * @param src 全分辨率 BGR 图。
     * @param coarse 检测阶段得到的粗四边形。
     * @param outDir JPEG 输出目录，不存在会自动创建。
     * @param detector 提供 [DocDetector.refine] 的检测器实例。
     * @param filter 色彩模式，默认 [Filter.GRAY]。
     * @param jpegQuality JPEG 压缩质量（0..100）。
     */
    fun process(
        src: Mat,
        coarse: Quad,
        outDir: File,
        detector: DocDetector,
        filter: Filter = Filter.GRAY,
        jpegQuality: Int = 92,
    ): Result {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        val refined = detector.refine(gray, coarse, band = maxOf(8, (coarse.bbox().width() * 0.01f).toInt()))
            .first
        gray.release()

        // landscape sheet -> landscape A4; portrait sheet -> portrait A4
        val topW = hypot(refined[1].x - refined[0].x, refined[1].y - refined[0].y)
        val botW = hypot(refined[2].x - refined[3].x, refined[2].y - refined[3].y)
        val leftH = hypot(refined[3].x - refined[0].x, refined[3].y - refined[0].y)
        val rightH = hypot(refined[2].x - refined[1].x, refined[2].y - refined[1].y)
        val wide = (topW + botW) > (leftH + rightH)
        val outW = if (wide) A4_LONG else A4_SHORT
        val outH = if (wide) A4_SHORT else A4_LONG

        val srcPts = MatOfPoint2f(*refined.toPointList().toTypedArray())
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((outW - 1).toDouble(), 0.0),
            Point((outW - 1).toDouble(), (outH - 1).toDouble()),
            Point(0.0, (outH - 1).toDouble())
        )
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val warped = Mat()
        Imgproc.warpPerspective(
            src, warped, m, Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE
        )
        srcPts.release()
        dstPts.release()
        m.release()

        val enhanced = when (filter) {
            Filter.COLOR -> warped.clone()
            Filter.ENHANCE -> enhanceColor(warped)
            Filter.GRAY -> enhanceGray(warped)
        }
        warped.release()

        outDir.mkdirs()
        val file = File(outDir, "scan_${System.currentTimeMillis()}.jpg")
        val bmp = ImageOps.matToBitmap(enhanced)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it) }
        enhanced.release()
        return Result(bmp, file, refined)
    }

    /** Colour kept: only the L channel is flattened, so cast/ink colours survive. */
    private fun enhanceColor(bgr: Mat): Mat {
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val ch = ArrayList<Mat>(3)
        Core.split(lab, ch)
        lab.release()

        val bg = ImageOps.background(ch[0], ds = 8, k = 21)
        val ln = Mat()
        Core.divide(ch[0], bg, ln, 232.0)
        bg.release()
        ch[0].release()
        ch[0] = ln

        val merged = Mat()
        Core.merge(ch, merged)
        ch.forEach { it.release() }

        val out = Mat()
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_Lab2BGR)
        merged.release()

        val blur = Mat()
        Imgproc.GaussianBlur(out, blur, Size(0.0, 0.0), 1.6)
        Core.addWeighted(out, 1.3, blur, -0.3, 0.0, out)
        blur.release()
        return out
    }

    /** Grayscale scan look: divide by a large-radius background, then unsharp. */
    private fun enhanceGray(bgr: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val bg = ImageOps.background(gray, ds = 8, k = 21)
        val norm = Mat()
        Core.divide(gray, bg, norm, 232.0)
        bg.release()

        val blur = Mat()
        Imgproc.GaussianBlur(norm, blur, Size(0.0, 0.0), 1.6)
        Core.addWeighted(norm, 1.4, blur, -0.4, 0.0, norm)
        blur.release()

        val out = Mat()
        Imgproc.cvtColor(norm, out, Imgproc.COLOR_GRAY2BGR)
        norm.release()
        gray.release()
        return out
    }
}
