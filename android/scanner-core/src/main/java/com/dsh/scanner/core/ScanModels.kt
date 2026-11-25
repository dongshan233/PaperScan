package com.dsh.scanner.core

import android.graphics.Bitmap

/** 一帧分析结果；Overlay 需要的测量值都在这里。 */
data class FrameResult(
    /** 平滑后的四边形、评分、状态机和清晰度等快照。 */
    val snapshot: DocTracker.Snapshot,

    /** 分析帧宽度（已应用旋转，单位 px）。 */
    val imageWidth: Int,

    /** 分析帧高度（已应用旋转，单位 px）。 */
    val imageHeight: Int,

    /** 检测耗时的 EMA（含模型兜底），用于 HUD 的 ms/帧。 */
    val frameMs: Float,
)

/** DocAligner 角点模型的加载结果。 */
data class ModelLoadResult(
    /** 是否加载成功；失败时仍可走经典边缘检测。 */
    val loaded: Boolean,

    /** 加载耗时，包含读取 assets 和创建 ORT session。 */
    val elapsedMs: Long,

    /** 失败原因；成功时为 null。 */
    val error: String? = null,
)

/** 内置图片自检的结果。 */
sealed interface SelfTestResult {
    /** 检出纸张并完成矫正、保存。 */
    data class Detected(
        /** 内置原图；所有权交给调用方，结果页会缩放或回收。 */
        val source: Bitmap,

        /** 检测到的纸张四边形（tl/tr/br/bl，原图坐标）。 */
        val quad: Quad,

        /** 已矫正、去阴影并保存的扫描件。 */
        val scan: ScanCapture.Result,

        /** 检测+精修耗时，单位 ms。 */
        val detectionMs: Long,

        /** 最佳候选的边缘支撑率，0..1。 */
        val support: Float,

        /** 纸张面积占整帧的比例，0..1。 */
        val areaRatio: Float,
    ) : SelfTestResult

    /** 自检图里没有检出纸张。 */
    data class NoPaper(
        /** 检测耗时，单位 ms。 */
        val detectionMs: Long,
    ) : SelfTestResult

    /** 自检过程抛异常。 */
    data class Failed(
        /** 异常信息。 */
        val message: String,
    ) : SelfTestResult
}
