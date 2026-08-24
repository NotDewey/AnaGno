package com.comicreader.app.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import android.util.Log
import com.comicreader.app.data.comic.ComicPageRef
import com.comicreader.app.data.bubble.BubbleDetector
import com.comicreader.app.data.bubble.BubbleDetectionContract
import com.comicreader.app.data.panel.PanelDetector
import com.comicreader.app.data.preferences.ReaderPreferences
import com.comicreader.app.data.repository.ComicRepository
import com.comicreader.app.domain.model.Bookmark
import com.comicreader.app.domain.model.Bubble
import com.comicreader.app.domain.model.Comic
import com.comicreader.app.domain.model.Panel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class ReadingMode { HORIZONTAL_PAGES, VERTICAL_SCROLL }
enum class ReadingDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

private const val BUBBLE_DIAGNOSTIC_TAG =
    BubbleDetectionContract.DIAGNOSTIC_TAG

private const val PREVIEW_REQUEST_DEBOUNCE_MS =
    50L

data class PageNavigationRequest(
    val id: Long,
    val page: Int
)

data class ReaderPage(
    val ref: ComicPageRef,
    val localPath: String? = null,
    val errorMessage: String? = null
)

data class ReaderUiState(
    val comic: Comic? = null,
    val pages: List<ReaderPage> = emptyList(),
    val currentPage: Int = 0,
    val readingMode: ReadingMode = ReadingMode.HORIZONTAL_PAGES,
    val readingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
    val bookmarks: List<Bookmark> = emptyList(),
    val reviewPages: List<Int> = emptyList(),
    val isPanelEditorOpen: Boolean = false,
    val isLoadingPanels: Boolean = false,
    val isDetectingPanels: Boolean = false,
    val isSavingPanels: Boolean = false,
    val editingPanels: List<Panel> = emptyList(),
    val panelEditorError: String? = null,
    val pageTurn3dEnabled: Boolean = true,
    val bubbleZoomEnabled: Boolean = false,
    val bubbles: List<Bubble> = emptyList(),
    val activeBubbleIndex: Int = 0,
    val isDetectingBubbles: Boolean = false,
    val bubbleZoomError: String? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val navigationRequest: PageNavigationRequest? = null,
    val showCompletionPrompt: Boolean = false,
    val showRatingDialog: Boolean = false
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val repository: ComicRepository,
    private val preferences: ReaderPreferences,
    private val panelDetector: PanelDetector,
    private val bubbleDetector: BubbleDetector,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val comicId: Long = checkNotNull(savedStateHandle["comicId"])
    private val pageLoadJobs = mutableMapOf<Int, Job>()
    private val bookmarkMutationsInFlight = mutableSetOf<Int>()
    private var nextTemporaryPanelId = -1L
    private var bubbleLoadJob: Job? = null
    private var pendingLastBubblePage: Int? = null

    private var completionMutationInFlight = false

    /*
     * Scrubber previews use a latest-request-wins pipeline. Rapid slider
     * movement cancels only the pending debounce job; superseded page loads are
     * cancelled without surfacing CancellationException as a page error.
     */
    private var previewRequestJob: Job? = null
    private var latestPreviewTarget: Int? = null

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        loadComic()

        viewModelScope.launch {
            repository.observeBookmarks(comicId).collect { bookmarks ->
                _uiState.value = _uiState.value.copy(bookmarks = bookmarks)
            }
        }

        viewModelScope.launch {
            repository.observeReviewPages(comicId).collect { reviewPages ->
                _uiState.value = _uiState.value.copy(reviewPages = reviewPages)
            }
        }
    }

    fun relinkComic(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                repository.relinkComic(comicId, uri)
                loadComicContent()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Couldn't reconnect this comic"
                )
            }
        }
    }

    fun requestPage(index: Int) {
        val state = _uiState.value
        val comic = state.comic ?: return
        val page = state.pages.getOrNull(index) ?: return
        if (page.localPath?.let { File(it).isFile } == true) return
        if (pageLoadJobs[index]?.isActive == true) return

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val path =
                    repository.loadPage(
                        comic,
                        page.ref
                    )

                updatePage(index) {
                    it.copy(
                        localPath =
                            path,
                        errorMessage =
                            null
                    )
                }
            } catch (cancelled: CancellationException) {
                /*
                 * Cancellation is expected when the scrubber advances to a
                 * newer page. Never turn it into a visible page error.
                 */
                throw cancelled
            } catch (error: Exception) {
                updatePage(index) {
                    it.copy(
                        localPath =
                            null,
                        errorMessage =
                            error.message
                                ?: "Couldn't load this page"
                    )
                }
            } finally {
                if (pageLoadJobs[index] === coroutineContext[Job]) {
                    pageLoadJobs.remove(index)
                }
            }
        }
        pageLoadJobs[index] = job
        job.start()
    }

    /**
     * Latest-page-wins scrubber loading.
     *
     * A short debounce prevents pages crossed during a fast drag from starting
     * expensive extraction/decoding work. The currently displayed page and its
     * immediate neighbors remain protected.
     */
    fun requestPreviewPage(index: Int) {
        val state =
            _uiState.value

        if (state.pages.isEmpty()) {
            return
        }

        val target =
            index.coerceIn(
                0,
                state.pages.lastIndex
            )

        if (
            latestPreviewTarget ==
            target &&
            (
                    state.pages[target]
                        .localPath
                        ?.let {
                            File(it).isFile
                        } ==
                            true ||
                            pageLoadJobs[target]
                                ?.isActive ==
                            true
                    )
        ) {
            return
        }

        latestPreviewTarget =
            target

        previewRequestJob
            ?.cancel()

        previewRequestJob =
            viewModelScope.launch {
                /*
                 * This is long enough to skip pages merely crossed by the
                 * thumb, but short enough to feel immediate when it pauses.
                 */
                delay(
                    PREVIEW_REQUEST_DEBOUNCE_MS
                )

                if (
                    latestPreviewTarget !=
                    target
                ) {
                    return@launch
                }

                val current =
                    _uiState.value
                        .currentPage

                cancelPageLoadsExcept(
                    setOf(
                        current,
                        current - 1,
                        current + 1,
                        target
                    )
                )

                requestPage(
                    target
                )
            }
    }

    fun onPageChanged(page: Int) {
        clearPreviewRequest()

        val changed =
            page !=
                    _uiState.value.currentPage
        if (changed) bubbleLoadJob?.cancel()
        if (pendingLastBubblePage != null && pendingLastBubblePage != page) {
            pendingLastBubblePage = null
        }
        _uiState.value = _uiState.value.copy(
            currentPage = page,
            bubbles = if (changed) emptyList() else _uiState.value.bubbles,
            activeBubbleIndex = if (changed) 0 else _uiState.value.activeBubbleIndex,
            isDetectingBubbles = if (changed) false else _uiState.value.isDetectingBubbles,
            bubbleZoomError = if (changed) null else _uiState.value.bubbleZoomError
        )
        cancelPageLoadsExcept(setOf(page, page - 1, page + 1))
        requestPage(page)
        requestPage(page - 1)
        requestPage(page + 1)
        persistProgressAndMaybeComplete(page)
        if (_uiState.value.bubbleZoomEnabled) loadBubblesForPage(page)
    }

    fun toggleReadingMode() {
        val newMode = if (_uiState.value.readingMode == ReadingMode.HORIZONTAL_PAGES)
            ReadingMode.VERTICAL_SCROLL else ReadingMode.HORIZONTAL_PAGES
        bubbleLoadJob?.cancel()
        _uiState.value = _uiState.value.copy(
            readingMode = newMode,
            pageTurn3dEnabled = false,
            bubbleZoomEnabled = false,
            bubbles = emptyList(),
            activeBubbleIndex = 0,
            isDetectingBubbles = false,
            bubbleZoomError = null
        )
        viewModelScope.launch { preferences.saveReadingMode(comicId, newMode) }
    }

    fun toggleReadingDirection() {
        val newDirection = if (_uiState.value.readingDirection == ReadingDirection.LEFT_TO_RIGHT)
            ReadingDirection.RIGHT_TO_LEFT else ReadingDirection.LEFT_TO_RIGHT
        _uiState.value = _uiState.value.copy(
            readingDirection = newDirection,
            bubbles = orderBubbles(_uiState.value.bubbles, newDirection),
            activeBubbleIndex = 0
        )
        viewModelScope.launch { preferences.saveReadingDirection(comicId, newDirection) }
    }

    fun togglePageTurn3d() {
        val state =
            _uiState.value

        val enabled =
            !state.pageTurn3dEnabled

        if (!enabled) {
            _uiState.value =
                state.copy(
                    pageTurn3dEnabled = false
                )
            return
        }

        /*
         * 3D Page Turn owns the full-page horizontal gesture surface.
         * Bubble Zoom and the Guided View panel workflow therefore cannot
         * remain active at the same time.
         */
        bubbleLoadJob?.cancel()
        pendingLastBubblePage = null

        _uiState.value =
            state.copy(
                pageTurn3dEnabled = true,
                readingMode =
                    ReadingMode.HORIZONTAL_PAGES,
                bubbleZoomEnabled = false,
                bubbles = emptyList(),
                activeBubbleIndex = 0,
                isDetectingBubbles = false,
                bubbleZoomError = null,
                isPanelEditorOpen = false,
                isLoadingPanels = false,
                isDetectingPanels = false,
                isSavingPanels = false,
                editingPanels = emptyList(),
                panelEditorError = null
            )

        viewModelScope.launch {
            preferences.saveReadingMode(
                comicId,
                ReadingMode.HORIZONTAL_PAGES
            )
        }
    }

    fun toggleBubbleZoom() {
        val state = _uiState.value
        val enabled = !state.bubbleZoomEnabled

        if (!enabled) {
            bubbleLoadJob?.cancel()
            pendingLastBubblePage = null
            _uiState.value = state.copy(
                bubbleZoomEnabled = false,
                bubbles = emptyList(),
                activeBubbleIndex = 0,
                isDetectingBubbles = false,
                bubbleZoomError = null
            )
            return
        }

        /*
         * Bubble Zoom and 3D Page Turn are mutually exclusive. Selecting
         * Bubble Zoom switches directly instead of rejecting the action.
         */
        _uiState.value = state.copy(
            pageTurn3dEnabled = false,
            bubbleZoomEnabled = true,
            readingMode = ReadingMode.HORIZONTAL_PAGES,
            bubbles = emptyList(),
            activeBubbleIndex = 0,
            isDetectingBubbles = false,
            bubbleZoomError = null
        )
        viewModelScope.launch { preferences.saveReadingMode(comicId, ReadingMode.HORIZONTAL_PAGES) }
        loadBubblesForPage(state.currentPage)
    }

    fun nextBubble() {
        val state = _uiState.value
        if (state.isDetectingBubbles || state.bubbles.isEmpty()) return
        if (state.activeBubbleIndex < state.bubbles.lastIndex) {
            _uiState.value = state.copy(activeBubbleIndex = state.activeBubbleIndex + 1)
        } else if (state.currentPage < state.pages.lastIndex) {
            jumpToPage(state.currentPage + 1, selectLastBubble = false)
        }
    }

    fun previousBubble() {
        val state = _uiState.value
        if (state.isDetectingBubbles || state.bubbles.isEmpty()) return
        if (state.activeBubbleIndex > 0) {
            _uiState.value = state.copy(activeBubbleIndex = state.activeBubbleIndex - 1)
        } else if (state.currentPage > 0) {
            jumpToPage(state.currentPage - 1, selectLastBubble = true)
        }
    }

    fun retryBubbleDetection() {
        loadBubblesForPage(_uiState.value.currentPage, forceDetection = true)
    }

    fun moveActiveBubbleEarlier() = moveActiveBubble(-1)

    fun moveActiveBubbleLater() = moveActiveBubble(1)

    private fun moveActiveBubble(delta: Int) {
        val state = _uiState.value
        if (state.bubbles.isEmpty()) return
        val from = state.activeBubbleIndex
        val to = (from + delta).coerceIn(0, state.bubbles.lastIndex)
        if (from == to) return
        val reordered = state.bubbles.toMutableList().apply {
            val moved = removeAt(from)
            add(to, moved)
        }.mapIndexed { index, bubble -> bubble.copy(order = index, isManual = true) }
        _uiState.value = state.copy(bubbles = reordered, activeBubbleIndex = to)
        viewModelScope.launch { repository.saveBubbles(comicId, state.currentPage, reordered) }
    }

    fun jumpToPage(page: Int) = jumpToPage(page, selectLastBubble = false)

    private fun jumpToPage(
        page: Int,
        selectLastBubble: Boolean
    ) {
        clearPreviewRequest()

        val target =
            page.coerceIn(
                0,
                (
                        _uiState.value
                            .pages.size -
                                1
                        ).coerceAtLeast(0)
            )

        pendingLastBubblePage =
            target.takeIf {
                selectLastBubble
            }

        cancelPageLoadsExcept(
            setOf(
                target,
                target - 1,
                target + 1
            )
        )
        _uiState.value = _uiState.value.copy(
            currentPage = target,
            bubbles = emptyList(),
            activeBubbleIndex = 0,
            bubbleZoomError = null,
            navigationRequest = PageNavigationRequest(
                id = System.nanoTime(),
                page = target
            )
        )
        requestPage(
            target
        )
        requestPage(
            target - 1
        )
        requestPage(
            target + 1
        )

        if (
            _uiState.value
                .bubbleZoomEnabled
        ) {
            loadBubblesForPage(
                target
            )
        }
        persistProgressAndMaybeComplete(target)
    }

    fun dismissCompletionPrompt() {
        _uiState.value =
            _uiState.value.copy(
                showCompletionPrompt = false
            )
    }

    fun openRatingDialog() {
        val comic =
            _uiState.value.comic
                ?: return

        if (!comic.isFinished) {
            markComicFinished(
                showPrompt = false,
                openRatingAfter = true
            )
            return
        }

        _uiState.value =
            _uiState.value.copy(
                showCompletionPrompt = false,
                showRatingDialog = true
            )
    }

    fun dismissRatingDialog() {
        _uiState.value =
            _uiState.value.copy(
                showRatingDialog = false
            )
    }

    fun submitRating(rating: Float) {
        viewModelScope.launch {
            val updatedComic =
                repository.setRating(
                    comicId = comicId,
                    rating = rating
                )

            _uiState.value =
                _uiState.value.copy(
                    comic =
                        updatedComic
                            ?: _uiState.value.comic,
                    showCompletionPrompt = false,
                    showRatingDialog = false
                )
        }
    }

    fun markComicFinishedFromSettings() {
        markComicFinished(
            showPrompt = true
        )
    }

    fun markComicUnfinished() {
        if (completionMutationInFlight) {
            return
        }

        completionMutationInFlight = true

        viewModelScope.launch {
            try {
                val updatedComic =
                    repository.markUnfinished(
                        comicId
                    )

                _uiState.value =
                    _uiState.value.copy(
                        comic =
                            updatedComic
                                ?: _uiState.value.comic
                                    ?.copy(
                                        isFinished = false,
                                        finishedAt = null
                                    ),
                        showCompletionPrompt = false,
                        showRatingDialog = false
                    )
            } finally {
                completionMutationInFlight = false
            }
        }
    }

    fun consumeNavigationRequest(requestId: Long) {
        if (_uiState.value.navigationRequest?.id == requestId) {
            _uiState.value = _uiState.value.copy(navigationRequest = null)
        }
    }

    fun jumpToNextReviewPage() {
        val state = _uiState.value

        if (state.pageTurn3dEnabled) {
            return
        }

        val target = state.reviewPages.firstOrNull { it > state.currentPage }
            ?: state.reviewPages.firstOrNull()
            ?: return
        jumpToPage(target)
    }

    fun addBookmarkForCurrentPage() {
        toggleBookmarkForCurrentPage()
    }

    fun toggleBookmarkForCurrentPage() {
        val page = _uiState.value.currentPage
        if (page in bookmarkMutationsInFlight) return
        val existing = _uiState.value.bookmarks.firstOrNull { it.pageIndex == page }

        bookmarkMutationsInFlight += page
        viewModelScope.launch {
            try {
                if (existing == null) {
                    repository.addBookmark(comicId, page)
                } else {
                    repository.removeBookmark(existing)
                }
            } finally {
                bookmarkMutationsInFlight -= page
            }
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        viewModelScope.launch { repository.removeBookmark(bookmark) }
    }

    fun openPanelEditor() {
        val state = _uiState.value

        if (state.pageTurn3dEnabled) {
            return
        }

        val comic = state.comic ?: return
        val pageIndex = state.currentPage
        if (state.pages.getOrNull(pageIndex)?.localPath?.let { File(it).isFile } != true) {
            requestPage(pageIndex)
            _uiState.value = state.copy(panelEditorError = "Wait for this page to finish loading")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isPanelEditorOpen = true,
                isLoadingPanels = true,
                panelEditorError = null
            )
            try {
                val saved = repository.getPanels(comic.id, pageIndex)
                val panels =
                    if (saved.isNotEmpty()) saved else listOf(newDefaultPanel(comic.id, pageIndex))
                _uiState.value = _uiState.value.copy(
                    editingPanels = panels.sortedBy(Panel::order),
                    isLoadingPanels = false
                )
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingPanels = false,
                    panelEditorError = error.message ?: "Couldn't load panel coordinates"
                )
            }
        }
    }

    fun closePanelEditor() {
        _uiState.value = _uiState.value.copy(
            isPanelEditorOpen = false,
            isLoadingPanels = false,
            isDetectingPanels = false,
            isSavingPanels = false,
            editingPanels = emptyList(),
            panelEditorError = null
        )
    }

    fun detectPanelsForEditor() {
        val state = _uiState.value
        val comic = state.comic ?: return
        val pageIndex = state.currentPage
        val pagePath = state.pages.getOrNull(pageIndex)?.localPath
        if (pagePath == null || state.isDetectingPanels) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDetectingPanels = true,
                panelEditorError = null
            )
            try {
                val detected = panelDetector.detect(pagePath, comic.id, pageIndex)
                if (detected.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isDetectingPanels = false,
                        panelEditorError = "No panels were detected. Your current rectangles were kept."
                    )
                    return@launch
                }

                val editable = detected.mapIndexed { index, panel ->
                    panel.copy(id = nextTemporaryPanelId--, order = index)
                }
                _uiState.value = _uiState.value.copy(
                    editingPanels = editable,
                    isDetectingPanels = false,
                    panelEditorError = null
                )
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDetectingPanels = false,
                    panelEditorError = error.message ?: "Panel detection failed"
                )
            }
        }
    }

    fun updateEditingPanel(panel: Panel) {
        val panels = _uiState.value.editingPanels.toMutableList()
        val index = panels.indexOfFirst { it.id == panel.id }
        if (index < 0) return
        val minimumSize = 0.03f
        val left = panel.left.coerceIn(
            0f,
            (panel.right - minimumSize).coerceAtLeast(0f)
        )
        val top = panel.top.coerceIn(
            0f,
            (panel.bottom - minimumSize).coerceAtLeast(0f)
        )
        panels[index] = panel.copy(
            left = left,
            top = top,
            right = panel.right.coerceIn((left + minimumSize).coerceAtMost(1f), 1f),
            bottom = panel.bottom.coerceIn((top + minimumSize).coerceAtMost(1f), 1f)
        )
        _uiState.value = _uiState.value.copy(editingPanels = panels)
    }

    fun addEditingPanel(): Long {
        val state = _uiState.value
        val comic = state.comic ?: return 0
        val panel = newDefaultPanel(comic.id, state.currentPage).copy(
            order = state.editingPanels.size
        )
        _uiState.value = state.copy(editingPanels = state.editingPanels + panel)
        return panel.id
    }

    fun deleteEditingPanel(panelId: Long) {
        if (_uiState.value.editingPanels.size <= 1) {
            _uiState.value = _uiState.value.copy(
                panelEditorError = "A page must contain at least one panel"
            )
            return
        }
        val panels = _uiState.value.editingPanels.filterNot { it.id == panelId }
            .mapIndexed { index, panel -> panel.copy(order = index) }
        _uiState.value = _uiState.value.copy(editingPanels = panels, panelEditorError = null)
    }

    fun moveEditingPanel(panelId: Long, direction: Int) {
        val panels = _uiState.value.editingPanels.sortedBy(Panel::order).toMutableList()
        val from = panels.indexOfFirst { it.id == panelId }
        val to = (from + direction).coerceIn(0, panels.lastIndex)
        if (from < 0 || from == to) return
        val panel = panels.removeAt(from)
        panels.add(to, panel)
        _uiState.value = _uiState.value.copy(
            editingPanels = panels.mapIndexed { index, item -> item.copy(order = index) }
        )
    }

    fun saveEditingPanels() {
        val state = _uiState.value
        val comic = state.comic ?: return
        val pageIndex = state.currentPage
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSavingPanels = true, panelEditorError = null)
            try {
                repository.savePanels(
                    comic.id,
                    pageIndex,
                    state.editingPanels.sortedBy(Panel::order)
                )
                closePanelEditor()
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSavingPanels = false,
                    panelEditorError = error.message ?: "Couldn't save panel coordinates"
                )
            }
        }
    }

    private fun updatePage(index: Int, update: (ReaderPage) -> ReaderPage) {
        val pages = _uiState.value.pages.toMutableList()
        val current = pages.getOrNull(index) ?: return
        pages[index] = update(current)
        _uiState.value = _uiState.value.copy(pages = pages)
        val updatedPage =
            pages[index]

        if (
            index ==
            latestPreviewTarget &&
            updatedPage.errorMessage ==
            null &&
            updatedPage.localPath
                ?.let {
                    File(it).isFile
                } ==
            true
        ) {
            /*
             * The requested preview has priority. Only after it is available do
             * we warm the adjacent workspace cards.
             */
            requestPage(
                index - 1
            )
            requestPage(
                index + 1
            )
        }

        if (index == _uiState.value.currentPage && _uiState.value.bubbleZoomEnabled) {
            if (updatedPage.errorMessage == null &&
                updatedPage.localPath?.let { File(it).isFile } == true
            ) {
                loadBubblesForPage(index)
            } else {
                bubbleLoadJob?.cancel()
                pendingLastBubblePage = null
                _uiState.value = _uiState.value.copy(
                    bubbles = emptyList(),
                    activeBubbleIndex = 0,
                    isDetectingBubbles = false,
                    bubbleZoomError = updatedPage.errorMessage
                )
            }
        }
    }

    private fun loadBubblesForPage(pageIndex: Int, forceDetection: Boolean = false) {
        val state = _uiState.value
        val comic = state.comic ?: return
        val pagePath = state.pages.getOrNull(pageIndex)?.localPath
        if (pagePath?.let { File(it).isFile } != true) {
            bubbleLoadJob?.cancel()
            _uiState.value = _uiState.value.copy(
                bubbles = emptyList(),
                activeBubbleIndex = 0,
                isDetectingBubbles = false,
                bubbleZoomError = null
            )
            requestPage(pageIndex)
            return
        }

        bubbleLoadJob?.cancel()
        bubbleLoadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDetectingBubbles = forceDetection,
                bubbleZoomError = null,
                bubbles = emptyList(),
                activeBubbleIndex = 0
            )
            try {
                val indexed = if (forceDetection) null else {
                    repository.getIndexedBubbles(
                        comicId = comic.id,
                        pageIndex = pageIndex,
                        // A background-indexed page is fast, but the first
                        // interactive V35.1 visit still needs one A/B capture.
                        requireEvidence = true
                    )
                }
                val detected = indexed ?: run {
                    val beforeDetection = _uiState.value
                    if (beforeDetection.currentPage != pageIndex ||
                        !beforeDetection.bubbleZoomEnabled
                    ) {
                        return@launch
                    }
                    _uiState.value = beforeDetection.copy(isDetectingBubbles = true)
                    repository.getOrDetectBubbles(
                        comic = comic,
                        pageIndex = pageIndex,
                        pagePath = pagePath,
                        detector = bubbleDetector,
                        forceDetection = forceDetection,
                        // Interactive pages may export the V35.1 A/B evidence.
                        // The background whole-comic indexer leaves this off so
                        // diagnostics never turn into hundreds of extra PNGs.
                        exportEvidence = true
                    )
                }
                val currentState = _uiState.value
                val currentPath = currentState.pages.getOrNull(pageIndex)?.localPath
                if (currentState.currentPage != pageIndex ||
                    !currentState.bubbleZoomEnabled ||
                    currentPath != pagePath ||
                    !File(pagePath).isFile
                ) {
                    return@launch
                }
                val ordered = if (detected.any(Bubble::isManual)) detected.sortedBy(Bubble::order)
                else orderBubbles(detected, _uiState.value.readingDirection)
                ordered.forEachIndexed { index, bubble ->
                    val maskFile = File(bubble.maskPath)
                    Log.d(
                        BUBBLE_DIAGNOSTIC_TAG,
                        "stage=RESULT_HANDOFF outcome=READY index=$index/${ordered.size} " +
                                "page=$pageIndex exists=${maskFile.isFile} bytes=${maskFile.length()} " +
                                "text=\"${bubble.text.replace('\n', ' ')}\""
                    )
                }
                val startIndex = if (pendingLastBubblePage == pageIndex) ordered.lastIndex else 0
                if (pendingLastBubblePage == pageIndex) pendingLastBubblePage = null
                _uiState.value = _uiState.value.copy(
                    bubbles = ordered,
                    activeBubbleIndex = startIndex.coerceAtLeast(0),
                    isDetectingBubbles = false,
                    bubbleZoomError = if (detected.isEmpty()) "No dialogue was detected on this page" else null
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (_uiState.value.currentPage == pageIndex) {
                    _uiState.value = _uiState.value.copy(
                        bubbles = emptyList(),
                        activeBubbleIndex = 0,
                        isDetectingBubbles = false,
                        bubbleZoomError = error.message ?: "Bubble detection failed"
                    )
                }
            }
        }
    }

    private fun orderBubbles(
        bubbles: List<Bubble>,
        direction: ReadingDirection
    ): List<Bubble> {
        if (bubbles.any(Bubble::isManual)) return bubbles.sortedBy(Bubble::order)
        // Hybrid detection stores an OCR-based LTR order. Keep that stable;
        // geometry remains the fallback when the reader requests RTL.
        return if (direction == ReadingDirection.LEFT_TO_RIGHT) {
            bubbles.sortedBy(Bubble::order)
        } else {
            orderBubbleGroup(bubbles, direction)
        }
    }

    private fun orderBubbleGroup(
        bubbles: List<Bubble>,
        direction: ReadingDirection
    ): List<Bubble> {
        val rows = mutableListOf<MutableList<Bubble>>()
        bubbles.sortedWith(compareBy<Bubble>({ (it.top + it.bottom) / 2f }, { it.left }))
            .forEach { bubble ->
                val bestRow = rows.maxByOrNull { row ->
                    row.maxOf { existing ->
                        if (sameVisualRow(existing, bubble)) verticalOverlap(
                            existing,
                            bubble
                        ) else -1f
                    }
                }
                val overlap = bestRow?.maxOf { existing ->
                    if (sameVisualRow(existing, bubble)) verticalOverlap(existing, bubble) else -1f
                } ?: -1f
                if (bestRow != null && overlap >= 0f) bestRow += bubble
                else rows += mutableListOf(bubble)
            }
        return rows.sortedBy { row -> row.minOf(Bubble::top) }.flatMap { row ->
            if (direction == ReadingDirection.LEFT_TO_RIGHT) {
                row.sortedBy { it.left }
            } else {
                row.sortedByDescending { it.right }
            }
        }
    }

    private fun verticalOverlap(a: Bubble, b: Bubble): Float {
        val overlap = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        val smallerHeight = minOf(a.bottom - a.top, b.bottom - b.top).coerceAtLeast(0.0001f)
        return overlap / smallerHeight
    }

    private fun sameVisualRow(a: Bubble, b: Bubble): Boolean {
        if (verticalOverlap(a, b) < 0.25f) return false
        val smallerHeight = minOf(a.bottom - a.top, b.bottom - b.top).coerceAtLeast(0.0001f)
        val topDifference = kotlin.math.abs(a.top - b.top)
        val centerA = (a.top + a.bottom) / 2f
        val centerB = (b.top + b.bottom) / 2f
        val centerDifference = kotlin.math.abs(centerA - centerB)
        return topDifference <= smallerHeight * 0.55f ||
                centerDifference <= smallerHeight * 0.55f
    }

    private fun persistProgressAndMaybeComplete(
        page: Int
    ) {
        val state =
            _uiState.value

        val comic =
            state.comic
                ?: return

        val openedAt =
            System.currentTimeMillis()

        _uiState.value =
            state.copy(
                comic = comic.copy(
                    lastReadPage = page,
                    dateLastOpened = openedAt
                )
            )

        viewModelScope.launch {
            repository.updateProgress(
                comicId = comicId,
                page = page
            )
        }

        val reachedFinalPage =
            state.pages.isNotEmpty() &&
                    page >= state.pages.lastIndex

        if (
            reachedFinalPage &&
            !comic.isFinished
        ) {
            markComicFinished(
                showPrompt = true
            )
        }
    }

    private fun markComicFinished(
        showPrompt: Boolean,
        openRatingAfter: Boolean = false
    ) {
        val comic =
            _uiState.value.comic
                ?: return

        if (comic.isFinished) {
            _uiState.value =
                _uiState.value.copy(
                    showCompletionPrompt =
                        showPrompt,
                    showRatingDialog =
                        openRatingAfter
                )
            return
        }

        if (completionMutationInFlight) {
            return
        }

        completionMutationInFlight = true

        viewModelScope.launch {
            try {
                val updatedComic =
                    repository.markFinished(
                        comicId
                    )

                _uiState.value =
                    _uiState.value.copy(
                        comic =
                            updatedComic
                                ?: comic.copy(
                                    isFinished = true,
                                    finishedAt =
                                        System.currentTimeMillis()
                                ),
                        showCompletionPrompt =
                            showPrompt,
                        showRatingDialog =
                            openRatingAfter
                    )
            } finally {
                completionMutationInFlight = false
            }
        }
    }

    private fun clearPreviewRequest() {
        previewRequestJob
            ?.cancel()
        previewRequestJob =
            null
        latestPreviewTarget =
            null
    }

    private fun cancelPageLoadsExcept(indices: Set<Int>) {
        pageLoadJobs.filterKeys { it !in indices }.values.forEach(Job::cancel)
    }

    private fun newDefaultPanel(comicId: Long, pageIndex: Int): Panel = Panel(
        id = nextTemporaryPanelId--,
        comicId = comicId,
        pageIndex = pageIndex,
        order = 0,
        left = 0.08f,
        top = 0.08f,
        right = 0.92f,
        bottom = 0.92f
    )

    private fun loadComic() {
        viewModelScope.launch { loadComicContent() }
    }

    private suspend fun loadComicContent() {
        clearPreviewRequest()

        var loadedComic: Comic? =
            null
        try {
            loadedComic = repository.getComic(comicId)
                ?: error("Comic not found")
            repository.activatePanelDetection(comicId)
            val refs = repository.getPageRefs(loadedComic)
            require(refs.isNotEmpty()) { "This comic has no readable pages" }
            val savedMode = preferences.readingMode(comicId).first()
            val savedDirection = preferences.readingDirection(comicId).first()

            val currentPage = loadedComic.lastReadPage.coerceIn(0, refs.lastIndex)
            pageLoadJobs.values.forEach(Job::cancel)
            pageLoadJobs.clear()
            _uiState.value = _uiState.value.copy(
                comic = loadedComic,
                pages = refs.map(::ReaderPage),
                currentPage = currentPage,
                readingMode = savedMode,
                readingDirection = savedDirection,
                isLoading = false,
                errorMessage = null,
                navigationRequest = null
            )
            requestPage(currentPage)
            requestPage(currentPage - 1)
            requestPage(currentPage + 1)
            repository.activateBubbleDetection(comicId)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                comic = loadedComic,
                pages = emptyList(),
                isLoading = false,
                errorMessage = e.message ?: "Couldn't open this comic"
            )
        }
    }
}
