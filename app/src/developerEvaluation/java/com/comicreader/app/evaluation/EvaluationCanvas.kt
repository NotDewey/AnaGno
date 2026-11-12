package com.comicreader.app.evaluation

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.floor
import java.util.concurrent.Executors

/** All annotation vertices are original, decoded page pixels; preview sampling never changes them. */
internal class EvaluationCanvas(context: Context) : View(context) {
    enum class Mode { NAVIGATE, POLYGON, RECTANGLE }
    var mode = Mode.NAVIGATE
    var selected = -1
        set(value) { field = value; invalidate() }
    var annotations: List<EvaluationRegion> = emptyList()
        set(value) { field = value; invalidate() }
    var pending: List<EvaluationPoint> = emptyList()
        set(value) { field = value; invalidate() }
    private var page: EvaluationPage? = null
    private var loaded: Loaded? = null
    // Only one decode may run; rapid gestures replace the pending viewport, never queue bitmaps.
    private val nativeWorker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var released = false
    private var decoding = false
    private var viewportVersion = 0
    internal data class NativeInspection(val bounds: Rect, val bitmap: Bitmap)
    internal var nativeInspection: NativeInspection? = null
        private set
    internal var inspectionState = "Sampled overview"
        private set
    internal var onInspectionChanged: (() -> Unit)? = null
    private val decodeViewport = Runnable { decodeNativeViewport() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var anchor: EvaluationPoint? = null
    private var moved = false
    private var multiTouch = false
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (mode != Mode.NAVIGATE) return false
            val old = zoom
            val fitScale = transform(1f).third
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, maxOf(20f, 4f / fitScale, zoom))
            val ratio = zoom / old
            val (left, top, _) = transform(old)
            val (newLeft, newTop, _) = transform(zoom)
            panX += detector.focusX - ((detector.focusX - left) * ratio + newLeft)
            panY += detector.focusY - ((detector.focusY - top) * ratio + newTop)
            viewportChanged()
            return true
        }
    })

    fun setPage(value: EvaluationPage, image: Loaded) {
        loaded?.bitmap?.recycle()
        page = value
        loaded = image
        pending = emptyList()
        fit()
    }
    fun fit() { zoom = 1f; panX = 0f; panY = 0f; viewportChanged() }

    /** Enter true source-pixel inspection at the current viewport center. Fit returns to overview. */
    fun inspectNative() {
        val p = page ?: return
        val (left, top, scale) = transform()
        if (scale <= 0f) return
        setInspectionViewport(maxOf(1f, width / 1024f, height / 1024f),
            ((width / 2f - left) / scale).coerceIn(0f, p.width.toFloat()),
            ((height / 2f - top) / scale).coerceIn(0f, p.height.toFloat()))
    }

    /** Uses the same transform as touch navigation; also permits fixture-based viewport checks. */
    internal fun setInspectionViewport(scale: Float, centerX: Float, centerY: Float) {
        val p = page ?: return
        val fitScale = min(width.toFloat() / p.width, height.toFloat() / p.height)
        require(scale.isFinite() && scale > 0f && fitScale > 0f && centerX.isFinite() && centerY.isFinite())
        zoom = scale / fitScale
        panX = (p.width / 2f - centerX) * scale
        panY = (p.height / 2f - centerY) * scale
        viewportChanged()
    }

    fun release() {
        released = true
        viewportVersion++
        mainHandler.removeCallbacks(decodeViewport)
        nativeWorker.shutdownNow()
        nativeInspection?.bitmap?.recycle(); nativeInspection = null
        loaded?.bitmap?.recycle(); loaded = null
        onInspectionChanged = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewportChanged()
    }

    private fun viewportChanged() {
        viewportVersion++
        nativeInspection?.bitmap?.recycle(); nativeInspection = null
        mainHandler.removeCallbacks(decodeViewport)
        inspectionState = if (transform().third >= 1f - 0.000001f) "Preview • native pixels pending" else "Sampled overview • use Native inspect"
        if (!released) mainHandler.postDelayed(decodeViewport, 120)
        onInspectionChanged?.invoke()
        invalidate()
    }

    private fun decodeNativeViewport() {
        val p = page ?: return
        val (left, top, scale) = transform()
        if (released || decoding || width <= 0 || height <= 0 || scale < 1f - 0.000001f) return
        val bounds = Rect(
            floor(-left / scale).toInt().coerceIn(0, p.width),
            floor(-top / scale).toInt().coerceIn(0, p.height),
            ceil((width - left) / scale).toInt().coerceIn(0, p.width),
            ceil((height - top) / scale).toInt().coerceIn(0, p.height))
        if (bounds.isEmpty || bounds.width().toLong() * bounds.height() > 4_194_304L) {
            inspectionState = "Preview • pan onto page or zoom further for native pixels"
            onInspectionChanged?.invoke(); invalidate()
            return
        }
        decoding = true
        val version = viewportVersion
        nativeWorker.execute {
            var bitmap: Bitmap? = null
            var failure: String? = null
            try {
                // Stream and decoder both close on failure and success; no persistent file handles.
                p.image.inputStream().use { stream ->
                    @Suppress("DEPRECATION")
                    val decoder = BitmapRegionDecoder.newInstance(stream, false)
                        ?: error("Source does not support region decoding")
                    try {
                        check(decoder.width == p.width && decoder.height == p.height) { "Source dimensions changed" }
                        bitmap = decoder.decodeRegion(bounds, BitmapFactory.Options().apply {
                            inSampleSize = 1; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
                        }) ?: error("Native crop unavailable")
                    } finally { decoder.recycle() }
                }
            } catch (error: Exception) {
                bitmap?.recycle(); bitmap = null
                failure = error.message ?: "Region decoding failed"
            } catch (_: OutOfMemoryError) {
                bitmap?.recycle(); bitmap = null
                failure = "Native crop memory unavailable; zoom further"
            }
            mainHandler.post {
                decoding = false
                if (released || version != viewportVersion) {
                    bitmap?.recycle()
                    if (!released) { mainHandler.removeCallbacks(decodeViewport); mainHandler.postDelayed(decodeViewport, 120) }
                } else {
                    nativeInspection = bitmap?.let { NativeInspection(Rect(bounds), it) }
                    inspectionState = if (bitmap != null) "Native source pixels • ${bounds.width()}×${bounds.height()} crop"
                        else "Preview • ${failure ?: "Native crop unavailable"}"
                    onInspectionChanged?.invoke()
                    invalidate()
                }
            }
        }
    }
    fun undoPoint() { pending = pending.dropLast(1) }
    fun cancelContour() { pending = emptyList(); anchor = null }
    fun contour(): List<EvaluationPoint> {
        if (pending.size < 3) return emptyList()
        val area = pending.indices.sumOf { index ->
            val a = pending[index]; val b = pending[(index + 1) % pending.size]
            (a.x.toDouble() * b.y - b.x.toDouble() * a.y)
        }
        return pending.takeIf { abs(area) >= 1.0 } ?: emptyList()
    }

    private fun transform(z: Float = zoom): Triple<Float, Float, Float> {
        val p = page ?: return Triple(0f, 0f, 1f)
        val scale = min(width.toFloat() / p.width, height.toFloat() / p.height) * z
        return Triple((width - p.width * scale) / 2f + panX, (height - p.height * scale) / 2f + panY, scale)
    }
    internal fun mapViewPoint(x: Float, y: Float): EvaluationPoint? {
        val p = page ?: return null
        val (left, top, scale) = transform()
        if (!scale.isFinite() || scale <= 0f) return null
        // Undo the real draw transform, then use the tested geometry inverse/boundary tolerance.
        return EvaluationGeometry.fitPoint((x - left) / scale, (y - top) / scale,
            p.width.toFloat(), p.height.toFloat(), p.width, p.height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.DKGRAY)
        val p = page ?: return
        val image = loaded ?: return
        val (left, top, scale) = transform()
        if (scale <= 0f) return
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, p.width.toFloat(), p.height.toFloat())
        paint.style = Paint.Style.FILL
        paint.isFilterBitmap = true
        canvas.drawBitmap(image.bitmap, null, RectF(0f, 0f, p.width.toFloat(), p.height.toFloat()), paint)
        nativeInspection?.let { native ->
            paint.isFilterBitmap = false
            canvas.drawBitmap(native.bitmap, null, RectF(native.bounds), paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f / scale
        paint.color = Color.CYAN
        image.outlines.forEachIndexed { index, path ->
            canvas.drawPath(path, paint)
            val b = p.predictions[index].bounds
            paint.style = Paint.Style.FILL
            paint.textSize = 13f * resources.displayMetrics.scaledDensity / scale
            canvas.drawText(p.predictions[index].id, b[0] * p.width, b[1] * p.height, paint)
            paint.style = Paint.Style.STROKE
        }
        annotations.forEachIndexed { index, region ->
            paint.strokeWidth = (if (index == selected) 3f else 1.5f) / scale
            region.contours.forEach { contour ->
                paint.color = if (contour.subtract) Color.RED else if (region.verified) Color.GREEN else Color.YELLOW
                canvas.drawPath(path(contour.points, true), paint)
            }
        }
        paint.strokeWidth = 2f / scale
        paint.color = Color.MAGENTA
        canvas.drawPath(path(pending, false), paint)
        pending.forEach { canvas.drawCircle(it.x, it.y, 3f / scale, paint) }
        canvas.restore()
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.textSize = 14f * resources.displayMetrics.scaledDensity
        canvas.drawText("${mode.name.lowercase()} • ${"%.1f".format(zoom)}× • ${p.width}×${p.height}px", 8f, paint.textSize + 5f, paint)
        canvas.drawText(inspectionState, 8f, paint.textSize * 2 + 10f, paint)
        pending.lastOrNull()?.let { canvas.drawText("x=${"%.1f".format(it.x)} y=${"%.1f".format(it.y)}", 8f, paint.textSize * 3 + 15f, paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || page == null) return false
        if (mode == Mode.NAVIGATE) scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                lastX = event.x; lastY = event.y; anchor = mapViewPoint(event.x, event.y)
                moved = false; multiTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> { multiTouch = true; if (mode != Mode.NAVIGATE) { anchor = null; pending = emptyList() } }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - lastX) + abs(event.y - lastY) > 3f) moved = true
                if (mode == Mode.NAVIGATE && !scaler.isInProgress && event.pointerCount == 1) {
                    panX += event.x - lastX; panY += event.y - lastY
                    viewportChanged()
                } else if (mode == Mode.RECTANGLE && !multiTouch) {
                    val a = anchor
                    val b = mapViewPoint(event.x, event.y)
                    if (a != null && b != null) pending = listOf(a, EvaluationPoint(b.x, a.y), b, EvaluationPoint(a.x, b.y))
                }
                lastX = event.x; lastY = event.y; invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.POLYGON && !multiTouch && !moved) mapViewPoint(event.x, event.y)?.let { pending = pending + it }
                anchor = null
                parent.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> { anchor = null; parent.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    data class Loaded(val bitmap: Bitmap, val outlines: List<Path>)
    companion object {
        private fun path(points: List<EvaluationPoint>, close: Boolean): Path = Path().apply {
            points.firstOrNull()?.let { moveTo(it.x, it.y) }
            points.drop(1).forEach { lineTo(it.x, it.y) }
            if (close) close()
        }
        fun load(page: EvaluationPage): Loaded {
            require(page.width in 1..EvaluationGeometry.MAX_DIMENSION && page.height in 1..EvaluationGeometry.MAX_DIMENSION &&
                page.width.toLong() * page.height <= EvaluationGeometry.MAX_PIXELS) { "Page exceeds canvas safety limit" }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(page.image.path, options)
            require(options.outWidth == page.width && options.outHeight == page.height) { "Decoded page dimensions changed" }
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (maxOf(page.width, page.height) / options.inSampleSize > 2048) options.inSampleSize *= 2
            val bitmap = BitmapFactory.decodeFile(page.image.path, options) ?: error("Page raster cannot be decoded")
            return try { Loaded(bitmap, page.predictions.map { prediction -> outline(prediction, page) }) }
            catch (error: Exception) { bitmap.recycle(); throw error }
        }
        /** Trace exposed alpha-pixel edges, including holes; never substitute bounding rectangles. */
        private fun outline(prediction: EvaluationPrediction, page: EvaluationPage): Path {
            val bounds = prediction.bounds
            require(bounds.size == 4 && bounds.all { it.isFinite() && it in 0f..1f } && bounds[2] > bounds[0] && bounds[3] > bounds[1]) { "Invalid prediction bounds: ${prediction.id}" }
            val maskOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(prediction.mask.path, maskOptions)
            require(maskOptions.outWidth in 1..16384 && maskOptions.outHeight in 1..16384 && maskOptions.outWidth.toLong() * maskOptions.outHeight <= 16_777_216) { "Prediction mask exceeds canvas safety limit" }
            val mask = BitmapFactory.decodeFile(prediction.mask.path) ?: error("Missing actual prediction mask: ${prediction.id}")
            try {
                val w = mask.width; val h = mask.height
                var previous = IntArray(w)
                var current = IntArray(w)
                var next = IntArray(w)
                mask.getPixels(current, 0, w, 0, 0, w, 1)
                fun solid(row: IntArray, x: Int) = x in 0 until w && (row[x] ushr 24) >= 128
                val path = Path()
                val x0 = bounds[0] * page.width; val y0 = bounds[1] * page.height
                val sx = (bounds[2] - bounds[0]) * page.width / w
                val sy = (bounds[3] - bounds[1]) * page.height / h
                fun edge(x: Int, y: Int, xx: Int, yy: Int) {
                    path.moveTo(x0 + x * sx, y0 + y * sy)
                    path.lineTo(x0 + xx * sx, y0 + yy * sy)
                }
                for (y in 0 until h) {
                    if (y + 1 < h) mask.getPixels(next, 0, w, 0, y + 1, w, 1) else next.fill(0)
                    for (x in 0 until w) if (solid(current, x)) {
                        if (!solid(previous, x)) edge(x, y, x + 1, y)
                        if (!solid(current, x + 1)) edge(x + 1, y, x + 1, y + 1)
                        if (!solid(next, x)) edge(x + 1, y + 1, x, y + 1)
                        if (!solid(current, x - 1)) edge(x, y + 1, x, y)
                    }
                    val reuse = previous
                    previous = current; current = next; next = reuse
                }
                return path
            } finally { mask.recycle() }
        }
    }
}
