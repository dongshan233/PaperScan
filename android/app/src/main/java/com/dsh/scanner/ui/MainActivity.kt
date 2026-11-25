package com.dsh.scanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dsh.scanner.R
import com.dsh.scanner.core.DocTracker
import com.dsh.scanner.core.ScanEvent
import com.dsh.scanner.core.ScannerCore
import com.dsh.scanner.databinding.ActivityMainBinding
import com.dsh.scanner.viewmodel.MainViewModel
import com.dsh.scanner.viewmodel.ScanUiState
import com.dsh.scanner.viewmodel.labelRes
import com.dsh.scanner.viewmodel.resolve
import kotlinx.coroutines.launch

/**
 * MVVM View: inflates the layout, owns CameraX and renders [ScanUiState].
 * Every recognition/capture decision is delegated to [MainViewModel].
 *
 * 拍摄页（参考图样式）：识别成功的结果以缩略图形式加到底部区域，
 * 最多 [MainViewModel.maxCaptures] 张，达到上限后限制继续添加。
 */
class MainActivity : AppCompatActivity() {

    /** 当前 Activity 的 ViewBinding。 */
    private lateinit var binding: ActivityMainBinding

    /** 扫描状态机；配置变化时由 ViewModelStore 保留。 */
    private lateinit var viewModel: MainViewModel

    /** CameraX 绑定/拍照适配器。 */
    private lateinit var cameraController: CameraController

    /** CameraX 回调无条件转发给 ViewModel。 */
    private val cameraListener = object : CameraController.Listener {
        override fun onCameraBound() = viewModel.onCameraBound()

        override fun onCameraError(message: String) = viewModel.onCameraError(message)

        override fun onPhotoReady(bitmap: Bitmap) = viewModel.onPhotoCaptured(bitmap)

        override fun onPhotoError(message: String) = viewModel.onPhotoCaptureError(message)
    }

    /** 相机权限结果回调；授权后才允许绑定相机。 */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.onCameraPermissionGranted() else viewModel.onCameraPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // OpenCV 已在 ScannerApplication.onCreate 中通过 ScannerCore.init() 初始化；
        // 这里再调一次是幂等的兜底（例如宿主漏注册 Application 时）。
        val openCvReady = ScannerCore.init()

        viewModel = ViewModelProvider(
            this,
            MainViewModel.factory(applicationContext),
        )[MainViewModel::class.java]

        cameraController = CameraController(
            appContext = applicationContext,
            lifecycleOwner = this,
            previewView = binding.previewView,
            analyzer = viewModel.imageAnalyzer,
            listener = cameraListener,
        )

        binding.shutterButton.setOnClickListener {
            if (viewModel.isLimitReached()) {
                // 拍满两张后按钮变为「开始解析」；解析流程待业务接入
                Toast.makeText(this, R.string.start_analysis, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.onShutterClick()
            }
        }
        binding.addTile.setOnClickListener { viewModel.onAddPageClick() }
        binding.backHomeButton.setOnClickListener { finish() }

        // 扶正动画背景与页面外围配色一致；动画结束后定格为静态截取图
        binding.rectifyView.bgColor = ContextCompat.getColor(this, R.color.scan_outer_bg)
        binding.rectifyView.onFinished = { showReviewImage() }

        // 占位图标按钮：具体能力待接入（图标后续由设计稿替换）
        binding.flipCameraButton.setOnClickListener {
            Toast.makeText(this, R.string.icon_placeholder, Toast.LENGTH_SHORT).show()
        }
        binding.galleryButton.setOnClickListener {
            Toast.makeText(this, R.string.icon_placeholder, Toast.LENGTH_SHORT).show()
        }

        observeState()

        if (!openCvReady) {
            Log.e(TAG, "OpenCV init failed")
            viewModel.onOpenCvInitFailed()
        }

        if (intent.getBooleanExtra(EXTRA_VIRTUAL, false)) {
            viewModel.startVirtualMode()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.onCameraPermissionGranted()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        if (intent.getBooleanExtra(EXTRA_SELFTEST, false)) viewModel.startSelfTest()
        if (intent.getBooleanExtra(EXTRA_FORCECAPTURE, false)) viewModel.startForceCaptureTest()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.events.collect(::handleEvent) }
                launch { viewModel.capturedScans.collect(::renderCaptured) }
                launch { viewModel.reviewScan.collect(::renderReview) }
            }
        }
    }

    /** 正在播放/已播放的查看数据，避免重复触发动画。 */
    private var playingReview: MainViewModel.ReviewData? = null

    /**
     * 「查看结果」状态渲染：进入时在预览区播放
     * "识别方框 → 扶正（缩放/旋转）→ 截取图"的过渡动画；
     * null 时停止动画、恢复相机预览显示。
     */
    private fun renderReview(review: MainViewModel.ReviewData?) {
        if (review === playingReview) return
        playingReview = review
        if (review == null) {
            binding.rectifyView.stop()
            binding.rectifyView.visibility = View.GONE
            binding.resultImage.visibility = View.GONE
            return
        }
        binding.overlay.clear()
        binding.resultImage.visibility = View.GONE
        binding.rectifyView.play(review.source, review.quad, review.scan)
    }

    /** 动画结束回调：收起动画层，定格为静态截取图。 */
    private fun showReviewImage() {
        val review = viewModel.reviewScan.value ?: return
        binding.rectifyView.visibility = View.GONE
        binding.resultImage.setImageBitmap(review.scan)
        binding.resultImage.visibility = View.VISIBLE
    }

    private fun render(state: ScanUiState) {
        val overlay = state.overlay
        val reason = overlay?.snapshot?.hints?.firstOrNull()
        binding.statusText.text = when {
            reason != null -> getString(reason.labelRes())
            viewModel.reviewScan.value != null -> {
                if (viewModel.isLimitReached()) {
                    getString(R.string.review_complete, viewModel.capturedScans.value.size, viewModel.maxCaptures)
                } else {
                    state.status.resolve(this)
                }
            }
            overlay?.snapshot?.state == DocTracker.State.SEARCH -> getString(R.string.status_idle)
            else -> state.status.resolve(this)
        }

        binding.previewView.visibility = if (state.previewVisible) View.VISIBLE else View.INVISIBLE
        if (state.previewVisible) binding.debugImage.visibility = View.GONE

        if (overlay != null) {
            binding.overlay.update(overlay.snapshot, overlay.imageWidth, overlay.imageHeight, overlay.frameMs)
        } else {
            binding.overlay.clear()
        }

        // 快门：拍摄中、查看截取图时置灰；拍满两张后变为「开始解析」（不再置灰）
        val limitReached = viewModel.isLimitReached()
        binding.shutterIcon.visibility = if (limitReached) View.GONE else View.VISIBLE
        binding.shutterLabel.visibility = if (limitReached) View.VISIBLE else View.GONE
        val shutterEnabled = !state.capturing && viewModel.reviewScan.value == null
        binding.shutterButton.isEnabled = shutterEnabled
        binding.shutterButton.alpha = if (shutterEnabled) 1f else 0.45f

        syncCamera(state)
    }

    /** 拍摄条件不达标原因 → 状态胶囊文案。 */
    private fun DocTracker.CaptureHint.labelRes(): Int = when (this) {
        DocTracker.CaptureHint.AREA_TOO_SMALL -> R.string.hint_area_small
        DocTracker.CaptureHint.LOW_SUPPORT -> R.string.hint_support_low
        DocTracker.CaptureHint.UNSTABLE -> R.string.hint_unstable
        DocTracker.CaptureHint.MOTION -> R.string.hint_motion
        DocTracker.CaptureHint.BLURRY -> R.string.hint_blurry
        DocTracker.CaptureHint.OBLIQUE -> R.string.hint_oblique
    }

    private fun renderCaptured(scans: List<MainViewModel.CapturedScan>) {
        binding.countBadge.text = scans.size.toString()

        binding.thumbList.removeAllViews()
        val selectedBorder = ContextCompat.getDrawable(this, R.drawable.bg_thumb_selected)
        val badgeBg = ContextCompat.getDrawable(this, R.drawable.bg_badge)
        val deleteBadgeBg = ContextCompat.getDrawable(this, R.drawable.bg_delete_badge)
        scans.forEachIndexed { index, scan ->
            // 缩略图容器：黄框图 + 左上角序号 + 右上角删除
            val item = FrameLayout(this)
            item.layoutParams = LinearLayout.LayoutParams(dp(120), dp(68)).apply {
                marginEnd = dp(16)
            }

            val thumb = ImageView(this)
            thumb.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            thumb.setImageBitmap(scan.thumbnail)
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.clipToOutline = true
            thumb.background = selectedBorder
            item.addView(thumb)

            // 设计图的黄色序号角标（左上）
            val number = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(18), dp(18)).apply {
                    gravity = Gravity.TOP or Gravity.START
                    marginStart = dp(2)
                    topMargin = dp(2)
                }
                background = badgeBg
                gravity = Gravity.CENTER
                text = (index + 1).toString()
                textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.scan_text_dark))
            }
            item.addView(number)

            val delete = FrameLayout(this)
            delete.layoutParams = FrameLayout.LayoutParams(dp(18), dp(18)).apply {
                gravity = Gravity.TOP or Gravity.END
                marginEnd = dp(2)
                topMargin = dp(2)
            }
            delete.background = deleteBadgeBg
            delete.contentDescription = getString(R.string.delete_scan)
            val closeIcon = ImageView(this)
            closeIcon.layoutParams = FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER)
            closeIcon.setImageResource(R.drawable.ic_ph_close)
            delete.addView(closeIcon)
            delete.setOnClickListener { viewModel.onDeleteScan(index) }
            item.addView(delete)

            binding.thumbList.addView(item)
        }

        // 未达上限显示「再拍一页」，达到上限后隐藏并锁定快门
        binding.addTile.visibility =
            if (scans.size < viewModel.maxCaptures) View.VISIBLE else View.GONE
    }

    private fun syncCamera(state: ScanUiState) {
        if (state.previewActive) {
            cameraController.bind()
            cameraController.setTorch(state.torchOn)
            cameraController.setAnalyzing(!state.capturing)
        } else {
            cameraController.unbind()
        }
    }

    private fun handleEvent(event: ScanEvent) {
        when (event) {
            ScanEvent.CapturePhoto -> cameraController.takePhoto()

            // 拍摄结果进底部缩略图区；不再播放全屏扶正动画
            is ScanEvent.ShowResult -> viewModel.onScanResult(event)

            is ScanEvent.ShowDebugImage -> {
                binding.debugImage.setImageBitmap(event.bitmap)
                binding.debugImage.visibility = View.VISIBLE
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onPause() {
        viewModel.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        cameraController.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PaperScan"
        const val EXTRA_VIRTUAL = "virtual"
        const val EXTRA_SELFTEST = "selftest"
        const val EXTRA_FORCECAPTURE = "forcecapture"
    }
}
