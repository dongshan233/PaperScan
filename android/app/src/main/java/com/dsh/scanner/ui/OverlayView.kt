package com.dsh.scanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import com.dsh.scanner.core.DocTracker
import com.dsh.scanner.core.Quad
import kotlin.math.max

/**
 * 按设计图：预览上只画一条细黄线勾勒识别到的纸张四边形。
 * 不绘制调试 HUD、角点圆点或倒计时环；拍摄条件提示由状态胶囊统一展示。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** 当前识别四边形；null 表示不画。 */
    private var quad: Quad? = null

    /** 分析帧尺寸，用于映射回 View 坐标。 */
    private var imgW = 640
    private var imgH = 480

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#FCDF81")
    }

    fun update(
        snapshot: DocTracker.Snapshot,
        imageWidth: Int,
        imageHeight: Int,
        frameMs: Float,
    ) {
        quad = snapshot.quad?.copyOf()
        imgW = imageWidth.coerceAtLeast(1)
        imgH = imageHeight.coerceAtLeast(1)
        postInvalidateOnAnimation()
    }

    fun clear() {
        quad = null
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val q = quad ?: return
        if (width <= 0 || height <= 0) return

        // PreviewView 使用 fillCenter（铺满裁切）；四边形必须按同一变换映射
        val scale = max(width.toFloat() / imgW, height.toFloat() / imgH)
        val dx = (width - imgW * scale) / 2f
        val dy = (height - imgH * scale) / 2f
        val pts = Array(4) { i -> PointF(q[i].x * scale + dx, q[i].y * scale + dy) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            lineTo(pts[1].x, pts[1].y)
            lineTo(pts[2].x, pts[2].y)
            lineTo(pts[3].x, pts[3].y)
            close()
        }
        canvas.drawPath(path, stroke)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
