package com.comicreader.app.data.comic

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.comicreader.app.data.cbr.CbrExtractor
import com.comicreader.app.data.cbz.CbzExtractor
import com.comicreader.app.data.pdf.PdfExtractor
import java.util.Locale

enum class ComicFormat { CBZ, CBR, PDF }

data class ComicImportResult(
    val coverPath: String,
    val pageCount: Int
)

data class ComicPageRef(
    val index: Int,
    val format: ComicFormat,
    val entryName: String? = null
)

class ComicContentManager(
    private val context: Context,
    private val cbzExtractor: CbzExtractor
) {
    private val cbrExtractor = CbrExtractor(context)
    private val pdfExtractor = PdfExtractor(context)

    suspend fun prepareComic(uri: Uri, comicCacheKey: String): ComicImportResult =
        when (detectFormat(uri)) {
            ComicFormat.CBZ -> cbzExtractor.prepareComic(uri, comicCacheKey)
            ComicFormat.CBR -> cbrExtractor.prepareComic(uri, comicCacheKey)
            ComicFormat.PDF -> pdfExtractor.prepareComic(uri, comicCacheKey)
        }

    suspend fun listPages(uri: Uri, comicCacheKey: String): List<ComicPageRef> =
        when (detectFormat(uri)) {
            ComicFormat.CBZ -> cbzExtractor.listPages(uri, comicCacheKey)
            ComicFormat.CBR -> cbrExtractor.listPages(uri, comicCacheKey)
            ComicFormat.PDF -> pdfExtractor.listPages(uri, comicCacheKey)
        }

    suspend fun loadPage(
        uri: Uri,
        comicCacheKey: String,
        page: ComicPageRef
    ): String = when (page.format) {
        ComicFormat.CBZ -> cbzExtractor.extractPage(
            uri, comicCacheKey, page.index,
            requireNotNull(page.entryName) { "CBZ page is missing its archive entry" }
        )
        ComicFormat.CBR -> cbrExtractor.extractPage(
            uri, comicCacheKey, page.index,
            requireNotNull(page.entryName) { "CBR page is missing its archive entry" }
        )
        ComicFormat.PDF -> pdfExtractor.renderPage(uri, comicCacheKey, page.index)
    }

    fun clearComic(comicCacheKey: String) {
        cbzExtractor.clearComic(comicCacheKey)
        cbrExtractor.clearComic(comicCacheKey)
        pdfExtractor.clearComic(comicCacheKey)
    }

    fun displayName(uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) {
                cursor.getString(column)?.takeIf(String::isNotBlank)?.let { return it }
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun detectFormat(uri: Uri): ComicFormat {
        val name = displayName(uri).lowercase(Locale.ROOT)
        when (name.substringAfterLast('.', "")) {
            "cbz", "zip" -> return ComicFormat.CBZ
            "cbr", "rar" -> return ComicFormat.CBR
            "pdf" -> return ComicFormat.PDF
        }

        when (context.contentResolver.getType(uri)?.lowercase(Locale.ROOT)) {
            "application/pdf" -> return ComicFormat.PDF
            "application/zip", "application/vnd.comicbook+zip" -> return ComicFormat.CBZ
            "application/vnd.rar", "application/x-rar-compressed",
            "application/vnd.comicbook-rar", "application/x-cbr" -> return ComicFormat.CBR
        }

        val header = context.contentResolver.openInputStream(uri)?.use { input ->
            ByteArray(8).also { input.read(it) }
        } ?: byteArrayOf()
        return when {
            header.size >= 5 && header.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray()) ->
                ComicFormat.PDF
            header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() ->
                ComicFormat.CBZ
            header.size >= 7 && header.copyOfRange(0, 7).contentEquals(
                byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
            ) -> ComicFormat.CBR
            header.size >= 8 && header.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
            ) -> ComicFormat.CBR
            else -> error("Unsupported file. Choose a CBZ, CBR, or PDF comic.")
        }
    }
}

internal val COMIC_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

internal object NaturalOrderComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val ax = splitAlphaNum(a)
        val bx = splitAlphaNum(b)
        var index = 0
        while (index < ax.size && index < bx.size) {
            val left = ax[index]
            val right = bx[index]
            val comparison = if (left.first().isDigit() && right.first().isDigit()) {
                left.toLongOrNull()?.compareTo(right.toLongOrNull() ?: 0L)
                    ?: left.compareTo(right)
            } else {
                left.compareTo(right, ignoreCase = true)
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
        var digits = value.first().isDigit()
        value.forEach { character ->
            if (character.isDigit() != digits) {
                result += current.toString()
                current = StringBuilder()
                digits = character.isDigit()
            }
            current.append(character)
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}
