package com.dsh.scanner.viewmodel

import androidx.annotation.StringRes
import com.dsh.scanner.R
import com.dsh.scanner.model.DocTracker
import com.dsh.scanner.model.ScanCapture

/** Everything the scanner screen renders. Bitmaps travel through [ScanEvent]. */
data class ScanUiState(
    val status: UiText = UiText.Res(R.string.status_idle),
    val filter: ScanCapture.Filter = ScanCapture.Filter.ENHANCE,
    val torchOn: Boolean = false,
    val overlay: OverlayFrame? = null,
    val previewVisible: Boolean = true,
    val previewActive: Boolean = false,
    val capturing: Boolean = false,
    val resultFileName: String? = null,
    val rescanVisible: Boolean = false,
)

/** One tracker snapshot plus the analysis-frame geometry it was measured in. */
data class OverlayFrame(
    val snapshot: DocTracker.Snapshot,
    val imageWidth: Int,
    val imageHeight: Int,
    val frameMs: Float,
)

@StringRes
fun ScanCapture.Filter.labelRes(): Int = when (this) {
    ScanCapture.Filter.COLOR -> R.string.filter_color
    ScanCapture.Filter.ENHANCE -> R.string.filter_enhance
    ScanCapture.Filter.GRAY -> R.string.filter_gray
}
