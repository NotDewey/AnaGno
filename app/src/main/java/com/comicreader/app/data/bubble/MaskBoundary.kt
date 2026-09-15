package com.comicreader.app.data.bubble

/** Smooth only the inside edge. Never admit panel pixels outside the selected mask. */
internal fun softenMaskBoundary(alpha: IntArray, width: Int, height: Int): IntArray {
    require(width > 0 && height > 0 && alpha.size == width * height)
    val result = alpha.copyOf()
    for (y in 0 until height) for (x in 0 until width) {
        val index = y * width + x
        // Retain native antialiasing and all fully transparent pixels.
        if (alpha[index] < 224) continue
        var weighted = 0
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx
            val ny = y + dy
            val weight = (if (dx == 0) 2 else 1) * (if (dy == 0) 2 else 1)
            if (nx in 0 until width && ny in 0 until height) {
                weighted += alpha[ny * width + nx] * weight
            }
        }
        // Keep thin tails/connecting strokes visible; interior text stays opaque.
        result[index] = minOf(alpha[index], maxOf(128, (weighted + 8) / 16))
    }
    return result
}
