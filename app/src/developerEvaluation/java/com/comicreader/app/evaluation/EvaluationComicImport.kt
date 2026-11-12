package com.comicreader.app.evaluation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.comicreader.app.data.repository.ComicRepository
import com.comicreader.app.domain.model.Comic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/** Only imports explicitly selected documents into this developer application's library. */
object EvaluationComicImport {
    suspend fun importSelected(context: Context, repository: ComicRepository, uri: Uri?): Comic? {
        if (uri == null) return null
        check(context.packageName in setOf("com.comicreader.app.evaluation", "com.comicreader.app.bubblevalidation")) {
            "Comic import is restricted to the isolated evaluation app."
        }
        return withContext(Dispatchers.IO) {
            importSelected(
                uri = uri,
                isSupported = { selected ->
                    val resolver = context.contentResolver
                    val name = resolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                    } ?: selected.lastPathSegment.orEmpty()
                    name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("cbz", "zip", "cbr", "rar", "pdf") ||
                        resolver.getType(selected)?.lowercase(Locale.ROOT) in setOf(
                            "application/pdf", "application/zip", "application/vnd.comicbook+zip",
                            "application/vnd.rar", "application/x-rar-compressed", "application/vnd.comicbook-rar", "application/x-cbr"
                        )
                },
                persistRead = { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) },
                requireReadable = { requireReadable(context, it) },
                importComic = { repository.importComic(it) }
            )
        }
    }

    /** Callback seam for QA; cancellation never invokes any document or repository callback. */
    suspend fun importSelected(
        uri: Uri?,
        isSupported: (Uri) -> Boolean,
        persistRead: (Uri) -> Unit,
        requireReadable: (Uri) -> Unit,
        importComic: suspend (Uri) -> Comic
    ): Comic? {
        if (uri == null) return null
        require(uri.scheme == "content") { "Choose the original CBZ, CBR or PDF using Import CBZ/CBR/PDF." }
        try {
            require(isSupported(uri)) { "Unsupported document. Choose a CBZ, CBR or PDF using Import CBZ/CBR/PDF." }
            persistRead(uri)
            requireReadable(uri)
            return importComic(uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SecurityException) {
            throw IllegalStateException("Read permission unavailable or not persistent. Use Import CBZ/CBR/PDF to select the original file again from a provider that grants persistent read access.", error)
        } catch (error: IOException) {
            throw IllegalStateException("Document unavailable or unreadable. Make the original file available locally, then select it again using Import CBZ/CBR/PDF.", error)
        }
    }

    fun requireReadable(context: Context, uri: Uri) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { }
                ?: throw IOException("Document provider did not open the file")
        } catch (error: SecurityException) {
            throw IllegalStateException("Read permission was revoked. Use Import CBZ/CBR/PDF to select the original file again in Anagno Evaluation.", error)
        } catch (error: IOException) {
            throw IllegalStateException("Document unavailable. Make the original file available locally and select it again using Import CBZ/CBR/PDF.", error)
        }
    }
}
