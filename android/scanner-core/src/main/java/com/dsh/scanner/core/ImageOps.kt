package com.dsh.scanner.core

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object ImageOps {

    /**
     * RGBA 像素数据 -> 摆正后的 CV_8UC4 Mat（处理行 padding 与旋转）。
     * 宿主从相机框架提取数据后经 [ScanFrame.Rgba] 进入识别链路。
     */
    fun rgbaFromBytes(
        data: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        rotationDegrees: Int,
    ): Mat {
        val mat = Mat(height, width, CvType.CV_8UC4)
        if (rowStride == width * 4) {
            mat.put(0, 0, data, 0, rowStride * height)
        } else {
            val row = ByteArray(width * 4)
            for (r in 0 until height) {
                val off = r * rowStride
                if (off + width * 4 > data.size) break
                System.arraycopy(data, off, row, 0, width * 4)
                mat.put(r, 0, row)
            }
        }
        when (rotationDegrees) {
            90 -> Core.rotate(mat, mat, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(mat, mat, Core.ROTATE_180)
            270 -> Core.rotate(mat, mat, Core.ROTATE_90_COUNTERCLOCKWISE)
        }
        return mat
    }

    /** Large-radius blur via a pyramid; sigma-40 Gaussian is far too slow. */
    fun background(src: Mat, ds: Int = 4, k: Int = 21): Mat {
        val sw = maxOf(src.cols() / ds, 8)
        val sh = maxOf(src.rows() / ds, 8)
        val small = Mat()
        Imgproc.resize(src, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val blurred = Mat()
        Imgproc.blur(small, blurred, Size(k.toDouble(), k.toDouble()))
        small.release()
        val up = Mat()
        Imgproc.resize(blurred, up, src.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        blurred.release()
        return up
    }

    fun laplacianVariance(gray: Mat, region: Rect?): Float {
        val roi = if (region != null && region.width > 8 && region.height > 8) Mat(gray, region) else gray
        val lap = Mat()
        Imgproc.Laplacian(roi, lap, CvType.CV_64F)
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(lap, mean, std)
        val v = std.get(0, 0)[0]
        lap.release()
        if (roi !== gray) roi.release()
        return (v * v).toFloat()
    }

    fun bitmapToMat(bmp: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bmp, mat)
        return mat
    }

    /**
     * Bitmap (ARGB_8888) -> BGR Mat.
     *
     * [Utils.bitmapToMat] produces RGBA, but the detector/capture pipeline speaks
     * BGR. Feeding RGBA in directly swaps the red and blue channels, which is
     * invisible on black-and-white exam paper but wrong for coloured content.
     */
    fun bitmapToBgr(bmp: Bitmap): Mat {
        val rgba = bitmapToMat(bmp)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return bgr
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        return bmp
    }
}

fun android.graphics.RectF.toCvRect(): Rect =
    Rect(left.toInt().coerceAtLeast(0), top.toInt().coerceAtLeast(0),
        width().toInt().coerceAtLeast(1), height().toInt().coerceAtLeast(1))
