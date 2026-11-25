package com.dsh.scanner.core

import android.graphics.PointF
import kotlin.math.hypot

/**
 * Temporal tracking + stability scoring + capture state machine.
 * Port of the Tracker in scanner/pipeline.py.
 *
 * 参数来自 [TrackerConfig]；默认值是真机调参结果，请勿随手"清理"。
 */
class DocTracker(
    /** 综合评分的过线阈值，达到后才开始累计稳定帧。 */
    private val scoreOn: Float,

    /** 连续多少帧过线才进入倒计时。 */
    private val stableFrames: Int,

    /** 倒计时时长；期间掉线会立即中止。 */
    private val countdownMs: Long,

    /** 拍完后的冷却时长，避免连续触发。 */
    private val cooldownMs: Long,

    /** 陀螺仪角速度上限（°/s），超过则判定为手抖。 */
    private val imuGate: Float,
) {
    constructor(config: TrackerConfig) : this(
        config.scoreOn,
        config.stableFrames,
        config.countdownMs,
        config.cooldownMs,
        config.imuGate,
    )

    enum class State {
        /** 当前帧没有可用纸张。 */
        SEARCH,

        /** 检测到纸张，但评分还没到拍摄阈值。 */
        TRACKING,

        /** 评分已过线，正在累计稳定帧。 */
        LOCKED,

        /** 倒计时中，归零后触发拍摄。 */
        COUNTDOWN,
    }

    /**
     * 拍摄触发条件中当前不达标的因素。
     * 按对用户行动的优先级排序（声明序即优先级），宿主取第一个展示提示。
     * 阈值与评分计算同源，评分不过线时必至少命中一项。
     */
    enum class CaptureHint {
        /** 纸张面积占比太小：请把纸张放满取景框或靠近一些。 */
        AREA_TOO_SMALL,

        /** 纸张边缘支撑率低：边缘不清晰、被遮挡或对比度不足。 */
        LOW_SUPPORT,

        /** 四边形仍在移动：请放稳。 */
        UNSTABLE,

        /** 陀螺仪角速度偏高：手在移动。 */
        MOTION,

        /** Laplacian 清晰度不足：画面模糊或未对焦。 */
        BLURRY,

        /** 几何规整度低：拍摄角度过斜或四边形不规整。 */
        OBLIQUE,
    }

    data class Snapshot(
        /** 当前平滑后的纸张四边形（tl/tr/br/bl）；连续丢帧后为 null。 */
        val quad: Quad?,

        /** EMA 平滑后的综合评分，0..1。 */
        val score: Float,

        /** 当前跟踪/拍摄状态机状态。 */
        val state: State,

        /** 倒计时进度，0..1；0 表示未在倒计时。 */
        val progress: Float,

        /** 本帧是否应触发拍摄（倒计时刚归零）。 */
        val shouldCapture: Boolean,

        /** 稳定度，1 表示完全静止，0 表示抖动很大。 */
        val stability: Float,

        /** 纸张边缘的支撑率，0..1。 */
        val support: Float,

        /** 纸张区域的 Laplacian 方差，越大越清晰。 */
        val sharpness: Float,

        /** 当前陀螺仪角速度，单位 °/s。 */
        val omega: Float,

        /** 当前不达标的拍摄条件；空集合表示全部达标（或无需提示）。 */
        val hints: Set<CaptureHint> = emptySet(),
    )

    /** 当前平滑后的四边形，供快照和手动拍照读取。 */
    private var smooth: Quad? = null

    /** 上一帧原始检测四边形，用于判断跳变。 */
    private var prev: Quad? = null

    /** 连续丢帧计数；超过 3 帧清空平滑框。 */
    private var lost = 0

    /** 四边形移动速度的 EMA（px/帧）。 */
    private var vel = 0f

    /** 综合评分的 EMA，0..1。 */
    private var scoreEma = 0f

    /** 连续过线帧数。 */
    private var stable = 0

    /** 已累计的倒计时时长（ms）。 */
    private var countdown = 0L

    /** 拍照后的剩余冷却时长（ms）。 */
    private var cooldown = 0L

    /** 最近一次有效检测的边缘支撑率，丢帧时衰减沿用。 */
    private var lastSupport = 0f

    /** 最近一次有效检测的面积占比，丢帧时沿用。 */
    private var lastArea = 0f

    /** 当前平滑四边形；没有跟踪目标时为 null。 */
    fun currentQuad(): Quad? = smooth

    fun reset() {
        smooth = null
        prev = null
        lost = 0
        vel = 0f
        scoreEma = 0f
        stable = 0
        countdown = 0L
    }

    /**
     * 吃进一帧检测结果，输出平滑后的状态机快照。
     *
     * @param det 本帧检测；null 表示丢失。
     * @param sharpness 纸张区域的 Laplacian 方差。
     * @param omegaDeg 当前陀螺仪角速度（°/s）。
     * @param dtMs 距上一帧的毫秒数，内部会夹到 1..200。
     */
    fun update(
        det: DocDetector.Detection?,
        sharpness: Float,
        omegaDeg: Float,
        dtMs: Long,
    ): Snapshot {
        val dt = dtMs.coerceIn(1L, 200L)

        if (det != null) {
            lastSupport = det.support.toFloat()
            lastArea = det.areaRatio.toFloat()
            val m = det.quad
            val s = smooth
            val jumped = s != null && prev != null &&
                quadMeanDistance(m, s) > 0.30f * quadDiagonal(s)
            if (s == null || jumped) {
                smooth = m.copyOf()
                vel = 0f
            } else {
                val alpha = if (omegaDeg > 30f) 0.5f else 0.22f
                val ns = Array(4) { i ->
                    PointF(
                        alpha * m[i].x + (1 - alpha) * s[i].x,
                        alpha * m[i].y + (1 - alpha) * s[i].y
                    )
                }
                val step = quadMeanDistance(ns, s)
                vel = 0.6f * vel + 0.4f * step
                smooth = ns
            }
            prev = m.copyOf()
            lost = 0
        } else {
            lost++
            if (lost > 3) {
                smooth = null
                prev = null
            }
        }

        val quad = smooth
        var raw = 0f
        var stability = 0f
        var support = 0f
        var hints = emptySet<CaptureHint>()

        if (quad != null) {
            support = if (det != null) lastSupport else lastSupport * 0.8f
            val areaScore = smoothstep(lastArea, 0.18f, 0.40f) *
                (1f - smoothstep(lastArea, 0.92f, 0.99f)).coerceIn(0f, 1f)
            stability = (1f - vel / 1.15f).coerceIn(0f, 1f)
            val imu = (1f - omegaDeg / 2.6f / 10f).coerceIn(0f, 1f)
            val sharp = ((sharpness - 30f) / 200f).coerceIn(0f, 1f)
            val geom = rectangularity(quad)
            raw = 0.26f * support + 0.18f * areaScore + 0.22f * stability +
                0.10f * imu + 0.16f * sharp + 0.08f * geom
            if (det == null) raw *= 0.6f
            if (lastArea < 0.15f || support < 0.35f) raw *= 0.35f
            raw = raw.coerceIn(0f, 1f)

            // 只在有效检测帧上计算不达标原因；按 CaptureHint 声明序插入，保持优先级
            if (det != null) {
                val failing = linkedSetOf<CaptureHint>()
                if (lastArea < 0.20f) failing.add(CaptureHint.AREA_TOO_SMALL)
                if (support < 0.45f) failing.add(CaptureHint.LOW_SUPPORT)
                if (stability < 0.60f) failing.add(CaptureHint.UNSTABLE)
                if (omegaDeg > imuGate * 0.5f) failing.add(CaptureHint.MOTION)
                if (sharpness < 80f) failing.add(CaptureHint.BLURRY)
                if (geom < 0.55f) failing.add(CaptureHint.OBLIQUE)
                hints = failing
            }
        }

        scoreEma = 0.45f * scoreEma + 0.55f * raw

        if (cooldown > 0) cooldown -= dt

        val gate = quad != null && det != null && scoreEma >= scoreOn &&
            omegaDeg <= imuGate && cooldown <= 0L

        if (gate) stable++ else if (scoreEma < scoreOn - 0.18f) stable = 0 else stable = (stable - 1).coerceAtLeast(0)

        countdown = if (stable >= stableFrames) {
            (countdown + dt).coerceAtMost(countdownMs)
        } else {
            (countdown - 3 * dt).coerceAtLeast(0L)
        }

        var shouldCapture = false
        if (countdown >= countdownMs && cooldown <= 0L) {
            shouldCapture = true
            countdown = 0L
            stable = 0
            cooldown = cooldownMs
        }

        val state = when {
            countdown > 0 -> State.COUNTDOWN
            stable > 0 -> State.LOCKED
            quad != null -> State.TRACKING
            else -> State.SEARCH
        }

        return Snapshot(
            quad = quad,
            score = scoreEma,
            state = state,
            progress = countdown.toFloat() / countdownMs,
            shouldCapture = shouldCapture,
            stability = stability,
            support = support,
            sharpness = sharpness,
            omega = omegaDeg,
            hints = if (countdown > 0L) emptySet() else hints,
        )
    }

    private fun smoothstep(v: Float, a: Float, b: Float): Float {
        val t = ((v - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
