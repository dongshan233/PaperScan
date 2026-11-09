package com.dsh.scanner.model

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object ImageOps {

    /** YUV_420_888 -> upright grayscale Mat (rotation from the image info applied). */
    fun yuvToGray(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val w = image.width
        val h = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val raw = if (pixelStride == 1 && rowStride == w) {
            Mat(h, w, CvType.CV_8UC1).apply { put(0, 0, data) }
        } else {
            val m = Mat(h, w, CvType.CV_8UC1)
            val row = ByteArray(w)
            for (r in 0 until h) {
                val off = r * rowStride
                if (off >= data.size) break
                val n = minOf(w, data.size - off)
                System.arraycopy(data, off, row, 0, n)
                m.put(r, 0, row)
            }
            m
        }

        when (image.imageInfo.rotationDegrees) {
            90 -> Core.rotate(raw, raw, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(raw, raw, Core.ROTATE_180)
            270 -> Core.rotate(raw, raw, Core.ROTATE_90_COUNTERCLOCKWISE)
        }
        return raw
    }

    /** YUV_420_888 -> BGR for the corner model. Uses bulk copies: the naive
     *  per-byte version cost ~200 ms on the test device. */
    fun yuvToBgr(image: ImageProxy): Mat {
        val w = image.width
        val h = image.height
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yBuf = yP.buffer.also { it.rewind() }

        val nv21 = ByteArray(w * h * 3 / 2)
        // Y: one bulk read per row (rowStride may be padded)
        val row = ByteArray(w)
        for (r in 0 until h) {
            val off = r * yP.rowStride
            if (off + w > yBuf.limit()) break
            yBuf.position(off)
            yBuf.get(row, 0, w)
            System.arraycopy(row, 0, nv21, r * w, w)
        }

        val uvStart = w * h
        val uvBytes = w * h / 2
        val vBuf = vP.buffer.also { it.rewind() }
        val uBuf = uP.buffer.also { it.rewind() }
        val planar = uP.pixelStride == 1 && vP.pixelStride == 1
        if (planar) {
            // I420 layout: U then V, each (w/2 x h/2), rows may be padded
            val cw = w / 2
            val ch = h / 2
            var dst = uvStart
            for (r in 0 until ch) {
                uBuf.position(r * uP.rowStride)
                uBuf.get(nv21, dst + r * cw, cw)
            }
            dst = uvStart + cw * ch
            for (r in 0 until ch) {
                vBuf.position(r * vP.rowStride)
                vBuf.get(nv21, dst + r * cw, cw)
            }
        } else if (vP.pixelStride == 2 && vP.rowStride == w && vBuf.remaining() >= uvBytes) {
            // semi-planar, V first -> the V plane already is the interleaved VU buffer
            vBuf.get(nv21, uvStart, uvBytes)
        } else {
            var off = uvStart
            for (r in 0 until h / 2) {
                for (c in 0 until w / 2) {
                    nv21[off++] = vBuf.get(r * vP.rowStride + c * vP.pixelStride)
                    nv21[off++] = uBuf.get(r * uP.rowStride + c * uP.pixelStride)
                }
            }
        }

        val yuv = Mat(h * 3 / 2, w, CvType.CV_8UC1)
        yuv.put(0, 0, nv21)
        val bgr = Mat()
        if (planar) {
            Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_I420)
        } else {
            Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_NV21)
        }
        yuv.release()
        return bgr
    }

    /** RGBA_8888 ImageProxy -> upright CV_8UC4 Mat (row padding handled). */
    fun imageToRgba(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val buf = plane.buffer.also { it.rewind() }
        val w = image.width
        val h = image.height
        val rowBytes = w * 4
        val mat = Mat(h, w, CvType.CV_8UC4)
        if (plane.rowStride == rowBytes) {
            val all = ByteArray(rowBytes * h)
            buf.get(all)
            mat.put(0, 0, all)
        } else {
            val row = ByteArray(rowBytes)
            for (r in 0 until h) {
                buf.position(r * plane.rowStride)
                buf.get(row, 0, rowBytes)
                mat.put(r, 0, row)
            }
        }
        when (image.imageInfo.rotationDegrees) {
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
