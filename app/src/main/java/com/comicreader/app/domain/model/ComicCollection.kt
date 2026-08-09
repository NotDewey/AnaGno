package com.comicreader.app.domain.model

/**
 * Determines how a collection is presented on the Collections screen.
 */
enum class CollectionLayoutStyle {
    HAND_FAN,
    RIBBON_SPREAD,
    HERO_MOSAIC
}

/**
 * A user-created, ordered group of comics.
 *
 * coverComicId points at one of the comics in the collection. If that comic is
 * later removed from the library, Room clears the stored cover and the UI falls
 * back to the first remaining comic.
 */
data class ComicCollection(
    val id: Long = 0,
    val name: String,
    val comics: List<Comic> = emptyList(),
    val coverComicId: Long? = null,
    val layoutStyle: CollectionLayoutStyle =
        CollectionLayoutStyle.HAND_FAN,
    val dateCreated: Long = System.currentTimeMillis(),
    val dateUpdated: Long = System.currentTimeMillis()
) {
    val coverComic: Comic?
        get() = comics.firstOrNull { it.id == coverComicId }
            ?: comics.firstOrNull()
}