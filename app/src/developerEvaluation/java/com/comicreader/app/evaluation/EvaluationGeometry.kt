package com.comicreader.app.evaluation

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class EvaluationPoint(val x: Float, val y: Float)

data class EvaluationContour(
    val points: List<EvaluationPoint>,
    val subtract: Boolean = false
)

data class EvaluationRegion(
    val id: String,
    val contours: List<EvaluationContour>,
    val verified: Boolean = false
)

/**
 * Human annotations in original page pixel-edge coordinates, including width/height edges.
 * Geometry never promotes a draft to verified; export must enforce human verification.
 * Simple polygons use even-odd fill at (x + .5, y + .5), with intersections defining
 * [left, right) spans and [top, bottom) active edges. No antialiasing or clipping.
 * The union of subtract contours is removed from the union of additive contours.
 */
object EvaluationGeometry {
    const val MAX_DIMENSION = 16_384
    const val MAX_PIXELS = 16_777_216L
    private const val MAX_CONTOURS = 128
    private const val MAX_POINTS = 4_096

    /** Tight bounds of included raster pixels: [left, top, rightExclusive, bottomExclusive]. */
    fun box(region: EvaluationRegion, width: Int, height: Int): List<Int> =
        raster(region, width, height) { _, _ -> }

    /** Full-resolution ARGB_8888: included = 0xffffffff, excluded = 0x00000000. */
    fun mask(region: EvaluationRegion, width: Int, height: Int): Bitmap {
        val contours = validate(region, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            raster(region.copy(contours = contours), width, height, validated = true) { y, row ->
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    /** Inverse of centered ContentScale.Fit; padding/outside/nonfinite touches return null. */
    fun fitPoint(
        x: Float,
        y: Float,
        viewWidth: Float,
        viewHeight: Float,
        width: Int,
        height: Int
    ): EvaluationPoint? {
        dimensions(width, height)
        if (!x.isFinite() || !y.isFinite() || !viewWidth.isFinite() ||
            !viewHeight.isFinite() || viewWidth <= 0f || viewHeight <= 0f
        ) return null
        val scale = min(viewWidth.toDouble() / width, viewHeight.toDouble() / height)
        val left = (viewWidth - width * scale) / 2.0
        val top = (viewHeight - height * scale) / 2.0
        val pageX = (x - left) / scale
        val pageY = (y - top) / scale
        val tolerance = 0.001
        if (pageX < -tolerance || pageX > width + tolerance || pageY < -tolerance || pageY > height + tolerance) return null
        return EvaluationPoint(pageX.coerceIn(0.0, width.toDouble()).toFloat(), pageY.coerceIn(0.0, height.toDouble()).toFloat())
    }

    private fun dimensions(width: Int, height: Int) {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION &&
            width.toLong() * height <= MAX_PIXELS
        ) { "Page exceeds geometry limits: $width x $height" }
    }

    private fun validate(region: EvaluationRegion, width: Int, height: Int): List<EvaluationContour> {
        dimensions(width, height)
        require(region.id.isNotBlank()) { "Region needs an ID" }
        require(region.contours.size in 1..MAX_CONTOURS) { "Invalid contour count" }
        require(region.contours.sumOf { it.points.size.toLong() } <= MAX_POINTS) {
            "Too many contour points"
        }
        require(region.contours.any { !it.subtract }) { "Region needs an additive contour" }
        return region.contours.map { contour ->
            val raw = contour.points
            val points = if (raw.size > 1 && raw.first() == raw.last()) raw.dropLast(1) else raw
            require(points.size >= 3 && points.distinct().size == points.size) {
                "Contour needs at least three distinct vertices without repeats"
            }
            require(points.all {
                it.x.isFinite() && it.y.isFinite() &&
                    it.x >= 0f && it.x <= width && it.y >= 0f && it.y <= height
            }) { "Contour coordinates must be finite and inside the original page" }
            var twiceArea = 0.0
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                val c = points[(i + 2) % points.size]
                twiceArea += a.x.toDouble() * b.y - b.x.toDouble() * a.y
                require(cross(a, b, c) != 0.0 ||
                    (b.x.toDouble() - a.x) * (c.x.toDouble() - b.x) +
                    (b.y.toDouble() - a.y) * (c.y.toDouble() - b.y) > 0.0
                ) { "Contour doubles back on itself" }
                for (j in i + 2 until points.size) {
                    if (i == 0 && j == points.lastIndex) continue
                    require(!intersects(a, b, points[j], points[(j + 1) % points.size])) {
                        "Contour must not self-intersect or self-touch"
                    }
                }
            }
            require(twiceArea != 0.0) { "Contour has zero area" }
            contour.copy(points = points)
        }
    }

    private fun cross(a: EvaluationPoint, b: EvaluationPoint, c: EvaluationPoint): Double =
        (b.x.toDouble() - a.x) * (c.y.toDouble() - a.y) -
            (b.y.toDouble() - a.y) * (c.x.toDouble() - a.x)

    private fun intersects(a: EvaluationPoint, b: EvaluationPoint, c: EvaluationPoint, d: EvaluationPoint): Boolean {
        fun onSegment(p: EvaluationPoint, q: EvaluationPoint, r: EvaluationPoint): Boolean =
            q.x >= min(p.x, r.x) && q.x <= max(p.x, r.x) &&
                q.y >= min(p.y, r.y) && q.y <= max(p.y, r.y)
        val abC = cross(a, b, c)
        val abD = cross(a, b, d)
        val cdA = cross(c, d, a)
        val cdB = cross(c, d, b)
        return ((abC > 0 && abD < 0 || abC < 0 && abD > 0) &&
            (cdA > 0 && cdB < 0 || cdA < 0 && cdB > 0)) ||
            (abC == 0.0 && onSegment(a, c, b)) ||
            (abD == 0.0 && onSegment(a, d, b)) ||
            (cdA == 0.0 && onSegment(c, a, d)) ||
            (cdB == 0.0 && onSegment(c, b, d))
    }

    private inline fun raster(
        region: EvaluationRegion,
        width: Int,
        height: Int,
        validated: Boolean = false,
        emit: (Int, IntArray) -> Unit
    ): List<Int> {
        val contours = if (validated) region.contours else validate(region, width, height)
        val ordered = contours.sortedBy { it.subtract }
        val row = IntArray(width)
        val intersections = DoubleArray(contours.maxOf { it.points.size })
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        for (y in 0 until height) {
            row.fill(0)
            val centerY = y + 0.5
            for (contour in ordered) {
                var count = 0
                val points = contour.points
                for (i in points.indices) {
                    val a = points[i]
                    val b = points[(i + 1) % points.size]
                    if ((a.y <= centerY && centerY < b.y) || (b.y <= centerY && centerY < a.y)) {
                        // Canonical edge direction makes reversed winding numerically identical.
                        val low = if (a.y < b.y) a else b
                        val high = if (a.y < b.y) b else a
                        intersections[count++] = low.x + (centerY - low.y) *
                            (high.x.toDouble() - low.x) / (high.y.toDouble() - low.y)
                    }
                }
                java.util.Arrays.sort(intersections, 0, count)
                for (i in 0 until count step 2) {
                    val start = ceil(intersections[i] - 0.5).toInt().coerceIn(0, width)
                    val end = ceil(intersections[i + 1] - 0.5).toInt().coerceIn(0, width)
                    row.fill(if (contour.subtract) 0 else -1, start, end)
                }
            }
            val first = row.indexOfFirst { it != 0 }
            if (first >= 0) {
                left = min(left, first)
                right = max(right, row.indexOfLast { it != 0 } + 1)
                top = min(top, y)
                bottom = y + 1
            }
            emit(y, row)
        }
        require(left < right && top < bottom) { "Region contains no included pixel centers" }
        return listOf(left, top, right, bottom)
    }
}
