package com.comicreader.app.ui.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comicreader.app.data.repository.ComicRepository
import com.comicreader.app.domain.model.Comic
import com.comicreader.app.domain.model.PanelAnalysisProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class LibrarySortOption(val label: String) {
    DATE_ADDED("Date added"),
    TITLE("Title"),
    LAST_OPENED("Last opened"),
    READING_PROGRESS("Reading progress"),
    PAGE_COUNT("Page count"),
    FILE_SIZE("File size"),
    RATING("Rating")
}

data class LibraryUiState(
    val comics: List<Comic> = emptyList(),
    val currentlyReading: List<Comic> = emptyList(),
    val totalLibraryCount: Int = 0,
    val totalReadingCount: Int = 0,
    val isImporting: Boolean = false,
    val importProgress: Float? = null,
    val searchQuery: String = "",
    val panelProgress: Map<Long, PanelAnalysisProgress> = emptyMap(),
    val errorMessage: String? = null
)

private data class LibraryContent(
    val comics: List<Comic>,
    val currentlyReading: List<Comic>,
    val totalLibraryCount: Int,
    val totalReadingCount: Int,
    val panelProgress: Map<Long, PanelAnalysisProgress>
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: ComicRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val isImporting = MutableStateFlow(false)
    private val importProgress = MutableStateFlow<Float?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)

    private val _sortOption = MutableStateFlow(LibrarySortOption.DATE_ADDED)
    val sortOption = _sortOption.asStateFlow()

    private val _sortDescending = MutableStateFlow(true)
    val sortDescending = _sortDescending.asStateFlow()

    private val fileSizes = MutableStateFlow<Map<Long, Long>>(emptyMap())

    private val unsortedComicsFlow = searchQuery.flatMapLatest { query ->
        if (query.isBlank()) repository.observeLibrary() else repository.search(query)
    }

    private val comicsFlow = combine(
        unsortedComicsFlow,
        _sortOption,
        _sortDescending,
        fileSizes
    ) { comics, option, descending, sizes ->
        sortComics(comics, option, descending, sizes)
    }

    private val currentlyReadingFlow = combine(
        repository.observeCurrentlyReading(),
        searchQuery
    ) { comics, query ->
        val cleanQuery = query.trim()

        if (cleanQuery.isEmpty()) {
            comics
        } else {
            comics.filter { comic ->
                comic.title.contains(cleanQuery, ignoreCase = true) ||
                        comic.series?.contains(cleanQuery, ignoreCase = true) == true
            }
        }
    }

    private val libraryContent = combine(
        comicsFlow,
        currentlyReadingFlow,
        repository.observeLibrary(),
        repository.observeCurrentlyReading(),
        repository.observePanelProgress()
    ) { comics, currentlyReading, fullLibrary, fullReading, progress ->
        LibraryContent(
            comics = comics,
            currentlyReading = currentlyReading,
            totalLibraryCount = fullLibrary.size,
            totalReadingCount = fullReading.size,
            panelProgress = progress.associateBy(PanelAnalysisProgress::comicId)
        )
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryContent,
        searchQuery,
        isImporting,
        importProgress,
        errorMessage
    ) { content, query, importing, progress, error ->
        LibraryUiState(
            comics = content.comics,
            currentlyReading = content.currentlyReading,
            totalLibraryCount = content.totalLibraryCount,
            totalReadingCount = content.totalReadingCount,
            isImporting = importing,
            importProgress = progress,
            searchQuery = query,
            panelProgress = content.panelProgress,
            errorMessage = error
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        LibraryUiState()
    )

    init {
        viewModelScope.launch {
            repository.resumeLastOpenedPanelDetection()
        }

        viewModelScope.launch {
            repository.observeLibrary().collectLatest { comics ->
                val validIds = comics.map(Comic::id).toSet()
                val existing = fileSizes.value.filterKeys { it in validIds }
                val missing = comics.filterNot { existing.containsKey(it.id) }

                val loaded = if (missing.isEmpty()) {
                    emptyMap()
                } else {
                    withContext(Dispatchers.IO) {
                        missing.associate { comic ->
                            comic.id to resolveFileSize(comic.uri)
                        }
                    }
                }

                fileSizes.value = existing + loaded
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun setSortOption(option: LibrarySortOption) {
        _sortOption.value = option
    }

    fun setSortDescending(descending: Boolean) {
        _sortDescending.value = descending
    }

    /** Called with URIs returned from the SAF "open document(s)" picker. */
    fun importComicFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            isImporting.value = true
            importProgress.value = 0f

            try {
                repository.importMultiple(uris) { progress ->
                    importProgress.value = progress
                }
            } catch (e: Exception) {
                errorMessage.value = "Couldn't import: ${e.message ?: e::class.simpleName}"
            } finally {
                isImporting.value = false
                importProgress.value = null
            }
        }
    }

    fun dismissError() {
        errorMessage.value = null
    }

    fun toggleFavorite(comic: Comic) {
        viewModelScope.launch { repository.setFavorite(comic.id, !comic.isFavorite) }
    }

    fun deleteComic(comic: Comic) {
        viewModelScope.launch { repository.deleteComic(comic) }
    }

    fun deleteComics(comics: List<Comic>) {
        if (comics.isEmpty()) return

        viewModelScope.launch {
            try {
                comics.forEach { repository.deleteComic(it) }
            } catch (error: Exception) {
                errorMessage.value = "Couldn't delete: ${error.message ?: error::class.simpleName}"
            }
        }
    }

    fun renameComic(comic: Comic, newTitle: String) {
        viewModelScope.launch {
            try {
                repository.renameComic(comic.id, newTitle)
            } catch (error: Exception) {
                errorMessage.value = "Couldn't rename: ${error.message ?: error::class.simpleName}"
            }
        }
    }

    fun markFinished(comics: List<Comic>) {
        if (comics.isEmpty()) return

        viewModelScope.launch {
            comics.forEach { comic ->
                repository.markFinished(comic.id)
            }
        }
    }

    fun removeFromContinueReading(comics: List<Comic>) {
        if (comics.isEmpty()) return

        viewModelScope.launch {
            comics.forEach { comic ->
                repository.removeFromCurrentlyReading(comic.id)
            }
        }
    }

    private fun sortComics(
        comics: List<Comic>,
        option: LibrarySortOption,
        descending: Boolean,
        fileSizes: Map<Long, Long>
    ): List<Comic> {
        val comparator: Comparator<Comic> =
            when (option) {
                LibrarySortOption.DATE_ADDED -> compareBy { it.dateAdded }
                LibrarySortOption.TITLE ->
                    compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                LibrarySortOption.LAST_OPENED ->
                    compareBy { it.dateLastOpened ?: Long.MIN_VALUE }
                LibrarySortOption.READING_PROGRESS ->
                    compareBy { comic ->
                        if (comic.pageCount <= 0) 0f
                        else (comic.lastReadPage.coerceIn(0, comic.pageCount - 1) + 1)
                            .toFloat() / comic.pageCount.toFloat()
                    }
                LibrarySortOption.PAGE_COUNT -> compareBy { it.pageCount }
                LibrarySortOption.FILE_SIZE ->
                    Comparator { first, second ->
                        compareFileSizes(
                            fileSizes[first.id] ?: -1L,
                            fileSizes[second.id] ?: -1L,
                            descending
                        )
                    }
                LibrarySortOption.RATING -> compareBy { it.userRating ?: 0f }
            }

        val directed =
            if (option == LibrarySortOption.FILE_SIZE) comparator
            else if (descending) comparator.reversed()
            else comparator

        return comics.sortedWith(
            directed.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        )
    }

    private fun compareFileSizes(
        firstSize: Long,
        secondSize: Long,
        descending: Boolean
    ): Int {
        val firstKnown = firstSize >= 0L
        val secondKnown = secondSize >= 0L

        if (firstKnown != secondKnown) return if (firstKnown) -1 else 1
        if (!firstKnown) return 0

        return if (descending) secondSize.compareTo(firstSize)
        else firstSize.compareTo(secondSize)
    }

    private fun resolveFileSize(uriString: String): Long {
        val uri = Uri.parse(uriString)

        val queried =
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                        cursor.getLong(index)
                    } else {
                        -1L
                    }
                } ?: -1L
            }.getOrDefault(-1L)

        if (queried >= 0L) return queried

        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }.getOrDefault(-1L)
    }

}