package com.dsh.scanner.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dsh.scanner.R
import com.dsh.scanner.model.DocTracker
import com.dsh.scanner.model.FrameResult
import com.dsh.scanner.model.ModelLoadResult
import com.dsh.scanner.model.Quad
import com.dsh.scanner.model.ScanCapture
import com.dsh.scanner.model.ScanEngine
import com.dsh.scanner.model.SelfTestResult
import com.dsh.scanner.model.scaled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MVVM ViewModel: owns the scan state machine and all page/scan decisions.
 *
 * It knows nothing about Activity, CameraX use cases or Views. Camera frames
 * arrive through [imageAnalyzer]; when a capture is warranted the ViewModel
 * emits [ScanEvent.CapturePhoto] and the View performs the CameraX call.
 */
class MainViewModel(
    /** 模型层：帧分析、模型加载和全分辨率处理。 */
    private val engine: ScanEngine,
) : ViewModel() {

    /** 供 View 持续观察的 UI 状态。 */
    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    /** 一次性事件：拍照、结果显示和虚拟帧；View 按生命周期收集。 */
    private val _events = Channel<ScanEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * Adapter handed to CameraX. The engine closes the [ImageProxy]; frames are
     * dropped while frozen or while a capture is in flight.
     */
    val imageAnalyzer: ImageAnalysis.Analyzer = object : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            if (!acceptingFrames || frozen || capturing.get()) {
                image.close()
                return
            }
            try {
                engine.analyze(image)?.let { onFrame(it, FrameSource.CAMERA) }
            } catch (t: Throwable) {
                Log.e(TAG, "camera frame failed", t)
            }
        }
    }

    /** 防止自动触发和手动快门重复拍照。 */
    private val capturing = AtomicBoolean(false)

    /** 是否接收分析帧；拍照中、结果页和解绑后为 false。 */
    @Volatile private var acceptingFrames = false

    /** 已识别成功并冻结；点「重新扫描」才解除。 */
    @Volatile private var frozen = false

    /** 自检/虚拟模式期间为 true，避免逐帧状态覆盖自定义提示。 */
    @Volatile private var suppressStatus = false

    /** 当前是否运行虚拟相机调试入口。 */
    @Volatile private var virtualMode = false

    /** 当前帧来源；决定拍照走 CameraX 还是直接处理内置 Bitmap。 */
    @Volatile private var frameSource = FrameSource.CAMERA

    /** 最近一帧分析图宽度（px，已按旋转摆正），用于缩放四边形。 */
    @Volatile private var analysisW = 640

    /** 最近一帧分析图高度（px，已按旋转摆正）。 */
    @Volatile private var analysisH = 480

    /** 当前拍照使用的四边形（分析帧坐标）。 */
    @Volatile private var pendingQuad: Quad? = null

    /** 虚拟模式使用的内置图；同时作为 debugImage 的显示源。 */
    @Volatile private var virtualBitmap: Bitmap? = null

    /** 虚拟相机循环任务，重新扫描/退出时取消。 */
    private var virtualJob: Job? = null

    /** 搜索态状态栏里的模型文案（未加载/加载中/耗时/失败）。 */
    private var modelText: UiText = UiText.Res(R.string.model_not_loaded)

    /** DocAligner 模型是否已成功加载。 */
    private var modelLoaded = false

    /** 模型是否正在加载，避免并发重复创建 ORT session。 */
    private var modelLoading = false

    // ---------------------------------------------------------------- lifecycle

    fun onResume() {
        engine.startMotion()
    }

    fun onPause() {
        engine.stopMotion()
    }

    override fun onCleared() {
        virtualJob?.cancel()
        engine.stopMotion()
        engine.close()
        super.onCleared()
    }

    // ------------------------------------------------------------ view actions

    fun onCameraPermissionGranted() {
        if (virtualMode) return
        frozen = false
        capturing.set(false)
        _uiState.update {
            it.copy(
                previewActive = true,
                previewVisible = true,
                status = UiText.Res(R.string.status_idle),
            )
        }
    }

    fun onCameraPermissionDenied() {
        _uiState.update {
            it.copy(
                previewActive = false,
                status = UiText.Res(R.string.status_permission_required),
            )
        }
    }

    fun onOpenCvInitFailed() {
        _uiState.update { it.copy(status = UiText.Res(R.string.status_opencv_failed)) }
    }

    fun onFilterClick() {
        _uiState.update { it.copy(filter = it.filter.next()) }
    }

    fun onTorchClick() {
        _uiState.update { it.copy(torchOn = !it.torchOn) }
    }

    fun onShutterClick() {
        if (!acceptingFrames || frozen || capturing.get()) return
        val quad = engine.currentQuad() ?: return
        requestCapture(quad, frameSource)
    }

    fun onRectifyFinished() {
        if (virtualMode) return
        _uiState.update { it.copy(rescanVisible = true) }
    }

    fun onRescan() {
        acceptingFrames = false
        frozen = true
        if (virtualMode) {
            virtualJob?.cancel()
            virtualJob = null
            engine.reset()
            pendingQuad = null
            capturing.set(false)
            frozen = false
            _uiState.update {
                it.copy(
                    overlay = null,
                    capturing = false,
                    resultFileName = null,
                    rescanVisible = false,
                    status = UiText.Res(R.string.status_idle),
                )
            }
            startVirtualMode()
            return
        }

        engine.reset()
        pendingQuad = null
        capturing.set(false)
        frozen = false
        suppressStatus = false
        _uiState.update {
            it.copy(
                overlay = null,
                capturing = false,
                resultFileName = null,
                rescanVisible = false,
                torchOn = false,
                status = UiText.Res(R.string.status_idle),
                previewActive = true,
                previewVisible = true,
            )
        }
    }

    // ----------------------------------------------------------- camera events

    fun onCameraBound() {
        if (virtualMode || !_uiState.value.previewActive) return
        frameSource = FrameSource.CAMERA
        acceptingFrames = true
        if (!suppressStatus && _uiState.value.resultFileName == null) {
            _uiState.update { it.copy(status = UiText.Res(R.string.status_search, modelText)) }
        }
        viewModelScope.launch { loadModelAwait() }
    }

    fun onCameraError(message: String) {
        acceptingFrames = false
        _uiState.update {
            it.copy(
                previewActive = false,
                status = UiText.Res(R.string.status_camera_failed, message),
            )
        }
    }

    fun onPhotoCaptured(bitmap: Bitmap) {
        viewModelScope.launch {
            if (!capturing.get()) {
                bitmap.recycle()
                return@launch
            }
            val quad = pendingQuad
            val w = analysisW
            val h = analysisH
            if (quad == null || w <= 0 || h <= 0) {
                bitmap.recycle()
                failCapture(UiText.Res(R.string.status_capture_failed))
                return@launch
            }
            val scaled = quad.scaled(bitmap.width.toFloat() / w, bitmap.height.toFloat() / h)
            processCapture(bitmap, scaled, keepSource = false, announceSaved = true)
        }
    }

    fun onPhotoCaptureError(message: String) {
        Log.e(TAG, "capture error: $message")
        viewModelScope.launch {
            failCapture(UiText.Res(R.string.status_capture_failed))
        }
    }

    // ------------------------------------------------------------ scan pipeline

    /**
     * Called from the analysis thread (camera) or the main thread (virtual mode).
     * State updates are thread-safe; capture starts are guarded by [capturing].
     */
    private fun onFrame(frame: FrameResult, source: FrameSource) {
        if (!acceptingFrames || frozen || source != frameSource) return

        analysisW = frame.imageWidth
        analysisH = frame.imageHeight

        val overlay = OverlayFrame(
            snapshot = frame.snapshot,
            imageWidth = frame.imageWidth,
            imageHeight = frame.imageHeight,
            frameMs = frame.frameMs,
        )
        val status = when {
            virtualMode -> if (frame.snapshot.state == DocTracker.State.COUNTDOWN) {
                UiText.Res(
                    R.string.status_virtual_countdown,
                    stateText(frame.snapshot.state),
                    frame.snapshot.score,
                )
            } else {
                UiText.Res(
                    R.string.status_virtual,
                    stateText(frame.snapshot.state),
                    frame.snapshot.score,
                )
            }

            suppressStatus -> null
            else -> statusFor(frame.snapshot.state)
        }
        _uiState.update { current ->
            current.copy(overlay = overlay, status = status ?: current.status)
        }

        if (frame.snapshot.shouldCapture) {
            frame.snapshot.quad?.let { requestCapture(it, source) }
        }
    }

    private fun requestCapture(quad: Quad, source: FrameSource) {
        if (source != frameSource) return
        if (!capturing.compareAndSet(false, true)) return

        acceptingFrames = false
        frozen = true
        pendingQuad = quad.copyOf()
        _uiState.update {
            it.copy(
                capturing = true,
                overlay = null,
                status = UiText.Res(R.string.status_capturing),
            )
        }

        when (source) {
            FrameSource.CAMERA -> _events.trySend(ScanEvent.CapturePhoto)
            FrameSource.VIRTUAL -> {
                val bitmap = virtualBitmap
                if (bitmap == null) {
                    failCapture(UiText.Res(R.string.status_capture_failed))
                } else {
                    viewModelScope.launch {
                        processCapture(bitmap, quad.copyOf(), keepSource = true, announceSaved = false)
                    }
                }
            }
        }
    }

    private suspend fun processCapture(
        source: Bitmap,
        quad: Quad,
        keepSource: Boolean,
        announceSaved: Boolean,
    ) {
        val filter = _uiState.value.filter
        _uiState.update { it.copy(status = UiText.Res(R.string.status_processing)) }

        val outcome = withContext(Dispatchers.Default) {
            try {
                Result.success(engine.processCapture(source, quad, filter))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
        outcome.onSuccess { result ->
            val status = if (announceSaved) {
                UiText.Res(R.string.status_saved, result.file.name)
            } else {
                UiText.Res(R.string.status_done)
            }
            showResult(source, result, keepSource, status)
        }.onFailure { error ->
            Log.e(TAG, "scan processing failed", error)
            if (!keepSource && !source.isRecycled) source.recycle()
            failCapture(
                UiText.Res(
                    R.string.status_processing_failed,
                    error.message ?: error.javaClass.simpleName,
                ),
            )
        }
    }

    private fun failCapture(status: UiText) {
        pendingQuad = null
        capturing.set(false)
        frozen = false
        acceptingFrames = !virtualMode && _uiState.value.previewActive
        _uiState.update { it.copy(capturing = false, status = status) }
    }

    /**
     * Ends this recognition: stop frames, unbind the preview (state drives it),
     * trim the animation source and hand both bitmaps to the View.
     */
    private fun showResult(
        source: Bitmap,
        result: ScanCapture.Result,
        keepSource: Boolean,
        status: UiText,
    ) {
        acceptingFrames = false
        frozen = true
        capturing.set(false)
        pendingQuad = null
        _uiState.update {
            it.copy(
                previewActive = false,
                capturing = false,
                overlay = null,
                resultFileName = result.file.name,
                rescanVisible = false,
                status = status,
            )
        }

        val (still, stillQuad) = try {
            prepareStill(source, result.quad)
        } catch (t: Throwable) {
            Log.e(TAG, "prepare still failed", t)
            source to result.quad
        }
        if (!keepSource && still !== source && !source.isRecycled) source.recycle()
        _events.trySend(ScanEvent.ShowResult(still, stillQuad, result.bitmap, result.file.name))
    }

    /** The animation source is capped at ~1400 px; 12 MP per frame is too slow. */
    private fun prepareStill(source: Bitmap, quad: Quad, maxSide: Int = 1400): Pair<Bitmap, Quad> {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSide) return source to quad
        val k = maxSide.toFloat() / longest
        val bitmap = Bitmap.createScaledBitmap(
            source,
            (source.width * k).toInt().coerceAtLeast(1),
            (source.height * k).toInt().coerceAtLeast(1),
            true,
        )
        return bitmap to quad.scaled(k, k)
    }

    // -------------------------------------------------------------- model load

    private suspend fun loadModelAwait(): Boolean {
        if (modelLoaded) return true
        if (modelLoading) {
            var waited = 0
            while (modelLoading && waited < 10_000) {
                delay(50)
                waited += 50
            }
            return modelLoaded
        }

        modelLoading = true
        modelText = UiText.Res(R.string.model_loading)
        refreshSearchStatus()
        val result = withContext(Dispatchers.IO) {
            try {
                engine.loadModel()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                ModelLoadResult(false, 0L, t.message ?: t.javaClass.simpleName)
            }
        }
        modelLoading = false
        modelLoaded = result.loaded
        modelText = if (result.loaded) {
            UiText.Res(R.string.model_loaded, result.elapsedMs)
        } else {
            UiText.Res(R.string.model_failed)
        }
        refreshSearchStatus()
        return result.loaded
    }

    private fun refreshSearchStatus() {
        if (virtualMode || suppressStatus) return
        _uiState.update { state ->
            if (state.resultFileName != null) return@update state
            if (state.overlay == null || state.overlay.snapshot.state == DocTracker.State.SEARCH) {
                state.copy(status = UiText.Res(R.string.status_search, modelText))
            } else {
                state
            }
        }
    }

    // ------------------------------------------------------------- debug hooks

    /** Virtual camera: feed the bundled image through the live loop. */
    fun startVirtualMode() {
        if (virtualJob?.isActive == true) return
        virtualMode = true
        suppressStatus = true
        frameSource = FrameSource.VIRTUAL
        _uiState.update {
            it.copy(
                previewVisible = false,
                previewActive = false,
                resultFileName = null,
                status = UiText.Res(R.string.status_virtual_loading),
            )
        }

        virtualJob = viewModelScope.launch {
            delay(600)
            loadModelAwait()
            val bitmap = withContext(Dispatchers.IO) { engine.loadAssetBitmap("selftest.jpg") }
            if (bitmap == null) {
                _uiState.update { it.copy(status = UiText.Res(R.string.status_virtual_failed)) }
                return@launch
            }

            virtualBitmap = bitmap
            _events.send(ScanEvent.ShowDebugImage(bitmap))
            capturing.set(false)
            frozen = false
            pendingQuad = null
            acceptingFrames = true

            while (isActive && acceptingFrames) {
                val frame = withContext(Dispatchers.Default) {
                    try {
                        engine.analyzeBitmap(bitmap, 0f)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        Log.e(TAG, "virtual frame failed", t)
                        null
                    }
                }
                if (frame != null) onFrame(frame, FrameSource.VIRTUAL)
                delay(66)
            }
        }
    }

    /** Debug hook: full offline path on the bundled image. */
    fun startSelfTest() {
        viewModelScope.launch {
            delay(800)
            suppressStatus = true
            _uiState.update { it.copy(status = UiText.Res(R.string.status_selftest_running)) }

            val outcome = withContext(Dispatchers.Default) {
                try {
                    engine.runSelfTest(_uiState.value.filter)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    SelfTestResult.Failed(t.message ?: t.javaClass.simpleName)
                }
            }

            when (outcome) {
                is SelfTestResult.NoPaper -> _uiState.update {
                    it.copy(status = UiText.Res(R.string.status_selftest_no_paper))
                }

                is SelfTestResult.Failed -> _uiState.update {
                    it.copy(status = UiText.Res(R.string.status_selftest_failed, outcome.message))
                }

                is SelfTestResult.Detected -> showResult(
                    source = outcome.source,
                    result = outcome.scan,
                    keepSource = false,
                    status = UiText.Res(
                        R.string.status_selftest_ok,
                        outcome.detectionMs,
                        outcome.support,
                        outcome.areaRatio,
                        outcome.scan.file.name,
                    ),
                )
            }
        }
    }

    /** Debug hook: real CameraX capture path with a synthetic centre quad. */
    fun startForceCaptureTest() {
        viewModelScope.launch {
            delay(3500)
            suppressStatus = true
            if (capturing.get() || virtualMode) return@launch
            if (!acceptingFrames) {
                Log.w(TAG, "force capture skipped: camera is not accepting frames")
                return@launch
            }

            val w = analysisW.toFloat()
            val h = analysisH.toFloat()
            val ix = w * 0.1f
            val iy = h * 0.1f
            val quad: Quad = arrayOf(
                PointF(ix, iy),
                PointF(w - ix, iy),
                PointF(w - ix, h - iy),
                PointF(ix, h - iy),
            )
            Log.i(TAG, "force capture with synthetic quad")
            frameSource = FrameSource.CAMERA
            requestCapture(quad, FrameSource.CAMERA)
        }
    }

    // ------------------------------------------------------------------ mapping

    private fun statusFor(state: DocTracker.State): UiText = when (state) {
        DocTracker.State.SEARCH -> UiText.Res(R.string.status_search, modelText)
        DocTracker.State.TRACKING -> UiText.Res(R.string.status_tracking)
        DocTracker.State.LOCKED -> UiText.Res(R.string.status_locked)
        DocTracker.State.COUNTDOWN -> UiText.Res(R.string.status_countdown)
    }

    private fun stateText(state: DocTracker.State): UiText = when (state) {
        DocTracker.State.SEARCH -> UiText.Res(R.string.state_search)
        DocTracker.State.TRACKING -> UiText.Res(R.string.state_tracking)
        DocTracker.State.LOCKED -> UiText.Res(R.string.state_locked)
        DocTracker.State.COUNTDOWN -> UiText.Res(R.string.state_countdown)
    }

    private enum class FrameSource {
        /** 真实 CameraX 分析流；拍照要发事件给 View。 */
        CAMERA,

        /** 内置图片虚拟流；拍照直接处理 [virtualBitmap]。 */
        VIRTUAL,
    }

    companion object {
        private const val TAG = "PaperScan"

        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MainViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return MainViewModel(ScanEngine(context.applicationContext)) as T
                }
            }
    }
}
