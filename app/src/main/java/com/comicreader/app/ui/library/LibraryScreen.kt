package com.comicreader.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.floor
import kotlin.math.max
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.comicreader.app.domain.model.Comic
import com.comicreader.app.domain.model.PanelAnalysisProgress
import com.comicreader.app.ui.components.ContextualDockAction
import com.comicreader.app.ui.components.TopSearchBar
import androidx.compose.foundation.background

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onComicClick: (Comic) -> Unit,
    onContextualActionChanged:
        (ContextualDockAction?) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var isEditing by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var renameTarget by remember { mutableStateOf<Comic?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val selectedComics = state.comics.filter { it.id in selectedIds }

    fun leaveEditMode() {
        isEditing = false
        selectedIds = emptySet()
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.importComicFiles(uris) }

    val canShowContextualAction =
        !isEditing &&
                !state.isImporting &&
                renameTarget == null &&
                !showDeleteConfirmation

    LaunchedEffect(
        canShowContextualAction,
        onContextualActionChanged
    ) {
        onContextualActionChanged(
            if (
                canShowContextualAction
            ) {
                ContextualDockAction(
                    route =
                        "library",
                    contentDescription =
                        "Import comic",
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

    // The ViewModel survives tab switches (that's what keeps scroll position / avoids
    // re-fetching on the bottom nav), so searchQuery would otherwise persist too.
    // Reset it explicitly on every fresh entry to this screen instead.
    LaunchedEffect(Unit) {
        viewModel.onSearchQueryChanged("")
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(state.comics.map(Comic::id)) {
        selectedIds = selectedIds.intersect(state.comics.map(Comic::id).toSet())
        if (isEditing && state.comics.isEmpty()) leaveEditMode()
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
                Text(if (selectedComics.size == 1) "Remove comic?" else "Remove comics?")
            },
            text = {
                Text(
                    "Remove ${selectedComics.size} selected item${if (selectedComics.size == 1) "" else "s"} " +
                            "from the library? Saved progress, bookmarks, panels, and cached pages will be removed. " +
                            "The original CBZ, CBR, or PDF file will not be deleted."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteComics(selectedComics)
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
                        IconButton(
                            onClick = {
                                selectedComics.singleOrNull()?.let {
                                    renameTarget = it
                                    renameText = it.title
                                }
                            },
                            enabled = selectedComics.size == 1
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename selected comic")
                        }
                        IconButton(
                            onClick = { showDeleteConfirmation = true },
                            enabled = selectedComics.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove selected comics")
                        }
                    }
                )
            }
        },
        snackbarHost = {
            SnackbarHost(
                snackbarHostState
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!isEditing) {
                TopSearchBar(
                    title = "Library",
                    query = state.searchQuery,
                    onQueryChange = viewModel::onSearchQueryChanged,
                    placeholder = "Search by title or series",
                    trailing = {
                        IconButton(
                            onClick = {
                                isEditing = true
                            },
                            enabled =
                                state.comics.isNotEmpty()
                        ) {
                            Icon(
                                imageVector =
                                    Icons.Default.Tune,
                                contentDescription =
                                    "Manage library"
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

            if (state.comics.isEmpty() && !state.isImporting) {
                EmptyLibraryMessage()
            } else {
                /*
                 * Clip the scrolling viewport itself, rather than relying on
                 * the rounded corners of the final row. This keeps the visible
                 * lower edge rounded even when the grid is stopped halfway
                 * through a comic cover.
                 */
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        /*
                         * The lower edge follows the same rounded silhouette
                         * as each comic cover, column by column. This avoids
                         * one large outer curve and prevents partially visible
                         * covers from ending in a flat horizontal cut.
                         */
                        .clip(
                            ComicGridBottomCurveShape()
                        ),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        top = 8.dp,
                        end = 8.dp,
                        /*
                         * Lets the final row scroll completely above the
                         * floating glass navigation dock.
                         */
                        bottom = 112.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.comics, key = { it.id }) { comic ->
                        ComicGridItem(
                            comic = comic,
                            panelProgress = state.panelProgress[comic.id]
                                ?: PanelAnalysisProgress(comicId = comic.id),
                            isEditing = isEditing,
                            isSelected = comic.id in selectedIds,
                            onClick = { onComicClick(comic) },
                            onToggleSelection = {
                                selectedIds = if (comic.id in selectedIds)
                                    selectedIds - comic.id else selectedIds + comic.id
                            },
                            onLongPress = {
                                isEditing = true
                                selectedIds = selectedIds + comic.id
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(comic) }
                        )
                    }
                }
            }
        }
    }
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
                IconButton(onClick = onToggleFavorite, modifier = Modifier.align(Alignment.TopEnd)) {
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