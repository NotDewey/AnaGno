package com.comicreader.app.data.cbr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.comicreader.app.data.comic.COMIC_IMAGE_EXTENSIONS
import com.comicreader.app.data.comic.ComicFormat
import com.comicreader.app.data.comic.ComicImportResult
import com.comicreader.app.data.comic.ComicPageRef
import com.comicreader.app.data.comic.NaturalOrderComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max

private const val ARCHIVE_BLOCK_SIZE = 64 * 1024L
private const val COVER_MAX_DIMENSION = 800
private const val MAX_CACHED_PAGES = 12

/** Reads RAR4 and RAR5 comic archives directly from a persisted SAF URI. */
class CbrExtractor(private val context: Context) {
    private val extractionMutex = Mutex()

    suspend fun prepareComic(uri: Uri, comicCacheKey: String): ComicImportResult =
        withContext(Dispatchers.IO) {
            val entries = imageEntryNames(uri)
            require(entries.isNotEmpty()) { "This CBR does not contain any supported images" }
            saveIndex(comicCacheKey, entries)
            val firstPage = extractPage(uri, comicCacheKey, 0, entries.first())
            val cover = createCoverIfNeeded(File(firstPage), comicCacheKey)
            clearLegacyArchiveCopy(comicCacheKey)
            ComicImportResult(cover.absolutePath, entries.size)
        }

    suspend fun listPages(uri: Uri, comicCacheKey: String): List<ComicPageRef> =
        withContext(Dispatchers.IO) {
            clearLegacyArchiveCopy(comicCacheKey)
            val entries = loadIndex(comicCacheKey) ?: imageEntryNames(uri).also {
                saveIndex(comicCacheKey, it)
            }
            entries.mapIndexed { index, name ->
                ComicPageRef(index, ComicFormat.CBR, name)
            }
        }

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
                .takeIf { it in COMIC_IMAGE_EXTENSIONS } ?: "jpg"
            val output = File(pageDir, "page_%04d.%s".format(pageIndex, extension))

            if (!output.isFile || output.length() == 0L) {
                val partial = File(pageDir, "${output.name}.part")
                partial.delete()
                output.delete()
                try {
                    withArchive(uri) { archive ->
                        var entry = Archive.readNextHeader(archive)
                        var found = false
                        while (entry != 0L) {
                            if (entryName(entry) == entryName) {
                                ParcelFileDescriptor.open(
                                    partial,
                                    ParcelFileDescriptor.MODE_CREATE or
                                            ParcelFileDescriptor.MODE_TRUNCATE or
                                            ParcelFileDescriptor.MODE_READ_WRITE
                                ).use { destination ->
                                    Archive.readDataIntoFd(archive, destination.fd)
                                }
                                found = true
                                break
                            }
                            Archive.readDataSkip(archive)
                            entry = Archive.readNextHeader(archive)
                        }
                        check(found) { "Page ${pageIndex + 1} is missing from the CBR" }
                    }
                    check(partial.isFile && partial.length() > 0L) {
                        "Page ${pageIndex + 1} could not be extracted"
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
        indexFile(comicCacheKey).delete()
        clearLegacyArchiveCopy(comicCacheKey)
    }

    private fun imageEntryNames(uri: Uri): List<String> = withArchive(uri) { archive ->
        val names = mutableListOf<String>()
        var entry = Archive.readNextHeader(archive)
        while (entry != 0L) {
            val name = entryName(entry)
            if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) in COMIC_IMAGE_EXTENSIONS) {
                names += name
            }
            Archive.readDataSkip(archive)
            entry = Archive.readNextHeader(archive)
        }
        names.sortedWith(NaturalOrderComparator)
    }

    private fun <T> withArchive(uri: Uri, block: (Long) -> T): T {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Unable to open the selected CBR")
        val archive = Archive.readNew()
        try {
            Archive.setCharset(archive, StandardCharsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatRar(archive)
            Archive.readSupportFormatRar5(archive)
            Archive.readOpenFd(archive, descriptor.fd, ARCHIVE_BLOCK_SIZE)
            return block(archive)
        } catch (error: Exception) {
            throw IllegalStateException(
                "Couldn't read this CBR. It may be encrypted, damaged, moved, or its permission may have expired.",
                error
            )
        } finally {
            runCatching { Archive.free(archive) }
            runCatching { descriptor.close() }
        }
    }

    private fun entryName(entry: Long): String =
        ArchiveEntry.pathnameUtf8(entry)
            ?: ArchiveEntry.pathname(entry)?.toString(StandardCharsets.UTF_8)
            ?: error("CBR contains an entry with no filename")

    private fun createCoverIfNeeded(pageFile: File, comicCacheKey: String): File {
        val cover = coverFile(comicCacheKey)
        if (cover.isFile && cover.length() > 0L) return cover
        val partial = File(cover.parentFile, "${cover.name}.part")
        partial.delete()
        cover.delete()
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(pageFile.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cover image couldn't be decoded" }
            var sample = 1
            while (max(bounds.outWidth / sample, bounds.outHeight / sample) > COVER_MAX_DIMENSION) sample *= 2
            val bitmap = BitmapFactory.decodeFile(
                pageFile.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: error("Cover image couldn't be decoded")
            try {
                FileOutputStream(partial).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream))
                }
            } finally {
                bitmap.recycle()
            }
            if (!partial.renameTo(cover)) error("Couldn't finish saving the cover")
            return cover
        } finally {
            partial.delete()
        }
    }

    private fun trimPageCache(pageDir: File, keep: File) {
        pageDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedByDescending { if (it == keep) Long.MAX_VALUE else it.lastModified() }
            ?.drop(MAX_CACHED_PAGES)?.forEach(File::delete)
    }

    /** Keeps the lightweight filename index so reopening a large CBR is instant. */
    private fun saveIndex(key: String, entries: List<String>) {
        val index = indexFile(key)
        val partial = File(index.parentFile, "${index.name}.part")
        partial.delete()
        try {
            DataOutputStream(FileOutputStream(partial)).use { output ->
                output.writeInt(entries.size)
                entries.forEach { name ->
                    val bytes = name.toByteArray(StandardCharsets.UTF_8)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
            }
            index.delete()
            if (!partial.renameTo(index)) error("Couldn't save the CBR page index")
        } finally {
            partial.delete()
        }
    }

    private fun loadIndex(key: String): List<String>? = runCatching {
        val index = indexFile(key)
        if (!index.isFile || index.length() == 0L) return null
        DataInputStream(FileInputStream(index)).use { input ->
            val count = input.readInt()
            require(count in 1..100_000)
            List(count) {
                val byteCount = input.readInt()
                require(byteCount in 1..1_048_576)
                val bytes = ByteArray(byteCount)
                input.readFully(bytes)
                bytes.toString(StandardCharsets.UTF_8)
            }
        }
    }.getOrNull()

    private fun coverFile(key: String): File =
        File(context.filesDir, "comic-covers").apply { mkdirs() }.resolve("$key.jpg")

    private fun pageCacheDir(key: String): File =
        File(context.cacheDir, "comic-pages/$key").apply { mkdirs() }

    private fun indexFile(key: String): File =
        File(context.filesDir, "comic-indexes").apply { mkdirs() }.resolve("$key.cbr-index")

    private fun clearLegacyArchiveCopy(key: String) {
        File(context.filesDir, "comics/$key").deleteRecursively()
    }
}
