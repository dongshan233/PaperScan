package com.dsh.scanner.viewmodel

import android.graphics.Bitmap
import com.dsh.scanner.model.Quad

/** 一次性事件：相机动作和位图交接，不属于持续状态。 */
sealed interface ScanEvent {
    /** ViewModel 已判定该拍照；CameraX 调用由 View 执行。 */
    data object CapturePhoto : ScanEvent

    /** 播放斜纸扶正动画，结束后显示扫描件。 */
    data class ShowResult(
        /** 动画用的原图，长边不超过约 1400 px。 */
        val source: Bitmap,

        /** 纸张在 [source] 坐标系下的四边形（tl/tr/br/bl）。 */
        val quad: Quad,

        /** 最终矫正后的扫描件。 */
        val scan: Bitmap,

        /** 扫描件文件名，用于结果页标题。 */
        val fileName: String,
    ) : ScanEvent

    /** 虚拟相机调试：把合成帧放到 Overlay 下面显示。 */
    data class ShowDebugImage(
        /** 内置 selftest 图片；由 ImageView 持有。 */
        val bitmap: Bitmap,
    ) : ScanEvent
}
