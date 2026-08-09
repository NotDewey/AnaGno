package com.comicreader.app.domain.model

/**
 * A single imported CBZ, CBR, or PDF, tracked as a "book" in the library.
 */
data class Comic(
    val id: Long = 0,
    val title: String,
    val uri: String,          // SAF content:// URI to the original CBZ, CBR, or PDF
    val coverPagePath: String?, // cached extracted cover image path
    val pageCount: Int,
    val lastReadPage: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    val dateLastOpened: Long? = null,
    val series: String? = null,
    val isFavorite: Boolean = false,
    /** True after the reader reaches the final page or the user marks it done. */
    val isFinished: Boolean = false,
    val finishedAt: Long? = null,
    /** Personal score from 1 to 5. Null means the comic has not been rated. */
    val userRating: Float? = null,
    val ratedAt: Long? = null
)

data class Bookmark(
    val id: Long = 0,
    val comicId: Long,
    val pageIndex: Int,
    val label: String? = null,
    val dateCreated: Long = System.currentTimeMillis()
)

/**
 * One panel's normalized bounding box within a page (0f..1f range),
 * used by Guided View (V2). Kept in the model layer now so the reader
 * architecture doesn't need to change when Guided View ships.
 */
data class Panel(
    val id: Long = 0,
    val comicId: Long,
    val pageIndex: Int,
    val order: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * A detected dialogue region. Coordinates are normalized to the source page so
 * they remain valid at every screen size and orientation.
 */
data class Bubble(
    val id: Long = 0,
    val comicId: Long,
    val pageIndex: Int,
    val order: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String = "",
    /** Transparent PNG containing only the segmented balloon pixels. */
    val maskPath: String = "",
    val confidence: Float = 1f,
    /** True after the reader has explicitly corrected this balloon's order. */
    val isManual: Boolean = false
)

enum class PanelPageStatus {
    PROCESSING,
    AI_DETECTED,
    NEEDS_REVIEW,
    MANUAL,
    FAILED
}

data class PanelAnalysisProgress(
    val comicId: Long,
    val analyzedPages: Int = 0,
    val reviewPages: Int = 0,
    val processingPages: Int = 0
)