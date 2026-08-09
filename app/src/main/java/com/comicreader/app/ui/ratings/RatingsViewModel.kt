package com.comicreader.app.ui.ratings

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

data class RatingsUiState(
    val comics: List<Comic> = emptyList(),
    val totalRatedCount: Int = 0,
    val searchQuery: String = ""
)

@HiltViewModel
class RatingsViewModel @Inject constructor(
    repository: ComicRepository
) : ViewModel() {

    private val searchQuery =
        MutableStateFlow("")

    val uiState: StateFlow<RatingsUiState> =
        combine(
            repository.observeFinishedAndRated(),
            searchQuery
        ) { comics, query ->
            val cleanQuery =
                query.trim()

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

            RatingsUiState(
                comics = visibleComics,
                totalRatedCount = comics.size,
                searchQuery = query
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RatingsUiState()
        )

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }
}
