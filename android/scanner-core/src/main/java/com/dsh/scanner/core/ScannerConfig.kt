package com.dsh.scanner.core

import java.io.File

/**
 * Scanner 的全部可配置项。默认值均为真机调参结果，请勿随意改动，
 * 否则触发行为（何时自动拍照）会静默变化。
 */
data class ScannerConfig(
    /** 跟踪/评分/触发状态机参数。 */
    val tracker: TrackerConfig = TrackerConfig(),

    /** 经典边缘检测器参数。 */
    val detector: DetectorConfig = DetectorConfig(),

    /** DocAligner 角点模型参数。 */
    val model: ModelConfig = ModelConfig(),

    /** 扫描件输出策略；[OutputConfig.dir] 为 null 时用应用外部私有目录下的 `scans`。 */
    val output: OutputConfig = OutputConfig(),

    /** 运动数据来源；null 时使用硬件陀螺仪 [GyroscopeMotionSource]。 */
    val motionSource: MotionSource? = null,
)

/** 时序跟踪与拍摄状态机参数（对应 `DocTracker`）。 */
data class TrackerConfig(
    /** 综合评分的过线阈值，达到后才开始累计稳定帧。 */
    val scoreOn: Float = 0.72f,

    /** 连续多少帧过线才进入倒计时。 */
    val stableFrames: Int = 5,

    /** 倒计时时长（ms）；期间掉线会立即中止。 */
    val countdownMs: Long = 700L,

    /** 拍完后的冷却时长（ms），避免连续触发。 */
    val cooldownMs: Long = 1500L,

    /** 陀螺仪角速度上限（°/s），超过则判定为手抖。 */
    val imuGate: Float = 25f,
)

/** 经典边缘检测参数（对应 `DocDetector`）。 */
data class DetectorConfig(
    /** 白桌白纸场景：额外合并一组宽松 Canny 边缘，提高召回。 */
    val lowContrast: Boolean = false,

    /** 纸张面积占整帧的最小比例，低于此值不参与候选。 */
    val minAreaRatio: Double = 0.15,

    /** 候选四边形四条边的边缘支撑率下限。 */
    val minSupport: Double = 0.40,
)

/** 角点模型参数（对应 `DocModelDetector`）。 */
data class ModelConfig(
    /** 模型资产文件名（module assets 内）；也可指向宿主合并进来的资产。 */
    val assetName: String = "docaligner_fastvit_t8.onnx",

    /** ONNX 推理线程数。CPU 上 4 线程优于 NNAPI（实测 147ms vs 628ms）。 */
    val inferenceThreads: Int = 4,

    /** 模型推理的调度间隔（每 N 帧跑一次，中间帧复用缓存结果）。 */
    val runEveryNFrames: Int = 3,

    /** 缓存结果可沿用的最大帧数（无 ROI 时）。 */
    val cacheMaxFrames: Int = 6,

    /** 缓存四边形可作为模型 ROI 的最大帧龄。 */
    val roiMaxAgeFrames: Int = 9,

    /** 是否输出逐次推理的调试日志（输入统计、热力图峰值等）。 */
    val debugLogs: Boolean = false,
)

/** 扫描件输出参数（对应 `ScanCapture` 落盘行为）。 */
data class OutputConfig(
    /** JPEG 输出目录；null 时用 `getExternalFilesDir(null)/scans`。 */
    val dir: File? = null,

    /** JPEG 压缩质量（0..100）。 */
    val jpegQuality: Int = 92,
)
