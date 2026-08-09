package com.comicreader.app.data.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.comicreader.app.domain.model.Panel
import com.github.pedrovgs.deeppanel.DeepPanel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

private const val DETECTION_MAX_DIMENSION = 2048
private const val MINIMUM_PANEL_SIZE = 0.02f

/** Runs the bundled TensorFlow Lite panel model entirely on the device. */
@Singleton
class PanelDetector @Inject constructor(
    @ApplicationContext context: Context
) {
    private val detector: DeepPanel

    init {
        DeepPanel.initialize(context.applicationContext)
        detector = DeepPanel()
    }

    suspend fun detect(
        pagePath: String,
        comicId: Long,
        pageIndex: Int
    ): List<Panel> = withContext(Dispatchers.Default) {
        val bitmap = decodeSampledBitmap(pagePath)
            ?: error("Couldn't decode this page for panel detection")

        try {
            val detected = synchronized(detector) {
                detector.extractPanelsInfo(bitmap).panels.panelsInfo
            }

            detected
                .sortedBy { it.panelNumberInPage }
                .mapNotNull { panel ->
                    val left = (panel.left.toFloat() / bitmap.width).coerceIn(0f, 1f)
                    val top = (panel.top.toFloat() / bitmap.height).coerceIn(0f, 1f)
                    val right = (panel.right.toFloat() / bitmap.width).coerceIn(0f, 1f)
                    val bottom = (panel.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f)
                    if (right - left < MINIMUM_PANEL_SIZE || bottom - top < MINIMUM_PANEL_SIZE) {
                        null
                    } else {
                        Panel(
                            comicId = comicId,
                            pageIndex = pageIndex,
                            order = 0,
                            left = left,
                            top = top,
                            right = right,
                            bottom = bottom
                        )
                    }
                }
                .mapIndexed { order, panel -> panel.copy(order = order) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeSampledBitmap(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > DETECTION_MAX_DIMENSION) {
            sampleSize *= 2
        }

        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }
}