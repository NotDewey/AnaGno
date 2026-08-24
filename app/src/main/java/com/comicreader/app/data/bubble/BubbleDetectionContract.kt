package com.comicreader.app.data.bubble

/** Shared contract for detector output, Room state, reader handoff and workers. */
object BubbleDetectionContract {
    const val MASK_VERSION = "v34_"
    const val DIAGNOSTIC_TAG = "BubbleZoomV34"
    const val INDEX_TAG = "BubbleIndexV34"
}

/**
 * Stable class order for the future multi-class ONNX model.
 *
 * The current one-class model maps to [GENERIC]. A replacement model may emit
 * the six learned classes below without changing the reader/cache pipeline.
 * Every balloon-class mask must include its complete body and tail.
 */
enum class BubbleModelClass {
    GENERIC,
    SPEECH,
    THOUGHT,
    SHOUT,
    WHISPER,
    ELECTRONIC,
    CAPTION,
    UNKNOWN
}

enum class BubblePageStatus {
    PROCESSING,
    READY,
    EMPTY,
    FAILED
}
