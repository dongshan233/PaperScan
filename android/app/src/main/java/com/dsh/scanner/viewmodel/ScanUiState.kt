package com.dsh.scanner.viewmodel

import androidx.annotation.StringRes
import com.dsh.scanner.R
import com.dsh.scanner.core.DocTracker
import com.dsh.scanner.core.ModelState
import com.dsh.scanner.core.OverlayFrame
import com.dsh.scanner.core.ScanCapture
import com.dsh.scanner.core.ScanStatus

/** 扫描页的可渲染状态；由 [ScanSnapshot]（module）与宿主状态合并而来。 */
data class ScanUiState(
    /** 底部状态栏文案；由 [ScanStatus] 语义映射而来。 */
    val status: UiText = UiText.Res(R.string.status_idle),

    /** 当前色彩模式；点按滤镜按钮按 COLOR → ENHANCE → GRAY 循环。 */
    val filter: ScanCapture.Filter = ScanCapture.Filter.GRAY,

    /** 手电筒是否开启；由 View 同步给 CameraControl（宿主自有状态）。 */
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

/** 滤镜枚举到 `strings.xml` 文案的映射。 */
@StringRes
fun ScanCapture.Filter.labelRes(): Int = when (this) {
    ScanCapture.Filter.COLOR -> R.string.filter_color
    ScanCapture.Filter.ENHANCE -> R.string.filter_enhance
    ScanCapture.Filter.GRAY -> R.string.filter_gray
}

/** module 的状态语义 → 宿主文案。 */
fun ScanStatus.toUiText(): UiText = when (this) {
    ScanStatus.Idle -> UiText.Res(R.string.status_idle)
    ScanStatus.PermissionRequired -> UiText.Res(R.string.status_permission_required)
    ScanStatus.OpenCvInitFailed -> UiText.Res(R.string.status_opencv_failed)
    is ScanStatus.CameraError -> UiText.Res(R.string.status_camera_failed, message)
    is ScanStatus.Searching -> UiText.Res(R.string.status_search, model.toUiText())
    ScanStatus.Tracking -> UiText.Res(R.string.status_tracking)
    ScanStatus.Locked -> UiText.Res(R.string.status_locked)
    ScanStatus.Countdown -> UiText.Res(R.string.status_countdown)
    ScanStatus.Capturing -> UiText.Res(R.string.status_capturing)
    ScanStatus.Processing -> UiText.Res(R.string.status_processing)
    is ScanStatus.Saved -> UiText.Res(R.string.status_saved, fileName)
    ScanStatus.Done -> UiText.Res(R.string.status_done)
    is ScanStatus.Failed -> UiText.Res(R.string.status_processing_failed, reason)
    ScanStatus.VirtualLoading -> UiText.Res(R.string.status_virtual_loading)
    is ScanStatus.Virtual -> if (countingDown) {
        UiText.Res(R.string.status_virtual_countdown, state.stateText(), score)
    } else {
        UiText.Res(R.string.status_virtual, state.stateText(), score)
    }
    ScanStatus.VirtualFailed -> UiText.Res(R.string.status_virtual_failed)
    ScanStatus.SelfTestRunning -> UiText.Res(R.string.status_selftest_running)
    ScanStatus.SelfTestNoPaper -> UiText.Res(R.string.status_selftest_no_paper)
    is ScanStatus.SelfTestFailed -> UiText.Res(R.string.status_selftest_failed, message)
    is ScanStatus.SelfTestOk -> UiText.Res(
        R.string.status_selftest_ok,
        detectionMs,
        support,
        areaRatio,
        fileName,
    )
}

/** 模型加载状态 → 状态栏后缀文案。 */
fun ModelState.toUiText(): UiText = when (this) {
    ModelState.NotLoaded -> UiText.Res(R.string.model_not_loaded)
    ModelState.Loading -> UiText.Res(R.string.model_loading)
    is ModelState.Loaded -> UiText.Res(R.string.model_loaded, elapsedMs)
    is ModelState.Failed -> UiText.Res(R.string.model_failed)
}

private fun DocTracker.State.stateText(): UiText = when (this) {
    DocTracker.State.SEARCH -> UiText.Res(R.string.state_search)
    DocTracker.State.TRACKING -> UiText.Res(R.string.state_tracking)
    DocTracker.State.LOCKED -> UiText.Res(R.string.state_locked)
    DocTracker.State.COUNTDOWN -> UiText.Res(R.string.state_countdown)
}
