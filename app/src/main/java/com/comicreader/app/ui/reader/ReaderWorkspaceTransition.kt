package com.comicreader.app.ui.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.comicreader.app.ui.haptics.AppHaptics
import com.comicreader.app.ui.haptics.HapticThrottle
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * Visual transition between immersive reading and the light, freely scrollable
 * page-navigation workspace.
 *
 * ReaderScreen owns reader state and behavior. This file owns:
 *
 * - the shared transition timeline
 * - black-to-light workspace background
 * - the framed page carousel
 * - free horizontal scrolling
 * - focused-page elevation
 * - top and bottom chrome motion
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderWorkspaceTransition(
    visible: Boolean,
    pages: List<ReaderPage>,
    currentPage: Int,
    readingDirection: ReadingDirection,
    title: String,
    remainingPages: Int,
    onPageNeeded: (Int) -> Unit,
    onBack: () -> Unit,
    onBookmarks: () -> Unit,
    onToggleReadingMode: () -> Unit,
    onSettings: () -> Unit,
    onPreviewPageNeeded: (Int) -> Unit,
    onScrubberCommit: (Int) -> Unit,
    onJumpToPage: (Int) -> Unit,
    externalTargetPage: Int?,
    onExternalTargetReady: (Int) -> Unit,
    onCurrentPageTap: () -> Unit,
    modifier: Modifier = Modifier,
    liveReader: @Composable BoxScope.() -> Unit,
    overlayContent: @Composable BoxScope.() -> Unit = {}
) {
    val controlsProgress by
    animateFloatAsState(
        targetValue =
            if (visible) {
                1f
            } else {
                0f
            },
        animationSpec = tween(
            durationMillis =
                if (visible) {
                    470
                } else {
                    420
                },
            easing =
                FastOutSlowInEasing
        ),
        label =
            "reader-workspace-progress"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(
                lerp(
                    Color.Black,
                    WORKSPACE_BACKGROUND,
                    controlsProgress
                )
            )
    ) {
        val density =
            LocalDensity.current

        val workspaceActive =
            visible ||
                    controlsProgress >
                    0.001f

        val readerFade =
            (
                    1f -
                            controlsProgress *
                            3.2f
                    ).coerceIn(
                    0f,
                    1f
                )

        val workspaceAlpha =
            (
                    controlsProgress *
                            3.2f
                    ).coerceIn(
                    0f,
                    1f
                )

        /*
         * The final page slot occupies 86% of the screen width. Scaling the
         * complete carousel by 1 / 0.86 at progress zero aligns the current
         * card with the immersive reader before it contracts.
         */
        val pageSlotWidth =
            maxWidth *
                    0.86f

        val maximumPageHeight =
            maxHeight *
                    0.70f

        val immersiveToWorkspaceScale =
            1f /
                    0.86f

        val sharedWorkspaceScale =
            immersiveToWorkspaceScale +
                    (
                            1f -
                                    immersiveToWorkspaceScale
                            ) *
                    controlsProgress

        val workspaceTranslationY =
            with(density) {
                7.dp.toPx()
            } *
                    controlsProgress

        val horizontalPadding =
            (
                    maxWidth -
                            pageSlotWidth
                    ) /
                    2f

        val topChromeProgress =
            (
                    controlsProgress /
                            0.82f
                    ).coerceIn(
                    0f,
                    1f
                )

        val bottomChromeProgress =
            (
                    (
                            controlsProgress -
                                    0.06f
                            ) /
                            0.88f
                    ).coerceIn(
                    0f,
                    1f
                )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha =
                        readerFade
                },
            content =
                liveReader
        )

        if (workspaceActive) {
            ReaderWorkspaceCarousel(
                pages =
                    pages,
                currentPage =
                    currentPage,
                readingDirection =
                    readingDirection,
                pageSlotWidth =
                    pageSlotWidth,
                maximumPageHeight =
                    maximumPageHeight,
                horizontalPadding =
                    horizontalPadding,
                transitionProgress =
                    controlsProgress,
                workspaceAlpha =
                    workspaceAlpha,
                sharedScale =
                    sharedWorkspaceScale,
                translationYPx =
                    workspaceTranslationY,
                onPageNeeded =
                    onPageNeeded,
                onPageSettled =
                    onJumpToPage,
                externalTargetPage =
                    externalTargetPage,
                onExternalTargetReady =
                    onExternalTargetReady,
                onPageTap = { pageIndex ->
                    if (
                        pageIndex !=
                        currentPage
                    ) {
                        onJumpToPage(
                            pageIndex
                        )
                    }

                    onCurrentPageTap()
                },
                userScrollEnabled =
                    visible &&
                            controlsProgress >=
                            0.98f,
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(12f)
            )
        }

        overlayContent()

        if (workspaceActive) {
            Surface(
                color =
                    Color.Transparent,
                contentColor =
                    Color(0xFF15171C),
                tonalElevation =
                    0.dp,
                shadowElevation =
                    0.dp,
                modifier = Modifier
                    .align(
                        Alignment.TopCenter
                    )
                    .windowInsetsPadding(
                        WindowInsets
                            .statusBarsIgnoringVisibility
                    )
                    .graphicsLayer {
                        alpha =
                            topChromeProgress

                        translationY =
                            with(density) {
                                (
                                        -34.dp *
                                                (
                                                        1f -
                                                                topChromeProgress
                                                        )
                                        ).toPx()
                            }
                    }
                    .zIndex(40f)
            ) {
                CompactReaderTopBar(
                    title =
                        title,
                    onBack =
                        onBack,
                    onBookmarks =
                        onBookmarks,
                    onToggleReadingMode =
                        onToggleReadingMode,
                    onSettings =
                        onSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                )
            }

            Surface(
                color =
                    Color.Transparent,
                contentColor =
                    Color(0xFF30343B),
                tonalElevation =
                    0.dp,
                shadowElevation =
                    0.dp,
                modifier = Modifier
                    .align(
                        Alignment.BottomCenter
                    )
                    /*
                     * Lift the complete scrubber block by roughly 15% of its
                     * normal 66 dp resting height, giving the slider and page
                     * count more breathing room above the bottom edge.
                     */
                    .padding(
                        bottom = 10.dp
                    )
                    .graphicsLayer {
                        alpha =
                            bottomChromeProgress

                        translationY =
                            with(density) {
                                (
                                        48.dp *
                                                (
                                                        1f -
                                                                bottomChromeProgress
                                                        )
                                        ).toPx()
                            }
                    }
                    .zIndex(40f)
            ) {
                ReaderBottomControls(
                    pages =
                        pages,
                    currentPage =
                        currentPage,
                    pageCount =
                        pages.size,
                    remainingPages =
                        remainingPages,
                    onPreviewPageNeeded =
                        onPreviewPageNeeded,
                    onJumpToPage =
                        onScrubberCommit,
                    modifier = Modifier
                        .fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ReaderWorkspaceCarousel(
    pages: List<ReaderPage>,
    currentPage: Int,
    readingDirection: ReadingDirection,
    pageSlotWidth: Dp,
    maximumPageHeight: Dp,
    horizontalPadding: Dp,
    transitionProgress: Float,
    workspaceAlpha: Float,
    sharedScale: Float,
    translationYPx: Float,
    onPageNeeded: (Int) -> Unit,
    onPageSettled: (Int) -> Unit,
    externalTargetPage: Int?,
    onExternalTargetReady: (Int) -> Unit,
    onPageTap: (Int) -> Unit,
    userScrollEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex =
                currentPage.coerceIn(
                    0,
                    pages.lastIndex
                )
        )

    var focusedPageIndex by
    remember {
        mutableIntStateOf(
            currentPage
        )
    }

    var lastMagnetizedPage by
    remember {
        mutableIntStateOf(
            -1
        )
    }

    val hapticView =
        LocalView.current

    val scrollHapticThrottle =
        remember {
            HapticThrottle(
                minimumIntervalMillis =
                    65L
            )
        }

    var lastScrollHapticPage by
    remember {
        mutableIntStateOf(
            currentPage
        )
    }

    var externalTargetImageReady by
    remember(
        externalTargetPage
    ) {
        mutableStateOf(
            false
        )
    }

    /*
     * Re-center the carousel when controls open or when a scrubber/bookmark
     * changes the current page outside this free-scroll workspace.
     */
    LaunchedEffect(
        currentPage,
        userScrollEnabled,
        externalTargetPage
    ) {
        if (
            externalTargetPage ==
            null &&
            !listState.isScrollInProgress &&
            currentPage in
            pages.indices
        ) {
            listState.scrollToItem(
                currentPage
            )
            focusedPageIndex =
                currentPage
        }
    }

    /*
     * Slider commit handoff:
     *
     * Position the requested page immediately while the full-page preview is
     * still covering the workspace. The soft magnet is locked during this
     * transaction, so it cannot re-select the old nearest page.
     */
    LaunchedEffect(
        externalTargetPage,
        pages.size
    ) {
        val target =
            externalTargetPage
                ?.takeIf {
                    it in
                            pages.indices
                }
                ?: return@LaunchedEffect

        lastMagnetizedPage =
            -1
        externalTargetImageReady =
            false
        focusedPageIndex =
            target

        listState.scrollToItem(
            target
        )
    }

    /*
     * Remove the preview only after:
     *
     * - the target card is centered
     * - its image has successfully rendered
     * - one complete Compose frame has presented that result
     */
    LaunchedEffect(
        externalTargetPage,
        externalTargetImageReady,
        listState
    ) {
        val target =
            externalTargetPage
                ?.takeIf {
                    it in
                            pages.indices
                }
                ?: return@LaunchedEffect

        if (!externalTargetImageReady) {
            return@LaunchedEffect
        }

        snapshotFlow {
            isWorkspacePageCentered(
                listState =
                    listState,
                pageIndex =
                    target
            )
        }
            .first {
                it
            }

        withFrameNanos { }

        if (
            externalTargetPage ==
            target
        ) {
            onExternalTargetReady(
                target
            )
        }
    }

    /*
     * The page closest to the viewport center receives the stronger frame
     * shadow while the user freely scrolls.
     */
    LaunchedEffect(
        listState
    ) {
        snapshotFlow {
            nearestWorkspacePage(
                listState =
                    listState
            )
        }.collect { nearest ->
            if (
                nearest != null &&
                nearest != focusedPageIndex
            ) {
                focusedPageIndex =
                    nearest

                val userDrivenScroll =
                    listState
                        .isScrollInProgress &&
                            userScrollEnabled &&
                            externalTargetPage ==
                            null

                if (
                    userDrivenScroll &&
                    nearest !=
                    lastScrollHapticPage
                ) {
                    if (
                        scrollHapticThrottle
                            .tryAcquire()
                    ) {
                        AppHaptics.scrollTick(
                            hapticView
                        )
                    }

                    lastScrollHapticPage =
                        nearest
                } else if (
                    !userDrivenScroll
                ) {
                    lastScrollHapticPage =
                        nearest
                }
            }
        }
    }

    /*
     * Free scrolling stays genuinely free.
     *
     * When the user releases between two pages, the carousel remains exactly
     * there. A page only receives a gentle magnetic pull when its center is
     * already close to the viewport center.
     */
    LaunchedEffect(
        listState,
        currentPage,
        readingDirection,
        userScrollEnabled,
        externalTargetPage
    ) {
        snapshotFlow {
            WorkspaceScrollSnapshot(
                isScrolling =
                    listState
                        .isScrollInProgress,
                candidate =
                    nearestWorkspacePageMagnet(
                        listState =
                            listState
                    )
            )
        }.collect { snapshot ->
            if (
                snapshot.isScrolling
            ) {
                lastMagnetizedPage =
                    -1
                return@collect
            }

            if (
                !userScrollEnabled ||
                externalTargetPage !=
                null
            ) {
                return@collect
            }

            val candidate =
                snapshot.candidate
                    ?: return@collect

            val attractionRange =
                candidate.itemSizePx *
                        PAGE_MAGNET_RANGE_FRACTION

            val closeEnough =
                abs(
                    candidate.centerDeltaPx
                ) <=
                        attractionRange

            if (!closeEnough) {
                /*
                 * Deliberately leave the row between pages.
                 */
                lastMagnetizedPage =
                    -1
                return@collect
            }

            if (
                candidate.index ==
                lastMagnetizedPage &&
                abs(
                    candidate.centerDeltaPx
                ) <=
                1f
            ) {
                return@collect
            }

            lastMagnetizedPage =
                candidate.index

            val physicalCorrection =
                candidate.centerDeltaPx

            val scrollCorrection =
                if (
                    readingDirection ==
                    ReadingDirection.RIGHT_TO_LEFT
                ) {
                    -physicalCorrection
                } else {
                    physicalCorrection
                }

            if (
                abs(
                    scrollCorrection
                ) >
                1f
            ) {
                if (
                    scrollHapticThrottle
                        .tryAcquire()
                ) {
                    AppHaptics.magnetTick(
                        hapticView
                    )
                }

                listState.animateScrollBy(
                    value =
                        scrollCorrection,
                    animationSpec =
                        tween(
                            durationMillis =
                                PAGE_MAGNET_DURATION_MILLIS,
                            easing =
                                FastOutSlowInEasing
                        )
                )
            }

            if (
                candidate.index !=
                currentPage
            ) {
                onPageSettled(
                    candidate.index
                )
            }
        }
    }

    LazyRow(
        state =
            listState,
        reverseLayout =
            readingDirection ==
                    ReadingDirection.RIGHT_TO_LEFT,
        contentPadding =
            PaddingValues(
                horizontal =
                    horizontalPadding
            ),
        horizontalArrangement =
            Arrangement.spacedBy(
                PAGE_GUTTER
            ),
        verticalAlignment =
            Alignment.CenterVertically,
        userScrollEnabled =
            userScrollEnabled &&
                    externalTargetPage ==
                    null,
        modifier = modifier
            .graphicsLayer {
                alpha =
                    workspaceAlpha
                scaleX =
                    sharedScale
                scaleY =
                    sharedScale
                translationY =
                    translationYPx
                clip =
                    false
            }
    ) {
        itemsIndexed(
            items =
                pages,
            key = { _, page ->
                page.ref.index
            }
        ) { index, page ->
            Box(
                modifier = Modifier
                    .width(
                        pageSlotWidth
                    )
                    .fillMaxHeight(),
                contentAlignment =
                    Alignment.Center
            ) {
                ReaderWorkspacePageCard(
                    page =
                        page,
                    pageNumber =
                        index + 1,
                    maximumWidth =
                        pageSlotWidth,
                    maximumHeight =
                        maximumPageHeight,
                    focused =
                        index ==
                                focusedPageIndex,
                    transitionProgress =
                        transitionProgress,
                    onImageReadyChanged = { ready ->
                        if (
                            index ==
                            externalTargetPage
                        ) {
                            externalTargetImageReady =
                                ready
                        }
                    },
                    onPageNeeded = {
                        onPageNeeded(
                            index
                        )
                    },
                    onTap = {
                        onPageTap(
                            index
                        )
                    }
                )
            }
        }
    }
}

private fun isWorkspacePageCentered(
    listState:
    androidx.compose.foundation.lazy.LazyListState,
    pageIndex: Int
): Boolean {
    val layout =
        listState.layoutInfo

    val item =
        layout.visibleItemsInfo
            .firstOrNull {
                it.index ==
                        pageIndex
            }
            ?: return false

    val viewportCenter =
        (
                layout.viewportStartOffset +
                        layout.viewportEndOffset
                ) /
                2f

    val itemCenter =
        item.offset +
                item.size /
                2f

    return abs(
        itemCenter -
                viewportCenter
    ) <=
            EXTERNAL_TARGET_CENTER_TOLERANCE_PX
}

private data class WorkspacePageMagnet(
    val index: Int,
    val centerDeltaPx: Float,
    val itemSizePx: Int
)

private data class WorkspaceScrollSnapshot(
    val isScrolling: Boolean,
    val candidate: WorkspacePageMagnet?
)

private fun nearestWorkspacePage(
    listState:
    androidx.compose.foundation.lazy.LazyListState
): Int? =
    nearestWorkspacePageMagnet(
        listState =
            listState
    )
        ?.index

private fun nearestWorkspacePageMagnet(
    listState:
    androidx.compose.foundation.lazy.LazyListState
): WorkspacePageMagnet? {
    val layout =
        listState.layoutInfo

    if (
        layout.visibleItemsInfo.isEmpty()
    ) {
        return null
    }

    val viewportCenter =
        (
                layout.viewportStartOffset +
                        layout.viewportEndOffset
                ) /
                2f

    val nearest =
        layout.visibleItemsInfo
            .minByOrNull { item ->
                abs(
                    item.offset +
                            item.size /
                            2f -
                            viewportCenter
                )
            }
            ?: return null

    return WorkspacePageMagnet(
        index =
            nearest.index,
        centerDeltaPx =
            nearest.offset +
                    nearest.size /
                    2f -
                    viewportCenter,
        itemSizePx =
            nearest.size
    )
}

@Composable
private fun ReaderWorkspacePageCard(
    page: ReaderPage,
    pageNumber: Int,
    maximumWidth: Dp,
    maximumHeight: Dp,
    focused: Boolean,
    transitionProgress: Float,
    onImageReadyChanged: (Boolean) -> Unit,
    onPageNeeded: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagePath =
        page.localPath

    val pageAspectRatio =
        remember(
            pagePath
        ) {
            pagePath
                ?.let(
                    ::imageAspectRatio
                )
                ?.takeIf {
                    it >
                            0f
                }
                ?: DEFAULT_PAGE_ASPECT
        }

    /*
     * The physical frame wraps the real page dimensions rather than a cropped
     * preview. Wide pages are limited by available height.
     */
    val contentWidth =
        if (
            maximumWidth /
            pageAspectRatio <=
            maximumHeight
        ) {
            maximumWidth
        } else {
            maximumHeight *
                    pageAspectRatio
        }

    val contentHeight =
        contentWidth /
                pageAspectRatio

    val cardWidth =
        contentWidth +
                PAGE_FRAME_PADDING *
                2

    val cardHeight =
        contentHeight +
                PAGE_FRAME_PADDING *
                2

    val shadowElevation =
        (
                if (focused) {
                    CURRENT_PAGE_SHADOW
                } else {
                    NEIGHBOR_PAGE_SHADOW
                }
                ) *
                transitionProgress

    Surface(
        color =
            PAGE_FRAME_COLOR,
        contentColor =
            Color.Unspecified,
        shape =
            RectangleShape,
        border =
            BorderStroke(
                width =
                    1.dp,
                color =
                    PAGE_BORDER_COLOR
            ),
        tonalElevation =
            0.dp,
        shadowElevation =
            shadowElevation,
        modifier = modifier
            .size(
                width =
                    cardWidth,
                height =
                    cardHeight
            )
            .pointerInput(
                page.ref.index,
                onTap
            ) {
                detectTapGestures {
                    onTap()
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    PAGE_FRAME_COLOR
                )
                .padding(
                    PAGE_FRAME_PADDING
                ),
            contentAlignment =
                Alignment.Center
        ) {
            when {
                page.localPath == null -> {
                    LaunchedEffect(
                        page.ref.index
                    ) {
                        onPageNeeded()
                    }
                }

                else -> {
                    ReliableLocalPageImage(
                        pageIndex =
                            page.ref.index,
                        loadedPath =
                            requireNotNull(
                                page.localPath
                            ),
                        contentDescription =
                            "Workspace page $pageNumber",
                        contentScale =
                            ContentScale.FillBounds,
                        onPageNeeded =
                            onPageNeeded,
                        onImageReadyChanged =
                            onImageReadyChanged,
                        errorTextColor =
                            Color(0xFF30343B),
                        modifier = Modifier
                            .fillMaxSize()
                    )
                }
            }
        }
    }
}

private val WORKSPACE_BACKGROUND =
    Color(0xFFF3F4F7)

private val PAGE_FRAME_COLOR =
    Color(0xFFECEEF2)

private val PAGE_BORDER_COLOR =
    Color(0xFFD4D7DD)

private val PAGE_FRAME_PADDING =
    5.dp

private val PAGE_GUTTER =
    10.dp

private val CURRENT_PAGE_SHADOW =
    10.dp

private val NEIGHBOR_PAGE_SHADOW =
    4.dp

/*
 * Attraction applies only when a page center is already within 13% of that
 * page's width from the viewport center. Mid-gap positions remain untouched.
 */
private const val PAGE_MAGNET_RANGE_FRACTION =
    0.13f

private const val PAGE_MAGNET_DURATION_MILLIS =
    145

private const val EXTERNAL_TARGET_CENTER_TOLERANCE_PX =
    2f

private const val DEFAULT_PAGE_ASPECT =
    2f / 3f
