package com.dsh.scanner.model

import android.graphics.Bitmap

/** Result of one analysed frame; everything the overlay needs, already measured. */
data class FrameResult(
    val snapshot: DocTracker.Snapshot,
    val imageWidth: Int,
    val imageHeight: Int,
    val frameMs: Float,
)

/** Outcome of loading the optional DocAligner corner model. */
data class ModelLoadResult(
    val loaded: Boolean,
    val elapsedMs: Long,
    val error: String? = null,
)

/** Outcome of the bundled-image self test. */
sealed interface SelfTestResult {
    data class Detected(
        val source: Bitmap,
        val quad: Quad,
        val scan: ScanCapture.Result,
        val detectionMs: Long,
        val support: Float,
        val areaRatio: Float,
    ) : SelfTestResult

    data class NoPaper(val detectionMs: Long) : SelfTestResult

    data class Failed(val message: String) : SelfTestResult
}
