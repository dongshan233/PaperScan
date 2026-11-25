package com.dsh.scanner.viewmodel

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

    /** 供 View 持续观察的 UI 状态（module 快照 + 宿主状态合并）。 */
    val uiState: StateFlow<ScanUiState> =
        combine(coordinator.state, _torchOn.asStateFlow()) { scan, torch ->
            ScanUiState(
                status = scan.status.toUiText(),
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
     * Adapter handed to CameraX. The adapter closes the [ImageProxy]; frames are
     * dropped while frozen or while a capture is in flight (module-side gate).
     */
    val imageAnalyzer: ImageAnalysis.Analyzer =
        CameraFrames.analyzer { frame -> coordinator.onCameraFrame(frame) }

    // ---------------------------------------------------------------- lifecycle

    fun onResume() = coordinator.onResume()

    fun onPause() = coordinator.onPause()

    override fun onCleared() {
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

    fun onShutterClick() = coordinator.onShutterClick()

    fun onRectifyFinished() = coordinator.onRectifyFinished()

    fun onRescan() = coordinator.onRescan()

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
