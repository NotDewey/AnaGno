package com.comicreader.app.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.util.Log
import com.comicreader.app.data.bubble.BubbleDetectionContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.imageLoader
import coil.request.ImageRequest
import com.comicreader.app.domain.model.Panel
import com.comicreader.app.domain.model.Bubble
import com.comicreader.app.ui.reader.pagecurl.OpenGlPageCurlReader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.AutoStories

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class
)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    var showReaderSettings by rememberSaveable {
        mutableStateOf(false)
    }
    var bubbleControlsVisible by rememberSaveable {
        mutableStateOf(false)
    }
    var scrubberPreviewPage by rememberSaveable {
        mutableStateOf<Int?>(null)
    }
    var retainedScrubberPreviewPage by rememberSaveable {
        mutableStateOf<Int?>(null)
    }

    /*
     * A slider release is an external workspace navigation transaction:
     *
     * 1. Keep the full-page preview visible.
     * 2. Update the reader's selected page.
     * 3. Reposition the workspace carousel invisibly behind the preview.
     * 4. Remove the preview only after the selected card is centered and ready.
     */
    var pendingScrubberCommitPage by
    rememberSaveable {
        mutableStateOf<Int?>(null)
    }

    val relinkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::relinkComic)
    }

    /*
     * Keep the actual Android system bars hidden for the whole reader session.
     * The custom reader chrome overlays the existing black page margins, so
     * opening controls never changes the TextureView viewport or page layout.
     */
    ReaderSystemBars(
        immersive = true
    )

    LaunchedEffect(state.bubbleZoomEnabled) {
        if (state.bubbleZoomEnabled) {
            controlsVisible = false
        }

        bubbleControlsVisible = false
    }

    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) {
            scrubberPreviewPage =
                null
            retainedScrubberPreviewPage =
                null
            pendingScrubberCommitPage =
                null
        }
    }

    if (state.showCompletionPrompt) {
        ComicFinishedDialog(
            comicTitle =
                state.comic?.title.orEmpty(),
            onRateNow =
                viewModel::openRatingDialog,
            onLater =
                viewModel::dismissCompletionPrompt
        )
    }

    if (state.showRatingDialog) {
        ComicRatingDialog(
            comicTitle =
                state.comic?.title.orEmpty(),
            initialRating =
                state.comic?.userRating,
            onSave =
                viewModel::submitRating,
            onDismiss =
                viewModel::dismissRatingDialog
        )
    }

    if (showBookmarks) {
        BookmarkSheet(
            bookmarks = state.bookmarks,
            onBookmarkClick = { bookmark ->
                viewModel.jumpToPage(bookmark.pageIndex)
                showBookmarks = false
            },
            onDeleteBookmark = viewModel::removeBookmark,
            onDismiss = { showBookmarks = false }
        )
    }

    if (
        showReaderSettings &&
        !state.isPanelEditorOpen
    ) {
        ReaderSettingsSheet(
            currentPageIsBookmarked =
                state.bookmarks.any { bookmark ->
                    bookmark.pageIndex == state.currentPage
                },
            pageTurn3dEnabled =
                state.pageTurn3dEnabled,
            bubbleZoomEnabled =
                state.bubbleZoomEnabled,
            readingDirection =
                state.readingDirection,
            reviewPageCount =
                state.reviewPages.size,
            canEditPanels =
                state.pages.getOrNull(state.currentPage)
                    ?.localPath != null,
            isFinished =
                state.comic?.isFinished == true,
            userRating =
                state.comic?.userRating,
            onToggleFinished = {
                if (state.comic?.isFinished == true) {
                    viewModel.markComicUnfinished()
                } else {
                    viewModel.markComicFinishedFromSettings()
                }
                showReaderSettings = false
            },
            onRateComic = {
                viewModel.openRatingDialog()
                showReaderSettings = false
            },
            onEditPanels = {
                /*
                 * Keep showReaderSettings=true. The sheet is temporarily hidden
                 * while the full-screen editor is active and reappears
                 * automatically after Save or Cancel.
                 */
                viewModel.openPanelEditor()
            },
            onTogglePageTurn3d = {
                val enabling =
                    !state.pageTurn3dEnabled

                bubbleControlsVisible = false

                if (enabling) {
                    controlsVisible = false
                }

                viewModel.togglePageTurn3d()
            },
            onToggleBubbleZoom = {
                val enabling =
                    !state.bubbleZoomEnabled

                bubbleControlsVisible = false

                if (enabling) {
                    controlsVisible = false
                }

                viewModel.toggleBubbleZoom()
            },
            onToggleReadingDirection =
                viewModel::toggleReadingDirection,
            onToggleBookmark = {
                viewModel.toggleBookmarkForCurrentPage()
                showReaderSettings = false
            },
            onOpenReviewPages = {
                viewModel.jumpToNextReviewPage()
            },
            onDismiss = {
                showReaderSettings = false
            }
        )
    }

    if (state.isLoading) {
        LoadingReader()
        return
    }

    if (state.errorMessage != null || state.pages.isEmpty()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    state.errorMessage ?: "This comic has no readable pages",
                    color = Color.White
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        relinkLauncher.launch(
                            arrayOf(
                                "application/vnd.comicbook+zip",
                                "application/vnd.comicbook-rar",
                                "application/x-cbr",
                                "application/zip",
                                "application/vnd.rar",
                                "application/x-rar-compressed",
                                "application/pdf",
                                "*/*"
                            )
                        )
                    }
                ) {
                    Text("Locate comic file")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onBack) { Text("Back to library") }
            }
        }
        return
    }

    if (state.isPanelEditorOpen) {
        PanelEditorScreen(
            pageNumber = state.currentPage + 1,
            pagePath = state.pages.getOrNull(state.currentPage)?.localPath,
            panels = state.editingPanels,
            isLoading = state.isLoadingPanels,
            isDetecting = state.isDetectingPanels,
            isSaving = state.isSavingPanels,
            errorMessage = state.panelEditorError,
            onPanelChanged = viewModel::updateEditingPanel,
            onAddPanel = viewModel::addEditingPanel,
            onDeletePanel = viewModel::deleteEditingPanel,
            onMovePanel = viewModel::moveEditingPanel,
            onDetectPanels = viewModel::detectPanelsForEditor,
            onSave = viewModel::saveEditingPanels,
            onCancel = viewModel::closePanelEditor
        )
        return
    }

    /*
     * The OpenGL reader owns its own bitmap cache, while Workspace page cards
     * are displayed by Coil. Warm only the current 3D page after the curl has
     * settled so opening Workspace does not begin a second full-page decode on
     * the first animation frame. This is deliberately not an always-mounted
     * hidden carousel, which would keep extra page UI and images alive while
     * reading on lower-memory devices.
     */
    WorkspaceTransitionPagePreloader(
        enabled =
            state.pageTurn3dEnabled &&
                    !state.bubbleZoomEnabled &&
                    !isLandscape,
        page =
            state.pages.getOrNull(
                state.currentPage
            ),
        viewportWidthDp =
            configuration.screenWidthDp,
        viewportHeightDp =
            configuration.screenHeightDp
    )

    ReaderWorkspaceTransition(
        visible =
            controlsVisible,
        pages =
            state.pages,
        currentPage =
            state.currentPage,
        readingDirection =
            state.readingDirection,
        title =
            state.comic?.title.orEmpty(),
        remainingPages =
            (
                    state.pages.size -
                            state.currentPage -
                            1
                    ).coerceAtLeast(0),
        onPageNeeded =
            viewModel::requestPage,
        onBack =
            onBack,
        onBookmarks = {
            controlsVisible = true
            showBookmarks = true
        },
        onToggleReadingMode =
            viewModel::toggleReadingMode,
        onSettings = {
            showReaderSettings = true
        },
        onPreviewPageNeeded = { page ->
            /*
             * A new drag supersedes any older slider handoff.
             */
            pendingScrubberCommitPage =
                null
            if (
                retainedScrubberPreviewPage ==
                null
            ) {
                /*
                 * Keep the already-presented reader page visible until the
                 * first scrubber target has actually decoded.
                 */
                retainedScrubberPreviewPage =
                    state.currentPage
            }
            scrubberPreviewPage =
                page
            viewModel.requestPreviewPage(
                page
            )
        },
        onScrubberCommit = { page ->
            /*
             * Do not clear the preview here. It hides the carousel's instant
             * repositioning and disappears only after workspace confirmation.
             */
            pendingScrubberCommitPage =
                page
            if (
                retainedScrubberPreviewPage ==
                null
            ) {
                retainedScrubberPreviewPage =
                    state.currentPage
            }
            scrubberPreviewPage =
                page
            viewModel.jumpToPage(
                page
            )
        },
        onJumpToPage = { page ->
            /*
             * Normal carousel/tap navigation remains immediate.
             */
            pendingScrubberCommitPage =
                null
            viewModel.jumpToPage(
                page
            )
            scrubberPreviewPage =
                null
            retainedScrubberPreviewPage =
                null
        },
        externalTargetPage =
            pendingScrubberCommitPage,
        onExternalTargetReady = { page ->
            if (
                pendingScrubberCommitPage ==
                page
            ) {
                pendingScrubberCommitPage =
                    null
                scrubberPreviewPage =
                    null
                retainedScrubberPreviewPage =
                    null
            }
        },
        onCurrentPageTap = {
            controlsVisible =
                false
        },
        liveReader = {
            when (
                state.readingMode
            ) {
                ReadingMode.HORIZONTAL_PAGES -> {
                    HorizontalPageReader(
                        pages =
                            state.pages,
                        startPage =
                            state.currentPage,
                        currentPage =
                            state.currentPage,
                        isLandscape =
                            isLandscape,
                        /*
                         * Reader chrome must never swap the active reader
                         * engine. Free-scroll remains available in the
                         * codebase, but is no longer tied to opening controls.
                         */
                        freeScrollEnabled =
                            false,
                        readingDirection =
                            state.readingDirection,
                        pageTurn3dEnabled =
                            state.pageTurn3dEnabled,
                        bubbleZoomEnabled =
                            state.bubbleZoomEnabled,
                        bubbles =
                            state.bubbles,
                        activeBubbleIndex =
                            state.activeBubbleIndex,
                        onPreviousBubble =
                            viewModel::previousBubble,
                        onNextBubble =
                            viewModel::nextBubble,
                        navigationRequest =
                            state.navigationRequest,
                        onNavigationConsumed =
                            viewModel::consumeNavigationRequest,
                        onPageChanged =
                            viewModel::onPageChanged,
                        onPageNeeded =
                            viewModel::requestPage,
                        onToggleControls = {
                            if (
                                state.bubbleZoomEnabled
                            ) {
                                bubbleControlsVisible =
                                    !bubbleControlsVisible
                            } else {
                                controlsVisible =
                                    !controlsVisible
                            }
                        }
                    )
                }

                ReadingMode.VERTICAL_SCROLL -> {
                    VerticalScrollReader(
                        pages =
                            state.pages,
                        startPage =
                            state.currentPage,
                        navigationRequest =
                            state.navigationRequest,
                        onNavigationConsumed =
                            viewModel::consumeNavigationRequest,
                        onVisiblePageChanged =
                            viewModel::onPageChanged,
                        onPageNeeded =
                            viewModel::requestPage,
                        onToggleControls = {
                            controlsVisible =
                                !controlsVisible
                        }
                    )
                }
            }

        },
        overlayContent = {
            /*
             * The live reader fades behind the workspace carousel, so the
             * scrubber preview must live in this overlay layer instead. It now
             * appears above the framed pages but below the top/bottom chrome,
             * preserving full slider interaction.
             */
            val previewIndex =
                scrubberPreviewPage
            val retainedPreviewIndex =
                retainedScrubberPreviewPage

            if (
                controlsVisible &&
                previewIndex != null
            ) {
                ScrubberLivePagePreview(
                    page =
                        state.pages.getOrNull(
                            previewIndex
                        ),
                    pageNumber =
                        previewIndex + 1,
                    retainedPage =
                        retainedPreviewIndex
                            ?.let(
                                state.pages::getOrNull
                            ),
                    retainedPageNumber =
                        retainedPreviewIndex
                            ?.plus(1),
                    onRequestedImageReady = {
                        if (
                            scrubberPreviewPage ==
                            previewIndex
                        ) {
                            retainedScrubberPreviewPage =
                                previewIndex
                        }
                    },
                    onRetry = {
                        viewModel.requestPreviewPage(
                            previewIndex
                        )
                    },
                    modifier = Modifier
                        .matchParentSize()
                        .zIndex(30f)
                )
            }

            if (
                state.bubbleZoomEnabled &&
                (
                        bubbleControlsVisible ||
                                state.isDetectingBubbles ||
                                state.bubbleZoomError != null
                        )
            ) {
                BubbleZoomControls(
                    showFullControls =
                        bubbleControlsVisible,
                    isDetecting =
                        state.isDetectingBubbles,
                    bubbleNumber =
                        state.activeBubbleIndex + 1,
                    bubbleCount =
                        state.bubbles.size,
                    errorMessage =
                        state.bubbleZoomError,
                    onRetry =
                        viewModel::retryBubbleDetection,
                    onClose = {
                        bubbleControlsVisible =
                            false
                        viewModel.toggleBubbleZoom()
                    },
                    onSettings = {
                        bubbleControlsVisible =
                            false
                        showReaderSettings =
                            true
                    },
                    modifier = Modifier
                        .align(
                            Alignment.BottomCenter
                        )
                        .padding(
                            bottom = 20.dp
                        )
                )
            }
        }
    )
}


@Composable
private fun BubbleZoomControls(
    showFullControls: Boolean,
    isDetecting: Boolean,
    bubbleNumber: Int,
    bubbleCount: Int,
    errorMessage: String?,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black.copy(alpha = 0.76f),
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
        shadowElevation = 6.dp,
        modifier = modifier
    ) {
        if (showFullControls) {
            Row(
                modifier = Modifier.padding(
                    start = 4.dp,
                    end = 4.dp,
                    top = 3.dp,
                    bottom = 3.dp
                ),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription =
                            "Turn off Bubble Zoom",
                        tint = Color.White
                    )
                }

                Text(
                    text = "Bubble Zoom",
                    color = Color.White,
                    style =
                        MaterialTheme.typography.labelLarge
                )

                Spacer(Modifier.width(12.dp))

                when {
                    isDetecting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )

                        Spacer(Modifier.width(7.dp))

                        Text(
                            text = "Finding dialogue…",
                            color = Color.White,
                            style =
                                MaterialTheme.typography
                                    .labelMedium
                        )
                    }

                    errorMessage != null -> {
                        TextButton(
                            onClick = onRetry
                        ) {
                            Text("Retry")
                        }
                    }

                    bubbleCount > 0 -> {
                        Text(
                            text =
                                "$bubbleNumber / $bubbleCount",
                            color =
                                Color.White.copy(alpha = 0.86f),
                            style =
                                MaterialTheme.typography
                                    .labelMedium
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription =
                            "Bubble Zoom settings",
                        tint = Color.White
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 9.dp
                ),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                when {
                    isDetecting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )

                        Spacer(Modifier.width(8.dp))

                        Text(
                            text = "Finding dialogue…",
                            color = Color.White,
                            style =
                                MaterialTheme.typography
                                    .labelMedium
                        )
                    }

                    errorMessage != null -> {
                        TextButton(
                            onClick = onRetry
                        ) {
                            Text(
                                text = "$errorMessage · Retry"
                            )
                        }
                    }
                }
            }
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelEditorScreen(
    pageNumber: Int,
    pagePath: String?,
    panels: List<Panel>,
    isLoading: Boolean,
    isDetecting: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onPanelChanged: (Panel) -> Unit,
    onAddPanel: () -> Long,
    onDeletePanel: (Long) -> Unit,
    onMovePanel: (Long, Int) -> Unit,
    onDetectPanels: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    var selectedPanelId by remember { mutableLongStateOf(panels.firstOrNull()?.id ?: 0L) }

    LaunchedEffect(panels.map { it.id }) {
        if (panels.none { it.id == selectedPanelId }) {
            selectedPanelId = panels.firstOrNull()?.id ?: 0L
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Surface(color = Color.Black.copy(alpha = 0.92f)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel, enabled = !isSaving && !isDetecting) { Text("Cancel") }
                Text(
                    "Edit panels · Page $pageNumber",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                TextButton(
                    onClick = onDetectPanels,
                    enabled = !isLoading && !isSaving && !isDetecting && pagePath != null
                ) { Text(if (isDetecting) "Detecting…" else "Detect") }
                TextButton(
                    onClick = onSave,
                    enabled = !isLoading && !isDetecting && !isSaving && panels.isNotEmpty()
                ) { Text(if (isSaving) "Saving…" else "Save") }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            when {
                isLoading || pagePath == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                    val loadedPath = requireNotNull(pagePath)
                    val pageAspect = remember(loadedPath) { imageAspectRatio(loadedPath) }
                    val availableAspect = with(LocalDensity.current) {
                        maxWidth.toPx() / maxHeight.toPx().coerceAtLeast(1f)
                    }
                    val imageWidth: Dp
                    val imageHeight: Dp
                    if (availableAspect > pageAspect) {
                        imageHeight = maxHeight
                        imageWidth = maxHeight * pageAspect
                    } else {
                        imageWidth = maxWidth
                        imageHeight = maxWidth / pageAspect
                    }

                    Box(
                        Modifier
                            .size(imageWidth, imageHeight)
                            .align(Alignment.Center)
                            .clipToBounds()
                    ) {
                        SubcomposeAsyncImage(
                            model = loadedPath,
                            contentDescription = "Page $pageNumber",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )
                        PanelRectangles(
                            panels = panels,
                            selectedPanelId = selectedPanelId,
                            onSelect = { selectedPanelId = it },
                            onPanelChanged = onPanelChanged
                        )
                        if (isDetecting) {
                            Box(
                                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }

        errorMessage?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Surface(color = Color.Black.copy(alpha = 0.92f)) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(
                    onClick = { selectedPanelId = onAddPanel() },
                    enabled = !isLoading && !isDetecting && !isSaving
                ) { Text("Add") }
                TextButton(
                    onClick = { onDeletePanel(selectedPanelId) },
                    enabled = selectedPanelId != 0L && !isDetecting && !isSaving
                ) { Text("Delete") }
                TextButton(
                    onClick = { onMovePanel(selectedPanelId, -1) },
                    enabled = selectedPanelId != 0L && !isDetecting && !isSaving
                ) { Text("Earlier") }
                TextButton(
                    onClick = { onMovePanel(selectedPanelId, 1) },
                    enabled = selectedPanelId != 0L && !isDetecting && !isSaving
                ) { Text("Later") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelRectangles(
    panels: List<Panel>,
    selectedPanelId: Long,
    onSelect: (Long) -> Unit,
    onPanelChanged: (Panel) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val imageWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val imageHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

        for (panel in panels.sortedBy(Panel::order)) {
            key(panel.id) {
                val currentPanel by rememberUpdatedState(panel)
                val selected = panel.id == selectedPanelId
                val color = if (selected) Color.Yellow else Color.Cyan
                val panelWidth = panel.right - panel.left
                val panelHeight = panel.bottom - panel.top

                Box(
                    Modifier
                        .offset(x = maxWidth * panel.left, y = maxHeight * panel.top)
                        .size(maxWidth * panelWidth, maxHeight * panelHeight)
                        .zIndex(if (selected) 2f else 1f)
                        .border(if (selected) 3.dp else 2.dp, color)
                        .pointerInput(panel.id, imageWidthPx, imageHeightPx) {
                            detectDragGestures(
                                onDragStart = { onSelect(panel.id) },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val activePanel = currentPanel
                                    val activeWidth = activePanel.right - activePanel.left
                                    val activeHeight = activePanel.bottom - activePanel.top
                                    val dx = dragAmount.x / imageWidthPx
                                    val dy = dragAmount.y / imageHeightPx
                                    val newLeft = (activePanel.left + dx)
                                        .coerceIn(0f, 1f - activeWidth)
                                    val newTop = (activePanel.top + dy)
                                        .coerceIn(0f, 1f - activeHeight)
                                    onPanelChanged(
                                        activePanel.copy(
                                            left = newLeft,
                                            top = newTop,
                                            right = newLeft + activeWidth,
                                            bottom = newTop + activeHeight
                                        )
                                    )
                                }
                            )
                        }
                ) {
                    Text(
                        text = "${panel.order + 1}",
                        color = Color.Black,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .background(color)
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )

                    ResizeHandle(
                        color = color,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset((-10).dp, (-10).dp),
                        onDrag = { dx, dy ->
                            onSelect(panel.id)
                            val activePanel = currentPanel
                            onPanelChanged(
                                activePanel.copy(
                                    left = (activePanel.left + dx / imageWidthPx)
                                        .coerceIn(0f, activePanel.right - 0.03f),
                                    top = (activePanel.top + dy / imageHeightPx)
                                        .coerceIn(0f, activePanel.bottom - 0.03f)
                                )
                            )
                        }
                    )
                    ResizeHandle(
                        color = color,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(10.dp, 10.dp),
                        onDrag = { dx, dy ->
                            onSelect(panel.id)
                            val activePanel = currentPanel
                            onPanelChanged(
                                activePanel.copy(
                                    right = (activePanel.right + dx / imageWidthPx)
                                        .coerceIn(activePanel.left + 0.03f, 1f),
                                    bottom = (activePanel.bottom + dy / imageHeightPx)
                                        .coerceIn(activePanel.top + 0.03f, 1f)
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ResizeHandle(
    color: Color,
    modifier: Modifier = Modifier,
    onDrag: (Float, Float) -> Unit
) {
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier
            .size(24.dp)
            .background(color, MaterialTheme.shapes.small)
            .border(2.dp, Color.Black, MaterialTheme.shapes.small)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount.x, dragAmount.y)
                }
            }
    )
}

private val pageAspectRatioCache =
    ConcurrentHashMap<String, Float>()

internal fun imageAspectRatio(path: String): Float {
    pageAspectRatioCache[path]
        ?.let {
            return it
        }

    val options =
        BitmapFactory.Options().apply {
            inJustDecodeBounds =
                true
        }

    BitmapFactory.decodeFile(
        path,
        options
    )

    val decodedRatio =
        if (
            options.outWidth > 0 &&
            options.outHeight > 0
        ) {
            options.outWidth.toFloat() /
                    options.outHeight.toFloat()
        } else {
            2f / 3f
        }

    return pageAspectRatioCache
        .putIfAbsent(
            path,
            decodedRatio
        )
        ?: decodedRatio
}


@Composable
private fun ComicFinishedDialog(
    comicTitle: String,
    onRateNow: () -> Unit,
    onLater: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        icon = {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text("Comic finished")
        },
        text = {
            Text(
                if (comicTitle.isBlank()) {
                    "This comic is now marked as finished."
                } else {
                    "“$comicTitle” is now marked as finished. Would you like to rate it?"
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onRateNow) {
                Text("Rate now")
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text("Later")
            }
        }
    )
}

@Composable
private fun ComicRatingDialog(
    comicTitle: String,
    initialRating: Float?,
    onSave: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRating by
    rememberSaveable(
        comicTitle,
        initialRating
    ) {
        mutableStateOf(
            initialRating
                ?.toInt()
                ?.coerceIn(1, 5)
                ?: 0
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialRating == null) {
                    "Rate comic"
                } else {
                    "Edit rating"
                }
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (comicTitle.isNotBlank()) {
                    Text(
                        comicTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceEvenly
                ) {
                    repeat(5) { index ->
                        val starNumber = index + 1

                        IconButton(
                            onClick = {
                                selectedRating = starNumber
                            }
                        ) {
                            Icon(
                                imageVector =
                                    if (starNumber <= selectedRating) {
                                        Icons.Default.Star
                                    } else {
                                        Icons.Default.StarBorder
                                    },
                                contentDescription =
                                    "$starNumber stars",
                                tint =
                                    MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    if (selectedRating == 0) {
                        "Choose a rating"
                    } else {
                        "$selectedRating of 5 stars"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        selectedRating.toFloat()
                    )
                },
                enabled = selectedRating > 0
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    currentPageIsBookmarked: Boolean,
    pageTurn3dEnabled: Boolean,
    bubbleZoomEnabled: Boolean,
    readingDirection: ReadingDirection,
    reviewPageCount: Int,
    canEditPanels: Boolean,
    isFinished: Boolean,
    userRating: Float?,
    onToggleFinished: () -> Unit,
    onRateComic: () -> Unit,
    onEditPanels: () -> Unit,
    onTogglePageTurn3d: () -> Unit,
    onToggleBubbleZoom: () -> Unit,
    onToggleReadingDirection: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenReviewPages: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Text(
            text = "Reader settings",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(
                horizontal = 24.dp,
                vertical = 8.dp
            )
        )

        ListItem(
            headlineContent = {
                Text(
                    if (currentPageIsBookmarked) {
                        "Remove page bookmark"
                    } else {
                        "Bookmark this page"
                    }
                )
            },
            leadingContent = {
                Icon(
                    imageVector =
                        if (currentPageIsBookmarked) {
                            Icons.Default.Bookmark
                        } else {
                            Icons.Default.BookmarkBorder
                        },
                    contentDescription = null
                )
            },
            modifier = Modifier.clickable(
                onClick = onToggleBookmark
            )
        )

        ListItem(
            headlineContent = {
                Text(
                    if (isFinished) {
                        "Mark as unfinished"
                    } else {
                        "Mark as finished"
                    }
                )
            },
            supportingContent = {
                Text(
                    if (isFinished) {
                        "Return this comic to Continue Reading"
                    } else {
                        "Remove it from Home and mark its Library cover as completed"
                    }
                )
            },
            leadingContent = {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint =
                        if (isFinished) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                )
            },
            modifier = Modifier.clickable(
                onClick = onToggleFinished
            )
        )

        if (isFinished) {
            ListItem(
                headlineContent = {
                    Text(
                        if (userRating == null) {
                            "Rate comic"
                        } else {
                            "Edit rating"
                        }
                    )
                },
                supportingContent = {
                    Text(
                        userRating?.let { rating ->
                            "${rating.toInt()} of 5 stars"
                        } ?:
                        "Add it to your Ratings collection"
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint =
                            MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.clickable(
                    onClick = onRateComic
                )
            )
        }

        ListItem(
            headlineContent = {
                Text("3D Page Turn")
            },
            supportingContent = {
                Text(
                    if (bubbleZoomEnabled) {
                        "Turn pages with a flexible paper animation · turns off Bubble Zoom"
                    } else {
                        "Turn pages with a flexible paper animation"
                    }
                )
            },
            leadingContent = {
                Icon(
                    Icons.Default.AutoStories,
                    contentDescription = null
                )
            },
            trailingContent = {
                Switch(
                    checked =
                        pageTurn3dEnabled,
                    onCheckedChange = {
                        onTogglePageTurn3d()
                    }
                )
            },
            modifier = Modifier.clickable(
                onClick =
                    onTogglePageTurn3d
            )
        )

        ListItem(
            headlineContent = {
                Text("Bubble Zoom")
            },
            supportingContent = {
                Text(
                    if (pageTurn3dEnabled) {
                        "Enlarge detected dialogue balloons · turns off 3D Page Turn"
                    } else {
                        "Enlarge detected dialogue balloons"
                    }
                )
            },
            leadingContent = {
                Icon(
                    imageVector =
                        if (bubbleZoomEnabled) {
                            Icons.Default.ChatBubble
                        } else {
                            Icons.Default.ChatBubbleOutline
                        },
                    contentDescription = null
                )
            },
            trailingContent = {
                Switch(
                    checked =
                        bubbleZoomEnabled,
                    onCheckedChange = {
                        onToggleBubbleZoom()
                    }
                )
            },
            modifier = Modifier.clickable(
                onClick =
                    onToggleBubbleZoom
            )
        )

        ListItem(
            headlineContent = {
                Text("Reading direction")
            },
            supportingContent = {
                Text(
                    if (
                        readingDirection ==
                        ReadingDirection.LEFT_TO_RIGHT
                    ) {
                        "Left to right"
                    } else {
                        "Right to left"
                    }
                )
            },
            leadingContent = {
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = null
                )
            },
            modifier = Modifier.clickable(
                onClick = onToggleReadingDirection
            )
        )

        ListItem(
            headlineContent = {
                Text("Edit panels")
            },
            supportingContent = {
                Text(
                    if (pageTurn3dEnabled) {
                        "Unavailable while 3D Page Turn is enabled"
                    } else {
                        "Adjust Guided View panel rectangles"
                    }
                )
            },
            leadingContent = {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null
                )
            },
            modifier = Modifier.clickable(
                enabled =
                    canEditPanels &&
                            !pageTurn3dEnabled,
                onClick =
                    onEditPanels
            )
        )

        if (reviewPageCount > 0) {
            ListItem(
                headlineContent = {
                    Text("Review detected panels")
                },
                supportingContent = {
                    Text(
                        if (pageTurn3dEnabled) {
                            "Unavailable while 3D Page Turn is enabled"
                        } else {
                            "$reviewPageCount page" +
                                    if (reviewPageCount == 1) {
                                        " needs review"
                                    } else {
                                        "s need review"
                                    }
                        }
                    )
                },
                leadingContent = {
                    BadgedBox(
                        badge = {
                            Badge {
                                Text(reviewPageCount.toString())
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null
                        )
                    }
                },
                modifier = Modifier.clickable(
                    enabled =
                        !pageTurn3dEnabled,
                    onClick =
                        onOpenReviewPages
                )
            )
        }

        Spacer(
            Modifier
                .height(20.dp)
                .navigationBarsPadding()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkSheet(
    bookmarks: List<com.comicreader.app.domain.model.Bookmark>,
    onBookmarkClick: (com.comicreader.app.domain.model.Bookmark) -> Unit,
    onDeleteBookmark: (com.comicreader.app.domain.model.Bookmark) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "Bookmarks",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        if (bookmarks.isEmpty()) {
            Text(
                text = "No bookmarked pages yet",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(bookmarks, key = { it.id }) { bookmark ->
                    ListItem(
                        headlineContent = {
                            Text(bookmark.label ?: "Page ${bookmark.pageIndex + 1}")
                        },
                        supportingContent = bookmark.label?.let {
                            { Text("Page ${bookmark.pageIndex + 1}") }
                        },
                        leadingContent = {
                            Icon(Icons.Default.Bookmark, contentDescription = null)
                        },
                        trailingContent = {
                            IconButton(onClick = { onDeleteBookmark(bookmark) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete bookmark")
                            }
                        },
                        modifier = Modifier.clickable { onBookmarkClick(bookmark) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ReaderSystemBars(immersive: Boolean) {
    val view =
        LocalView.current
    val darkTheme =
        isSystemInDarkTheme()

    DisposableEffect(
        view,
        immersive,
        darkTheme
    ) {
        val activity =
            view.context.findActivity()
        val window =
            activity?.window
        val controller =
            window?.let {
                WindowCompat.getInsetsController(
                    it,
                    view
                )
            }

        /*
         * Critical for the OpenGL reader:
         *
         * System bars now overlay the reader instead of resizing the window.
         * This keeps GLSurfaceView's surface and viewport dimensions stable
         * while the reader chrome opens and closes.
         */
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(
                window,
                false
            )

            window.statusBarColor =
                android.graphics.Color.TRANSPARENT
            window.navigationBarColor =
                android.graphics.Color.TRANSPARENT
        }

        controller?.isAppearanceLightStatusBars =
            !immersive
        controller?.isAppearanceLightNavigationBars =
            !immersive

        if (immersive) {
            controller?.hide(
                WindowInsetsCompat.Type.systemBars()
            )
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(
                WindowInsetsCompat.Type.systemBars()
            )
        }

        onDispose {
            controller?.show(
                WindowInsetsCompat.Type.systemBars()
            )

            /*
             * Return to the app-wide edge-to-edge contract. Setting this to
             * true was shrinking every destination after leaving the reader
             * because the root Compose layouts were still consuming insets.
             */
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(
                    window,
                    false
                )

                window.statusBarColor =
                    android.graphics.Color.TRANSPARENT
                window.navigationBarColor =
                    android.graphics.Color.TRANSPARENT
            }

            controller?.isAppearanceLightStatusBars =
                !darkTheme
            controller?.isAppearanceLightNavigationBars =
                !darkTheme
        }
    }
}

@Composable
internal fun CompactReaderTopBar(
    title: String,
    onBack: () -> Unit,
    onBookmarks: () -> Unit,
    onToggleReadingMode: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            /*
             * Android status bars remain hidden. Do not reserve their height;
             * use the existing upper black comic margin instead.
             */
            .padding(
                horizontal = 8.dp,
                vertical = 1.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompactReaderIconButton(
            onClick = onBack
        ) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "Back"
            )
        }

        Text(
            text = title,
            color = Color(0xFF15171C),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 6.dp)
        )

        CompactReaderIconButton(
            onClick = onBookmarks
        ) {
            Icon(
                Icons.Default.Bookmarks,
                contentDescription = "Bookmarks"
            )
        }

        CompactReaderIconButton(
            onClick = onToggleReadingMode
        ) {
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription =
                    "Change horizontal or vertical reading"
            )
        }

        CompactReaderIconButton(
            onClick = onSettings
        ) {
            Icon(
                Icons.Default.Tune,
                contentDescription = "Reader settings"
            )
        }
    }
}
@Composable
private fun CompactReaderIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = Color(0xFF1F232B),
        shape = CircleShape
    ) {
        Box(
            modifier = Modifier.size(34.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
internal fun ReaderBottomControls(
    pages: List<ReaderPage>,
    currentPage: Int,
    pageCount: Int,
    remainingPages: Int,
    onPreviewPageNeeded: (Int) -> Unit,
    onJumpToPage: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderPage by remember(pageCount) {
        mutableFloatStateOf(currentPage.toFloat())
    }
    var sliderIsBeingDragged by remember {
        mutableStateOf(false)
    }

    val sliderMax =
        (pageCount - 1)
            .coerceAtLeast(1)
            .toFloat()

    val previewPageIndex =
        sliderPage
            .roundToInt()
            .coerceIn(
                0,
                (pageCount - 1)
                    .coerceAtLeast(0)
            )

    val controlsHeight by animateDpAsState(
        targetValue =
            if (sliderIsBeingDragged) {
                112.dp
            } else {
                66.dp
            },
        animationSpec = spring(),
        label = "reader-controls-height"
    )

    LaunchedEffect(currentPage) {
        if (!sliderIsBeingDragged) {
            sliderPage =
                currentPage.toFloat()
        }
    }

    /*
     * Runs only when the rounded page number changes. Tiny movements inside
     * the same page do not start duplicate page loads.
     */
    LaunchedEffect(
        sliderIsBeingDragged,
        previewPageIndex
    ) {
        if (sliderIsBeingDragged) {
            onPreviewPageNeeded(
                previewPageIndex
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            /*
             * The Android navigation bar stays hidden, so the custom scrubber
             * can occupy the existing lower black margin without reserving an
             * additional inset.
             */
            .padding(
                horizontal = 18.dp,
                vertical = 3.dp
            )
            .height(controlsHeight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {
            if (!sliderIsBeingDragged) {
                val remainingLabel =
                    when (remainingPages) {
                        1 ->
                            "1 page left in volume"

                        else ->
                            "$remainingPages pages left in volume"
                    }

                Text(
                    text = remainingLabel,
                    color = Color(0xFF4A4F59),
                    style =
                        MaterialTheme.typography
                            .bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(bottom = 2.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment =
                    Alignment.Bottom
            ) {
                /*
                 * The bubble and Slider share this exact measured width.
                 * The page-count label sits outside this Box, so it no longer
                 * pushes the thumb and bubble onto different tracks.
                 */
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .height(
                            if (sliderIsBeingDragged) {
                                84.dp
                            } else {
                                30.dp
                            }
                        )
                ) {
                    val previewSize =
                        60.dp

                    val thumbDiameter =
                        20.dp

                    val thumbTravel =
                        (
                                maxWidth -
                                        thumbDiameter
                                ).coerceAtLeast(0.dp)

                    val progress =
                        if (sliderMax <= 0f) {
                            0f
                        } else {
                            (
                                    sliderPage /
                                            sliderMax
                                    ).coerceIn(0f, 1f)
                        }

                    val thumbCenterX =
                        thumbDiameter / 2f +
                                thumbTravel * progress

                    val previewX =
                        thumbCenterX -
                                previewSize / 2f

                    Slider(
                        value =
                            sliderPage.coerceIn(
                                0f,
                                sliderMax
                            ),
                        onValueChange = {
                            sliderIsBeingDragged =
                                true
                            sliderPage = it
                        },
                        onValueChangeFinished = {
                            val selectedPage =
                                sliderPage
                                    .roundToInt()
                                    .coerceIn(
                                        0,
                                        (pageCount - 1)
                                            .coerceAtLeast(0)
                                    )

                            sliderIsBeingDragged =
                                false

                            onJumpToPage(
                                selectedPage
                            )
                        },
                        valueRange =
                            0f..sliderMax,
                        enabled =
                            pageCount > 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .align(
                                Alignment.BottomCenter
                            )
                            .graphicsLayer {
                                scaleY = 0.72f
                            }
                    )

                    if (sliderIsBeingDragged) {
                        PageScrubberPreview(
                            pageNumber =
                                previewPageIndex + 1,
                            modifier = Modifier
                                .offset(
                                    x = previewX,
                                    y = (-18).dp
                                )
                                .size(previewSize)
                                .align(
                                    Alignment.TopStart
                                )
                        )
                    }
                }

                Spacer(
                    Modifier.width(12.dp)
                )

                Text(
                    text =
                        "${sliderPage.roundToInt() + 1} / $pageCount",
                    color = Color(0xFF30343B),
                    style =
                        MaterialTheme.typography
                            .bodySmall,
                    modifier = Modifier
                        .padding(bottom = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun PageScrubberPreview(
    pageNumber: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 4.dp,
                shape = CircleShape,
                clip = false
            )
            .clip(CircleShape)
            .background(
                MaterialTheme.colorScheme
                    .surfaceVariant
                    .copy(alpha = 0.94f)
            )
            .border(
                width = 1.dp,
                color =
                    MaterialTheme.colorScheme
                        .primary
                        .copy(alpha = 0.48f),
                shape = CircleShape
            ),
        contentAlignment =
            Alignment.Center
    ) {
        Text(
            text = pageNumber.toString(),
            color =
                MaterialTheme.colorScheme
                    .onSurface,
            style =
                MaterialTheme.typography
                    .titleMedium
        )
    }
}

@Composable
private fun ScrubberLivePagePreview(
    page: ReaderPage?,
    pageNumber: Int,
    retainedPage: ReaderPage?,
    retainedPageNumber: Int?,
    onRequestedImageReady: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val requestedPath =
        page?.localPath

    val retainedPath =
        retainedPage
            ?.localPath

    val retainedIsRequested =
        page != null &&
                retainedPage?.ref?.index ==
                page.ref.index &&
                retainedPath ==
                requestedPath

    var requestedImageReady by
    remember(
        page?.ref?.index,
        requestedPath
    ) {
        mutableStateOf(
            retainedIsRequested
        )
    }

    val hasRetainedImage =
        retainedPath !=
                null

    Box(
        modifier = modifier
            .background(Color.White),
        contentAlignment =
            Alignment.Center
    ) {
        /*
         * Sticky preview layer: never discard the last decoded page merely
         * because a distant scrubber target is still being extracted or
         * decoded. The new target is prepared above this layer and becomes
         * visible only after ReliableLocalPageImage reports success.
         */
        if (
            hasRetainedImage &&
            !retainedIsRequested
        ) {
            ReliableLocalPageImage(
                pageIndex =
                    requireNotNull(
                        retainedPage
                    ).ref.index,
                loadedPath =
                    requireNotNull(
                        retainedPath
                    ),
                contentDescription =
                    "Retained preview page ${retainedPageNumber ?: retainedPage.ref.index + 1}",
                contentScale =
                    ContentScale.Fit,
                onPageNeeded = {},
                errorTextColor =
                    Color(0xFF30343B),
                modifier = Modifier
                    .fillMaxSize()
            )
        }

        when {
            page == null -> {
                if (!hasRetainedImage) {
                    CircularProgressIndicator()
                }
            }

            page.localPath == null -> {
                if (page.errorMessage == null) {
                    /*
                     * When a retained page exists, extraction happens without
                     * covering that page with loading chrome. The scrubber's
                     * page-number bubble already communicates the new target.
                     */
                    if (!hasRetainedImage) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(34.dp)
                        )
                    }
                } else {
                    Surface(
                        color =
                            Color.White.copy(
                                alpha = 0.92f
                            ),
                        shape =
                            MaterialTheme.shapes.medium,
                        tonalElevation =
                            0.dp,
                        shadowElevation =
                            4.dp
                    ) {
                        Column(
                            horizontalAlignment =
                                Alignment.CenterHorizontally,
                            modifier = Modifier
                                .padding(24.dp)
                        ) {
                            Text(
                                text =
                                    page.errorMessage,
                                color = Color(0xFF30343B),
                                style =
                                    MaterialTheme.typography
                                        .bodyMedium,
                                textAlign =
                                    TextAlign.Center
                            )

                            Spacer(
                                Modifier.height(12.dp)
                            )

                            Button(
                                onClick = onRetry
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            else -> {
                val loadedPath =
                    requireNotNull(
                        page.localPath
                    )

                ReliableLocalPageImage(
                    pageIndex =
                        page.ref.index,
                    loadedPath =
                        loadedPath,
                    contentDescription =
                        "Preview page $pageNumber",
                    contentScale =
                        ContentScale.Fit,
                    onPageNeeded =
                        onRetry,
                    onImageReadyChanged = { ready ->
                        requestedImageReady =
                            ready

                        if (ready) {
                            onRequestedImageReady()
                        }
                    },
                    errorTextColor =
                        Color(0xFF30343B),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha =
                                if (
                                    requestedImageReady ||
                                    !hasRetainedImage ||
                                    retainedIsRequested
                                ) {
                                    1f
                                } else {
                                    0f
                                }
                        }
                )
            }
        }
    }
}

@Composable
private fun LoadingReader() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HorizontalPageReader(
    pages: List<ReaderPage>,
    startPage: Int,
    currentPage: Int,
    isLandscape: Boolean,
    freeScrollEnabled: Boolean,
    readingDirection: ReadingDirection,
    pageTurn3dEnabled: Boolean,
    bubbleZoomEnabled: Boolean,
    bubbles: List<Bubble>,
    activeBubbleIndex: Int,
    onPreviousBubble: () -> Unit,
    onNextBubble: () -> Unit,
    navigationRequest: PageNavigationRequest?,
    onNavigationConsumed: (Long) -> Unit,
    onPageChanged: (Int) -> Unit,
    onPageNeeded: (Int) -> Unit,
    onToggleControls: () -> Unit
) {
    if (
        freeScrollEnabled &&
        !isLandscape &&
        !bubbleZoomEnabled
    ) {
        FreeScrollPageReader(
            pages = pages,
            startPage = currentPage,
            readingDirection = readingDirection,
            navigationRequest = navigationRequest,
            onNavigationConsumed = onNavigationConsumed,
            onPageChanged = onPageChanged,
            onPageNeeded = onPageNeeded,
            onToggleControls = onToggleControls
        )
        return
    }

    if (
        pageTurn3dEnabled &&
        !freeScrollEnabled &&
        !isLandscape &&
        !bubbleZoomEnabled
    ) {
        OpenGlPageCurlReader(
            pages = pages,
            startPage = currentPage,
            readingDirection = readingDirection,
            navigationRequest = navigationRequest,
            onNavigationConsumed = onNavigationConsumed,
            onPageChanged = onPageChanged,
            onPageNeeded = onPageNeeded,
            onToggleControls = onToggleControls
        )
        return
    }

    val spreads: List<List<Int>> =
        remember(pages.size, isLandscape) {
            if (!isLandscape) {
                pages.indices.map { index ->
                    listOf(index)
                }
            } else {
                pages.indices.chunked(2)
            }
        }

    val startSpread =
        remember(startPage, spreads) {
            spreads.indexOfFirst { spread ->
                startPage in spread
            }.coerceAtLeast(0)
        }

    val pagerState =
        rememberPagerState(
            initialPage = startSpread
        ) {
            spreads.size
        }

    val pageTurnDensity =
        LocalDensity.current.density

    val scope = rememberCoroutineScope()
    var requestedSpread by remember {
        mutableIntStateOf(startSpread)
    }

    fun navigateBy(delta: Int) {
        requestedSpread =
            (requestedSpread + delta)
                .coerceIn(0, spreads.lastIndex)

        val target = requestedSpread

        scope.launch {
            pagerState.animateScrollToPage(target)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow {
            pagerState.currentPage
        }.collect { spreadIndex ->
            spreads
                .getOrNull(spreadIndex)
                ?.firstOrNull()
                ?.let(onPageChanged)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow {
            pagerState.isScrollInProgress to
                    pagerState.currentPage
        }.collect { (isScrolling, page) ->
            if (!isScrolling) {
                requestedSpread = page
            }
        }
    }

    LaunchedEffect(navigationRequest?.id) {
        val request =
            navigationRequest
                ?: return@LaunchedEffect

        val targetSpread =
            spreads.indexOfFirst { spread ->
                request.page in spread
            }.coerceAtLeast(0)

        requestedSpread = targetSpread

        if (
            abs(
                pagerState.currentPage -
                        targetSpread
            ) <= 1
        ) {
            pagerState.animateScrollToPage(
                targetSpread
            )
        } else {
            pagerState.scrollToPage(
                targetSpread
            )
        }

        onNavigationConsumed(request.id)
    }

    HorizontalPager(
        state = pagerState,
        reverseLayout =
            readingDirection ==
                    ReadingDirection.RIGHT_TO_LEFT,
        modifier = Modifier.fillMaxSize()
    ) { spreadIndex ->
        val rawPageOffset =
            (
                    pagerState.currentPage -
                            spreadIndex
                    ) +
                    pagerState.currentPageOffsetFraction

        val directedPageOffset =
            if (
                readingDirection ==
                ReadingDirection.RIGHT_TO_LEFT
            ) {
                -rawPageOffset
            } else {
                rawPageOffset
            }

        val turnProgress =
            (directedPageOffset * 2f)
                .coerceIn(
                    -1f,
                    1f
                )

        val turnAmount =
            abs(turnProgress)

        val pageTurnEnabled = false

        val pivotX =
            if (turnProgress < 0f) {
                0f
            } else {
                1f
            }

        val displayedPages =
            if (
                readingDirection ==
                ReadingDirection.RIGHT_TO_LEFT &&
                isLandscape
            ) {
                spreads[spreadIndex].reversed()
            } else {
                spreads[spreadIndex]
            }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (pageTurnEnabled) {
                        transformOrigin =
                            TransformOrigin(
                                pivotFractionX = pivotX,
                                pivotFractionY = 0.5f
                            )

                        /*
                         * A visibly book-like turn. The previous version used
                         * too little rotation and too much camera distance,
                         * making the perspective nearly impossible to see.
                         */
                        rotationY =
                            -turnProgress * 58f

                        cameraDistance =
                            pageTurnDensity * 9f

                        translationX =
                            -turnProgress *
                                    pageTurnDensity *
                                    14f

                        scaleX =
                            1f -
                                    turnAmount * 0.045f

                        scaleY =
                            1f -
                                    turnAmount * 0.012f

                        shadowElevation =
                            pageTurnDensity *
                                    10f *
                                    turnAmount

                        clip = false

                        alpha =
                            1f -
                                    turnAmount * 0.025f
                    }
                }
                .zIndex(
                    if (pageTurnEnabled) {
                        1f - turnAmount
                    } else {
                        0f
                    }
                )
        ) {
            Row(
                modifier = Modifier.fillMaxSize()
            ) {
                displayedPages.forEach { pageIndex ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        ZoomablePage(
                            page = pages[pageIndex],
                            bubbleZoomEnabled =
                                bubbleZoomEnabled &&
                                        pageIndex ==
                                        currentPage,
                            activeBubble =
                                if (
                                    pageIndex ==
                                    currentPage
                                ) {
                                    bubbles.getOrNull(
                                        activeBubbleIndex
                                    )
                                } else {
                                    null
                                },
                            onPreviousBubble =
                                onPreviousBubble,
                            onNextBubble =
                                onNextBubble,
                            onPageNeeded = {
                                onPageNeeded(pageIndex)
                            },
                            onTapLeft = {},
                            onTapRight = {},
                            onToggleControls =
                                onToggleControls
                        )
                    }
                }
            }

            if (
                pageTurnEnabled &&
                turnAmount > 0.001f
            ) {
                /*
                 * Broad lighting across the paper surface.
                 */
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                colors =
                                    if (pivotX == 0f) {
                                        listOf(
                                            Color.Black.copy(
                                                alpha =
                                                    0.28f *
                                                            turnAmount
                                            ),
                                            Color.Transparent,
                                            Color.White.copy(
                                                alpha =
                                                    0.08f *
                                                            turnAmount
                                            )
                                        )
                                    } else {
                                        listOf(
                                            Color.White.copy(
                                                alpha =
                                                    0.08f *
                                                            turnAmount
                                            ),
                                            Color.Transparent,
                                            Color.Black.copy(
                                                alpha =
                                                    0.28f *
                                                            turnAmount
                                            )
                                        )
                                    }
                            )
                        )
                )

                /*
                 * Concentrated fold/spine shadow so the effect remains
                 * obvious even on dark comic pages.
                 */
                Box(
                    modifier = Modifier
                        .align(
                            if (pivotX == 0f) {
                                Alignment.CenterStart
                            } else {
                                Alignment.CenterEnd
                            }
                        )
                        .width(34.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                colors =
                                    if (pivotX == 0f) {
                                        listOf(
                                            Color.Black.copy(
                                                alpha =
                                                    0.46f *
                                                            turnAmount
                                            ),
                                            Color.Black.copy(
                                                alpha =
                                                    0.18f *
                                                            turnAmount
                                            ),
                                            Color.Transparent
                                        )
                                    } else {
                                        listOf(
                                            Color.Transparent,
                                            Color.Black.copy(
                                                alpha =
                                                    0.18f *
                                                            turnAmount
                                            ),
                                            Color.Black.copy(
                                                alpha =
                                                    0.46f *
                                                            turnAmount
                                            )
                                        )
                                    }
                            )
                        )
                )

                /*
                 * Thin reflected rim along the bending edge.
                 */
                Box(
                    modifier = Modifier
                        .align(
                            if (pivotX == 0f) {
                                Alignment.CenterStart
                            } else {
                                Alignment.CenterEnd
                            }
                        )
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(
                            Color.White.copy(
                                alpha =
                                    0.42f *
                                            turnAmount
                            )
                        )
                )
            }
        }
    }
}


@Composable
private fun FreeScrollPageReader(
    pages: List<ReaderPage>,
    startPage: Int,
    readingDirection: ReadingDirection,
    navigationRequest: PageNavigationRequest?,
    onNavigationConsumed: (Long) -> Unit,
    onPageChanged: (Int) -> Unit,
    onPageNeeded: (Int) -> Unit,
    onToggleControls: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val pageWidth =
        configuration.screenWidthDp.dp * 0.74f

    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex =
                startPage.coerceIn(
                    0,
                    pages.lastIndex
                )
        )

    LaunchedEffect(listState) {
        var lastReportedPage = -1

        snapshotFlow {
            val layout = listState.layoutInfo
            val viewportCenter =
                (
                        layout.viewportStartOffset +
                                layout.viewportEndOffset
                        ) / 2

            layout.visibleItemsInfo
                .minByOrNull { item ->
                    abs(
                        item.offset +
                                item.size / 2 -
                                viewportCenter
                    )
                }
                ?.index
        }.collect { nearestPage ->
            if (
                nearestPage != null &&
                nearestPage != lastReportedPage
            ) {
                lastReportedPage = nearestPage
                onPageChanged(nearestPage)
            }
        }
    }

    LaunchedEffect(navigationRequest?.id) {
        val request =
            navigationRequest
                ?: return@LaunchedEffect

        val target =
            request.page.coerceIn(
                0,
                pages.lastIndex
            )

        listState.scrollToItem(target)
        onNavigationConsumed(request.id)
    }

    LazyRow(
        state = listState,
        reverseLayout =
            readingDirection ==
                    ReadingDirection.RIGHT_TO_LEFT,
        contentPadding = PaddingValues(
            horizontal = 28.dp,
            vertical = 4.dp
        ),
        horizontalArrangement =
            Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        items(
            items = pages,
            key = { page -> page.ref.index }
        ) { page ->
            val pageIndex = page.ref.index

            Box(
                modifier = Modifier
                    .width(pageWidth)
                    .fillMaxHeight()
                    .background(Color.White)
            ) {
                ZoomablePage(
                    page = page,
                    bubbleZoomEnabled = false,
                    activeBubble = null,
                    onPreviousBubble = {},
                    onNextBubble = {},
                    onPageNeeded = {
                        onPageNeeded(pageIndex)
                    },
                    onTapLeft = {},
                    onTapRight = {},
                    onToggleControls =
                        onToggleControls
                )
            }
        }
    }
}

@Composable
private fun VerticalScrollReader(
    pages: List<ReaderPage>,
    startPage: Int,
    navigationRequest: PageNavigationRequest?,
    onNavigationConsumed: (Long) -> Unit,
    onVisiblePageChanged: (Int) -> Unit,
    onPageNeeded: (Int) -> Unit,
    onToggleControls: () -> Unit
) {
    val initialPage = remember { startPage.coerceIn(0, pages.lastIndex) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = initialPage
    )

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect(onVisiblePageChanged)
    }

    LaunchedEffect(navigationRequest?.id) {
        val request = navigationRequest ?: return@LaunchedEffect
        val target = request.page.coerceIn(0, pages.lastIndex)
        if (abs(listState.firstVisibleItemIndex - target) <= 3) {
            listState.animateScrollToItem(target)
        } else {
            listState.scrollToItem(target)
        }
        onNavigationConsumed(request.id)
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(pages, key = { it.ref.index }) { page ->
            LazyPage(
                page = page,
                onPageNeeded = { onPageNeeded(page.ref.index) },
                onToggleControls = onToggleControls
            )
        }
    }
}

@Composable
private fun LazyPage(
    page: ReaderPage,
    onPageNeeded: () -> Unit,
    onToggleControls: () -> Unit
) {
    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
        ZoomablePage(
            page = page,
            bubbleZoomEnabled = false,
            activeBubble = null,
            onPreviousBubble = {},
            onNextBubble = {},
            onPageNeeded = onPageNeeded,
            onTapLeft = {},
            onTapRight = {},
            onToggleControls = onToggleControls
        )
    }
}

@Composable
private fun ZoomablePage(
    page: ReaderPage,
    bubbleZoomEnabled: Boolean,
    activeBubble: Bubble?,
    onPreviousBubble: () -> Unit,
    onNextBubble: () -> Unit,
    onPageNeeded: () -> Unit,
    onTapLeft: () -> Unit,
    onTapRight: () -> Unit,
    onToggleControls: () -> Unit
) {
    LaunchedEffect(page.ref.index) { onPageNeeded() }
    val currentOnTapLeft by rememberUpdatedState(onTapLeft)
    val currentOnTapRight by rememberUpdatedState(onTapRight)
    val currentOnToggleControls by rememberUpdatedState(onToggleControls)
    val currentOnPreviousBubble by rememberUpdatedState(onPreviousBubble)
    val currentOnNextBubble by rememberUpdatedState(onNextBubble)

    if (page.localPath == null) {
        if (page.errorMessage != null) {
            PageError(page.errorMessage, onPageNeeded)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    var scale by remember(page.ref.index) { mutableStateOf(1f) }
    var offset by remember(page.ref.index) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(bubbleZoomEnabled) {
        if (bubbleZoomEnabled) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val loadedPath = requireNotNull(page.localPath)
        var pageImageReady by remember(loadedPath) { mutableStateOf(false) }
        val pageAspect = remember(loadedPath) { imageAspectRatio(loadedPath) }
        val availableAspect = with(LocalDensity.current) {
            maxWidth.toPx() / maxHeight.toPx().coerceAtLeast(1f)
        }
        val fittedPageWidth: Dp
        val fittedPageHeight: Dp
        if (availableAspect > pageAspect) {
            fittedPageHeight = maxHeight
            fittedPageWidth = maxHeight * pageAspect
        } else {
            fittedPageWidth = maxWidth
            fittedPageHeight = maxWidth / pageAspect
        }
        val fittedPageLeft = (maxWidth - fittedPageWidth) / 2f
        val fittedPageTop = (maxHeight - fittedPageHeight) / 2f
        // Capture the BoxWithConstraints values before entering the nested BoxScope.
        val readerWidth = maxWidth
        val readerHeight = maxHeight

        val gestureModifier = if (bubbleZoomEnabled) {
            Modifier.pointerInput(page.ref.index, activeBubble?.pageIndex, activeBubble?.order) {
                detectTapGestures { tapPosition ->
                    when {
                        tapPosition.x <
                                size.width * 0.35f -> {
                            currentOnPreviousBubble()
                        }

                        tapPosition.x >
                                size.width * 0.65f -> {
                            currentOnNextBubble()
                        }

                        else -> {
                            currentOnToggleControls()
                        }
                    }
                }
            }
        } else {
            Modifier
                .pointerInput(page.ref.index) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var pointersRemainDown: Boolean
                        do {
                            val event = awaitPointerEvent()
                            val pressedPointers = event.changes.count { it.pressed }

                            // At normal scale, leave one-finger movement to the pager/list.
                            if (pressedPointers >= 2 || scale > 1f) {
                                val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                if (newScale <= 1.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    val pan = event.calculatePan()
                                    val maxX = size.width * (newScale - 1f) / 2f
                                    val maxY = size.height * (newScale - 1f) / 2f
                                    scale = newScale
                                    offset = Offset(
                                        x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                        y = (offset.y + pan.y).coerceIn(-maxY, maxY)
                                    )
                                }
                                event.changes.forEach { it.consume() }
                            }
                            pointersRemainDown = event.changes.any { it.pressed }
                        } while (pointersRemainDown)
                    }
                }
                .pointerInput(page.ref.index) {
                    detectTapGestures { tapPosition ->
                        if (
                            scale <= 1f &&
                            tapPosition.x >=
                            size.width * 0.35f &&
                            tapPosition.x <=
                            size.width * 0.65f
                        ) {
                            currentOnToggleControls()
                        }
                    }
                }
        }

        Box(Modifier.fillMaxSize().then(gestureModifier)) {
            ReliableLocalPageImage(
                pageIndex =
                    page.ref.index,
                loadedPath =
                    loadedPath,
                contentDescription =
                    "Page ${page.ref.index + 1}",
                contentScale =
                    ContentScale.Fit,
                onPageNeeded =
                    onPageNeeded,
                onImageReadyChanged = {
                    pageImageReady = it
                },
                errorTextColor =
                    Color.White,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val displayedScale =
                            if (bubbleZoomEnabled) {
                                1f
                            } else {
                                scale
                            }

                        val displayedOffset =
                            if (bubbleZoomEnabled) {
                                Offset.Zero
                            } else {
                                offset
                            }

                        scaleX = displayedScale
                        scaleY = displayedScale
                        translationX =
                            displayedOffset.x
                        translationY =
                            displayedOffset.y
                    }
            )

            if (bubbleZoomEnabled &&
                pageImageReady &&
                activeBubble?.pageIndex == page.ref.index
            ) {
                BubbleMagnifier(
                    bubble = activeBubble,
                    pageWidth = fittedPageWidth,
                    pageHeight = fittedPageHeight,
                    pageLeft = fittedPageLeft,
                    pageTop = fittedPageTop,
                    containerWidth = readerWidth,
                    containerHeight = readerHeight
                )
            }
        }
    }
}

private data class BubbleFrame(
    val left: Dp,
    val top: Dp,
    val width: Dp,
    val height: Dp,
    val scale: Float,
    val wasShrunkToFit: Boolean
)

private fun computeBubbleFrame(
    bubble: Bubble,
    pageWidth: Dp, pageHeight: Dp, pageLeft: Dp, pageTop: Dp,
    containerWidth: Dp, containerHeight: Dp
): BubbleFrame {
    val sourceWidth = (pageWidth * (bubble.right - bubble.left)).coerceAtLeast(20.dp)
    val sourceHeight = (pageHeight * (bubble.bottom - bubble.top)).coerceAtLeast(16.dp)
    val margin = 8.dp
    val availableWidth = (containerWidth - margin * 2).coerceAtLeast(1.dp)
    val availableHeight = (containerHeight - margin * 2).coerceAtLeast(1.dp)
    val maximumWidth = minOf(containerWidth * 0.86f, availableWidth)
    val maximumHeight = minOf(containerHeight * 0.48f, availableHeight)
    val magnification = minOf(
        2f,
        maximumWidth.value / sourceWidth.value,
        maximumHeight.value / sourceHeight.value
    ).coerceAtLeast(0.01f)
    val width = sourceWidth * magnification
    val height = sourceHeight * magnification
    val centerX = pageLeft + pageWidth * ((bubble.left + bubble.right) / 2f)
    val centerY = pageTop + pageHeight * ((bubble.top + bubble.bottom) / 2f)
    val left = (centerX - width / 2f).coerceIn(margin, (containerWidth - width - margin).coerceAtLeast(margin))
    val top = (centerY - height / 2f).coerceIn(margin, (containerHeight - height - margin).coerceAtLeast(margin))
    return BubbleFrame(
        left = left,
        top = top,
        width = width,
        height = height,
        scale = magnification,
        wasShrunkToFit = magnification < 1f
    )
}

@Composable
private fun BubbleMagnifier(
    bubble: Bubble,
    pageWidth: Dp,
    pageHeight: Dp,
    pageLeft: Dp,
    pageTop: Dp,
    containerWidth: Dp,
    containerHeight: Dp
) {
    // Detected bubbles have database id=0 until they are read back from Room.
    // Coordinates/path/order therefore form the real render identity.
    val frame = remember(
        bubble.pageIndex,
        bubble.order,
        bubble.left,
        bubble.top,
        bubble.right,
        bubble.bottom,
        bubble.maskPath,
        pageWidth,
        pageHeight,
        pageLeft,
        pageTop,
        containerWidth,
        containerHeight
    ) {
        computeBubbleFrame(bubble, pageWidth, pageHeight, pageLeft, pageTop, containerWidth, containerHeight)
    }

    LaunchedEffect(frame, bubble.maskPath) {
        Log.d(
            BubbleDetectionContract.DIAGNOSTIC_TAG,
            "stage=MASK_FRAME outcome=FIT page=${bubble.pageIndex} order=${bubble.order} " +
                    "scale=${"%.3f".format(frame.scale)} shrunk=${frame.wasShrunkToFit} " +
                    "frame=${frame.left.value.toInt()},${frame.top.value.toInt()}," +
                    "${frame.width.value.toInt()}x${frame.height.value.toInt()}"
        )
    }

    // NOT keyed on bubble.id — same Animatables persist across bubble changes,
    // so switching bubbles is a continuous camera move, not a dispose/recreate.
    val animLeft = remember { Animatable(frame.left.value) }
    val animTop = remember { Animatable(frame.top.value) }
    val animWidth = remember { Animatable(frame.width.value) }
    val animHeight = remember { Animatable(frame.height.value) }
    var hasAppeared by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // first-ever appearance: pop in from slightly smaller/inset, not full 3D flip
        val entrySpec = spring<Float>(dampingRatio = 0.86f, stiffness = 430f)
        animWidth.snapTo(frame.width.value * 0.7f)
        animHeight.snapTo(frame.height.value * 0.7f)
        animLeft.snapTo(frame.left.value + frame.width.value * 0.15f)
        animTop.snapTo(frame.top.value + frame.height.value * 0.15f)
        launch { animWidth.animateTo(frame.width.value, entrySpec) }
        launch { animHeight.animateTo(frame.height.value, entrySpec) }
        launch { animLeft.animateTo(frame.left.value, entrySpec) }
        launch { animTop.animateTo(frame.top.value, entrySpec) }
        hasAppeared = true
    }

    LaunchedEffect(frame) {
        if (!hasAppeared) return@LaunchedEffect
        val moveSpec = spring<Float>(dampingRatio = 0.94f, stiffness = 380f)
        launch { animLeft.animateTo(frame.left.value, moveSpec) }
        launch { animTop.animateTo(frame.top.value, moveSpec) }
        launch { animWidth.animateTo(frame.width.value, moveSpec) }
        launch { animHeight.animateTo(frame.height.value, moveSpec) }
    }

    // Commit every selected mask before the first suspend point. The previous
    // fade-out waited 90 ms before updating displayedPath, so a quick second
    // tap could cancel that coroutine and silently skip the intermediate
    // bubble even though it had reached RESULT_HANDOFF.
    var displayedPath by remember { mutableStateOf(bubble.maskPath) }
    val contentAlpha = remember { Animatable(1f) }
    LaunchedEffect(bubble.maskPath) {
        val targetPath = bubble.maskPath
        if (displayedPath != targetPath) {
            displayedPath = targetPath
            contentAlpha.snapTo(0f)
            Log.d(
                BubbleDetectionContract.DIAGNOSTIC_TAG,
                "stage=MASK_DISPLAY outcome=COMMITTED page=${bubble.pageIndex} " +
                        "order=${bubble.order} text=\"${bubble.text.replace('\n', ' ')}\""
            )
        }
        // A later selection can cancel only this fade-in; it cannot prevent
        // the selected path from becoming the image model.
        contentAlpha.animateTo(1f, tween(160))
    }

    Box(
        Modifier
            .offset(x = animLeft.value.dp, y = animTop.value.dp)
            .size(animWidth.value.dp, animHeight.value.dp)
            .zIndex(5f)
    ) {
        SubcomposeAsyncImage(
            model = displayedPath,
            contentDescription = "Enlarged speech balloon",
            // Preserve the native PNG aspect ratio. A mismatched database
            // bound may leave a little empty room, but it can no longer
            // stretch or crop the bubble to fill the animation frame.
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha.value }
        )
    }
}

private fun comicPageMemoryCacheKey(
    pageIndex: Int,
    loadedPath: String,
    fileLength: Long,
    fileModified: Long,
    retryGeneration: Int
): String =
    buildString {
        append("comic-page:")
        append(pageIndex)
        append(':')
        append(loadedPath)
        append(':')
        append(fileLength)
        append(':')
        append(fileModified)
        append(':')
        append(retryGeneration)
    }

@Composable
private fun WorkspaceTransitionPagePreloader(
    enabled: Boolean,
    page: ReaderPage?,
    viewportWidthDp: Int,
    viewportHeightDp: Int
) {
    val context =
        LocalContext.current
    val density =
        LocalDensity.current.density
    val pagePath =
        page?.localPath
    val pageFile =
        remember(pagePath) {
            pagePath?.let(::File)
        }
    val fileLength =
        pageFile
            ?.takeIf(File::isFile)
            ?.length()
            ?: 0L
    val fileModified =
        pageFile
            ?.takeIf(File::isFile)
            ?.lastModified()
            ?: 0L

    LaunchedEffect(
        enabled,
        page?.ref?.index,
        pagePath,
        fileLength,
        fileModified,
        viewportWidthDp,
        viewportHeightDp,
        density
    ) {
        val resolvedPage =
            page
                ?: return@LaunchedEffect
        val resolvedPath =
            pagePath
                ?: return@LaunchedEffect
        val resolvedFile =
            pageFile
                ?.takeIf(File::isFile)
                ?: return@LaunchedEffect

        if (!enabled) {
            return@LaunchedEffect
        }

        /*
         * Let the page-curl commit barrier and destination promotion finish
         * before doing low-priority Workspace preparation.
         */
        delay(
            WORKSPACE_PRELOAD_DELAY_MILLIS
        )

        withContext(
            Dispatchers.IO
        ) {
            imageAspectRatio(
                resolvedPath
            )
        }

        val targetWidth =
            (
                    viewportWidthDp *
                            density *
                            0.86f
                    ).roundToInt()
                .coerceAtLeast(1)
        val targetHeight =
            (
                    viewportHeightDp *
                            density *
                            0.70f
                    ).roundToInt()
                .coerceAtLeast(1)

        val request =
            ImageRequest.Builder(context)
                .data(
                    resolvedFile
                )
                .memoryCacheKey(
                    comicPageMemoryCacheKey(
                        pageIndex =
                            resolvedPage.ref.index,
                        loadedPath =
                            resolvedPath,
                        fileLength =
                            fileLength,
                        fileModified =
                            fileModified,
                        retryGeneration =
                            0
                    )
                )
                .size(
                    targetWidth,
                    targetHeight
                )
                .build()

        runCatching {
            context.imageLoader.execute(
                request
            )
        }
    }
}

private const val WORKSPACE_PRELOAD_DELAY_MILLIS =
    180L

/**
 * Displays an extracted comic page without treating the first transient Coil
 * decode failure as a permanent page error.
 *
 * Recovery sequence:
 * 1. Retry the existing file twice with a fresh Coil request key.
 * 2. If it still fails, delete the cached extraction and ask the repository to
 *    recreate it.
 * 3. Wait briefly for the recreated file, then retry it three more times.
 * 4. Show the manual Retry button only if every automatic recovery attempt
 *    fails.
 */
@Composable
internal fun ReliableLocalPageImage(
    pageIndex: Int,
    loadedPath: String,
    contentDescription: String,
    contentScale: ContentScale,
    onPageNeeded: () -> Unit,
    modifier: Modifier = Modifier,
    errorTextColor: Color = Color.White,
    onImageReadyChanged: (Boolean) -> Unit = {}
) {
    val context =
        LocalContext.current

    val currentOnPageNeeded by
    rememberUpdatedState(onPageNeeded)

    val currentOnImageReadyChanged by
    rememberUpdatedState(onImageReadyChanged)

    var retryGeneration by
    remember(
        pageIndex,
        loadedPath
    ) {
        mutableIntStateOf(0)
    }

    val pageFile =
        remember(loadedPath) {
            File(loadedPath)
        }

    val fileLength =
        pageFile
            .takeIf(File::isFile)
            ?.length()
            ?: 0L

    val fileModified =
        pageFile
            .takeIf(File::isFile)
            ?.lastModified()
            ?: 0L

    /*
     * A new memory-cache key forces Coil to create a fresh request even when
     * the recreated page uses exactly the same filesystem path.
     */
    val imageRequest =
        remember(
            loadedPath,
            retryGeneration,
            fileLength,
            fileModified
        ) {
            ImageRequest.Builder(context)
                .data(pageFile)
                .memoryCacheKey(
                    comicPageMemoryCacheKey(
                        pageIndex =
                            pageIndex,
                        loadedPath =
                            loadedPath,
                        fileLength =
                            fileLength,
                        fileModified =
                            fileModified,
                        retryGeneration =
                            retryGeneration
                    )
                )
                .build()
        }

    key(
        pageIndex,
        loadedPath,
        retryGeneration,
        fileLength,
        fileModified
    ) {
        SubcomposeAsyncImage(
            model =
                imageRequest,
            contentDescription =
                contentDescription,
            contentScale =
                contentScale,
            filterQuality =
                FilterQuality.High,
            loading = {
                SideEffect {
                    currentOnImageReadyChanged(
                        false
                    )
                }

                Box(
                    modifier =
                        Modifier.fillMaxSize(),
                    contentAlignment =
                        Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            },
            error = {
                SideEffect {
                    currentOnImageReadyChanged(
                        false
                    )
                }

                when {
                    /*
                     * First two failures: retry the intact file. These are
                     * commonly short decode/cache races and should never delete
                     * the extracted page immediately.
                     */
                    retryGeneration < 2 -> {
                        LaunchedEffect(
                            pageIndex,
                            loadedPath,
                            retryGeneration
                        ) {
                            delay(
                                180L *
                                        (
                                                retryGeneration +
                                                        1
                                                )
                            )

                            retryGeneration += 1
                        }

                        Box(
                            modifier =
                                Modifier.fillMaxSize(),
                            contentAlignment =
                                Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    /*
                     * Third failure: now treat the extraction as possibly
                     * incomplete. Delete it once, request a fresh extraction,
                     * and wait for that file before creating the next Coil
                     * request.
                     */
                    retryGeneration == 2 -> {
                        LaunchedEffect(
                            pageIndex,
                            loadedPath,
                            retryGeneration
                        ) {
                            runCatching {
                                pageFile.delete()
                            }

                            currentOnPageNeeded()

                            repeat(24) {
                                delay(125L)

                                if (
                                    pageFile.isFile &&
                                    pageFile.length() > 0L
                                ) {
                                    return@repeat
                                }
                            }

                            retryGeneration = 3
                        }

                        Box(
                            modifier =
                                Modifier.fillMaxSize(),
                            contentAlignment =
                                Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    /*
                     * Give the recreated file several fresh decode attempts.
                     */
                    retryGeneration < 6 -> {
                        LaunchedEffect(
                            pageIndex,
                            loadedPath,
                            retryGeneration
                        ) {
                            delay(
                                220L *
                                        (
                                                retryGeneration -
                                                        1
                                                )
                            )

                            retryGeneration += 1
                        }

                        Box(
                            modifier =
                                Modifier.fillMaxSize(),
                            contentAlignment =
                                Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment =
                                Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment =
                                    Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text =
                                        "Couldn't display this page",
                                    color =
                                        errorTextColor,
                                    textAlign =
                                        TextAlign.Center
                                )

                                Spacer(
                                    Modifier.height(12.dp)
                                )

                                Button(
                                    onClick = {
                                        /*
                                         * Restart at the repair stage so a
                                         * manual retry also recreates the file
                                         * and receives a new Coil request key.
                                         */
                                        retryGeneration = 2
                                    }
                                ) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }
            },
            success = {
                SideEffect {
                    currentOnImageReadyChanged(
                        true
                    )
                }

                SubcomposeAsyncImageContent()
            },
            modifier =
                modifier
        )
    }
}

@Composable
internal fun PageError(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
