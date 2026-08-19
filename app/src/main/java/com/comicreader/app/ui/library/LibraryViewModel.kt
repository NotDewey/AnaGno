package com.comicreader.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comicreader.app.data.repository.ComicRepository
import com.comicreader.app.domain.model.Comic
import com.comicreader.app.domain.model.PanelAnalysisProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val repository: ComicRepository
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val isImporting = MutableStateFlow(false)
    private val importProgress = MutableStateFlow<Float?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)

    private val comicsFlow = searchQuery.flatMapLatest { query ->
        if (query.isBlank()) repository.observeLibrary() else repository.search(query)
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
        viewModelScope.launch { repository.resumeLastOpenedPanelDetection() }
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
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
}
