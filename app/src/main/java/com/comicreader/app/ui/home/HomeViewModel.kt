package com.comicreader.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comicreader.app.data.repository.ComicRepository
import com.comicreader.app.domain.model.Comic
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val comics: List<Comic> = emptyList(),
    val totalReadingCount: Int = 0,
    val searchQuery: String = ""
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ComicRepository
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")

    val uiState: StateFlow<HomeUiState> =
        combine(
            repository.observeCurrentlyReading(),
            searchQuery
        ) { comics, query ->
            val cleanQuery = query.trim()

            val visibleComics =
                if (cleanQuery.isEmpty()) {
                    comics
                } else {
                    comics.filter { comic ->
                        comic.title.contains(
                            cleanQuery,
                            ignoreCase = true
                        ) ||
                                comic.series?.contains(
                                    cleanQuery,
                                    ignoreCase = true
                                ) == true
                    }
                }

            HomeUiState(
                comics = visibleComics,
                totalReadingCount = comics.size,
                searchQuery = query
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState()
        )

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun markFinished(comics: List<Comic>) {
        if (comics.isEmpty()) {
            return
        }

        viewModelScope.launch {
            comics.forEach { comic ->
                repository.markFinished(comic.id)
            }
        }
    }

    fun removeFromHome(comics: List<Comic>) {
        if (comics.isEmpty()) {
            return
        }

        viewModelScope.launch {
            comics.forEach { comic ->
                repository.removeFromCurrentlyReading(comic.id)
            }
        }
    }
}
