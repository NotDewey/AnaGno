package com.comicreader.app.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comicreader.app.data.repository.CollectionRepository
import com.comicreader.app.data.repository.ComicRepository
import com.comicreader.app.domain.model.Comic
import com.comicreader.app.domain.model.ComicCollection
import com.comicreader.app.domain.model.CollectionLayoutStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CollectionCreationStep {
    NAME,
    COMICS,
    COVER,
    LAYOUT
}

data class CollectionDraft(
    val step: CollectionCreationStep = CollectionCreationStep.NAME,
    val name: String = "",
    val selectedComicIds: Set<Long> = emptySet(),
    val coverComicId: Long? = null,
    val layoutStyle: CollectionLayoutStyle? = null
)

data class CollectionsUiState(
    val collections: List<ComicCollection> = emptyList(),
    val totalCollectionCount: Int = 0,
    val libraryComics: List<Comic> = emptyList(),
    val searchQuery: String = "",
    val draft: CollectionDraft? = null,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val collectionRepository: CollectionRepository,
    comicRepository: ComicRepository
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val draft = MutableStateFlow<CollectionDraft?>(null)
    private val isSaving = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    /**
     * This deliberately uses ComicRepository.observeLibrary(), the exact same
     * live library source used by LibraryViewModel.
     */
    private val content = combine(
        collectionRepository.observeCollections(),
        comicRepository.observeLibrary()
    ) { collections, comics ->
        collections to comics
    }

    val uiState: StateFlow<CollectionsUiState> = combine(
        content,
        searchQuery,
        draft,
        isSaving,
        errorMessage
    ) { (allCollections, comics), query, currentDraft, saving, error ->
        val cleanQuery = query.trim()

        val visibleCollections = if (cleanQuery.isBlank()) {
            allCollections
        } else {
            allCollections.filter { collection ->
                collection.name.contains(cleanQuery, ignoreCase = true) ||
                        collection.comics.any { comic ->
                            comic.title.contains(cleanQuery, ignoreCase = true) ||
                                    comic.series?.contains(cleanQuery, ignoreCase = true) == true
                        }
            }
        }

        CollectionsUiState(
            collections = visibleCollections,
            totalCollectionCount = allCollections.size,
            libraryComics = comics,
            searchQuery = query,
            draft = currentDraft,
            isSaving = saving,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CollectionsUiState()
    )

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun startCreation() {
        if (isSaving.value) return
        draft.value = CollectionDraft()
    }

    fun cancelCreation() {
        if (isSaving.value) return
        draft.value = null
    }

    fun onDraftNameChanged(name: String) {
        draft.value = draft.value?.copy(name = name)
    }

    fun toggleDraftComic(comicId: Long) {
        val current = draft.value ?: return
        val selectedIds = current.selectedComicIds.toMutableSet()

        if (!selectedIds.add(comicId)) {
            selectedIds.remove(comicId)
        }

        draft.value = current.copy(
            selectedComicIds = selectedIds,
            coverComicId = current.coverComicId?.takeIf { it in selectedIds }
        )
    }

    fun selectDraftCover(comicId: Long) {
        val current = draft.value ?: return
        if (comicId !in current.selectedComicIds) return

        draft.value = current.copy(coverComicId = comicId)
    }

    fun selectDraftLayout(layoutStyle: CollectionLayoutStyle) {
        val current = draft.value ?: return
        draft.value = current.copy(layoutStyle = layoutStyle)
    }

    fun previousCreationStep() {
        val current = draft.value ?: return

        draft.value = when (current.step) {
            CollectionCreationStep.NAME -> current
            CollectionCreationStep.COMICS ->
                current.copy(step = CollectionCreationStep.NAME)
            CollectionCreationStep.COVER ->
                current.copy(step = CollectionCreationStep.COMICS)
            CollectionCreationStep.LAYOUT ->
                current.copy(step = CollectionCreationStep.COVER)
        }
    }

    fun continueCreation() {
        val current = draft.value ?: return

        when (current.step) {
            CollectionCreationStep.NAME -> {
                val cleanName = current.name.trim()
                if (cleanName.isEmpty()) return

                draft.value = current.copy(
                    name = cleanName,
                    step = CollectionCreationStep.COMICS
                )
            }

            CollectionCreationStep.COMICS -> {
                if (current.selectedComicIds.isEmpty()) return

                draft.value = current.copy(
                    step = CollectionCreationStep.COVER,
                    coverComicId = current.coverComicId
                        ?.takeIf { it in current.selectedComicIds }
                        ?: current.selectedComicIds.first()
                )
            }

            CollectionCreationStep.COVER -> {
                if (current.coverComicId == null) return
                draft.value = current.copy(
                    step = CollectionCreationStep.LAYOUT
                )
            }

            CollectionCreationStep.LAYOUT -> saveDraft()
        }
    }

    private fun saveDraft() {
        val current = draft.value ?: return
        val coverComicId = current.coverComicId ?: return
        val layoutStyle = current.layoutStyle ?: return

        if (current.selectedComicIds.isEmpty() || isSaving.value) return

        viewModelScope.launch {
            isSaving.value = true
            errorMessage.value = null

            try {
                collectionRepository.createCollection(
                    name = current.name,
                    comicIds = current.selectedComicIds.toList(),
                    coverComicId = coverComicId,
                    layoutStyle = layoutStyle
                )
                draft.value = null
            } catch (error: Exception) {
                errorMessage.value =
                    error.message ?: "Couldn't create the collection"
            } finally {
                isSaving.value = false
            }
        }
    }

    fun addComicsToCollection(
        collection: ComicCollection,
        comicIds: List<Long>
    ) {
        if (comicIds.isEmpty() || isSaving.value) return

        viewModelScope.launch {
            isSaving.value = true
            errorMessage.value = null

            try {
                collectionRepository.addComicsToCollection(
                    collectionId = collection.id,
                    comicIds = comicIds
                )
            } catch (error: Exception) {
                errorMessage.value =
                    error.message ?: "Couldn't add comics to the collection"
            } finally {
                isSaving.value = false
            }
        }
    }

    fun renameCollection(
        collection: ComicCollection,
        newName: String
    ) {
        viewModelScope.launch {
            try {
                collectionRepository.renameCollection(
                    collectionId = collection.id,
                    name = newName
                )
            } catch (error: Exception) {
                errorMessage.value =
                    error.message ?: "Couldn't rename the collection"
            }
        }
    }

    fun deleteCollections(collections: List<ComicCollection>) {
        if (collections.isEmpty()) return

        viewModelScope.launch {
            try {
                collectionRepository.deleteCollections(
                    collections.map { collection -> collection.id }
                )
            } catch (error: Exception) {
                errorMessage.value =
                    error.message ?: "Couldn't delete the collections"
            }
        }
    }

    fun dismissError() {
        errorMessage.value = null
    }
}