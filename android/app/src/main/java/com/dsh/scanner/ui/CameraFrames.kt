package com.dsh.scanner.ui

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.dsh.scanner.core.ScanFrame

/**
 * CameraX → module 帧适配器：把 ImageProxy（RGBA_8888 输出）摘成
 * [ScanFrame.Rgba]。module 不依赖 CameraX，适配成本集中在这一处。
 *
 * 所有权契约：调用方负责 close [ImageProxy]；字节数组构造 ScanFrame 后
 * 可立即复用缓冲（Scanner 会拷入 native Mat）。
 */
object CameraFrames {

    /**
     * 提取 RGBA 像素数据。要求 ImageAnalysis 以
     * `OUTPUT_IMAGE_FORMAT_RGBA_8888` 输出（CameraController 已如此配置）。
     */
    fun toScanFrame(image: ImageProxy): ScanFrame {
        val plane = image.planes[0]
        val buf = plane.buffer.also { it.rewind() }
        val w = image.width
        val h = image.height
        val rowBytes = w * 4

        val data = ByteArray(rowBytes * h)
        if (plane.rowStride == rowBytes) {
            buf.get(data)
        } else {
            var off = 0
            for (r in 0 until h) {
                buf.position(r * plane.rowStride)
                buf.get(data, off, rowBytes)
                off += rowBytes
            }
        }
        return ScanFrame.Rgba(
            data = data,
            width = w,
            height = h,
            rowStride = rowBytes,
            rotationDegrees = image.imageInfo.rotationDegrees,
        )
    }

    /**
     * 分析器入口：要求上游以 `OUTPUT_IMAGE_FORMAT_RGBA_8888` 配置
     * （CameraController 已如此设置），否则仅 close 后丢弃。
     */
    fun analyzer(block: (ScanFrame) -> Unit): ImageAnalysis.Analyzer =
        ImageAnalysis.Analyzer { image ->
            try {
                if (image.planes.size == 1 && image.planes[0].pixelStride == 4) {
                    block(toScanFrame(image))
                }
            } finally {
                image.close()
            }
        }
}
