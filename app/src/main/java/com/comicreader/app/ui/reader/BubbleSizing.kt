package com.comicreader.app.ui.reader

import kotlin.math.sqrt

/** All dimensions use the same display units. OCR height is an estimate, not a font size. */
internal fun bubbleMagnification(
    sourceWidth: Float,
    sourceHeight: Float,
    textHeight: Float,
    targetTextHeight: Float,
    availableWidth: Float,
    availableHeight: Float
): Float {
    require(sourceWidth > 0f && sourceHeight > 0f)
    require(availableWidth > 0f && availableHeight > 0f)
    val desired = if (textHeight.isFinite() && textHeight > 0f) {
        (targetTextHeight / textHeight).coerceIn(1f, 3f)
    } else 1.6f
    // Limit occlusion for groups, while allowing small text to grow more than 2x.
    val areaLimit = sqrt(availableWidth * availableHeight * 0.22f / (sourceWidth * sourceHeight))
    val fit = minOf(
        availableWidth * 0.86f / sourceWidth,
        availableHeight * 0.48f / sourceHeight,
        areaLimit
    )
    return minOf(desired, fit).coerceAtLeast(0.01f)
}
