package com.dsh.scanner.core

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 * 识别编排层：帧驱动状态机、拍照互锁、结果交接。
 *
 * 它从原 app 的 MainViewModel 中剥离而来，包含全部识别域决策
 * （何时接收帧、何时触发拍照、拍照失败如何回退、结果如何冻结 UI），
 * 不持有 Activity/View，也不引用宿主资源。宿主 ViewModel 只需：
 * 订阅 [state] 与 [events]、转发用户动作、执行 [ScanEvent.CapturePhoto]。
 *
 * 线程模型：[onCameraFrame] 在相机分析线程调用；状态更新线程安全；
 * [capturing] 互锁保证自动触发与手动快门不会重复拍照。
 */
class ScanCoordinator(
    /** 协程作用域；建议传宿主 ViewModel 的 viewModelScope，随其销毁自动取消。 */
    private val scope: CoroutineScope,
    /** 识别引擎（module 门面）。 */
    private val engine: Scanner,
    /** 编排参数。 */
    private val config: CoordinatorConfig = CoordinatorConfig(),
) {

    /** 编排层的可调参数。 */
    data class CoordinatorConfig(
        /** 结果页动画原图的长边上限；12 MP 全尺寸每帧处理太慢。 */
        val stillMaxSide: Int = 1400,

        /** 模型加载的自旋等待上限（ms）。 */
        val modelLoadTimeoutMs: Long = 10_000L,

        /** 模型加载自旋轮询间隔（ms）。 */
        val modelLoadPollMs: Long = 50L,

        /** 虚拟相机：启动延迟（ms）。 */
        val virtualStartupDelayMs: Long = 600L,

        /** 虚拟相机：帧间隔（ms）。 */
        val virtualFrameIntervalMs: Long = 66L,

        /** 虚拟相机：自检图资产名。 */
        val virtualAssetName: String = "selftest.jpg",

        /** 自检调试：启动延迟（ms）。 */
        val selfTestStartupDelayMs: Long = 800L,

        /** 强制拍照调试：启动延迟（ms）。 */
        val forceCaptureDelayMs: Long = 3500L,
    )

    /** 帧来源；决定拍照走相机还是直接处理内置 Bitmap。 */
    private enum class FrameSource {
        /** 真实相机分析流；拍照要发事件给宿主。 */
        CAMERA,

        /** 内置图片虚拟流；拍照直接处理 [virtualBitmap]。 */
        VIRTUAL,
    }

    /** 供宿主持续观察的识别状态。 */
    private val _state = MutableStateFlow(ScanSnapshot())
    val state: StateFlow<ScanSnapshot> = _state.asStateFlow()

    /** 一次性事件：拍照、结果显示和虚拟帧；宿主按生命周期收集。 */
    private val _events = Channel<ScanEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 防止自动触发和手动快门重复拍照。 */
    private val capturing = AtomicBoolean(false)

    /** 是否接收分析帧；拍照中、结果页和解绑后为 false。 */
    @Volatile
    private var acceptingFrames = false

    /** 已识别成功并冻结；点「重新扫描」才解除。 */
    @Volatile
    private var frozen = false

    /** 自检/虚拟模式期间为 true，避免逐帧状态覆盖自定义提示。 */
    @Volatile
    private var suppressStatus = false

    /** 当前是否运行虚拟相机调试入口。 */
    @Volatile
    private var virtualMode = false

    /** 当前帧来源；决定拍照走相机还是直接处理内置 Bitmap。 */
    @Volatile
    private var frameSource = FrameSource.CAMERA

    /** 最近一帧分析图宽度（px，已按旋转摆正），用于缩放四边形。 */
    @Volatile
    private var analysisW = 640

    /** 最近一帧分析图高度（px，已按旋转摆正）。 */
    @Volatile
    private var analysisH = 480

    /** 当前拍照使用的四边形（分析帧坐标）。 */
    @Volatile
    private var pendingQuad: Quad? = null

    /** 虚拟模式使用的内置图；同时作为 debugImage 的显示源。 */
    @Volatile
    private var virtualBitmap: Bitmap? = null

    /** 虚拟相机循环任务，重新扫描/退出时取消。 */
    private var virtualJob: Job? = null

    /** 状态栏里的模型加载状态（未加载/加载中/耗时/失败）。 */
    private var modelState: ModelState = ModelState.NotLoaded

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

    // ------------------------------------------------------------- camera gate

    /**
     * 相机分析流的快速通道开关：宿主适配器可据此在不可收帧时直接丢弃
     * ImageProxy，避免无谓的像素拷贝。
     */
    fun isAcceptingCameraFrames(): Boolean =
        acceptingFrames && !frozen && !capturing.get() &&
            frameSource == FrameSource.CAMERA

    /**
     * 相机帧入口。调用 [Scanner.analyze] 并推进状态机；不可收帧时静默丢弃。
     * 调用方负责 close 相机帧对象；本方法不回收 [ScanFrame.OfBitmap] 的位图。
     */
    fun onCameraFrame(frame: ScanFrame) {
        if (!isAcceptingCameraFrames()) return
        try {
            engine.analyze(frame)?.let { onFrame(it, FrameSource.CAMERA) }
        } catch (t: Throwable) {
            Log.e(TAG, "camera frame failed", t)
        }
    }

    // ----------------------------------------------------------- camera events

    fun onCameraPermissionGranted() {
        if (virtualMode) return
        frozen = false
        capturing.set(false)
        _state.update {
            it.copy(
                previewActive = true,
                previewVisible = true,
                status = ScanStatus.Idle,
            )
        }
    }

    fun onCameraPermissionDenied() {
        _state.update {
            it.copy(previewActive = false, status = ScanStatus.PermissionRequired)
        }
    }

    fun onOpenCvInitFailed() {
        _state.update { it.copy(status = ScanStatus.OpenCvInitFailed) }
    }

    fun onCameraBound() {
        if (virtualMode || !_state.value.previewActive) return
        frameSource = FrameSource.CAMERA
        acceptingFrames = true
        if (!suppressStatus && _state.value.resultFileName == null) {
            _state.update { it.copy(status = ScanStatus.Searching(modelState)) }
        }
        scope.launch { loadModelAwait() }
    }

    fun onCameraError(message: String) {
        acceptingFrames = false
        _state.update {
            it.copy(previewActive = false, status = ScanStatus.CameraError(message))
        }
    }

    // ------------------------------------------------------------ view actions

    fun onFilterClick() {
        _state.update { it.copy(filter = it.filter.next()) }
    }

    fun onShutterClick() {
        if (!acceptingFrames || frozen || capturing.get()) return
        val quad = engine.currentQuad() ?: return
        requestCapture(quad, frameSource)
    }

    fun onRectifyFinished() {
        if (virtualMode) return
        _state.update { it.copy(rescanVisible = true) }
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
            _state.update {
                it.copy(
                    overlay = null,
                    capturing = false,
                    resultFileName = null,
                    rescanVisible = false,
                    status = ScanStatus.Idle,
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
        _state.update {
            it.copy(
                overlay = null,
                capturing = false,
                resultFileName = null,
                rescanVisible = false,
                status = ScanStatus.Idle,
                previewActive = true,
                previewVisible = true,
            )
        }
    }

    // --------------------------------------------------------- photo callbacks

    fun onPhotoCaptured(bitmap: Bitmap) {
        scope.launch {
            if (!capturing.get()) {
                bitmap.recycle()
                return@launch
            }
            val quad = pendingQuad
            val w = analysisW
            val h = analysisH
            if (quad == null || w <= 0 || h <= 0) {
                bitmap.recycle()
                failCapture(ScanStatus.Failed(CAPTURE_FAILED))
                return@launch
            }
            val scaled = quad.scaled(bitmap.width.toFloat() / w, bitmap.height.toFloat() / h)
            processCapture(bitmap, scaled, keepSource = false, announceSaved = true)
        }
    }

    fun onPhotoCaptureError(message: String) {
        Log.e(TAG, "capture error: $message")
        scope.launch {
            failCapture(ScanStatus.Failed(CAPTURE_FAILED))
        }
    }

    // ---------------------------------------------------------- scan pipeline

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
            virtualMode -> ScanStatus.Virtual(
                state = frame.snapshot.state,
                score = frame.snapshot.score,
                countingDown = frame.snapshot.state == DocTracker.State.COUNTDOWN,
            )

            suppressStatus -> null
            else -> statusFor(frame.snapshot.state)
        }
        _state.update { current ->
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
        _state.update {
            it.copy(
                capturing = true,
                overlay = null,
                status = ScanStatus.Capturing,
            )
        }

        when (source) {
            FrameSource.CAMERA -> _events.trySend(ScanEvent.CapturePhoto)
            FrameSource.VIRTUAL -> {
                val bitmap = virtualBitmap
                if (bitmap == null) {
                    failCapture(ScanStatus.Failed(CAPTURE_FAILED))
                } else {
                    scope.launch {
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
        val filter = _state.value.filter
        _state.update { it.copy(status = ScanStatus.Processing) }

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
                ScanStatus.Saved(result.file.name)
            } else {
                ScanStatus.Done
            }
            showResult(source, result, keepSource, status)
        }.onFailure { error ->
            Log.e(TAG, "scan processing failed", error)
            if (!keepSource && !source.isRecycled) source.recycle()
            failCapture(ScanStatus.Failed(error.message ?: error.javaClass.simpleName))
        }
    }

    private fun failCapture(status: ScanStatus) {
        pendingQuad = null
        capturing.set(false)
        frozen = false
        acceptingFrames = !virtualMode && _state.value.previewActive
        _state.update { it.copy(capturing = false, status = status) }
    }

    /**
     * Ends this recognition: stop frames, unbind the preview (state drives it),
     * trim the animation source and hand both bitmaps to the host.
     */
    private fun showResult(
        source: Bitmap,
        result: ScanCapture.Result,
        keepSource: Boolean,
        status: ScanStatus,
    ) {
        acceptingFrames = false
        frozen = true
        capturing.set(false)
        pendingQuad = null
        _state.update {
            it.copy(
                previewActive = false,
                capturing = false,
                overlay = null,
                resultFileName = result.file.name,
                rescanVisible = false,
                status = status,
            )
        }

        val (prepared, stillQuad) = try {
            prepareStill(source, result.quad)
        } catch (t: Throwable) {
            Log.e(TAG, "prepare still failed", t)
            source to result.quad
        }
        // ShowResult 的位图所有权归接收方：虚拟模式下 prepared 可能就是需要
        // 保留继续使用的 virtualBitmap，此时改发一份拷贝，避免接收方回收后破坏循环。
        val still = if (keepSource && prepared === source) {
            prepared.copy(prepared.config ?: Bitmap.Config.ARGB_8888, false)
        } else {
            prepared
        }
        if (!keepSource && prepared !== source && !source.isRecycled) source.recycle()
        _events.trySend(ScanEvent.ShowResult(still, stillQuad, result.bitmap, result.file.name))
    }

    /** The animation source is capped at ~1400 px; 12 MP per frame is too slow. */
    private fun prepareStill(source: Bitmap, quad: Quad): Pair<Bitmap, Quad> {
        val maxSide = config.stillMaxSide
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
            var waited = 0L
            while (modelLoading && waited < config.modelLoadTimeoutMs) {
                delay(config.modelLoadPollMs)
                waited += config.modelLoadPollMs
            }
            return modelLoaded
        }

        modelLoading = true
        modelState = ModelState.Loading
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
        modelState = if (result.loaded) {
            ModelState.Loaded(result.elapsedMs)
        } else {
            ModelState.Failed(result.error ?: "unknown")
        }
        refreshSearchStatus()
        return result.loaded
    }

    private fun refreshSearchStatus() {
        if (virtualMode || suppressStatus) return
        _state.update { state ->
            if (state.resultFileName != null) return@update state
            if (state.overlay == null || state.overlay.snapshot.state == DocTracker.State.SEARCH) {
                state.copy(status = ScanStatus.Searching(modelState))
            } else {
                state
            }
        }
    }

    private fun statusFor(state: DocTracker.State): ScanStatus = when (state) {
        DocTracker.State.SEARCH -> ScanStatus.Searching(modelState)
        DocTracker.State.TRACKING -> ScanStatus.Tracking
        DocTracker.State.LOCKED -> ScanStatus.Locked
        DocTracker.State.COUNTDOWN -> ScanStatus.Countdown
    }

    // ------------------------------------------------------------- debug hooks

    /** Virtual camera: feed the bundled image through the live loop. */
    fun startVirtualMode() {
        if (virtualJob?.isActive == true) return
        virtualMode = true
        suppressStatus = true
        frameSource = FrameSource.VIRTUAL
        _state.update {
            it.copy(
                previewVisible = false,
                previewActive = false,
                resultFileName = null,
                status = ScanStatus.VirtualLoading,
            )
        }

        virtualJob = scope.launch {
            delay(config.virtualStartupDelayMs)
            loadModelAwait()
            val bitmap = withContext(Dispatchers.IO) {
                engine.loadAssetBitmap(config.virtualAssetName)
            }
            if (bitmap == null) {
                _state.update { it.copy(status = ScanStatus.VirtualFailed) }
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
                delay(config.virtualFrameIntervalMs)
            }
        }
    }

    /** Debug hook: full offline path on a bundled asset. */
    fun startSelfTest(assetName: String = config.virtualAssetName) {
        scope.launch {
            delay(config.selfTestStartupDelayMs)
            suppressStatus = true
            _state.update { it.copy(status = ScanStatus.SelfTestRunning) }

            val filter = _state.value.filter
            val outcome = withContext(Dispatchers.Default) {
                try {
                    engine.runSelfTest(filter, assetName)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    SelfTestResult.Failed(t.message ?: t.javaClass.simpleName)
                }
            }

            when (outcome) {
                is SelfTestResult.NoPaper -> _state.update {
                    it.copy(status = ScanStatus.SelfTestNoPaper)
                }

                is SelfTestResult.Failed -> _state.update {
                    it.copy(status = ScanStatus.SelfTestFailed(outcome.message))
                }

                is SelfTestResult.Detected -> showResult(
                    source = outcome.source,
                    result = outcome.scan,
                    keepSource = false,
                    status = ScanStatus.SelfTestOk(
                        detectionMs = outcome.detectionMs,
                        support = outcome.support,
                        areaRatio = outcome.areaRatio,
                        fileName = outcome.scan.file.name,
                    ),
                )
            }
        }
    }

    /** Debug hook: real camera capture path with a synthetic centre quad. */
    fun startForceCaptureTest() {
        scope.launch {
            delay(config.forceCaptureDelayMs)
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

    private companion object {
        const val TAG = "PaperScan"
        const val CAPTURE_FAILED = "拍照失败"
    }
}
