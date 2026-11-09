package com.dsh.scanner

import android.graphics.PointF
import kotlin.math.hypot

/**
 * Temporal tracking + stability scoring + capture state machine.
 * Port of the Tracker in scanner/pipeline.py.
 */
class DocTracker(
    private val scoreOn: Float = 0.72f,
    private val stableFrames: Int = 5,
    private val countdownMs: Long = 700L,
    private val cooldownMs: Long = 1500L,
    private val imuGate: Float = 25f,
) {
    enum class State { SEARCH, TRACKING, LOCKED, COUNTDOWN }

    data class Snapshot(
        val quad: Quad?,
        val score: Float,
        val state: State,
        val progress: Float,
        val shouldCapture: Boolean,
        val stability: Float,
        val support: Float,
        val sharpness: Float,
        val omega: Float,
    )

    private var smooth: Quad? = null
    private var prev: Quad? = null
    private var lost = 0
    private var vel = 0f
    private var scoreEma = 0f
    private var stable = 0
    private var countdown = 0L
    private var cooldown = 0L
    private var lastSupport = 0f
    private var lastArea = 0f

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
        )
    }

    private fun smoothstep(v: Float, a: Float, b: Float): Float {
        val t = ((v - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
