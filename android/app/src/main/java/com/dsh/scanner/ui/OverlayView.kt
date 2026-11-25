package com.dsh.scanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.dsh.scanner.R
import com.dsh.scanner.core.DocTracker
import com.dsh.scanner.core.Quad
import kotlin.math.min

/** Draws the detected page outline plus the capture HUD over the preview. */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** 当前要绘制的四边形；null 表示不画检测框。 */
    private var quad: Quad? = null

    /** 当前分析帧宽度（px），用于映射到 View 坐标。 */
    private var imgW = 480

    /** 当前分析帧高度（px）。 */
    private var imgH = 360

    /** 跟踪状态，决定颜色、状态名和提示语。 */
    private var state = DocTracker.State.SEARCH

    /** 综合评分，0..1。 */
    private var score = 0f

    /** 倒计时进度，0..1；大于 0 时画倒计时环。 */
    private var progress = 0f

    /** 边缘支撑率，0..1。 */
    private var support = 0f

    /** 稳定度，1 表示完全静止。 */
    private var stability = 0f

    /** Laplacian 清晰度。 */
    private var sharpness = 0f

    /** 检测耗时的 EMA（含模型兜底）。 */
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
        canvas.drawText(context.getString(R.string.hud_score, score), tx, ty, small)
        ty += dp(18f)
        small.color = Color.parseColor("#D0D0D6")
        canvas.drawText(context.getString(R.string.hud_support, support), tx, ty, small)
        ty += dp(16f)
        canvas.drawText(context.getString(R.string.hud_shake, 1f - stability), tx, ty, small)
        ty += dp(16f)
        canvas.drawText(context.getString(R.string.hud_sharpness, sharpness.toInt()), tx, ty, small)
        ty += dp(16f)
        canvas.drawText(context.getString(R.string.hud_frame_ms, frameMs.toInt()), tx, ty, small)

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
            canvas.drawText(context.getString(R.string.hud_capture), cx, cy + dp(8f), text)
            text.textAlign = Paint.Align.LEFT
            stroke.strokeWidth = dp(3f)
        }

        // hint
        val hint = context.getString(hintRes())
        small.color = Color.WHITE
        small.textSize = dp(16f)
        val tw = small.measureText(hint)
        canvas.drawText(hint, (width - tw) / 2f, height - dp(96f), small)
        small.textSize = dp(12f)
    }

    private fun stateName(): String = context.getString(
        when (state) {
            DocTracker.State.SEARCH -> R.string.state_search
            DocTracker.State.TRACKING -> R.string.state_tracking
            DocTracker.State.LOCKED -> R.string.state_locked
            DocTracker.State.COUNTDOWN -> R.string.state_countdown
        },
    )

    private fun hintRes(): Int = when (state) {
        DocTracker.State.SEARCH -> R.string.hud_hint_search
        DocTracker.State.TRACKING -> R.string.hud_hint_tracking
        DocTracker.State.LOCKED -> R.string.hud_hint_locked
        DocTracker.State.COUNTDOWN -> R.string.hud_hint_countdown
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
