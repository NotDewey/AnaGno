package com.github.pedrovgs.deeppanel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.comicreader.app.R
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Anagno-local compatibility implementation of the DeepPanel 0.0.1 API.
 *
 * Why this exists:
 * - The original DeepPanel AAR bundles a native libdeep-panel.so built with an old NDK.
 * - That native library is not compatible with Android's 16 KB page-size requirement.
 * - The original native stage only performs connected-component labeling + bounding boxes,
 *   so the same post-processing is implemented here in Kotlin.
 * - Inference uses Google LiteRT 1.4.2 instead of DeepPanel's legacy TensorFlow Lite 2.2.0 runtime.
 *
 * This preserves the API Anagno already uses:
 *   DeepPanel.initialize(context)
 *   DeepPanel().extractPanelsInfo(bitmap).panels.panelsInfo
 */
class DeepPanel {

    companion object {
        const val modelInputImageSize = 224

        @Volatile
        private var interpreter: Interpreter? = null

        fun initialize(context: Context) {
            if (interpreter != null) return

            synchronized(this) {
                if (interpreter == null) {
                    interpreter = Interpreter(loadModel(context.applicationContext))
                }
            }
        }

        private fun loadModel(context: Context): ByteBuffer {
            val rawModelBytes = context.resources
                .openRawResource(R.raw.deep_panel_model)
                .use { it.readBytes() }

            return ByteBuffer.allocateDirect(rawModelBytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(rawModelBytes)
                rewind()
            }
        }
    }

    fun extractPanelsInfo(bitmap: Bitmap): PredictionResult {
        val runtime = interpreter
            ?: error("DeepPanel.initialize(context) must be called before detection")

        val resizedImage = resizeInput(bitmap)

        try {
            val modelInput = convertBitmapToByteBuffer(resizedImage)
            val prediction = Array(1) {
                Array(modelInputImageSize) {
                    Array(modelInputImageSize) {
                        FloatArray(3)
                    }
                }
            }

            synchronized(runtime) {
                runtime.run(modelInput, prediction)
            }

            return extractPanelsFromPrediction(
                prediction = prediction[0],
                originalWidth = bitmap.width,
                originalHeight = bitmap.height
            )
        } finally {
            if (resizedImage !== bitmap && !resizedImage.isRecycled) {
                resizedImage.recycle()
            }
        }
    }

    private fun resizeInput(bitmapToResize: Bitmap): Bitmap {
        val requestedWidth = modelInputImageSize.toFloat()
        val requestedHeight = modelInputImageSize.toFloat()

        val matrix = Matrix().apply {
            setRectToRect(
                RectF(
                    0f,
                    0f,
                    bitmapToResize.width.toFloat(),
                    bitmapToResize.height.toFloat()
                ),
                RectF(0f, 0f, requestedWidth, requestedHeight),
                Matrix.ScaleToFit.CENTER
            )
        }

        return Bitmap.createBitmap(
            modelInputImageSize,
            modelInputImageSize,
            Bitmap.Config.ARGB_8888
        ).also { output ->
            val canvas = Canvas(output)
            val backgroundPaint = Paint().apply { color = Color.BLACK }
            canvas.drawRect(0f, 0f, requestedWidth, requestedHeight, backgroundPaint)
            canvas.drawBitmap(bitmapToResize, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val floatTypeSizeInBytes = 4
        val numberOfChannels = 3
        val modelInputSize =
            floatTypeSizeInBytes *
                    modelInputImageSize *
                    modelInputImageSize *
                    numberOfChannels

        val imageData = ByteBuffer.allocateDirect(modelInputSize).apply {
            order(ByteOrder.nativeOrder())
        }

        val pixels = IntArray(modelInputImageSize * modelInputImageSize)
        bitmap.getPixels(
            pixels,
            0,
            bitmap.width,
            0,
            0,
            bitmap.width,
            bitmap.height
        )

        pixels.forEach { pixel ->
            imageData.putFloat(Color.red(pixel) / 255f)
            imageData.putFloat(Color.green(pixel) / 255f)
            imageData.putFloat(Color.blue(pixel) / 255f)
        }

        imageData.rewind()
        return imageData
    }

    private fun extractPanelsFromPrediction(
        prediction: Array<Array<FloatArray>>,
        originalWidth: Int,
        originalHeight: Int
    ): PredictionResult {
        val width = prediction.size
        val height = prediction.firstOrNull()?.size ?: 0

        if (width == 0 || height == 0) {
            return PredictionResult(emptyArray(), Panels(emptyList()))
        }

        /*
         * The original JNI implementation intentionally swaps the two prediction
         * indices when building the matrix. Keep that behavior so panel geometry
         * stays identical to DeepPanel 0.0.1.
         */
        val contentMask = Array(width) { IntArray(height) }

        for (x in 0 until width) {
            for (y in 0 until height) {
                val sourceY = x.coerceIn(prediction.indices)
                val sourceX = y.coerceIn(prediction[sourceY].indices)
                contentMask[x][y] = predictionToContentLabel(
                    prediction[sourceX][sourceY]
                )
            }
        }

        val labels = Array(width) { IntArray(height) }
        val componentSizes = mutableMapOf<Int, Int>()
        var nextLabel = 0

        // Four-connected component labeling, matching the old native stage.
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (contentMask[x][y] == 0 || labels[x][y] != 0) continue

                nextLabel += 1
                val size = floodFillComponent(
                    startX = x,
                    startY = y,
                    contentMask = contentMask,
                    labels = labels,
                    label = nextLabel,
                    width = width,
                    height = height
                )
                componentSizes[nextLabel] = size
            }
        }

        // DeepPanel 0.0.1 removes connected areas smaller than 3% of the model image.
        val minimumArea = (width * height * 0.03f).toInt()
        for (x in 0 until width) {
            for (y in 0 until height) {
                val label = labels[x][y]
                if (label != 0 && (componentSizes[label] ?: 0) < minimumArea) {
                    labels[x][y] = 0
                }
            }
        }

        val normalizedByRawLabel = mutableMapOf<Int, Int>()
        val bounds = mutableListOf<MutablePanelBounds>()

        /*
         * DeepPanel's native code normalizes labels while scanning X first and Y
         * second. Preserving that scan order also preserves panel ordering.
         */
        for (x in 0 until width) {
            for (y in 0 until height) {
                val rawLabel = labels[x][y]
                if (rawLabel == 0) continue

                val normalizedLabel = normalizedByRawLabel.getOrPut(rawLabel) {
                    bounds += MutablePanelBounds()
                    bounds.size
                }

                bounds[normalizedLabel - 1].include(x, y)
            }
        }

        val scale = max(originalWidth, originalHeight) / modelInputImageSize.toFloat()
        val horizontalCorrection =
            if (originalWidth < originalHeight) {
                ((width * scale) - originalWidth).toInt() / 2
            } else {
                0
            }
        val verticalCorrection =
            if (originalWidth < originalHeight) {
                0
            } else {
                ((height * scale) - originalHeight).toInt() / 2
            }

        val border = computeBorderSize(originalWidth, originalHeight)

        val panels = bounds.mapIndexed { index, box ->
            val proposedLeft =
                applyScaleAndAddBorder(box.minX, scale, -border) - horizontalCorrection
            val proposedTop =
                applyScaleAndAddBorder(box.minY, scale, -border) - verticalCorrection
            val proposedRight =
                applyScaleAndAddBorder(box.maxX, scale, border) - horizontalCorrection
            val proposedBottom =
                applyScaleAndAddBorder(box.maxY, scale, border) - verticalCorrection

            Panel(
                panelNumberInPage = index,
                left = proposedLeft.coerceIn(0, originalWidth),
                top = proposedTop.coerceIn(0, originalHeight),
                right = proposedRight.coerceIn(0, originalWidth),
                bottom = proposedBottom.coerceIn(0, originalHeight)
            )
        }

        return PredictionResult(
            rawPrediction = labels,
            panels = Panels(panels)
        )
    }

    private fun predictionToContentLabel(pixelPrediction: FloatArray): Int {
        if (pixelPrediction.size < 3) return 0

        val background = pixelPrediction[0]
        val border = pixelPrediction[1]
        val content = pixelPrediction[2]

        return if (
            background >= content && background > border
        ) {
            0
        } else if (
            border >= background && border >= content
        ) {
            0
        } else {
            1
        }
    }

    private fun floodFillComponent(
        startX: Int,
        startY: Int,
        contentMask: Array<IntArray>,
        labels: Array<IntArray>,
        label: Int,
        width: Int,
        height: Int
    ): Int {
        val queueX = IntArray(width * height)
        val queueY = IntArray(width * height)
        var head = 0
        var tail = 0
        var size = 0

        labels[startX][startY] = label
        queueX[tail] = startX
        queueY[tail] = startY
        tail += 1

        while (head < tail) {
            val x = queueX[head]
            val y = queueY[head]
            head += 1
            size += 1

            fun enqueue(nx: Int, ny: Int) {
                if (
                    nx in 0 until width &&
                    ny in 0 until height &&
                    contentMask[nx][ny] != 0 &&
                    labels[nx][ny] == 0
                ) {
                    labels[nx][ny] = label
                    queueX[tail] = nx
                    queueY[tail] = ny
                    tail += 1
                }
            }

            enqueue(x - 1, y)
            enqueue(x + 1, y)
            enqueue(x, y - 1)
            enqueue(x, y + 1)
        }

        return size
    }

    private fun applyScaleAndAddBorder(
        position: Int,
        scale: Float,
        border: Int
    ): Int = (position * scale).toInt() + border

    private fun computeBorderSize(
        originalWidth: Int,
        originalHeight: Int
    ): Int =
        if (originalHeight > originalWidth) {
            originalWidth * 30 / 3056
        } else {
            originalHeight * 30 / 1988
        }
}

private data class MutablePanelBounds(
    var minX: Int = Int.MAX_VALUE,
    var minY: Int = Int.MAX_VALUE,
    var maxX: Int = Int.MIN_VALUE,
    var maxY: Int = Int.MIN_VALUE
) {
    fun include(x: Int, y: Int) {
        minX = minOf(minX, x)
        minY = minOf(minY, y)
        maxX = maxOf(maxX, x)
        maxY = maxOf(maxY, y)
    }
}

typealias Prediction = Array<IntArray>

data class PredictionResult(
    val rawPrediction: Prediction,
    val panels: Panels
)

data class Panels(
    val panelsInfo: List<Panel>
) {
    val numberOfPanels: Int = panelsInfo.size
}

data class Panel(
    val panelNumberInPage: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int = right - left
    val height: Int = bottom - top
}


