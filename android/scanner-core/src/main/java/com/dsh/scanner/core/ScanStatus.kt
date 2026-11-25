package com.dsh.scanner.core

import android.graphics.Bitmap

/**
 * 扫描页的可渲染状态子集（module 对外契约）。
 * 宿主 ViewModel 观察它并与自己的宿主状态（如手电筒）合并后渲染。
 * 文案由宿主按 [ScanStatus] 自行映射，module 不引用宿主资源。
 */
data class ScanSnapshot(
    /** 当前状态/提示。 */
    val status: ScanStatus = ScanStatus.Idle,

    /** 当前色彩模式；点按滤镜按钮按 COLOR → ENHANCE → GRAY 循环。 */
    val filter: ScanCapture.Filter = ScanCapture.Filter.GRAY,

    /** 最新一帧的跟踪结果；null 表示不画检测框（拍照中/结果页）。 */
    val overlay: OverlayFrame? = null,

    /** 是否应该绑定并运行相机；结果页和解绑后为 false。 */
    val previewActive: Boolean = false,

    /** 预览是否可见；虚拟相机模式下为 false，让 debugImage 露出。 */
    val previewVisible: Boolean = true,

    /** 是否正在拍照/处理；为 true 时暂停分析流并禁用快门。 */
    val capturing: Boolean = false,

    /** 结果页扫描件文件名；非 null 表示当前显示结果页。 */
    val resultFileName: String? = null,

    /** 「重新扫描」按钮是否可见（扶正动画结束后才出现）。 */
    val rescanVisible: Boolean = false,
)

/**
 * 一帧跟踪结果及其坐标基准。宿主用 [imageWidth]/[imageHeight] 把四边形
 * 从分析帧坐标映射到预览控件的 fitCenter 坐标。
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

/**
 * 状态/提示描述。module 只描述语义，宿主负责映射成文案与多语言。
 */
sealed interface ScanStatus {
    /** 空闲（初始态）。 */
    data object Idle : ScanStatus

    /** 相机权限被拒绝。 */
    data object PermissionRequired : ScanStatus

    /** OpenCV native 库初始化失败。 */
    data object OpenCvInitFailed : ScanStatus

    /** 相机绑定/启动失败。 */
    data class CameraError(val message: String) : ScanStatus

    /** 搜索纸张；model 描述角点模型加载进度。 */
    data class Searching(val model: ModelState) : ScanStatus

    /** 检测到纸张，评分未过线。 */
    data object Tracking : ScanStatus

    /** 评分过线，正在累计稳定帧。 */
    data object Locked : ScanStatus

    /** 倒计时中。 */
    data object Countdown : ScanStatus

    /** 正在拍照。 */
    data object Capturing : ScanStatus

    /** 正在矫正处理。 */
    data object Processing : ScanStatus

    /** 扫描件已保存。 */
    data class Saved(val fileName: String) : ScanStatus

    /** 处理完成（虚拟模式，不落盘提示）。 */
    data object Done : ScanStatus

    /** 拍照/处理失败。 */
    data class Failed(val reason: String) : ScanStatus

    // ---- 虚拟相机调试 ----
    data object VirtualLoading : ScanStatus
    data class Virtual(val state: DocTracker.State, val score: Float, val countingDown: Boolean) : ScanStatus
    data object VirtualFailed : ScanStatus

    // ---- 自检调试 ----
    data object SelfTestRunning : ScanStatus
    data object SelfTestNoPaper : ScanStatus
    data class SelfTestFailed(val message: String) : ScanStatus
    data class SelfTestOk(
        val detectionMs: Long,
        val support: Float,
        val areaRatio: Float,
        val fileName: String,
    ) : ScanStatus
}

/** 角点模型加载状态，附在 [ScanStatus.Searching] 上。 */
sealed interface ModelState {
    data object NotLoaded : ModelState
    data object Loading : ModelState
    data class Loaded(val elapsedMs: Long) : ModelState
    data class Failed(val error: String) : ModelState
}

/** 一次性事件：相机动作和位图交接，不属于持续状态。 */
sealed interface ScanEvent {
    /** 编排层已判定该拍照；CameraX 调用由宿主执行。 */
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

    /** 虚拟相机调试：把合成帧放到预览下面显示。 */
    data class ShowDebugImage(
        /** 内置 selftest 图片；由宿主控件持有。 */
        val bitmap: Bitmap,
    ) : ScanEvent
}
