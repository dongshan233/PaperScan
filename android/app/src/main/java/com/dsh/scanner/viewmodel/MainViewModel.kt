package com.dsh.scanner.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dsh.scanner.R
import com.dsh.scanner.core.ScanCoordinator
import com.dsh.scanner.core.ScanEvent
import com.dsh.scanner.core.ScanFrame
import com.dsh.scanner.core.Scanner
import com.dsh.scanner.core.ScannerConfig
import com.dsh.scanner.ui.CameraFrames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * MVVM ViewModel（薄壳）：识别域的全部决策已抽到 [ScanCoordinator]（scanner-core），
 * 这里只做三件事：
 * 1. 把 module 的 [ScanCoordinator.state]/[ScanEvent] 转成宿主可渲染的 [ScanUiState]；
 * 2. 持有宿主自有状态（手电筒）并转发用户动作；
 * 3. 提供相机分析器适配器（ImageProxy → [ScanFrame]）。
 */
class MainViewModel(
    /** 识别引擎；生命周期与 ViewModel 一致，onCleared 时释放。 */
    private val engine: Scanner,
) : ViewModel() {

    /** module 编排层：持有全部识别域状态机与互锁。 */
    private val coordinator = ScanCoordinator(viewModelScope, engine)

    /** 宿主自有状态：手电筒。 */
    private val _torchOn = MutableStateFlow(false)

    // ------------------------------------------------------- 分段拍摄结果管理

    /** 单次任务最多拍摄张数；达到后限制继续添加。 */
    val maxCaptures = 2

    /** 已拍成的一条扫描结果（底部区域缩略图 + 保存的文件名）。 */
    data class CapturedScan(
        /** 底部区域显示用的缩略图；由 ViewModel 持有并随其销毁回收。 */
        val thumbnail: Bitmap,

        /** 扫描件文件名。 */
        val fileName: String,
    )

    /** 已拍摄的扫描结果列表（最多 [maxCaptures] 条）。 */
    private val _capturedScans = MutableStateFlow<List<CapturedScan>>(emptyList())

    /** 供 View 持续观察的已拍结果。 */
    val capturedScans: StateFlow<List<CapturedScan>> = _capturedScans.asStateFlow()

    /**
     * 「查看结果」所需的全部数据：扶正动画的源图、检测四边形和矫正后的截取图。
     * 位图由 ViewModel 持有，离开该状态或销毁时统一回收。
     */
    data class ReviewData(
        /** 动画源图（拍照得到的原图，含透视畸变）。 */
        val source: Bitmap,

        /** 识别出的纸张四边形（[source] 像素坐标，tl/tr/br/bl）。 */
        val quad: com.dsh.scanner.core.Quad,

        /** 矫正、去阴影后的扫描件，动画结束后的静态展示图。 */
        val scan: Bitmap,
    )

    /**
     * 预览区当前展示的查看数据。
     * 非 null 表示处于「查看结果」状态：相机已停、等待用户点击「再拍一页」。
     */
    private val _reviewScan = MutableStateFlow<ReviewData?>(null)

    /** 供 View 观察的查看数据（null 表示显示相机预览）。 */
    val reviewScan: StateFlow<ReviewData?> = _reviewScan.asStateFlow()

    /** 已拍数量是否达到上限。 */
    fun isLimitReached(): Boolean = _capturedScans.value.size >= maxCaptures

    /**
     * 供 View 持续观察的 UI 状态（module 快照 + 宿主状态合并）。
     * 处于「查看截取图」状态时，状态胶囊覆盖为拍摄进度提示。
     */
    val uiState: StateFlow<ScanUiState> =
        combine(
            coordinator.state,
            _torchOn.asStateFlow(),
            _capturedScans.asStateFlow(),
            _reviewScan.asStateFlow(),
        ) { scan, torch, captured, review ->
            ScanUiState(
                status = if (review != null) {
                    UiText.Res(R.string.review_hint, captured.size, maxCaptures)
                } else {
                    scan.status.toUiText()
                },
                filter = scan.filter,
                torchOn = torch,
                overlay = scan.overlay,
                previewActive = scan.previewActive,
                previewVisible = scan.previewVisible,
                capturing = scan.capturing,
                resultFileName = scan.resultFileName,
                rescanVisible = scan.rescanVisible,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanUiState())

    /** 一次性事件：拍照、结果显示和虚拟帧；View 按生命周期收集。 */
    val events = coordinator.events

    /**
     * 接收一次拍摄结果（自动触发或手动快门共用此入口）：
     * 生成缩略图加入底部区域，并把矫正后的截取图置入预览区展示；
     * 随后进入「查看结果」状态——相机保持冻结、识别暂停，
     * 只有用户点击「再拍一页」才会恢复预览与识别（见 [onAddPageClick]）。
     */
    fun onScanResult(event: ScanEvent.ShowResult) {
        if (isLimitReached()) {
            event.source.recycle()
            event.scan.recycle()
            return
        }
        val thumbW = 160
        val thumbH = (event.scan.height * (thumbW.toFloat() / event.scan.width))
            .toInt().coerceAtLeast(1)
        val thumbnail = Bitmap.createScaledBitmap(event.scan, thumbW, thumbH, true)
        _capturedScans.value = _capturedScans.value + CapturedScan(thumbnail, event.fileName)
        // 源图与矫正图一并移入查看状态：View 用它播放"扶正"动画，结束后静态展示
        recycleReview(_reviewScan.value)
        _reviewScan.value = ReviewData(event.source, event.quad, event.scan)
    }

    /** 回收查看状态持有的两张位图。 */
    private fun recycleReview(review: ReviewData?) {
        if (review == null) return
        review.source.takeIf { !it.isRecycled }?.recycle()
        review.scan.takeIf { !it.isRecycled }?.recycle()
    }

    /**
     * 「再拍一页」按钮：唯一的恢复入口。
     * 释放当前展示的截取图，恢复相机预览与识别；达到上限后此按钮隐藏，不可达。
     */
    fun onAddPageClick() {
        if (isLimitReached()) return
        recycleReview(_reviewScan.value)
        _reviewScan.value = null
        coordinator.onRescan()
    }

    /**
     * 删除指定位置的已拍结果（缩略图右上角删除按钮）。
     * 若删除时正处于「查看结果」状态（预览区展示的是截取图），则同时清掉展示图
     * 并恢复预览与识别，即"删除后重新预览拍摄"。
     */
    fun onDeleteScan(index: Int) {
        val scans = _capturedScans.value.toMutableList()
        if (index !in scans.indices) return
        scans.removeAt(index).thumbnail.takeIf { !it.isRecycled }?.recycle()
        _capturedScans.value = scans
        if (_reviewScan.value != null) {
            recycleReview(_reviewScan.value)
            _reviewScan.value = null
            coordinator.onRescan()
        }
    }

    /**
     * Adapter handed to CameraX. The adapter closes the [ImageProxy]; frames are
     * dropped while frozen or while a capture is in flight (module-side gate).
     */
    val imageAnalyzer: ImageAnalysis.Analyzer =
        CameraFrames.analyzer { frame -> coordinator.onCameraFrame(frame) }

    // ---------------------------------------------------------------- lifecycle

    fun onResume() = coordinator.onResume()

    fun onPause() = coordinator.onPause()

    override fun onCleared() {
        recycleReview(_reviewScan.value)
        _capturedScans.value.forEach { it.thumbnail.takeIf { t -> !t.isRecycled }?.recycle() }
        engine.close()
        super.onCleared()
    }

    // ------------------------------------------------------------ view actions

    fun onCameraPermissionGranted() = coordinator.onCameraPermissionGranted()

    fun onCameraPermissionDenied() = coordinator.onCameraPermissionDenied()

    fun onOpenCvInitFailed() = coordinator.onOpenCvInitFailed()

    fun onFilterClick() = coordinator.onFilterClick()

    fun onTorchClick() {
        _torchOn.value = !_torchOn.value
    }

    fun onShutterClick() {
        // 「查看结果」状态或达到上限时禁止拍摄
        if (isLimitReached() || _reviewScan.value != null) return
        coordinator.onShutterClick()
    }

    fun onRectifyFinished() = coordinator.onRectifyFinished()

    fun onRescan() = onAddPageClick()

    // ----------------------------------------------------------- camera events

    fun onCameraBound() = coordinator.onCameraBound()

    fun onCameraError(message: String) = coordinator.onCameraError(message)

    fun onPhotoCaptured(bitmap: android.graphics.Bitmap) = coordinator.onPhotoCaptured(bitmap)

    fun onPhotoCaptureError(message: String) = coordinator.onPhotoCaptureError(message)

    // ------------------------------------------------------------- debug hooks

    fun startVirtualMode() = coordinator.startVirtualMode()

    fun startSelfTest() = coordinator.startSelfTest()

    fun startForceCaptureTest() = coordinator.startForceCaptureTest()

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MainViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return MainViewModel(Scanner(context.applicationContext)) as T
                }
            }
    }
}
