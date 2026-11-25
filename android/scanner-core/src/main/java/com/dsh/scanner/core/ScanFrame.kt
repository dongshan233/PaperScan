package com.dsh.scanner.core

import android.graphics.Bitmap

/**
 * 帧输入抽象：把识别核心与具体的相机 API（如 CameraX 的 ImageProxy）解耦。
 *
 * 宿主负责从自己的相机框架提取数据并构造 [ScanFrame]，再交给 [Scanner.analyze]。
 * 所有权契约：[OfBitmap] 中的位图由调用方持有，[Scanner] 不会 recycle 它；
 * [Rgba] 的字节数组会被复制进 native Mat，构造后调用方可复用缓冲。
 */
sealed interface ScanFrame {

    /**
     * RGBA_8888 像素数据（已可带旋转信息，由 [Scanner] 内部摆正）。
     *
     * @param data 像素数据；按 [rowStride] 逐行读取，行尾 padding 会被跳过。
     * @param width 像素宽。
     * @param height 像素高。
     * @param rowStride 每行字节数；默认 `width * 4`。
     * @param rotationDegrees 需要顺时针旋转的角度（0/90/180/270），来自相机传感器方向。
     */
    class Rgba(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val rowStride: Int = width * 4,
        val rotationDegrees: Int = 0,
    ) : ScanFrame

    /** 直接给一张 Bitmap（虚拟相机、单图离线分析的入口）。 */
    class OfBitmap(
        val bitmap: Bitmap,
    ) : ScanFrame
}
