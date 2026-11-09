package com.dsh.scanner.viewmodel

import androidx.annotation.StringRes
import com.dsh.scanner.R
import com.dsh.scanner.model.DocTracker
import com.dsh.scanner.model.ScanCapture

/** 扫描页的可渲染状态；ViewModel 只产出这个对象，View 按字段渲染。 */
data class ScanUiState(
    /** 底部状态栏文案；搜索态会带上模型加载状态。 */
    val status: UiText = UiText.Res(R.string.status_idle),

    /** 当前色彩模式（默认黑白）；点按滤镜按钮按 COLOR → ENHANCE → GRAY 循环。 */
    val filter: ScanCapture.Filter = ScanCapture.Filter.GRAY,

    /** 手电筒是否开启；由 View 同步给 CameraControl。 */
    val torchOn: Boolean = false,

    /** 最新一帧的跟踪结果；null 表示不画检测框（拍照中/结果页）。 */
    val overlay: OverlayFrame? = null,

    /** PreviewView 是否可见；虚拟相机模式设为 false，让 debugImage 露出。 */
    val previewVisible: Boolean = true,

    /** 是否应该绑定并运行相机；结果页和解绑后为 false。 */
    val previewActive: Boolean = false,

    /** 是否正在拍照/处理；为 true 时暂停分析流并禁用快门。 */
    val capturing: Boolean = false,

    /** 结果页扫描件文件名；非 null 表示当前显示结果页。 */
    val resultFileName: String? = null,

    /** 「重新扫描」按钮是否可见（扶正动画结束后才出现）。 */
    val rescanVisible: Boolean = false,
)

/**
 * 一帧跟踪结果及其坐标基准。View 用 [imageWidth]/[imageHeight] 把四边形
 * 从分析帧坐标映射到 PreviewView 的 fitCenter 坐标。
 */
data class OverlayFrame(
    /** 平滑后的四边形、评分和状态机快照。 */
    val snapshot: DocTracker.Snapshot,

    /** 分析帧宽度（已应用旋转，单位 px）。 */
    val imageWidth: Int,

    /** 分析帧高度（已应用旋转，单位 px）。 */
    val imageHeight: Int,

    /** 检测耗时的 EMA（含模型兜底），用于 HUD 的 ms/帧。 */
    val frameMs: Float,
)

/** 滤镜枚举到 `strings.xml` 文案的映射。 */
@StringRes
fun ScanCapture.Filter.labelRes(): Int = when (this) {
    ScanCapture.Filter.COLOR -> R.string.filter_color
    ScanCapture.Filter.ENHANCE -> R.string.filter_enhance
    ScanCapture.Filter.GRAY -> R.string.filter_gray
}
