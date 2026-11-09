package com.dsh.scanner.viewmodel

import android.graphics.Bitmap
import com.dsh.scanner.model.Quad

/** One-shot effects: camera actions and bitmap hand-off, not persistent state. */
sealed interface ScanEvent {
    /** The ViewModel decided to capture; the View owns the CameraX call. */
    data object CapturePhoto : ScanEvent

    /** Show the rectify animation, then the finished scan. */
    data class ShowResult(
        val source: Bitmap,
        val quad: Quad,
        val scan: Bitmap,
        val fileName: String,
    ) : ScanEvent

    /** Virtual-camera debug hook: put the synthetic frame behind the overlay. */
    data class ShowDebugImage(val bitmap: Bitmap) : ScanEvent
}
