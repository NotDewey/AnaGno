package com.comicreader.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.comicreader.app.domain.model.Comic
import com.comicreader.app.domain.model.PanelAnalysisProgress
import com.comicreader.app.ui.components.ContextualDockAction
import com.comicreader.app.ui.components.TopSearchBar
import com.comicreader.app.ui.components.comicReaderDockShade
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private const val EDIT_NONE = "none"
private const val EDIT_LIBRARY = "library"
private const val EDIT_CONTINUE_READING = "continue_reading"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onComicClick: (Comic) -> Unit,
    onContextualActionChanged: (ContextualDockAction?) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var editMode by rememberSaveable { mutableStateOf(EDIT_NONE) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var renameTarget by remember { mutableStateOf<Comic?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showRemoveFromContinueConfirmation by remember { mutableStateOf(false) }
    var showFinishConfirmation by remember { mutableStateOf(false) }

    val isLibraryEditing = editMode == EDIT_LIBRARY
    val isContinueReadingEditing = editMode == EDIT_CONTINUE_READING
    val isEditing = editMode != EDIT_NONE

    val selectedLibraryComics = state.comics.filter { it.id in selectedIds }
    val selectedContinueReadingComics = state.currentlyReading.filter { it.id in selectedIds }

    fun leaveEditMode() {
        editMode = EDIT_NONE
        selectedIds = emptySet()
    }

    fun enterLibraryEditMode(comicId: Long? = null) {
        editMode = EDIT_LIBRARY
        selectedIds = comicId?.let(::setOf) ?: emptySet()
    }

    fun enterContinueReadingEditMode(comicId: Long) {
        editMode = EDIT_CONTINUE_READING
        selectedIds = setOf(comicId)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.importComicFiles(uris) }

    val canShowContextualAction =
        !isEditing &&
                !state.isImporting &&
                renameTarget == null &&
                !showDeleteConfirmation &&
                !showRemoveFromContinueConfirmation &&
                !showFinishConfirmation

    LaunchedEffect(canShowContextualAction, onContextualActionChanged) {
        onContextualActionChanged(
            if (canShowContextualAction) {
                ContextualDockAction(
                    route = "library",
                    contentDescription = "Import comic",
                    onClick = {
                        importLauncher.launch(
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
                )
            } else {
                null
            }
        )
    }

    // The ViewModel survives tab switches, so clear a stale search on a fresh entry.
    LaunchedEffect(Unit) {
        viewModel.onSearchQueryChanged("")
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(
        state.comics.map(Comic::id),
        state.currentlyReading.map(Comic::id),
        editMode
    ) {
        val availableIds = when (editMode) {
            EDIT_LIBRARY -> state.comics.map(Comic::id).toSet()
            EDIT_CONTINUE_READING -> state.currentlyReading.map(Comic::id).toSet()
            else -> emptySet()
        }

        selectedIds = selectedIds.intersect(availableIds)

        if (isEditing && availableIds.isEmpty()) {
            leaveEditMode()
        }
    }

    renameTarget?.let { comic ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename comic") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("Library title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This changes only the title inside the app. The original file is not renamed.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameComic(comic, renameText)
                        renameTarget = null
                        leaveEditMode()
                    },
                    enabled = renameText.trim().isNotEmpty()
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = {
                Text(if (selectedLibraryComics.size == 1) "Remove comic?" else "Remove comics?")
            },
            text = {
                Text(
                    "Remove ${selectedLibraryComics.size} selected item${if (selectedLibraryComics.size == 1) "" else "s"} " +
                            "from the library? Saved progress, bookmarks, panels, and cached pages will be removed. " +
                            "The original CBZ, CBR, or PDF file will not be deleted."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteComics(selectedLibraryComics)
                        showDeleteConfirmation = false
                        leaveEditMode()
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") }
            }
        )
    }

    if (showRemoveFromContinueConfirmation) {
        val itemCount = selectedContinueReadingComics.size

        AlertDialog(
            onDismissRequest = { showRemoveFromContinueConfirmation = false },
            title = { Text("Remove from Continue Reading?") },
            text = {
                Text(
                    if (itemCount == 1) {
                        "This comic will leave Continue Reading, but it will stay in your Library and keep its saved page. Opening it again will return it to Continue Reading."
                    } else {
                        "These $itemCount comics will leave Continue Reading, but they will stay in your Library and keep their saved pages. Opening them again will return them to Continue Reading."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeFromContinueReading(selectedContinueReadingComics)
                        showRemoveFromContinueConfirmation = false
                        leaveEditMode()
                    },
                    enabled = selectedContinueReadingComics.isNotEmpty()
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveFromContinueConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showFinishConfirmation) {
        val itemCount = selectedContinueReadingComics.size

        AlertDialog(
            onDismissRequest = { showFinishConfirmation = false },
            title = {
                Text(if (itemCount == 1) "Mark comic as finished?" else "Mark comics as finished?")
            },
            text = {
                Text(
                    if (itemCount == 1) {
                        "It will leave Continue Reading and its Library cover will become black and white. You can rate it later."
                    } else {
                        "These $itemCount comics will leave Continue Reading and their Library covers will become black and white. You can rate them later."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.markFinished(selectedContinueReadingComics)
                        showFinishConfirmation = false
                        leaveEditMode()
                    },
                    enabled = selectedContinueReadingComics.isNotEmpty()
                ) { Text("Mark finished") }
            },
            dismissButton = {
                TextButton(onClick = { showFinishConfirmation = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            if (isEditing) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { leaveEditMode() }) {
                            Icon(Icons.Default.Close, contentDescription = "Leave edit mode")
                        }
                    },
                    actions = {
                        if (isContinueReadingEditing) {
                            IconButton(
                                onClick = { showFinishConfirmation = true },
                                enabled = selectedContinueReadingComics.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Mark selected comics as finished"
                                )
                            }

                            IconButton(
                                onClick = { showRemoveFromContinueConfirmation = true },
                                enabled = selectedContinueReadingComics.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.RemoveCircleOutline,
                                    contentDescription = "Remove selected comics from Continue Reading"
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    selectedLibraryComics.singleOrNull()?.let {
                                        renameTarget = it
                                        renameText = it.title
                                    }
                                },
                                enabled = selectedLibraryComics.size == 1
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename selected comic")
                            }

                            IconButton(
                                onClick = { showDeleteConfirmation = true },
                                enabled = selectedLibraryComics.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove selected comics")
                            }
                        }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (!isEditing) {
                TopSearchBar(
                    title = "Library",
                    query = state.searchQuery,
                    onQueryChange = viewModel::onSearchQueryChanged,
                    placeholder = "Search by title or series",
                    trailing = {
                        IconButton(
                            onClick = { enterLibraryEditMode() },
                            enabled = state.comics.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Manage library"
                            )
                        }
                    }
                )
            }

            if (state.isImporting) {
                LinearProgressIndicator(
                    progress = { state.importProgress ?: 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Importing ${((state.importProgress ?: 0f) * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            if (state.totalLibraryCount == 0 && !state.isImporting) {
                EmptyLibraryMessage()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(ComicGridBottomCurveShape()),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        top = 6.dp,
                        end = 8.dp,
                        bottom = 112.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.currentlyReading.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = "Continue reading",
                                count = if (state.searchQuery.isBlank()) {
                                    state.totalReadingCount
                                } else {
                                    state.currentlyReading.size
                                }
                            )
                        }

                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ContinueReadingRow(
                                comics = state.currentlyReading,
                                isEditing = isContinueReadingEditing,
                                enabled = !isLibraryEditing,
                                selectedIds = selectedIds,
                                onComicClick = onComicClick,
                                onToggleSelection = { comicId ->
                                    selectedIds = if (comicId in selectedIds) {
                                        selectedIds - comicId
                                    } else {
                                        selectedIds + comicId
                                    }
                                },
                                onLongPress = { comicId ->
                                    enterContinueReadingEditMode(comicId)
                                }
                            )
                        }
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader(
                            title = "All comics",
                            count = if (state.searchQuery.isBlank()) {
                                state.totalLibraryCount
                            } else {
                                state.comics.size
                            },
                            topPadding = if (state.currentlyReading.isNotEmpty()) 20.dp else 10.dp
                        )
                    }

                    if (state.comics.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No comics match your search.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        gridItems(state.comics, key = { it.id }) { comic ->
                            ComicGridItem(
                                comic = comic,
                                panelProgress = state.panelProgress[comic.id]
                                    ?: PanelAnalysisProgress(comicId = comic.id),
                                isEditing = isLibraryEditing,
                                isSelected = comic.id in selectedIds,
                                enabled = !isContinueReadingEditing,
                                onClick = { onComicClick(comic) },
                                onToggleSelection = {
                                    selectedIds = if (comic.id in selectedIds) {
                                        selectedIds - comic.id
                                    } else {
                                        selectedIds + comic.id
                                    }
                                },
                                onLongPress = { enterLibraryEditMode(comic.id) },
                                onToggleFavorite = { viewModel.toggleFavorite(comic) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    topPadding: androidx.compose.ui.unit.Dp = 10.dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = topPadding, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.weight(1f))

        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ContinueReadingRow(
    comics: List<Comic>,
    isEditing: Boolean,
    enabled: Boolean,
    selectedIds: Set<Long>,
    onComicClick: (Comic) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onLongPress: (Long) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Make every Continue Reading card consume the whole visible section width.
        // The 8.dp subtraction matches the 4.dp content padding on both sides.
        val cardWidth = (maxWidth - 8.dp).coerceAtLeast(0.dp)

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            lazyItems(comics, key = { it.id }) { comic ->
                ContinueReadingCard(
                    comic = comic,
                    isEditing = isEditing,
                    isSelected = comic.id in selectedIds,
                    enabled = enabled,
                    modifier = Modifier.width(cardWidth),
                    onClick = { onComicClick(comic) },
                    onToggleSelection = { onToggleSelection(comic.id) },
                    onLongPress = { onLongPress(comic.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueReadingCard(
    comic: Comic,
    isEditing: Boolean,
    isSelected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onLongPress: () -> Unit
) {
    val progress = comic.readingProgress()
    val currentPage = if (comic.pageCount > 0) {
        comic.lastReadPage.coerceIn(0, comic.pageCount - 1) + 1
    } else {
        0
    }
    val pagesLeft = (comic.pageCount - currentPage).coerceAtLeast(0)
    val cardShape = RoundedCornerShape(18.dp)
    val coverShape = RoundedCornerShape(12.dp)
    val dockShade = comicReaderDockShade()

    Card(
        modifier = modifier
            .height(132.dp)
            .combinedClickable(
                enabled = enabled,
                onClick = {
                    if (isEditing) onToggleSelection() else onClick()
                },
                onLongClick = onLongPress
            )
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = cardShape
                    )
                } else {
                    Modifier
                }
            ),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = dockShade
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f)
            ) {
                AsyncImage(
                    model = comic.coverPagePath,
                    contentDescription = comic.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(coverShape)
                        .background(MaterialTheme.colorScheme.surface)
                )

                if (isEditing) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection() },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(0.dp)
                    )
                } else {
                    ReadingProgressBadge(
                        progress = progress,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = comic.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(14.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                )

                Spacer(Modifier.height(7.dp))

                Text(
                    text = when {
                        comic.pageCount <= 0 -> "Reading progress unavailable"
                        pagesLeft == 1 -> "Page $currentPage of ${comic.pageCount} · 1 page left"
                        else -> "Page $currentPage of ${comic.pageCount} · $pagesLeft pages left"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ReadingProgressBadge(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = 420f
        ),
        label = "Reading progress donut"
    )

    val progressColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier.size(38.dp),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.70f),
        shadowElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val strokeWidth = 3.dp.toPx()
                val radius = size.minDimension / 2f - strokeWidth / 2f

                drawCircle(
                    color = Color.White.copy(alpha = 0.28f),
                    radius = radius,
                    center = Offset(
                        size.width / 2f,
                        size.height / 2f
                    ),
                    style = Stroke(width = strokeWidth)
                )

                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round
                    )
                )
            }

            Text(
                text = "${(animatedProgress * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1
            )
        }
    }
}

private fun Comic.readingProgress(): Float {
    if (pageCount <= 0) return 0f

    return (lastReadPage.coerceIn(0, pageCount - 1) + 1).toFloat() / pageCount.toFloat()
}

/**
 * Clips the bottom of the scrolling grid as a row of comic-card curves.
 *
 * The column calculation mirrors GridCells.Adaptive(minSize = 120.dp) with
 * 8.dp horizontal content padding and 8.dp spacing. The curve radius matches
 * the comic cards much more closely than one oversized viewport corner.
 */
private class ComicGridBottomCurveShape(
    private val horizontalPadding: androidx.compose.ui.unit.Dp = 8.dp,
    private val minimumCellWidth: androidx.compose.ui.unit.Dp = 120.dp,
    private val horizontalSpacing: androidx.compose.ui.unit.Dp = 8.dp,
    private val cornerRadius: androidx.compose.ui.unit.Dp = 16.dp
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val horizontalPaddingPx =
            with(density) {
                horizontalPadding.toPx()
            }

        val minimumCellWidthPx =
            with(density) {
                minimumCellWidth.toPx()
            }

        val horizontalSpacingPx =
            with(density) {
                horizontalSpacing.toPx()
            }

        val requestedRadiusPx =
            with(density) {
                cornerRadius.toPx()
            }

        val availableWidth =
            (
                    size.width -
                            horizontalPaddingPx *
                            2f
                    )
                .coerceAtLeast(
                    0f
                )

        val columnCount =
            max(
                1,
                floor(
                    (
                            availableWidth +
                                    horizontalSpacingPx
                            ) /
                            (
                                    minimumCellWidthPx +
                                            horizontalSpacingPx
                                    )
                ).toInt()
            )

        val totalSpacing =
            horizontalSpacingPx *
                    (
                            columnCount -
                                    1
                            )

        val cellWidth =
            (
                    availableWidth -
                            totalSpacing
                    ) /
                    columnCount

        val radius =
            requestedRadiusPx
                .coerceAtMost(
                    cellWidth /
                            2f
                )
                .coerceAtMost(
                    size.height
                )

        val path =
            Path()

        /*
         * Everything above the final corner radius remains fully visible.
         */
        path.addRect(
            Rect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom =
                    (
                            size.height -
                                    radius
                            )
                        .coerceAtLeast(
                            0f
                        )
            )
        )

        /*
         * Each column gets its own rounded lower corners. The spaces between
         * columns reveal the screen background and visually continue the
         * natural curve already used by every comic cover.
         */
        repeat(
            columnCount
        ) {
                columnIndex ->

            val left =
                horizontalPaddingPx +
                        columnIndex *
                        (
                                cellWidth +
                                        horizontalSpacingPx
                                )

            val right =
                left +
                        cellWidth

            path.addRoundRect(
                RoundRect(
                    left = left,
                    top = 0f,
                    right = right,
                    bottom = size.height,
                    topLeftCornerRadius =
                        CornerRadius.Zero,
                    topRightCornerRadius =
                        CornerRadius.Zero,
                    bottomRightCornerRadius =
                        CornerRadius(
                            radius,
                            radius
                        ),
                    bottomLeftCornerRadius =
                        CornerRadius(
                            radius,
                            radius
                        )
                )
            )
        }

        return Outline.Generic(
            path
        )
    }
}

@Composable
private fun EmptyLibraryMessage() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Your library is empty", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Tap + to import a CBZ, CBR, or PDF", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicGridItem(
    comic: Comic,
    panelProgress: PanelAnalysisProgress,
    isEditing: Boolean,
    isSelected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onLongPress: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val finishedColorFilter =
        remember(comic.isFinished) {
            if (comic.isFinished) {
                ColorFilter.colorMatrix(
                    ColorMatrix().apply {
                        setToSaturation(0f)
                    }
                )
            } else {
                null
            }
        }

    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                enabled = enabled,
                onClick = { if (isEditing) onToggleSelection() else onClick() },
                onLongClick = onLongPress
            )
    ) {
        Box {
            AsyncImage(
                model = comic.coverPagePath,
                contentDescription = comic.title,
                contentScale = ContentScale.Crop,
                colorFilter = finishedColorFilter,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(MaterialTheme.shapes.medium)
                    .then(
                        if (isSelected) Modifier.border(
                            3.dp,
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.shapes.medium
                        ) else Modifier
                    )
            )
            if (comic.isFinished) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Finished",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(30.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                )
            }

            if (isEditing) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.align(Alignment.TopStart)
                )
            } else {
                IconButton(
                    onClick = onToggleFavorite,
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        imageVector = if (comic.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}