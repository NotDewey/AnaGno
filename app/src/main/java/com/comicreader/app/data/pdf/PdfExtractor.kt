package com.comicreader.app.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.comicreader.app.data.comic.ComicFormat
import com.comicreader.app.data.comic.ComicImportResult
import com.comicreader.app.data.comic.ComicPageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

private const val PAGE_SCALE = 2.5f
private const val PAGE_MAX_DIMENSION = 2400
private const val COVER_MAX_DIMENSION = 800
private const val MAX_CACHED_PAGES = 12

/** Renders PDF pages on-device with Android's PdfRenderer. */
class PdfExtractor(private val context: Context) {
    private val renderMutex = Mutex()

    suspend fun prepareComic(uri: Uri, comicCacheKey: String): ComicImportResult =
        withContext(Dispatchers.IO) {
            withRenderer(uri) { renderer ->
                require(renderer.pageCount > 0) { "This PDF has no pages" }
                val cover = createCoverIfNeeded(renderer, comicCacheKey)
                clearLegacyArchiveCopy(comicCacheKey)
                ComicImportResult(cover.absolutePath, renderer.pageCount)
            }
        }

    suspend fun listPages(uri: Uri, comicCacheKey: String): List<ComicPageRef> =
        withContext(Dispatchers.IO) {
            withRenderer(uri) { renderer ->
                clearLegacyArchiveCopy(comicCacheKey)
                List(renderer.pageCount) { ComicPageRef(it, ComicFormat.PDF) }
            }
        }

    suspend fun renderPage(uri: Uri, comicCacheKey: String, pageIndex: Int): String =
        withContext(Dispatchers.IO) {
            renderMutex.withLock {
                val pageDir = pageCacheDir(comicCacheKey)
                val output = File(pageDir, "page_%04d.jpg".format(pageIndex))
                if (!output.isFile || output.length() == 0L) {
                    val partial = File(pageDir, "${output.name}.part")
                    partial.delete()
                    output.delete()
                    try {
                        withRenderer(uri) { renderer ->
                            require(pageIndex in 0 until renderer.pageCount) { "PDF page is out of range" }
                            renderer.openPage(pageIndex).use { page ->
                                val (width, height) = scaledSize(
                                    page.width,
                                    page.height,
                                    PAGE_SCALE,
                                    PAGE_MAX_DIMENSION
                                )
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    FileOutputStream(partial).use { stream ->
                                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, stream))
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }
                        if (!partial.renameTo(output)) error("Couldn't finish caching page ${pageIndex + 1}")
                    } finally {
                        partial.delete()
                    }
                }
                output.setLastModified(System.currentTimeMillis())
                trimPageCache(pageDir, output)
                output.absolutePath
            }
        }

    fun clearComic(comicCacheKey: String) {
        pageCacheDir(comicCacheKey).deleteRecursively()
        coverFile(comicCacheKey).delete()
        clearLegacyArchiveCopy(comicCacheKey)
    }

    private fun createCoverIfNeeded(renderer: PdfRenderer, key: String): File {
        val cover = coverFile(key)
        if (cover.isFile && cover.length() > 0L) return cover
        val partial = File(cover.parentFile, "${cover.name}.part")
        partial.delete()
        cover.delete()
        try {
            renderer.openPage(0).use { page ->
                val (width, height) = scaledSize(page.width, page.height, 1f, COVER_MAX_DIMENSION)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    FileOutputStream(partial).use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream))
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            if (!partial.renameTo(cover)) error("Couldn't finish saving the PDF cover")
            return cover
        } finally {
            partial.delete()
        }
    }

    private fun <T> withRenderer(uri: Uri, block: (PdfRenderer) -> T): T {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Unable to open the selected PDF")
        try {
            PdfRenderer(descriptor).use { renderer -> return block(renderer) }
        } catch (error: Exception) {
            throw IllegalStateException(
                "Couldn't read this PDF. It may be password-protected, damaged, moved, or its permission may have expired.",
                error
            )
        } finally {
            runCatching { descriptor.close() }
        }
    }

    private fun scaledSize(width: Int, height: Int, scale: Float, maxDimension: Int): Pair<Int, Int> {
        val requestedScale = max(scale, 1f)
        val cappedScale = min(requestedScale, maxDimension.toFloat() / max(width, height).coerceAtLeast(1))
        return max(1, (width * cappedScale).toInt()) to max(1, (height * cappedScale).toInt())
    }

    private fun trimPageCache(pageDir: File, keep: File) {
        pageDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedByDescending { if (it == keep) Long.MAX_VALUE else it.lastModified() }
            ?.drop(MAX_CACHED_PAGES)?.forEach(File::delete)
    }

    private fun coverFile(key: String): File =
        File(context.filesDir, "comic-covers").apply { mkdirs() }.resolve("$key.jpg")

    private fun pageCacheDir(key: String): File =
        File(context.cacheDir, "comic-pages/$key").apply { mkdirs() }

    private fun clearLegacyArchiveCopy(key: String) {
        File(context.filesDir, "comics/$key").deleteRecursively()
    }
}
