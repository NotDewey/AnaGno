package com.comicreader.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.comicreader.app.data.cbz.CbzExtractor
import com.comicreader.app.data.comic.ComicContentManager
import com.comicreader.app.data.comic.ComicPageRef
import com.comicreader.app.data.local.dao.BookmarkDao
import com.comicreader.app.data.local.dao.BubbleDao
import com.comicreader.app.data.local.dao.ComicDao
import com.comicreader.app.data.local.dao.PanelDao
import com.comicreader.app.data.local.entities.toDomain
import com.comicreader.app.data.local.entities.toEntity
import com.comicreader.app.data.local.entities.PanelPageStateEntity
import com.comicreader.app.data.panel.PanelDetectionScheduler
import com.comicreader.app.data.panel.PanelDetector
import com.comicreader.app.domain.model.Bookmark
import com.comicreader.app.domain.model.Bubble
import com.comicreader.app.domain.model.Comic
import com.comicreader.app.domain.model.Panel
import com.comicreader.app.domain.model.PanelAnalysisProgress
import com.comicreader.app.domain.model.PanelPageStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComicRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val comicDao: ComicDao,
    private val bookmarkDao: BookmarkDao,
    private val bubbleDao: BubbleDao,
    private val panelDao: PanelDao,
    private val extractor: CbzExtractor
) {

    private val content = ComicContentManager(context, extractor)

    fun observeLibrary(): Flow<List<Comic>> =
        comicDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeCurrentlyReading(): Flow<List<Comic>> =
        comicDao.observeCurrentlyReading()
            .map { list ->
                list.map { entity -> entity.toDomain() }
            }

    fun observeFinishedAndRated(): Flow<List<Comic>> =
        comicDao.observeFinishedAndRated()
            .map { list ->
                list.map { entity -> entity.toDomain() }
            }

    fun search(query: String): Flow<List<Comic>> =
        comicDao.search(query).map { list -> list.map { it.toDomain() } }

    suspend fun getComic(id: Long): Comic? = comicDao.getById(id)?.toDomain()

    /**
     * Imports a CBZ, CBR, or PDF without copying the source document.
     * Reimporting the same URI repairs an older library entry.
     */
    suspend fun importComic(
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): Comic = withContext(Dispatchers.IO) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val uriString = uri.toString()
        val existing = comicDao.getByUri(uriString)
        val cacheKey = sha1(uriString)
        onProgress(0.1f)
        val prepared = content.prepareComic(
            uri = uri,
            comicCacheKey = cacheKey
        )
        onProgress(1f)

        val fileName = content.displayName(uri)
        val title = fileName
            .substringBeforeLast('.', missingDelimiterValue = fileName)
            .ifBlank { "Untitled" }

        if (existing != null) {
            val repaired = existing.copy(
                title = title,
                coverPagePath = prepared.coverPath,
                pageCount = prepared.pageCount
            )
            comicDao.update(repaired)
            return@withContext repaired.toDomain()
        }

        val comic = Comic(
            title = title,
            uri = uriString,
            coverPagePath = prepared.coverPath,
            pageCount = prepared.pageCount
        )
        val id = comicDao.insert(comic.toEntity())
        comic.copy(id = id)
    }

    suspend fun importMultiple(
        uris: List<Uri>,
        onProgress: (Float) -> Unit = {}
    ): List<Comic> {
        if (uris.isEmpty()) return emptyList()
        return uris.mapIndexed { index, uri ->
            importComic(uri) { fileProgress ->
                onProgress((index + fileProgress) / uris.size.toFloat())
            }
        }
    }

    suspend fun getPageRefs(comic: Comic): List<ComicPageRef> =
        content.listPages(
            uri = Uri.parse(comic.uri),
            comicCacheKey = sha1(comic.uri)
        )

    suspend fun loadPage(comic: Comic, page: ComicPageRef): String =
        content.loadPage(
            uri = Uri.parse(comic.uri),
            comicCacheKey = sha1(comic.uri),
            page = page
        )

    /** Reconnects an existing library entry to a moved/reselected source file. */
    suspend fun relinkComic(comicId: Long, newUri: Uri): Comic = withContext(Dispatchers.IO) {
        context.contentResolver.takePersistableUriPermission(
            newUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val existing = comicDao.getById(comicId)
            ?: error("Comic not found")
        val newUriString = newUri.toString()
        val oldCacheKey = sha1(existing.uri)
        val newCacheKey = sha1(newUriString)
        val prepared = content.prepareComic(
            uri = newUri,
            comicCacheKey = newCacheKey
        )

        val updated = existing.copy(
            uri = newUriString,
            coverPagePath = prepared.coverPath,
            pageCount = prepared.pageCount,
            lastReadPage = existing.lastReadPage.coerceIn(
                0,
                (prepared.pageCount - 1).coerceAtLeast(0)
            )
        )
        comicDao.update(updated)

        if (oldCacheKey != newCacheKey) {
            content.clearComic(oldCacheKey)
        }
        updated.toDomain()
    }

    suspend fun updateProgress(comicId: Long, page: Int) =
        comicDao.updateProgress(comicId, page)

    suspend fun setFavorite(comicId: Long, isFavorite: Boolean) =
        comicDao.setFavorite(comicId, isFavorite)

    suspend fun markFinished(comicId: Long): Comic? {
        comicDao.markFinished(comicId)
        return getComic(comicId)
    }

    /**
     * Removes a comic from Home without deleting it or resetting progress.
     * Opening or advancing the comic later records a new dateLastOpened and
     * returns it to Continue Reading.
     */
    suspend fun removeFromCurrentlyReading(comicId: Long) {
        comicDao.removeFromCurrentlyReading(comicId)
    }

    suspend fun markUnfinished(comicId: Long): Comic? {
        comicDao.markUnfinished(comicId)
        return getComic(comicId)
    }

    suspend fun setRating(
        comicId: Long,
        rating: Float
    ): Comic? {
        val normalizedRating =
            rating.coerceIn(1f, 5f)

        val comic =
            getComic(comicId)
                ?: return null

        if (!comic.isFinished) {
            comicDao.markFinished(comicId)
        }

        comicDao.setRating(
            comicId = comicId,
            rating = normalizedRating
        )

        return getComic(comicId)
    }

    suspend fun clearRating(comicId: Long): Comic? {
        comicDao.clearRating(comicId)
        return getComic(comicId)
    }

    suspend fun renameComic(comicId: Long, title: String) {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "Title cannot be empty" }
        comicDao.rename(comicId, cleanTitle)
    }

    suspend fun deleteComic(comic: Comic) {
        PanelDetectionScheduler.cancel(context, comic.id)
        comicDao.delete(comic.toEntity())
        content.clearComic(sha1(comic.uri))
        File(context.filesDir, "bubble_masks/${comic.id}").deleteRecursively()
    }

    fun observeBookmarks(comicId: Long): Flow<List<Bookmark>> =
        bookmarkDao.observeForComic(comicId).map { list -> list.map { it.toDomain() } }

    suspend fun addBookmark(comicId: Long, pageIndex: Int, label: String? = null) =
        bookmarkDao.insert(Bookmark(comicId = comicId, pageIndex = pageIndex, label = label).toEntity())

    suspend fun removeBookmark(bookmark: Bookmark) = bookmarkDao.delete(bookmark.toEntity())

    suspend fun getBubbles(comicId: Long, pageIndex: Int): List<Bubble> =
        bubbleDao.getForPage(comicId, pageIndex).map { it.toDomain() }

    suspend fun saveBubbles(comicId: Long, pageIndex: Int, bubbles: List<Bubble>) {
        val normalized = bubbles.mapIndexed { index, bubble ->
            bubble.copy(
                id = 0,
                comicId = comicId,
                pageIndex = pageIndex,
                order = index,
                left = bubble.left.coerceIn(0f, 1f),
                top = bubble.top.coerceIn(0f, 1f),
                right = bubble.right.coerceIn(0f, 1f),
                bottom = bubble.bottom.coerceIn(0f, 1f)
            ).toEntity()
        }
        bubbleDao.replaceForPage(comicId, pageIndex, normalized)
    }

    suspend fun getPanels(comicId: Long, pageIndex: Int): List<Panel> =
        panelDao.getForPage(comicId, pageIndex).map { it.toDomain() }

    fun observePanelProgress(): Flow<List<PanelAnalysisProgress>> =
        panelDao.observeAllProgress().map { rows ->
            rows.map { row ->
                PanelAnalysisProgress(
                    comicId = row.comicId,
                    analyzedPages = row.analyzedPages.toInt(),
                    reviewPages = row.reviewPages.toInt(),
                    processingPages = row.processingPages.toInt()
                )
            }
        }

    fun observeReviewPages(comicId: Long): Flow<List<Int>> =
        panelDao.observeReviewPages(comicId)

    /** Makes this the only comic being analyzed and records it as last opened. */
    suspend fun activatePanelDetection(comicId: Long) = withContext(Dispatchers.IO) {
        val comics = comicDao.getAll()
        val active = comics.firstOrNull { it.id == comicId } ?: return@withContext
        comicDao.updateProgress(comicId, active.lastReadPage)
        comics.forEach { comic ->
            if (comic.id == comicId) PanelDetectionScheduler.enqueue(context, comic.id)
            else PanelDetectionScheduler.cancel(context, comic.id)
        }
    }

    /** On app start, resumes only the most recently opened comic and cancels the rest. */
    suspend fun resumeLastOpenedPanelDetection() = withContext(Dispatchers.IO) {
        val comics = comicDao.getAll()
        val lastOpened = comics
            .filter { it.dateLastOpened != null }
            .maxByOrNull { it.dateLastOpened ?: Long.MIN_VALUE }
        comics.forEach { comic ->
            if (comic.id == lastOpened?.id) PanelDetectionScheduler.enqueue(context, comic.id)
            else PanelDetectionScheduler.cancel(context, comic.id)
        }
    }

    suspend fun savePanels(comicId: Long, pageIndex: Int, panels: List<Panel>) {
        val normalized = panels.mapIndexed { index, panel ->
            panel.copy(
                id = 0,
                comicId = comicId,
                pageIndex = pageIndex,
                order = index,
                left = panel.left.coerceIn(0f, 1f),
                top = panel.top.coerceIn(0f, 1f),
                right = panel.right.coerceIn(0f, 1f),
                bottom = panel.bottom.coerceIn(0f, 1f)
            ).toEntity()
        }
        panelDao.replaceManualForPage(comicId, pageIndex, normalized)
    }

    /** Processes a bounded batch and returns true when another batch is needed. */
    suspend fun detectNextPanelBatch(
        comicId: Long,
        detector: PanelDetector,
        batchSize: Int = 12
    ): Boolean = withContext(Dispatchers.IO) {
        val comic = getComic(comicId) ?: return@withContext false
        val pages = getPageRefs(comic)
        if (pages.isEmpty()) return@withContext false

        val states = panelDao.getStatesForComic(comicId).associateBy { it.pageIndex }
        val finished = setOf(
            PanelPageStatus.AI_DETECTED.name,
            PanelPageStatus.NEEDS_REVIEW.name,
            PanelPageStatus.MANUAL.name,
            PanelPageStatus.FAILED.name
        )
        val current = comic.lastReadPage.coerceIn(0, pages.lastIndex)
        val priority = buildList {
            add(current)
            for (distance in 1..5) add(current + distance)
            for (distance in 1..2) add(current - distance)
            addAll(pages.indices)
        }.distinct().filter { it in pages.indices }

        val pending = priority.filter { states[it]?.status !in finished }
        pending.take(batchSize).forEach { pageIndex ->
            if (!panelDao.beginDetectionUnlessManual(comicId, pageIndex)) return@forEach
            try {
                val pagePath = loadPage(comic, pages[pageIndex])
                val detected = detector.detect(pagePath, comicId, pageIndex)
                val status = when {
                    detected.isEmpty() -> PanelPageStatus.FAILED
                    detected.looksSuspicious() -> PanelPageStatus.NEEDS_REVIEW
                    else -> PanelPageStatus.AI_DETECTED
                }
                val entities = detected.map { it.copy(id = 0).toEntity() }
                panelDao.replaceDetectedUnlessManual(
                    comicId = comicId,
                    pageIndex = pageIndex,
                    panels = entities,
                    finalState = PanelPageStateEntity(
                        comicId = comicId,
                        pageIndex = pageIndex,
                        status = status.name,
                        panelCount = entities.size,
                        errorMessage = if (detected.isEmpty()) "No panels detected" else null
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                panelDao.markFailedUnlessManual(
                    comicId,
                    pageIndex,
                    error.message ?: "Page analysis failed"
                )
            }
        }
        pending.size > batchSize
    }

    private fun List<Panel>.looksSuspicious(): Boolean {
        if (size > 12) return true
        val areas = map { panel ->
            ((panel.right - panel.left) * (panel.bottom - panel.top)).coerceAtLeast(0f)
        }
        val totalArea = areas.sum()
        if (totalArea < 0.42f || totalArea > 1.35f) return true
        if (size == 1 && totalArea < 0.60f) return true

        for (first in indices) {
            for (second in first + 1 until size) {
                val a = this[first]
                val b = this[second]
                val intersectionWidth = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
                val intersectionHeight = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
                val intersection = intersectionWidth * intersectionHeight
                val union = areas[first] + areas[second] - intersection
                if (union > 0f && intersection / union > 0.35f) return true
            }
        }
        return false
    }

    private fun sha1(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}