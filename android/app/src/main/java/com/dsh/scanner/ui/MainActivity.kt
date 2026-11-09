package com.dsh.scanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dsh.scanner.R
import com.dsh.scanner.databinding.ActivityMainBinding
import com.dsh.scanner.viewmodel.MainViewModel
import com.dsh.scanner.viewmodel.ScanEvent
import com.dsh.scanner.viewmodel.ScanUiState
import com.dsh.scanner.viewmodel.labelRes
import com.dsh.scanner.viewmodel.resolve
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

/**
 * MVVM View: inflates the layout, owns CameraX and renders [ScanUiState].
 * Every recognition/capture decision is delegated to [MainViewModel].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var cameraController: CameraController
    private var lastResultName: String? = null

    private val cameraListener = object : CameraController.Listener {
        override fun onCameraBound() = viewModel.onCameraBound()

        override fun onCameraError(message: String) = viewModel.onCameraError(message)

        override fun onPhotoReady(bitmap: Bitmap) = viewModel.onPhotoCaptured(bitmap)

        override fun onPhotoError(message: String) = viewModel.onPhotoCaptureError(message)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.onCameraPermissionGranted() else viewModel.onCameraPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val openCvReady = OpenCVLoader.initLocal()

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

        binding.shutterButton.setOnClickListener { viewModel.onShutterClick() }
        binding.filterButton.setOnClickListener { viewModel.onFilterClick() }
        binding.torchButton.setOnClickListener { viewModel.onTorchClick() }
        binding.rescanButton.setOnClickListener { viewModel.onRescan() }
        binding.rectifyView.onFinished = { viewModel.onRectifyFinished() }

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
            }
        }
    }

    private fun render(state: ScanUiState) {
        binding.statusText.text = state.status.resolve(this)
        binding.filterButton.text = getString(state.filter.labelRes())
        binding.torchButton.text = getString(if (state.torchOn) R.string.torch_on else R.string.torch)

        binding.previewView.visibility = if (state.previewVisible) View.VISIBLE else View.INVISIBLE
        if (state.previewVisible) binding.debugImage.visibility = View.GONE

        val overlay = state.overlay
        if (overlay != null) {
            binding.overlay.update(overlay.snapshot, overlay.imageWidth, overlay.imageHeight, overlay.frameMs)
        } else {
            binding.overlay.clear()
        }

        val resultName = state.resultFileName
        if (resultName != null) {
            binding.resultCaption.text = getString(R.string.result_caption, resultName)
            binding.resultCaption.visibility = View.VISIBLE
        } else {
            binding.resultCaption.visibility = View.GONE
        }

        if (state.rescanVisible) {
            if (binding.rescanButton.visibility != View.VISIBLE) {
                binding.rescanButton.visibility = View.VISIBLE
                binding.rescanButton.alpha = 0f
                binding.rescanButton.animate().alpha(1f).setDuration(220L).start()
            }
        } else {
            binding.rescanButton.animate().cancel()
            binding.rescanButton.visibility = View.GONE
        }

        if (resultName == null && lastResultName != null) {
            binding.rectifyView.stop()
            binding.rectifyView.visibility = View.GONE
        }
        lastResultName = resultName

        syncCamera(state)
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

            is ScanEvent.ShowResult -> {
                binding.rectifyView.play(event.source, event.quad, event.scan)
                binding.resultCaption.text = getString(R.string.result_caption, event.fileName)
                binding.resultCaption.visibility = View.VISIBLE
                binding.rescanButton.visibility = View.GONE
            }

            is ScanEvent.ShowDebugImage -> {
                binding.debugImage.setImageBitmap(event.bitmap)
                binding.debugImage.visibility = View.VISIBLE
            }
        }
    }

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
