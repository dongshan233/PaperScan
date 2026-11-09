package com.dsh.scanner

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * 识别成功后的结果展示：把原始照片里那块斜着的纸，按照检测到的四边形
 * 逐渐拉直成 A4 正视图，最后交叉淡入增强后的扫描件。
 *
 * 动画分两段：
 *  - 0.08 → 0.70：四个角点从检测位置线性插值到目标矩形，期间用
 *    [Matrix.setPolyToPoly] 做透视映射，只画四边形内部，看起来就是纸被"扶正"；
 *  - 0.70 → 1.00：直接叠化到 [ScanCapture] 输出的去阴影扫描件。
 */
class RectifyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** 动画结束回调（主线程）。 */
    var onFinished: (() -> Unit)? = null

    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val resultPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#7CE38B")
    }

    private var source: Bitmap? = null
    private var result: Bitmap? = null

    /** 纸的四个角，源图像素坐标。 */
    private val quadPx = FloatArray(8)

    /** 源图四边形 / 目标矩形，均已换算到 View 坐标。 */
    private val fromPts = FloatArray(8)
    private val toPts = FloatArray(8)
    private val curPts = FloatArray(8)

    private val srcRect = RectF()
    private val dstRect = RectF()
    private val matrix = Matrix()
    private val pagePath = Path()
    private val ease = DecelerateInterpolator(1.9f)

    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var pendingStart = false
    private var cancelled = false

    /**
     * @param source 拍照得到的原图（含透视畸变）
     * @param quad   纸在 [source] 像素坐标下的四边形，顺序 tl/tr/br/bl
     * @param result 已经矫正、去阴影的扫描件
     */
    fun play(source: Bitmap, quad: Quad, result: Bitmap) {
        cancelAnimation()
        releaseBitmaps()
        this.source = source
        this.result = result
        for (i in 0 until 4) {
            quadPx[i * 2] = quad[i].x
            quadPx[i * 2 + 1] = quad[i].y
        }
        progress = 0f
        cancelled = false
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(160L).start()
        if (width > 0 && height > 0) startAnimation() else pendingStart = true
    }

    /** 收起结果页，停止动画并释放位图。 */
    fun stop() {
        cancelAnimation()
        releaseBitmaps()
        pagePath.reset()
        progress = 0f
        invalidate()
    }

    private fun releaseBitmaps() {
        val s = source
        val r = result
        source = null
        result = null
        if (s != null && !s.isRecycled) s.recycle()
        if (r != null && r !== s && !r.isRecycled) r.recycle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pendingStart && w > 0 && h > 0) startAnimation()
    }

    private fun startAnimation() {
        pendingStart = false
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = ANIM_MS
        a.addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                progress = 1f
                invalidate()
                onFinished?.invoke()
            }
        })
        animator = a
        a.start()
    }

    private fun cancelAnimation() {
        cancelled = true
        animator?.cancel()
        animator = null
        pendingStart = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val src = source ?: return
        val res = result ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(Color.BLACK)
        layoutFor(w, h, src, res)

        val warp = ease.getInterpolation(((progress - 0.08f) / 0.62f).coerceIn(0f, 1f))
        val fade = smooth(((progress - 0.70f) / 0.30f).coerceIn(0f, 1f))
        for (i in 0 until 8) curPts[i] = fromPts[i] + (toPts[i] - fromPts[i]) * warp

        // 背景原图：纸被扶正的过程中逐渐压暗，让视线集中到纸上
        photoPaint.alpha = ((1f - fade) * (1f - 0.8f * warp) * 255f * 0.7f).toInt().coerceIn(0, 255)
        canvas.drawBitmap(src, null, srcRect, photoPaint)

        // 纸本身：按当前插值四边形做透视映射，裁掉四边形以外的部分
        pagePath.reset()
        pagePath.moveTo(curPts[0], curPts[1])
        pagePath.lineTo(curPts[2], curPts[3])
        pagePath.lineTo(curPts[4], curPts[5])
        pagePath.lineTo(curPts[6], curPts[7])
        pagePath.close()

        var mapped = true
        try {
            matrix.setPolyToPoly(quadPx, 0, curPts, 0, 4)
        } catch (t: Throwable) {
            mapped = false
            matrix.reset()
        }
        canvas.save()
        canvas.clipPath(pagePath)
        if (mapped) {
            canvas.drawBitmap(src, matrix, pagePaint)
        } else {
            canvas.drawBitmap(src, null, dstRect, pagePaint)
        }
        canvas.restore()

        // 纸边高光，模拟"框住这张纸"的视觉锚点
        edgePaint.alpha = ((1f - fade) * 255f).toInt().coerceIn(0, 255)
        canvas.drawPath(pagePath, edgePaint)

        // 叠化到去阴影、摆正的扫描件
        if (fade > 0f) {
            resultPaint.alpha = (fade * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(res, null, dstRect, resultPaint)
        }
    }

    /** 换算出源图四边形、目标矩形在 View 里的位置。 */
    private fun layoutFor(w: Float, h: Float, src: Bitmap, res: Bitmap) {
        val s = min(w / src.width, h / src.height)
        val dx = (w - src.width * s) / 2f
        val dy = (h - src.height * s) / 2f
        srcRect.set(dx, dy, dx + src.width * s, dy + src.height * s)
        for (i in 0 until 4) {
            fromPts[i * 2] = quadPx[i * 2] * s + dx
            fromPts[i * 2 + 1] = quadPx[i * 2 + 1] * s + dy
        }

        val pad = dp(8f)
        val rs = min((w - 2f * pad) / res.width, (h - 2f * pad) / res.height)
        val rw = res.width * rs
        val rh = res.height * rs
        val left = (w - rw) / 2f
        val top = (h - rh) / 2f
        dstRect.set(left, top, left + rw, top + rh)
        toPts[0] = left; toPts[1] = top
        toPts[2] = left + rw; toPts[3] = top
        toPts[4] = left + rw; toPts[5] = top + rh
        toPts[6] = left; toPts[7] = top + rh
    }

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val ANIM_MS = 1250L
    }
}
