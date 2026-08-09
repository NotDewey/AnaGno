package com.comicreader.app.ui.collections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comicreader.app.data.repository.CollectionRepository
import com.comicreader.app.domain.model.ComicCollection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class CollectionDetailUiState(
    val collection: ComicCollection? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class CollectionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    collectionRepository: CollectionRepository
) : ViewModel() {

    private val collectionId: Long =
        checkNotNull(savedStateHandle["collectionId"])

    val uiState: StateFlow<CollectionDetailUiState> =
        collectionRepository.observeCollection(collectionId)
            .map { collection ->
                CollectionDetailUiState(
                    collection = collection,
                    isLoading = false
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = CollectionDetailUiState()
            )
}
