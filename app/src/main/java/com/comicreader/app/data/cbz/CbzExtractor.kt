package com.comicreader.app.data.cbz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.comicreader.app.data.comic.ComicFormat
import com.comicreader.app.data.comic.ComicImportResult
import com.comicreader.app.data.comic.ComicPageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
private const val COPY_BUFFER_SIZE = 1024 * 1024
private const val COVER_MAX_DIMENSION = 800
private const val MAX_CACHED_PAGES = 20

/**
 * Reads a CBZ directly from its persisted Storage Access Framework URI.
 *
 * The original archive stays in Downloads (or whichever provider supplied the
 * URI). The app stores only a small cover thumbnail and a bounded temporary
 * cache of pages that have recently been viewed.
 */
class CbzExtractor(private val context: Context) {

    private val extractionMutex = Mutex()

    /** Indexes the archive and creates a small persistent cover. No CBZ copy is made. */
    suspend fun prepareComic(
        uri: Uri,
        comicCacheKey: String
    ): ComicImportResult = withContext(Dispatchers.IO) {
        val entries = imageEntryNames(uri)
        require(entries.isNotEmpty()) { "This CBZ does not contain any supported images" }

        val cover = createCoverIfNeeded(uri, entries.first(), comicCacheKey)

        // Remove the multi-gigabyte archive copy created by the older app
        // architecture only after direct URI access has succeeded.
        clearLegacyArchiveCopy(comicCacheKey)

        ComicImportResult(
            coverPath = cover.absolutePath,
            pageCount = entries.size
        )
    }

    /** Reads only the ZIP directory and returns lightweight page references. */
    suspend fun listPages(uri: Uri, comicCacheKey: String): List<ComicPageRef> = withContext(Dispatchers.IO) {
        val entries = imageEntryNames(uri)
        clearLegacyArchiveCopy(comicCacheKey)
        entries.mapIndexed { index, entryName ->
            ComicPageRef(index = index, format = ComicFormat.CBZ, entryName = entryName)
        }
    }

    /**
     * Extracts one requested page into cacheDir. The cache is disposable and
     * never contains more than [MAX_CACHED_PAGES] page images per comic.
     */
    suspend fun extractPage(
        uri: Uri,
        comicCacheKey: String,
        pageIndex: Int,
        entryName: String
    ): String = withContext(Dispatchers.IO) {
        extractionMutex.withLock {
            val pageDir = pageCacheDir(comicCacheKey)
            val extension = entryName.substringAfterLast('.', "jpg")
                .lowercase(Locale.ROOT)
                .takeIf { it in IMAGE_EXTENSIONS } ?: "jpg"
            val output = File(pageDir, "page_%04d.%s".format(pageIndex, extension))

            if (!output.isFile || output.length() == 0L) {
                val partial = File(pageDir, "${output.name}.part")
                partial.delete()
                output.delete()

                try {
                    withZip(uri) { zip ->
                        val entry = zip.getEntry(entryName)
                            ?: error("Page ${pageIndex + 1} is missing from the CBZ")
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(partial).use { outputStream ->
                                input.copyTo(outputStream, COPY_BUFFER_SIZE)
                            }
                        }
                    }
                    if (!partial.renameTo(output)) {
                        error("Couldn't finish caching page ${pageIndex + 1}")
                    }
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

    private fun imageEntryNames(uri: Uri): List<String> = withZip(uri) { zip ->
        zip.entries.toList()
            .filter { entry ->
                !entry.isDirectory &&
                        entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS
            }
            .map { it.name }
            .sortedWith(NaturalOrderComparator)
    }

    private fun createCoverIfNeeded(
        uri: Uri,
        coverEntryName: String,
        comicCacheKey: String
    ): File {
        val cover = coverFile(comicCacheKey)
        if (cover.isFile && cover.length() > 0L) return cover

        val partial = File(cover.parentFile, "${cover.name}.part")
        partial.delete()
        cover.delete()

        try {
            withZip(uri) { zip ->
                val entry = zip.getEntry(coverEntryName) ?: error("Cover page is missing")

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                    "Cover image couldn't be decoded"
                }

                var sampleSize = 1
                while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > COVER_MAX_DIMENSION) {
                    sampleSize *= 2
                }

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = zip.getInputStream(entry).use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: error("Cover image couldn't be decoded")

                try {
                    FileOutputStream(partial).use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) {
                            "Cover image couldn't be saved"
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            }

            if (!partial.renameTo(cover)) error("Couldn't finish saving the cover")
            return cover
        } finally {
            partial.delete()
        }
    }

    /**
     * Opens a content URI as a random-access ZIP. Downloads/Documents providers
     * normally expose seekable descriptors. Cloud-only providers may not; those
     * should show the error and can receive a temporary-copy fallback later.
     */
    private fun <T> withZip(uri: Uri, block: (ZipFile) -> T): T {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Unable to open the selected CBZ")
        val input = FileInputStream(descriptor.fileDescriptor)
        val channel = input.channel

        try {
            return ZipFile.Builder()
                .setSeekableByteChannel(channel)
                .get()
                .use(block)
        } catch (error: Exception) {
            throw IllegalStateException(
                "Couldn't read this CBZ directly. The file may have moved, permission may have expired, or its storage provider may not support random access.",
                error
            )
        } finally {
            runCatching { channel.close() }
            runCatching { input.close() }
            runCatching { descriptor.close() }
        }
    }

    private fun trimPageCache(pageDir: File, keep: File) {
        val cachedPages = pageDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedByDescending { if (it == keep) Long.MAX_VALUE else it.lastModified() }
            .orEmpty()

        cachedPages.drop(MAX_CACHED_PAGES).forEach { it.delete() }
    }

    private fun coverFile(comicCacheKey: String): File {
        val coverDir = File(context.filesDir, "comic-covers").apply { mkdirs() }
        return File(coverDir, "$comicCacheKey.jpg")
    }

    private fun pageCacheDir(comicCacheKey: String): File =
        File(context.cacheDir, "comic-pages/$comicCacheKey").apply { mkdirs() }

    private fun clearLegacyArchiveCopy(comicCacheKey: String) {
        File(context.filesDir, "comics/$comicCacheKey").deleteRecursively()
    }
}

private object NaturalOrderComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val ax = splitAlphaNum(a)
        val bx = splitAlphaNum(b)
        var index = 0

        while (index < ax.size && index < bx.size) {
            val comparison = if (ax[index].first().isDigit() && bx[index].first().isDigit()) {
                ax[index].toLongOrNull()?.compareTo(bx[index].toLongOrNull() ?: 0L)
                    ?: ax[index].compareTo(bx[index])
            } else {
                ax[index].compareTo(bx[index], ignoreCase = true)
            }
            if (comparison != 0) return comparison
            index++
        }
        return ax.size - bx.size
    }

    private fun splitAlphaNum(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var isDigit = value.first().isDigit()

        for (character in value) {
            if (current.isNotEmpty() && character.isDigit() != isDigit) {
                result += current.toString()
                current = StringBuilder()
                isDigit = character.isDigit()
            }
            current.append(character)
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}