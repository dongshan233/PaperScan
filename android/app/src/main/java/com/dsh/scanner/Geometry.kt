package com.dsh.scanner

import android.graphics.PointF
import org.opencv.core.Point
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Document quadrilateral: always ordered tl, tr, br, bl (clockwise on screen). */
typealias Quad = Array<PointF>

fun orderQuad(points: List<PointF>): Quad {
    require(points.size == 4)
    val cx = points.map { it.x }.average().toFloat()
    val cy = points.map { it.y }.average().toFloat()
    val sorted = points.sortedBy { atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
    val start = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y } ?: 0
    return Array(4) { sorted[(start + it) % 4] }
}

fun quadArea(q: Quad): Float {
    var s = 0f
    for (i in 0 until 4) {
        val a = q[i]
        val b = q[(i + 1) % 4]
        s += a.x * b.y - b.x * a.y
    }
    return abs(s) * 0.5f
}

fun quadMeanDistance(a: Quad, b: Quad): Float {
    var s = 0f
    for (i in 0 until 4) s += hypot(a[i].x - b[i].x, a[i].y - b[i].y)
    return s / 4f
}

fun quadDiagonal(q: Quad): Float =
    hypot(q[2].x - q[0].x, q[2].y - q[0].y)

fun Quad.toPointList(): List<Point> = map { Point(it.x.toDouble(), it.y.toDouble()) }

fun Quad.scaled(sx: Float, sy: Float): Quad =
    Array(4) { PointF(this[it].x * sx, this[it].y * sy) }

fun Quad.bbox(): android.graphics.RectF {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (p in this) {
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
    }
    return android.graphics.RectF(minX, minY, maxX, maxY)
}

/** 0..1, 1 = perfect rectangle. */
fun rectangularity(q: Quad): Float {
    var worstDev = 0.0
    val lens = FloatArray(4)
    for (i in 0 until 4) {
        val a = q[i]
        val b = q[(i + 1) % 4]
        lens[i] = hypot(b.x - a.x, b.y - a.y) + 1e-4f
    }
    for (i in 0 until 4) {
        val e1x = q[(i + 1) % 4].x - q[i].x
        val e1y = q[(i + 1) % 4].y - q[i].y
        val e2x = q[(i + 2) % 4].x - q[(i + 1) % 4].x
        val e2y = q[(i + 2) % 4].y - q[(i + 1) % 4].y
        val cosv = ((e1x * e2x + e1y * e2y) / (lens[i] * lens[(i + 1) % 4])).toDouble()
        val deg = Math.toDegrees(kotlin.math.acos(cosv.coerceIn(-1.0, 1.0)))
        worstDev = maxOf(worstDev, abs(90.0 - deg))
    }
    val ratio = (minOf(lens[0], lens[2]) / maxOf(lens[0], lens[2])) *
        (minOf(lens[1], lens[3]) / maxOf(lens[1], lens[3]))
    val a = (1.0 - worstDev / 26.0).coerceIn(0.0, 1.0)
    val b = (ratio / 0.55).toDouble().coerceIn(0.0, 1.0)
    return (a * b).toFloat()
}
