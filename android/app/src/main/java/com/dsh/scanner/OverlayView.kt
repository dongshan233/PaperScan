package com.dsh.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Draws the detected page outline plus the capture HUD over the preview. */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var quad: Quad? = null
    private var imgW = 480
    private var imgH = 360
    private var state = DocTracker.State.SEARCH
    private var score = 0f
    private var progress = 0f
    private var support = 0f
    private var stability = 0f
    private var sharpness = 0f
    private var frameMs = 0f

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(15f)
        color = Color.WHITE
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12f)
        color = Color.parseColor("#D0D0D6")
    }

    fun update(
        snapshot: DocTracker.Snapshot,
        imageWidth: Int,
        imageHeight: Int,
        ms: Float,
    ) {
        quad = snapshot.quad?.copyOf()
        imgW = imageWidth
        imgH = imageHeight
        state = snapshot.state
        score = snapshot.score
        progress = snapshot.progress
        support = snapshot.support
        stability = snapshot.stability
        sharpness = snapshot.sharpness
        frameMs = ms
        postInvalidateOnAnimation()
    }

    fun clear() {
        quad = null
        state = DocTracker.State.SEARCH
        progress = 0f
        postInvalidateOnAnimation()
    }

    private fun colorForState(): Int = when (state) {
        DocTracker.State.SEARCH -> Color.parseColor("#FFB74D")
        DocTracker.State.TRACKING -> Color.parseColor("#FFD54F")
        DocTracker.State.LOCKED -> Color.parseColor("#66BB6A")
        DocTracker.State.COUNTDOWN -> Color.parseColor("#4CAF50")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // match PreviewView fitCenter: whole frame visible, letterboxed
        val scale = min(width.toFloat() / imgW, height.toFloat() / imgH)
        val dx = (width - imgW * scale) / 2f
        val dy = (height - imgH * scale) / 2f

        quad?.let { q ->
            val pts = Array(4) { i -> PointF(q[i].x * scale + dx, q[i].y * scale + dy) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                lineTo(pts[1].x, pts[1].y)
                lineTo(pts[2].x, pts[2].y)
                lineTo(pts[3].x, pts[3].y)
                close()
            }
            stroke.color = colorForState()
            canvas.drawPath(path, stroke)
            fill.color = colorForState()
            for (p in pts) canvas.drawCircle(p.x, p.y, dp(4f), fill)
        }

        // HUD panel
        val pw = dp(190f)
        val ph = dp(112f)
        val left = dp(16f)
        val top = dp(16f)
        fill.color = Color.parseColor("#99000000")
        canvas.drawRoundRect(RectF(left, top, left + pw, top + ph), dp(10f), dp(10f), fill)

        val tx = left + dp(12f)
        var ty = top + dp(24f)
        text.color = colorForState()
        canvas.drawText(stateName(), tx, ty, text)
        ty += dp(22f)
        small.color = Color.WHITE
        canvas.drawText("综合分 ${"%.2f".format(score)}", tx, ty, small)
        ty += dp(18f)
        small.color = Color.parseColor("#D0D0D6")
        canvas.drawText("边缘支撑 ${"%.2f".format(support)}", tx, ty, small)
        ty += dp(16f)
        canvas.drawText("抖动 ${"%.2f".format(1f - stability)}", tx, ty, small)
        ty += dp(16f)
        canvas.drawText("清晰度 ${sharpness.toInt()}", tx, ty, small)
        ty += dp(16f)
        canvas.drawText("${frameMs.toInt()} ms/帧", tx, ty, small)

        // score bar
        val bx = tx + dp(96f)
        val by = top + dp(34f)
        val bw = dp(70f)
        fill.color = Color.parseColor("#555555")
        canvas.drawRect(bx, by, bx + bw, by + dp(7f), fill)
        fill.color = colorForState()
        canvas.drawRect(bx, by, bx + bw * score.coerceIn(0f, 1f), by + dp(7f), fill)

        // countdown ring
        if (progress > 0f) {
            val cx = width - dp(64f)
            val cy = dp(64f)
            val r = dp(28f)
            stroke.color = Color.parseColor("#44FFFFFF")
            stroke.strokeWidth = dp(5f)
            canvas.drawCircle(cx, cy, r, stroke)
            stroke.color = colorForState()
            canvas.drawArc(
                RectF(cx - r, cy - r, cx + r, cy + r),
                -90f, 360f * progress, false, stroke
            )
            text.color = colorForState()
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("拍", cx, cy + dp(8f), text)
            text.textAlign = Paint.Align.LEFT
            stroke.strokeWidth = dp(3f)
        }

        // hint
        val hint = when (state) {
            DocTracker.State.SEARCH -> "把试卷完整放入取景框"
            DocTracker.State.TRACKING -> "识别中，请放稳"
            DocTracker.State.LOCKED -> "已锁定，保持不动"
            DocTracker.State.COUNTDOWN -> "保持不动，正在拍摄…"
        }
        small.color = Color.WHITE
        small.textSize = dp(16f)
        val tw = small.measureText(hint)
        canvas.drawText(hint, (width - tw) / 2f, height - dp(96f), small)
        small.textSize = dp(12f)
    }

    private fun stateName(): String = when (state) {
        DocTracker.State.SEARCH -> "搜索中"
        DocTracker.State.TRACKING -> "跟踪中"
        DocTracker.State.LOCKED -> "已锁定"
        DocTracker.State.COUNTDOWN -> "倒计时"
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
