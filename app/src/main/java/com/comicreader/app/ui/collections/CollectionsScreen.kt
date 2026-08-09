package com.comicreader.app.ui.collections

import android.os.Build
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.comicreader.app.domain.model.Comic
import com.comicreader.app.domain.model.ComicCollection
import com.comicreader.app.domain.model.CollectionLayoutStyle
import com.comicreader.app.ui.components.ContextualDockAction
import com.comicreader.app.ui.components.SearchPill
import com.comicreader.app.ui.components.TopSearchBar
import com.comicreader.app.ui.haptics.AppHaptics
import com.comicreader.app.ui.haptics.HapticThrottle
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)
@Composable
fun CollectionsScreen(
    onComicClick: (Comic) -> Unit,
    onContextualActionChanged:
        (ContextualDockAction?) -> Unit,
    viewModel: CollectionsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var isEditing by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var renameTarget by remember { mutableStateOf<ComicCollection?>(null) }
    var renameText by remember { mutableStateOf("") }
    var addComicsTarget by remember {
        mutableStateOf<ComicCollection?>(null)
    }
    var carouselCollectionId by rememberSaveable {
        mutableStateOf<Long?>(null)
    }

    /*
     * These two values deliberately survive navigation to Reader.
     *
     * When Reader is popped, Navigation Compose restores Collections and the
     * carousel should already be sitting exactly where the reader left it,
     * rather than replaying its pull-up animation.
     */
    var carouselHasBeenPresented by rememberSaveable {
        mutableStateOf(false)
    }
    var carouselSelectedComicId by rememberSaveable {
        mutableStateOf<Long?>(null)
    }

    var showDeleteConfirmation by remember {
        mutableStateOf(false)
    }

    val selectedCollections =
        state.collections.filter { collection ->
            collection.id in selectedIds
        }

    val carouselCollection =
        carouselCollectionId?.let { collectionId ->
            state.collections.firstOrNull { collection ->
                collection.id == collectionId
            }
        }

    fun leaveEditMode() {
        isEditing = false
        selectedIds = emptySet()
    }

    val canShowContextualAction =
        !isEditing &&
                state.draft == null &&
                carouselCollectionId == null &&
                addComicsTarget == null &&
                renameTarget == null &&
                !showDeleteConfirmation &&
                !state.isSaving

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
                        "collections",
                    contentDescription =
                        "Create collection",
                    onClick =
                        viewModel::startCreation
                )
            } else {
                null
            }
        )
    }

    LaunchedEffect(Unit) {
        viewModel.onSearchQueryChanged("")
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(state.collections.map { collection -> collection.id }) {
        selectedIds = selectedIds.intersect(
            state.collections.map { collection -> collection.id }.toSet()
        )

        if (isEditing && state.totalCollectionCount == 0) {
            leaveEditMode()
        }
    }

    carouselCollection?.let { collection ->
        CollectionCarouselSheet(
            collection = collection,
            skipEnterAnimation =
                carouselHasBeenPresented,
            initialSelectedComicId =
                carouselSelectedComicId,
            onPresented = {
                carouselHasBeenPresented =
                    true
            },
            onFocusedComicChanged = {
                    comicId ->
                carouselSelectedComicId =
                    comicId
            },
            onDismiss = {
                /*
                 * A genuine carousel dismissal resets presentation state.
                 * The next collection open should animate normally.
                 */
                carouselCollectionId = null
                carouselHasBeenPresented = false
                carouselSelectedComicId = null
            },
            onComicClick = { comic ->
                /*
                 * Keep the carousel state alive underneath Reader.
                 * Popping Reader will reveal it immediately at this comic.
                 */
                carouselSelectedComicId =
                    comic.id
                carouselHasBeenPresented =
                    true
                onComicClick(comic)
            }
        )
    }

    state.draft?.let { draft ->
        CreateCollectionSheet(
            draft = draft,
            libraryComics = state.libraryComics,
            isSaving = state.isSaving,
            onNameChanged = viewModel::onDraftNameChanged,
            onToggleComic = viewModel::toggleDraftComic,
            onSelectCover = viewModel::selectDraftCover,
            onSelectLayout = viewModel::selectDraftLayout,
            onBack = viewModel::previousCreationStep,
            onContinue = viewModel::continueCreation,
            onCancel = viewModel::cancelCreation
        )
    }

    addComicsTarget?.let { collection ->
        AddComicsSheet(
            collection = collection,
            libraryComics = state.libraryComics,
            isSaving = state.isSaving,
            onAdd = { comicIds ->
                viewModel.addComicsToCollection(
                    collection = collection,
                    comicIds = comicIds
                )
                addComicsTarget = null
                leaveEditMode()
            },
            onCancel = {
                addComicsTarget = null
            }
        )
    }

    renameTarget?.let { collection ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename collection") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Collection name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameCollection(
                            collection = collection,
                            newName = renameText
                        )
                        renameTarget = null
                        leaveEditMode()
                    },
                    enabled = renameText.trim().isNotEmpty()
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteConfirmation) {
        val itemCount = selectedCollections.size

        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmation = false
            },
            title = {
                Text(
                    if (itemCount == 1) {
                        "Delete collection?"
                    } else {
                        "Delete collections?"
                    }
                )
            },
            text = {
                Text(
                    if (itemCount == 1) {
                        "Delete the selected collection? Its comics will stay in your library."
                    } else {
                        "Delete $itemCount selected collections? Their comics will stay in your library."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCollections(selectedCollections)
                        showDeleteConfirmation = false
                        leaveEditMode()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            if (isEditing) {
                TopAppBar(
                    title = {
                        Text("${selectedIds.size} selected")
                    },
                    navigationIcon = {
                        IconButton(onClick = { leaveEditMode() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Leave edit mode"
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                addComicsTarget =
                                    selectedCollections.singleOrNull()
                            },
                            enabled =
                                selectedCollections.singleOrNull()
                                    ?.let { collection ->
                                        val existingComicIds =
                                            collection.comics
                                                .map { comic -> comic.id }
                                                .toSet()

                                        state.libraryComics.any { comic ->
                                            comic.id !in existingComicIds
                                        }
                                    } == true
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription =
                                    "Add comics to selected collection"
                            )
                        }

                        IconButton(
                            onClick = {
                                selectedCollections
                                    .singleOrNull()
                                    ?.let { collection ->
                                        renameTarget = collection
                                        renameText = collection.name
                                    }
                            },
                            enabled = selectedCollections.size == 1
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription =
                                    "Rename selected collection"
                            )
                        }

                        IconButton(
                            onClick = {
                                showDeleteConfirmation = true
                            },
                            enabled = selectedCollections.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription =
                                    "Delete selected collections"
                            )
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (!isEditing) {
                TopSearchBar(
                    title = "Collections",
                    query = state.searchQuery,
                    onQueryChange =
                        viewModel::onSearchQueryChanged,
                    placeholder = "Search collections",
                    trailing = {
                        IconButton(
                            onClick = {
                                viewModel.onSearchQueryChanged("")
                                isEditing = true
                            },
                            enabled =
                                state.totalCollectionCount > 0
                        ) {
                            Icon(
                                imageVector =
                                    Icons.Default.Tune,
                                contentDescription =
                                    "Manage collections"
                            )
                        }
                    }
                )
            }

            when {
                state.totalCollectionCount == 0 -> {
                    EmptyCollectionsMessage()
                }

                state.collections.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No collections match your search.",
                            style =
                                MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 6.dp,
                            bottom = 96.dp
                        ),
                        verticalArrangement =
                            Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = state.collections,
                            key = { collection -> collection.id }
                        ) { collection ->
                            CollectionRow(
                                collection = collection,
                                isEditing = isEditing,
                                isSelected =
                                    collection.id in selectedIds,
                                onClick = {
                                    /*
                                     * Fresh collection entry: allow the normal
                                     * pull-up and start from its designated cover.
                                     */
                                    carouselHasBeenPresented =
                                        false
                                    carouselSelectedComicId =
                                        collection.coverComicId
                                            ?: collection.comics
                                                .firstOrNull()
                                                ?.id
                                    carouselCollectionId =
                                        collection.id
                                },
                                onToggleSelection = {
                                    selectedIds =
                                        if (
                                            collection.id in selectedIds
                                        ) {
                                            selectedIds - collection.id
                                        } else {
                                            selectedIds + collection.id
                                        }
                                },
                                onLongPress = {
                                    viewModel.onSearchQueryChanged("")
                                    isEditing = true
                                    selectedIds =
                                        selectedIds + collection.id
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCollectionsMessage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "No collections yet",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap + to group comics into a collection.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionRow(
    collection: ComicCollection,
    isEditing: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onLongPress: () -> Unit
) {
    val shape = MaterialTheme.shapes.large
    val selectedBorder =
        if (isSelected) {
            Modifier.border(
                width = 3.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = shape
            )
        } else {
            Modifier
        }

    Card(
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .then(selectedBorder)
            .combinedClickable(
                onClick = {
                    if (isEditing) {
                        onToggleSelection()
                    } else {
                        onClick()
                    }
                },
                onLongClick = onLongPress
            )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            val contentModifier = Modifier
                .fillMaxWidth()
                .padding(end = if (isEditing) 42.dp else 0.dp)

            when (collection.layoutStyle) {
                CollectionLayoutStyle.HAND_FAN ->
                    HandFanCollectionLayout(
                        collection = collection,
                        modifier = contentModifier
                    )

                CollectionLayoutStyle.RIBBON_SPREAD ->
                    RibbonSpreadCollectionLayout(
                        collection = collection,
                        modifier = contentModifier
                    )

                CollectionLayoutStyle.HERO_MOSAIC ->
                    HeroMosaicCollectionLayout(
                        collection = collection,
                        modifier = contentModifier
                    )
            }

            if (isEditing) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = {
                        onToggleSelection()
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun HandFanCollectionLayout(
    collection: ComicCollection,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(
            horizontal = 14.dp,
            vertical = 12.dp
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HandFanDeck(collection)

        Spacer(Modifier.width(16.dp))

        CollectionTitleAndCount(
            collection = collection,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HandFanDeck(
    collection: ComicCollection
) {
    val cover = collection.coverComic
    val previewComics = buildList {
        if (cover != null) add(cover)
        addAll(
            collection.comics
                .filterNot { comic -> comic.id == cover?.id }
                .take(3)
        )
    }.take(4)

    Box(
        modifier = Modifier
            .width(142.dp)
            .height(132.dp)
    ) {
        if (previewComics.isEmpty()) {
            EmptyCollectionCover(
                modifier = Modifier.align(Alignment.CenterStart)
            )
        } else {
            val rotations = when (previewComics.size) {
                1 -> listOf(0f)
                2 -> listOf(-6f, 5f)
                3 -> listOf(-7f, 0f, 7f)
                else -> listOf(-8f, -3f, 3f, 8f)
            }

            previewComics.forEachIndexed { index, comic ->
                val coverShape = RoundedCornerShape(8.dp)

                AsyncImage(
                    model = comic.coverPagePath,
                    contentDescription = comic.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset(x = (index * 16).dp)
                        .width(82.dp)
                        .aspectRatio(2f / 3f)
                        // The chosen cover is first on the left and stays on top.
                        .zIndex((previewComics.size - index).toFloat())
                        .graphicsLayer {
                            rotationZ = rotations[index]
                        }
                        .shadow(4.dp, coverShape)
                        .clip(coverShape)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = coverShape
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
    }
}

@Composable
private fun RibbonSpreadCollectionLayout(
    collection: ComicCollection,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(
            horizontal = 14.dp,
            vertical = 12.dp
        )
    ) {
        CollectionTitleAndCount(collection)
        Spacer(Modifier.height(10.dp))
        RibbonSpreadPreview(
            collection = collection,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RibbonSpreadPreview(
    collection: ComicCollection,
    modifier: Modifier = Modifier
) {
    val cover = collection.coverComic
    val comics = buildList {
        if (cover != null) add(cover)
        addAll(
            collection.comics.filterNot { comic ->
                comic.id == cover?.id
            }
        )
    }

    BoxWithConstraints(
        modifier = modifier.height(112.dp)
    ) {
        if (comics.isEmpty()) {
            EmptyCollectionCover(
                modifier = Modifier.align(Alignment.CenterStart)
            )
        } else {
            val coverWidth = 74.dp
            val step = if (comics.size == 1) {
                0.dp
            } else {
                ((maxWidth - coverWidth) / (comics.size - 1).toFloat())
                    .coerceAtMost(58.dp)
                    .coerceAtLeast(1.dp)
            }

            comics.forEachIndexed { index, comic ->
                val shape = RoundedCornerShape(7.dp)
                AsyncImage(
                    model = comic.coverPagePath,
                    contentDescription = comic.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset(x = step * index.toFloat())
                        .width(coverWidth)
                        .aspectRatio(2f / 3f)
                        .zIndex((comics.size - index).toFloat())
                        .graphicsLayer {
                            rotationZ = when {
                                index == 0 -> -2f
                                index % 2 == 0 -> 1.5f
                                else -> -1.5f
                            }
                        }
                        .shadow(3.dp, shape)
                        .clip(shape)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = shape
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
    }
}

@Composable
private fun HeroMosaicCollectionLayout(
    collection: ComicCollection,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(
            horizontal = 14.dp,
            vertical = 12.dp
        )
    ) {
        HeroMosaicPreview(
            collection = collection,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        CollectionTitleAndCount(collection)
    }
}

@Composable
private fun HeroMosaicPreview(
    collection: ComicCollection,
    modifier: Modifier = Modifier
) {
    val cover = collection.coverComic
    val remainingComics = collection.comics.filterNot { comic ->
        comic.id == cover?.id
    }

    Row(
        modifier = modifier.height(156.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        MosaicComicTile(
            comic = cover,
            modifier = Modifier
                .weight(0.44f)
                .fillMaxHeight(),
            shape = RoundedCornerShape(10.dp)
        )

        Column(
            modifier = Modifier
                .weight(0.56f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(2) { rowIndex ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    repeat(2) { columnIndex ->
                        val slotIndex = rowIndex * 2 + columnIndex
                        val hasOverflow = remainingComics.size > 4
                        val comic = when {
                            hasOverflow && slotIndex == 3 ->
                                remainingComics.getOrNull(3)
                            else -> remainingComics.getOrNull(slotIndex)
                        }
                        val overflowCount =
                            if (hasOverflow && slotIndex == 3) {
                                remainingComics.size - 3
                            } else {
                                null
                            }

                        MosaicComicTile(
                            comic = comic,
                            overflowCount = overflowCount,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MosaicComicTile(
    comic: Comic?,
    modifier: Modifier,
    shape: RoundedCornerShape,
    overflowCount: Int? = null
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (comic != null) {
            AsyncImage(
                model = comic.coverPagePath,
                contentDescription = comic.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (overflowCount != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.56f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+$overflowCount",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
    }
}

@Composable
private fun EmptyCollectionCover(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(86.dp)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Empty",
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun CollectionTitleAndCount(
    collection: ComicCollection,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = collection.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "${collection.comics.size} comic" +
                    if (collection.comics.size == 1) "" else "s",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)
@Composable
private fun CollectionCarouselSheet(
    collection: ComicCollection,
    skipEnterAnimation: Boolean,
    initialSelectedComicId: Long?,
    onPresented: () -> Unit,
    onFocusedComicChanged: (Long) -> Unit,
    onDismiss: () -> Unit,
    onComicClick: (Comic) -> Unit
) {
    val sheetShape = RoundedCornerShape(
        topStart = 28.dp,
        topEnd = 28.dp
    )

    /*
     * Do not stack a second animation on top of Material's own sheet motion.
     *
     * The previous implementation used ModalBottomSheet + an additional
     * translating child. On slower GPUs that produced two independent motion
     * timelines and made the entrance look like a fast snap followed by a
     * second catch-up movement.
     *
     * The carousel now uses one full-screen dialog and one translation value.
     * That gives the pull-up a single stable timeline and keeps every frame of
     * the entrance under our control.
     */
    val sheetProgress = remember(
        collection.id
    ) {
        Animatable(0f)
    }

    var dismissDragOffsetPx by remember(
        collection.id
    ) {
        mutableFloatStateOf(0f)
    }

    var isClosing by remember(
        collection.id
    ) {
        mutableStateOf(false)
    }

    val coroutineScope =
        rememberCoroutineScope()

    fun dismissWithAnimation() {
        if (isClosing) {
            return
        }

        isClosing = true

        coroutineScope.launch {
            sheetProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis =
                        COLLECTION_SHEET_EXIT_DURATION_MILLIS,
                    easing =
                        COLLECTION_SHEET_EXIT_EASING
                )
            )

            onDismiss()
        }
    }

    LaunchedEffect(
        collection.id,
        skipEnterAnimation
    ) {
        if (skipEnterAnimation) {
            /*
             * Returning from Reader: the carousel is conceptually still open.
             * Render it fully deployed on the very first frame.
             */
            sheetProgress.snapTo(1f)
            dismissDragOffsetPx = 0f
            onPresented()
        } else {
            sheetProgress.snapTo(0f)

            /*
             * Fresh collection entry: present one complete frame at the start,
             * then run the normal cinematic pull-up.
             */
            withFrameNanos { }

            sheetProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis =
                        COLLECTION_SHEET_REVEAL_DURATION_MILLIS,
                    easing =
                        COLLECTION_SHEET_REVEAL_EASING
                )
            )

            onPresented()
        }
    }

    Dialog(
        onDismissRequest =
            ::dismissWithAnimation,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
    ) {
        val dialogView = LocalView.current
        val dialogWindow =
            (dialogView.parent as? DialogWindowProvider)?.window

        DisposableEffect(dialogWindow) {
            dialogWindow?.setBackgroundDrawable(
                ColorDrawable(AndroidColor.TRANSPARENT)
            )
            dialogWindow?.setDimAmount(0f)

            /*
             * Force the dialog surface itself to the full physical display.
             * On this Samsung device the v20 dialog surface was a few pixels
             * narrower than the screenshot width, exposing a bright strip at
             * the extreme right edge.
             */
            dialogWindow?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )

            onDispose { }
        }

        ImmersiveCarouselSystemBars()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                val density =
                    LocalDensity.current

                val sheetTravelPx =
                    with(density) {
                        maxHeight.toPx()
                    }

                val dragState =
                    rememberDraggableState {
                            delta ->
                        if (
                            sheetProgress.value >=
                            0.999f &&
                            !isClosing
                        ) {
                            dismissDragOffsetPx =
                                (
                                        dismissDragOffsetPx +
                                                delta
                                        ).coerceIn(
                                        0f,
                                        sheetTravelPx
                                    )
                        }
                    }

                val enterTranslationPx =
                    sheetTravelPx *
                            (
                                    1f -
                                            sheetProgress.value
                                    )

                Box(
                    modifier = Modifier
                        /*
                         * Overscan the moving sheet by 2dp on each horizontal
                         * side. The rounded clip can otherwise leave a
                         * sub-pixel transparent seam at the physical display
                         * edge on some Samsung renderers.
                         *
                         * The -2dp offset keeps the visual center unchanged.
                         */
                        .requiredWidth(
                            maxWidth +
                                    COLLECTION_SHEET_HORIZONTAL_OVERSCAN_DP.dp
                        )
                        .fillMaxHeight()
                        .offset(
                            x =
                                (
                                        -COLLECTION_SHEET_HORIZONTAL_OVERSCAN_DP /
                                                2f
                                        ).dp
                        )
                        .graphicsLayer {
                            translationY =
                                enterTranslationPx +
                                        dismissDragOffsetPx
                        }
                        .clip(sheetShape)
                        .draggable(
                            enabled =
                                sheetProgress.value >=
                                        0.999f &&
                                        !isClosing,
                            state =
                                dragState,
                            orientation =
                                Orientation.Vertical,
                            onDragStopped = {
                                    velocity ->
                                val shouldDismiss =
                                    dismissDragOffsetPx >=
                                            sheetTravelPx *
                                            COLLECTION_DRAG_DISMISS_FRACTION ||
                                            velocity >=
                                            COLLECTION_DRAG_DISMISS_VELOCITY_PX

                                if (shouldDismiss) {
                                    if (!isClosing) {
                                        isClosing = true

                                        coroutineScope.launch {
                                            Animatable(
                                                dismissDragOffsetPx
                                            ).animateTo(
                                                targetValue =
                                                    sheetTravelPx,
                                                animationSpec =
                                                    tween(
                                                        durationMillis =
                                                            COLLECTION_DRAG_DISMISS_DURATION_MILLIS,
                                                        easing =
                                                            COLLECTION_SHEET_EXIT_EASING
                                                    )
                                            ) {
                                                dismissDragOffsetPx =
                                                    value
                                            }

                                            onDismiss()
                                        }
                                    }
                                } else {
                                    coroutineScope.launch {
                                        Animatable(
                                            dismissDragOffsetPx
                                        ).animateTo(
                                            targetValue = 0f,
                                            animationSpec =
                                                tween(
                                                    durationMillis =
                                                        COLLECTION_DRAG_RETURN_DURATION_MILLIS,
                                                    easing =
                                                        COLLECTION_SHEET_REVEAL_EASING
                                                )
                                        ) {
                                            dismissDragOffsetPx =
                                                value
                                        }
                                    }
                                }
                            }
                        )
                ) {
                    CollectionCarouselSheetContent(
                        collection =
                            collection,
                        initialSelectedComicId =
                            initialSelectedComicId,
                        showGuidanceInitially =
                            !skipEnterAnimation,
                        onFocusedComicChangedPersistent =
                            onFocusedComicChanged,
                        onDismiss =
                            ::dismissWithAnimation,
                        onComicClick =
                            onComicClick,
                        revealProgress =
                            sheetProgress.value,
                        modifier =
                            Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun ImmersiveCarouselSystemBars() {
    val view = LocalView.current

    DisposableEffect(view) {
        val window =
            (view.parent as? DialogWindowProvider)?.window

        if (window == null) {
            return@DisposableEffect onDispose { }
        }

        val previousStatusBarColor =
            window.statusBarColor
        val previousNavigationBarColor =
            window.navigationBarColor

        val insetsController =
            WindowCompat.getInsetsController(window, view)

        val previousLightStatusBars =
            insetsController.isAppearanceLightStatusBars
        val previousLightNavigationBars =
            insetsController.isAppearanceLightNavigationBars

        val previousNavigationContrast =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced
            } else {
                null
            }

        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        window.statusBarColor =
            AndroidColor.TRANSPARENT
        window.navigationBarColor =
            AndroidColor.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        onDispose {
            window.statusBarColor =
                previousStatusBarColor
            window.navigationBarColor =
                previousNavigationBarColor

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                previousNavigationContrast != null
            ) {
                window.isNavigationBarContrastEnforced =
                    previousNavigationContrast
            }

            insetsController.isAppearanceLightStatusBars =
                previousLightStatusBars
            insetsController.isAppearanceLightNavigationBars =
                previousLightNavigationBars

            WindowCompat.setDecorFitsSystemWindows(
                window,
                true
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionCarouselSheetContent(
    collection: ComicCollection,
    initialSelectedComicId: Long?,
    showGuidanceInitially: Boolean,
    onFocusedComicChangedPersistent: (Long) -> Unit,
    onDismiss: () -> Unit,
    onComicClick: (Comic) -> Unit,
    revealProgress: Float,
    modifier: Modifier = Modifier
) {
    /*
     * The selected collection cover opens in the center. Upcoming comics
     * continue toward the right, while previously browsed comics naturally
     * move to the left.
     */
    val comics = remember(
        collection.id,
        collection.coverComicId,
        collection.comics
    ) {
        val chosenCover =
            collection.comics.firstOrNull { comic ->
                comic.id == collection.coverComicId
            }

        buildList {
            if (chosenCover != null) {
                add(chosenCover)
            }

            addAll(
                collection.comics.filterNot { comic ->
                    comic.id == chosenCover?.id
                }
            )
        }
    }

    if (comics.isEmpty()) {
        EmptyCarouselCollection(
            collectionName = collection.name,
            onDismiss = onDismiss,
            modifier = modifier
        )
        return
    }

    val initialSelectedIndex =
        comics.indexOfFirst { comic ->
            comic.id ==
                    initialSelectedComicId
        }
            .takeIf { index ->
                index >= 0
            }
            ?: 0

    var selectedIndex by rememberSaveable(
        collection.id
    ) {
        mutableIntStateOf(
            initialSelectedIndex
        )
    }

    LaunchedEffect(
        collection.id,
        initialSelectedComicId
    ) {
        val restoredIndex =
            comics.indexOfFirst { comic ->
                comic.id ==
                        initialSelectedComicId
            }

        if (
            restoredIndex >= 0 &&
            restoredIndex !=
            selectedIndex
        ) {
            selectedIndex =
                restoredIndex
        }
    }

    var openingIndex by remember(
        collection.id
    ) {
        mutableStateOf<Int?>(null)
    }

    val openingProgress = remember(
        collection.id
    ) {
        Animatable(0f)
    }

    val coroutineScope = rememberCoroutineScope()

    /*
     * The page counter and browse hint are onboarding chrome, not permanent
     * content. Keep them around long enough to be understood, then let the
     * artwork own the screen.
     */
    val guidanceAlpha = remember(
        collection.id
    ) {
        Animatable(
            if (
                showGuidanceInitially
            ) {
                1f
            } else {
                0f
            }
        )
    }

    LaunchedEffect(
        collection.id,
        showGuidanceInitially
    ) {
        if (!showGuidanceInitially) {
            guidanceAlpha.snapTo(0f)
            return@LaunchedEffect
        }

        guidanceAlpha.snapTo(1f)

        kotlinx.coroutines.delay(
            COLLECTION_SHEET_REVEAL_DURATION_MILLIS.toLong() +
                    COLLECTION_GUIDANCE_VISIBLE_MILLIS
        )

        guidanceAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis =
                    COLLECTION_GUIDANCE_FADE_MILLIS,
                easing = FastOutSlowInEasing
            )
        )
    }

    val safeSelectedIndex = selectedIndex.coerceIn(
        0,
        comics.lastIndex
    )

    val selectedComic = comics[safeSelectedIndex]
    val transitionProgress = openingProgress.value
    val supportingContentAlpha =
        (1f - transitionProgress * 1.15f)
            .coerceIn(0f, 1f)

    fun beginOpenTransition(index: Int) {
        if (openingIndex != null) {
            return
        }

        val safeIndex = index.coerceIn(
            0,
            comics.lastIndex
        )

        selectedIndex = safeIndex
        openingIndex = safeIndex

        coroutineScope.launch {
            openingProgress.snapTo(0f)
            openingProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = RIBBON_OPEN_DURATION_MILLIS,
                    easing = FastOutSlowInEasing
                )
            )

            onComicClick(comics[safeIndex])
        }
    }

    Box(modifier = modifier) {
        CarouselBlurredBackground(
            coverPath = selectedComic.coverPagePath
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .width(44.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            Color.White.copy(alpha = 0.52f)
                        )
                )
            }

            CarouselSheetHeader(
                collection = collection,
                onDismiss = onDismiss,
                revealProgress = revealProgress
            )

            RibbonSpreadComicCarousel(
                comics = comics,
                openingIndex = openingIndex,
                openingProgress = transitionProgress,
                onFocusedComicChanged = { index ->
                    if (openingIndex == null) {
                        selectedIndex = index

                        comics
                            .getOrNull(index)
                            ?.id
                            ?.let(
                                onFocusedComicChangedPersistent
                            )
                    }
                },
                onOpenComic = ::beginOpenTransition,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            /*
             * No per-comic subtitle under the artwork. The only temporary
             * information here is position + the first-use gesture hint.
             * Both sit closer to the cover; only the gesture hint disappears after three seconds.
             */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (-96).dp)
                    .graphicsLayer {
                        alpha =
                            supportingContentAlpha
                    },
                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {
                Crossfade(
                    targetState = selectedComic,
                    label = "ribbon-selected-comic-position"
                ) { comic ->
                    CarouselSelectedComicDetails(
                        comic = comic,
                        currentIndex = comics.indexOfFirst { item ->
                            item.id == comic.id
                        }.coerceAtLeast(0),
                        totalCount = comics.size
                    )
                }

                Text(
                    text =
                        "Swipe to browse · Tap a comic to read",
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        Color.White.copy(alpha = 0.78f),
                    textAlign =
                        TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha =
                                guidanceAlpha.value
                        }
                        .padding(
                            start = 24.dp,
                            end = 24.dp,
                            top = 6.dp,
                            bottom = 8.dp
                        )
                )
            }
        }
    }
}

@Composable
private fun RibbonSpreadComicCarousel(
    comics: List<Comic>,
    openingIndex: Int?,
    openingProgress: Float,
    onFocusedComicChanged: (Int) -> Unit,
    onOpenComic: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var ribbonPosition by rememberSaveable(
        comics.map { comic -> comic.id }
    ) {
        mutableFloatStateOf(0f)
    }

    var isDragging by remember {
        mutableStateOf(false)
    }

    var settlingJob by remember {
        mutableStateOf<kotlinx.coroutines.Job?>(null)
    }

    val coroutineScope = rememberCoroutineScope()
    val flingDecay = remember { exponentialDecay<Float>() }
    val hapticView = LocalView.current
    val scrollHapticThrottle = remember {
        HapticThrottle(
            minimumIntervalMillis = 55L
        )
    }

    var userScrollHapticsActive by remember {
        mutableStateOf(false)
    }

    val focusedIndex = ribbonPosition
        .roundToInt()
        .coerceIn(0, comics.lastIndex)

    var lastScrollHapticIndex by remember(
        comics.map { comic -> comic.id }
    ) {
        mutableIntStateOf(
            focusedIndex
        )
    }

    LaunchedEffect(
        focusedIndex,
        userScrollHapticsActive,
        openingIndex
    ) {
        onFocusedComicChanged(
            focusedIndex
        )

        if (
            userScrollHapticsActive &&
            openingIndex == null &&
            focusedIndex !=
            lastScrollHapticIndex
        ) {
            if (
                scrollHapticThrottle
                    .tryAcquire()
            ) {
                AppHaptics.scrollTick(
                    hapticView
                )
            }

            lastScrollHapticIndex =
                focusedIndex
        } else if (
            !userScrollHapticsActive
        ) {
            lastScrollHapticIndex =
                focusedIndex
        }
    }

    suspend fun animateRibbonToIndex(index: Int) {
        val targetIndex = index.coerceIn(
            0,
            comics.lastIndex
        )

        Animatable(ribbonPosition).animateTo(
            targetValue = targetIndex.toFloat(),
            animationSpec = tween(
                durationMillis = RIBBON_FOCUS_DURATION_MILLIS,
                easing = FastOutSlowInEasing
            )
        ) {
            ribbonPosition = value
        }
    }

    suspend fun applyRibbonSoftMagnet() {
        val nearestIndex = ribbonPosition
            .roundToInt()
            .coerceIn(0, comics.lastIndex)

        val centerDelta =
            nearestIndex.toFloat() - ribbonPosition

        /*
         * Match the workspace carousel: positions remain genuinely free unless
         * a comic is already within a very small attraction range of center.
         */
        if (
            centerDelta.absoluteValue <=
            RIBBON_MAGNET_RANGE_FRACTION
        ) {
            if (
                centerDelta.absoluteValue >
                RIBBON_MAGNET_HAPTIC_EPSILON &&
                scrollHapticThrottle
                    .tryAcquire()
            ) {
                AppHaptics.magnetTick(
                    hapticView
                )
            }

            Animatable(ribbonPosition).animateTo(
                targetValue = nearestIndex.toFloat(),
                animationSpec = tween(
                    durationMillis =
                        RIBBON_MAGNET_DURATION_MILLIS,
                    easing = FastOutSlowInEasing
                )
            ) {
                ribbonPosition = value
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
    ) {
        val density = LocalDensity.current
        val availableWidth = maxWidth
        val availableHeight = maxHeight

        /*
         * Use the exact geometry from the original HorizontalPager carousel:
         * 90% of the available width, capped at 290dp, with a 2:3 cover ratio.
         */
        val preferredCoverWidth = availableWidth * 0.90f
        val coverWidth = if (preferredCoverWidth > 290.dp) {
            290.dp
        } else {
            preferredCoverWidth
        }
        val coverHeight = coverWidth * 1.5f

        /*
         * Keep enough exposed cover width for every neighboring comic to
         * remain recognizable without breaking the ribbon formation.
         */
        val visualStep = when {
            coverWidth * 0.30f < 68.dp -> 68.dp
            coverWidth * 0.30f > 82.dp -> 82.dp
            else -> coverWidth * 0.30f
        }

        val gestureDistancePerComic = with(density) {
            visualStep.toPx()
        }

        val draggableState = rememberDraggableState { delta ->
            if (openingIndex != null) {
                return@rememberDraggableState
            }

            settlingJob?.cancel()
            ribbonPosition = (
                    ribbonPosition -
                            delta / gestureDistancePerComic
                    ).coerceIn(
                    0f,
                    comics.lastIndex.toFloat()
                )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    enabled = openingIndex == null,
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        settlingJob?.cancel()
                        isDragging = true
                        userScrollHapticsActive =
                            true
                        lastScrollHapticIndex =
                            focusedIndex
                    },
                    onDragStopped = { velocity ->
                        isDragging = false
                        settlingJob?.cancel()

                        settlingJob = coroutineScope.launch {
                            val freeScroll =
                                Animatable(ribbonPosition)

                            freeScroll.updateBounds(
                                lowerBound = 0f,
                                upperBound =
                                    comics.lastIndex.toFloat()
                            )

                            freeScroll.animateDecay(
                                initialVelocity =
                                    -velocity /
                                            gestureDistancePerComic,
                                animationSpec = flingDecay
                            ) {
                                ribbonPosition = value
                            }

                            applyRibbonSoftMagnet()
                            userScrollHapticsActive =
                                false
                        }
                    }
                )
        ) {
            comics.forEachIndexed { index, comic ->
                val distance = index - ribbonPosition
                val absoluteDistance = distance.absoluteValue
                val isFocused =
                    index == focusedIndex &&
                            (ribbonPosition - index)
                                .absoluteValue < 0.14f
                val isOpeningComic = index == openingIndex

                val left =
                    (availableWidth - coverWidth) / 2f +
                            visualStep * distance

                /* All covers remain aligned at the same height while browsing. */
                val restingTop =
                    (availableHeight - coverHeight) / 2f

                val openingLift =
                    if (isOpeningComic) {
                        (openingProgress * 58f).dp
                    } else {
                        0.dp
                    }

                val top = restingTop - openingLift

                val scale = when {
                    isOpeningComic ->
                        1f + openingProgress * 0.065f

                    openingIndex != null ->
                        1f - openingProgress * 0.035f

                    else -> 1f
                }

                val coverAlpha = when {
                    openingIndex == null -> 1f
                    isOpeningComic -> 1f
                    else ->
                        (1f - openingProgress * 1.08f)
                            .coerceIn(0f, 1f)
                }

                /*
                 * Performance-safe cinematic focus.
                 *
                 * The v17 experiment blurred every side cover dynamically.
                 * That forces expensive RenderEffect work while ribbonPosition
                 * changes every frame, which is especially noticeable on
                 * lower-end GPUs.
                 *
                 * Keep the smooth distance-based gray veil, but make it the
                 * only moving focus effect. The visual hierarchy remains
                 * continuous without paying for per-card blur passes.
                 */
                val normalizedDistance =
                    (
                            absoluteDistance /
                                    RIBBON_FOCUS_EFFECT_DISTANCE
                            ).coerceIn(
                            0f,
                            1f
                        )

                val easedDistance =
                    normalizedDistance *
                            normalizedDistance *
                            (
                                    3f -
                                            2f *
                                            normalizedDistance
                                    )

                val baseVeilAlpha =
                    RIBBON_MAX_VEIL_ALPHA *
                            easedDistance

                val openingVeilBoost =
                    if (
                        openingIndex != null &&
                        !isOpeningComic
                    ) {
                        RIBBON_OPENING_VEIL_BOOST *
                                openingProgress
                                    .coerceIn(
                                        0f,
                                        1f
                                    )
                    } else {
                        0f
                    }

                val veilAlpha =
                    if (isOpeningComic) {
                        0f
                    } else {
                        (
                                baseVeilAlpha +
                                        openingVeilBoost
                                ).coerceIn(
                                0f,
                                RIBBON_MAX_OPENING_VEIL_ALPHA
                            )
                    }

                val shape = RoundedCornerShape(14.dp)

                Box(
                    modifier = Modifier
                        .offset(
                            x = left,
                            y = top
                        )
                        .width(coverWidth)
                        .height(coverHeight)
                        .zIndex(
                            when {
                                isOpeningComic -> 1000f
                                isFocused -> 500f
                                else -> 100f - absoluteDistance
                            }
                        )
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = coverAlpha
                        }
                        .shadow(
                            elevation = when {
                                isOpeningComic ->
                                    (18f + openingProgress * 22f).dp

                                isFocused -> 18.dp
                                else -> 7.dp
                            },
                            shape = shape,
                            clip = false
                        )
                        .clip(shape)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            width = if (isFocused) {
                                1.15.dp
                            } else {
                                0.7.dp
                            },
                            color = Color.White.copy(
                                alpha = if (isFocused) {
                                    0.42f
                                } else {
                                    0.20f
                                }
                            ),
                            shape = shape
                        )
                        .clickable(
                            enabled =
                                openingIndex == null &&
                                        !isDragging
                        ) {
                            settlingJob?.cancel()
                            userScrollHapticsActive =
                                false
                            settlingJob = coroutineScope.launch {
                                /*
                                 * A side cover first glides into the center.
                                 * Then it rises while the rest of the ribbon
                                 * fades away before navigation begins.
                                 */
                                animateRibbonToIndex(index)

                                /*
                                 * Comic-open haptics are emitted centrally by
                                 * navigation so Home, Library, Collections and
                                 * Ratings all use the same tactile confirmation.
                                 */
                                onOpenComic(index)
                            }
                        }
                ) {
                    AsyncImage(
                        model = comic.coverPagePath,
                        contentDescription = comic.title,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier.fillMaxSize()
                    )

                    /*
                     * Always keep the veil layer in the tree. Only its layer
                     * alpha changes, avoiding add/remove work as a card crosses
                     * the center point.
                     */
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha =
                                    veilAlpha
                            }
                            .background(
                                RIBBON_SIDE_VEIL_COLOR
                            )
                    )
                }
            }
        }
    }
}

private const val RIBBON_MAGNET_RANGE_FRACTION =
    0.13f

private const val RIBBON_MAGNET_DURATION_MILLIS =
    145

private const val RIBBON_MAGNET_HAPTIC_EPSILON =
    0.003f

private const val RIBBON_FOCUS_DURATION_MILLIS =
    320

private const val RIBBON_OPEN_DURATION_MILLIS =
    920

/*
 * Continuous carousel focus treatment.
 *
 * The gray veil is intentionally the only per-frame side-cover effect.
 * Avoiding per-card RenderEffect blur keeps free scrolling responsive.
 */
private const val RIBBON_FOCUS_EFFECT_DISTANCE =
    1.45f

private const val RIBBON_MAX_VEIL_ALPHA =
    0.68f

private const val RIBBON_OPENING_VEIL_BOOST =
    0.10f

private const val RIBBON_MAX_OPENING_VEIL_ALPHA =
    0.74f

private val RIBBON_SIDE_VEIL_COLOR =
    Color(0xFF141619)

private const val CAROUSEL_BACKGROUND_BLUR_RADIUS_DP =
    20f

private const val CAROUSEL_BACKGROUND_DARKEN_ALPHA =
    0.62f

/*
 * One controlled pull-up timeline. The old implementation effectively had
 * Material's quick sheet motion plus a second 1050ms child translation.
 */
private const val COLLECTION_SHEET_HORIZONTAL_OVERSCAN_DP =
    8f

private const val COLLECTION_SHEET_REVEAL_DURATION_MILLIS =
    1120

private const val COLLECTION_SHEET_EXIT_DURATION_MILLIS =
    300

private const val COLLECTION_DRAG_DISMISS_DURATION_MILLIS =
    260

private const val COLLECTION_DRAG_RETURN_DURATION_MILLIS =
    240

private const val COLLECTION_DRAG_DISMISS_FRACTION =
    0.22f

private const val COLLECTION_DRAG_DISMISS_VELOCITY_PX =
    1450f

private const val COLLECTION_GUIDANCE_VISIBLE_MILLIS =
    3000L

private const val COLLECTION_GUIDANCE_FADE_MILLIS =
    520

private val COLLECTION_SHEET_REVEAL_EASING =
    CubicBezierEasing(
        0.20f,
        0.00f,
        0.18f,
        1f
    )

private val COLLECTION_SHEET_EXIT_EASING =
    CubicBezierEasing(
        0.40f,
        0f,
        0.72f,
        0.20f
    )

@Composable
private fun CarouselBlurredBackground(
    coverPath: String?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                MaterialTheme.colorScheme.surface
            )
    ) {
        /*
         * A single background layer is much cheaper than Crossfading two
         * full-screen 34dp blur passes every time focus crosses into a new
         * comic. Keep the atmospheric blur, but reduce the radius and render
         * only one cover at a time.
         */
        AsyncImage(
            model = coverPath,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.16f
                    scaleY = 1.16f
                }
                .blur(
                    CAROUSEL_BACKGROUND_BLUR_RADIUS_DP.dp
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Color.Black.copy(
                        alpha =
                            CAROUSEL_BACKGROUND_DARKEN_ALPHA
                    )
                )
        )
    }
}

@Composable
private fun CarouselSheetHeader(
    collection: ComicCollection,
    onDismiss: () -> Unit,
    revealProgress: Float
) {
    val titleAlpha =
        (
                (
                        revealProgress -
                                0.42f
                        ) /
                        0.30f
                ).coerceIn(
                0f,
                1f
            )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 8.dp,
                end = 16.dp,
                top = 6.dp,
                bottom = 4.dp
            )
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close collection",
                tint = Color.White
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                /*
                 * Move the collection title into the intentional gap above the artwork. Offset
                 * does not consume layout space, so the trusted cover geometry
                 * and carousel center stay exactly where they were.
                 */
                .offset(y = 120.dp)
                .padding(horizontal = 58.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = collection.name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = titleAlpha
                    }
            )
        }
    }
}

@Composable
private fun CarouselSelectedComicDetails(
    @Suppress("UNUSED_PARAMETER")
    comic: Comic,
    currentIndex: Int,
    totalCount: Int
) {
    Surface(
        color = Color.Black.copy(alpha = 0.28f),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = "${currentIndex + 1} / $totalCount",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 7.dp
            )
        )
    }
}

@Composable
private fun EmptyCarouselCollection(
    collectionName: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(
            MaterialTheme.colorScheme.surface
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(44.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = 0.42f)
                    )
            )
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 22.dp, start = 8.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close collection"
            )
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = collectionName,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "This collection is empty",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Use Edit → + to add comics.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddComicsSheet(
    collection: ComicCollection,
    libraryComics: List<Comic>,
    isSaving: Boolean,
    onAdd: (List<Long>) -> Unit,
    onCancel: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    var query by rememberSaveable(collection.id) {
        mutableStateOf("")
    }
    var selectedComicIds by remember(collection.id) {
        mutableStateOf<Set<Long>>(emptySet())
    }

    val existingComicIds =
        collection.comics
            .map { comic -> comic.id }
            .toSet()

    val availableComics =
        libraryComics.filterNot { comic ->
            comic.id in existingComicIds
        }

    val visibleComics =
        availableComics.filterByCollectionQuery(query)

    ModalBottomSheet(
        onDismissRequest = {
            if (!isSaving) {
                onCancel()
            }
        },
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(0.94f),
        shape = RoundedCornerShape(
            topStart = 28.dp,
            topEnd = 28.dp
        ),
        dragHandle = {
            BottomSheetDefaults.DragHandle()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 8.dp,
                        end = 16.dp,
                        bottom = 12.dp
                    )
            ) {
                IconButton(
                    onClick = onCancel,
                    enabled = !isSaving,
                    modifier = Modifier.align(
                        Alignment.CenterStart
                    )
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel"
                    )
                }

                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {
                    Text(
                        "Add comics",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        collection.name,
                        style = MaterialTheme.typography.labelMedium,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider()

            TopSearchBar(
                title = "Comics",
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search by title or series"
            )

            Text(
                text = "${selectedComicIds.size} selected",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 8.dp
                )
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    availableComics.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Every library comic is already in this collection."
                            )
                        }
                    }

                    visibleComics.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No comics match your search.")
                        }
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns =
                                GridCells.Adaptive(minSize = 120.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                bottom = 16.dp
                            ),
                            horizontalArrangement =
                                Arrangement.spacedBy(10.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(10.dp)
                        ) {
                            gridItems(
                                items = visibleComics,
                                key = { comic -> comic.id }
                            ) { comic ->
                                ComicChoiceCard(
                                    comic = comic,
                                    selected =
                                        comic.id in selectedComicIds,
                                    useRadioButton = false,
                                    onClick = {
                                        selectedComicIds =
                                            if (
                                                comic.id in selectedComicIds
                                            ) {
                                                selectedComicIds - comic.id
                                            } else {
                                                selectedComicIds + comic.id
                                            }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Button(
                onClick = {
                    onAdd(selectedComicIds.toList())
                },
                enabled =
                    selectedComicIds.isNotEmpty() && !isSaving,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(54.dp)
            ) {
                Text(
                    if (isSaving) {
                        "Adding…"
                    } else {
                        "Add comics"
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateCollectionSheet(
    draft: CollectionDraft,
    libraryComics: List<Comic>,
    isSaving: Boolean,
    onNameChanged: (String) -> Unit,
    onToggleComic: (Long) -> Unit,
    onSelectCover: (Long) -> Unit,
    onSelectLayout: (CollectionLayoutStyle) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    var comicSearchQuery by rememberSaveable {
        mutableStateOf("")
    }

    LaunchedEffect(draft.step) {
        comicSearchQuery = ""
    }

    val selectedComics =
        libraryComics.filter { comic ->
            comic.id in draft.selectedComicIds
        }

    val visibleLibraryComics =
        libraryComics.filterByCollectionQuery(comicSearchQuery)

    val visibleSelectedComics =
        selectedComics.filterByCollectionQuery(comicSearchQuery)

    val canContinue = when (draft.step) {
        CollectionCreationStep.NAME ->
            draft.name.trim().isNotEmpty()

        CollectionCreationStep.COMICS ->
            draft.selectedComicIds.isNotEmpty()

        CollectionCreationStep.COVER ->
            draft.coverComicId != null &&
                    draft.coverComicId in draft.selectedComicIds

        CollectionCreationStep.LAYOUT ->
            draft.layoutStyle != null
    }

    val stepLabel = when (draft.step) {
        CollectionCreationStep.NAME ->
            "1 of 4 · Name"

        CollectionCreationStep.COMICS ->
            "2 of 4 · Choose comics"

        CollectionCreationStep.COVER ->
            "3 of 4 · Choose cover"

        CollectionCreationStep.LAYOUT ->
            "4 of 4 · Choose layout"
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isSaving) {
                onCancel()
            }
        },
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(0.94f),
        shape = RoundedCornerShape(
            topStart = 28.dp,
            topEnd = 28.dp
        ),
        dragHandle = {
            BottomSheetDefaults.DragHandle()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 8.dp,
                        end = 16.dp,
                        bottom = 12.dp
                    )
            ) {
                IconButton(
                    onClick = onCancel,
                    enabled = !isSaving,
                    modifier = Modifier.align(
                        Alignment.CenterStart
                    )
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel"
                    )
                }

                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {
                    Text(
                        "New collection",
                        style =
                            MaterialTheme.typography.titleLarge
                    )
                    Text(
                        stepLabel,
                        style =
                            MaterialTheme.typography.labelMedium,
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (draft.step) {
                    CollectionCreationStep.NAME -> {
                        NameCollectionStep(
                            name = draft.name,
                            onNameChanged = onNameChanged
                        )
                    }

                    CollectionCreationStep.COMICS -> {
                        ChooseComicsStep(
                            comics = visibleLibraryComics,
                            totalComicCount =
                                libraryComics.size,
                            selectedIds =
                                draft.selectedComicIds,
                            query = comicSearchQuery,
                            onQueryChange = {
                                comicSearchQuery = it
                            },
                            onToggleComic = onToggleComic
                        )
                    }

                    CollectionCreationStep.COVER -> {
                        ChooseCoverStep(
                            comics = visibleSelectedComics,
                            totalComicCount =
                                selectedComics.size,
                            coverComicId =
                                draft.coverComicId,
                            query = comicSearchQuery,
                            onQueryChange = {
                                comicSearchQuery = it
                            },
                            onSelectCover =
                                onSelectCover
                        )
                    }

                    CollectionCreationStep.LAYOUT -> {
                        ChooseLayoutStep(
                            collectionName = draft.name,
                            comics = selectedComics,
                            coverComicId = draft.coverComicId,
                            selectedLayout = draft.layoutStyle,
                            onSelectLayout = onSelectLayout
                        )
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement =
                    Arrangement.spacedBy(12.dp),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                if (
                    draft.step !=
                    CollectionCreationStep.NAME
                ) {
                    TextButton(
                        onClick = onBack,
                        enabled = !isSaving
                    ) {
                        Text("Back")
                    }
                }

                Button(
                    onClick = onContinue,
                    enabled = canContinue && !isSaving,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                ) {
                    Text(
                        when (draft.step) {
                            CollectionCreationStep.NAME,
                            CollectionCreationStep.COMICS,
                            CollectionCreationStep.COVER ->
                                "Next"

                            CollectionCreationStep.LAYOUT ->
                                if (isSaving) {
                                    "Creating…"
                                } else {
                                    "Create"
                                }
                        }
                    )
                }
            }
        }
    }
}

private fun List<Comic>.filterByCollectionQuery(
    query: String
): List<Comic> {
    val cleanQuery = query.trim()

    if (cleanQuery.isEmpty()) {
        return this
    }

    return filter { comic ->
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

@Composable
private fun NameCollectionStep(
    name: String,
    onNameChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                horizontal = 16.dp,
                vertical = 20.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Name your collection:",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        SearchPill(
            query = name,
            onQueryChange = onNameChanged,
            placeholder = "Collection name",
            showSearchIcon = false,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Give this collection a name.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ChooseComicsStep(
    comics: List<Comic>,
    totalComicCount: Int,
    selectedIds: Set<Long>,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleComic: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopSearchBar(
            title = "Comics",
            query = query,
            onQueryChange = onQueryChange,
            placeholder = "Search by title or series"
        )

        Text(
            text = "${selectedIds.size} selected",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 8.dp
            )
        )

        when {
            totalComicCount == 0 -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Import comics into your library first."
                    )
                }
            }

            comics.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No comics match your search.")
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns =
                        GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 16.dp
                    ),
                    horizontalArrangement =
                        Arrangement.spacedBy(10.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(10.dp)
                ) {
                    gridItems(
                        items = comics,
                        key = { comic -> comic.id }
                    ) { comic ->
                        ComicChoiceCard(
                            comic = comic,
                            selected =
                                comic.id in selectedIds,
                            useRadioButton = false,
                            onClick = {
                                onToggleComic(comic.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChooseCoverStep(
    comics: List<Comic>,
    totalComicCount: Int,
    coverComicId: Long?,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelectCover: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopSearchBar(
            title = "Cover",
            query = query,
            onQueryChange = onQueryChange,
            placeholder = "Search selected comics"
        )

        Text(
            "Pick the comic that represents this collection.",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 8.dp
            )
        )

        if (comics.isEmpty() && totalComicCount > 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No selected comics match your search."
                )
            }
        } else {
            LazyVerticalGrid(
                columns =
                    GridCells.Adaptive(minSize = 120.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 16.dp
                ),
                horizontalArrangement =
                    Arrangement.spacedBy(10.dp),
                verticalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {
                gridItems(
                    items = comics,
                    key = { comic -> comic.id }
                ) { comic ->
                    ComicChoiceCard(
                        comic = comic,
                        selected =
                            comic.id == coverComicId,
                        useRadioButton = true,
                        onClick = {
                            onSelectCover(comic.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChooseLayoutStep(
    collectionName: String,
    comics: List<Comic>,
    coverComicId: Long?,
    selectedLayout: CollectionLayoutStyle?,
    onSelectLayout: (CollectionLayoutStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 14.dp,
            bottom = 20.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Choose how this collection appears",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "The preview uses the comics and cover you selected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(
            items = CollectionLayoutStyle.values().toList(),
            key = { layout -> layout.name }
        ) { layout ->
            val selected = selectedLayout == layout
            val previewCollection = ComicCollection(
                id = -1L,
                name = collectionName,
                comics = comics,
                coverComicId = coverComicId,
                layoutStyle = layout
            )
            val shape = RoundedCornerShape(18.dp)

            Card(
                shape = shape,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (selected) {
                            Modifier.border(
                                width = 3.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = shape
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable {
                        onSelectLayout(layout)
                    }
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 16.dp,
                                end = 8.dp,
                                top = 12.dp,
                                bottom = 8.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = layout.displayName(),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = layout.description(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RadioButton(
                            selected = selected,
                            onClick = {
                                onSelectLayout(layout)
                            }
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = 0.35f
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        when (layout) {
                            CollectionLayoutStyle.HAND_FAN ->
                                HandFanCollectionLayout(previewCollection)

                            CollectionLayoutStyle.RIBBON_SPREAD ->
                                RibbonSpreadCollectionLayout(previewCollection)

                            CollectionLayoutStyle.HERO_MOSAIC ->
                                HeroMosaicCollectionLayout(previewCollection)
                        }
                    }
                }
            }
        }
    }
}

private fun CollectionLayoutStyle.displayName(): String =
    when (this) {
        CollectionLayoutStyle.HAND_FAN -> "Hand Fan"
        CollectionLayoutStyle.RIBBON_SPREAD -> "Ribbon Spread"
        CollectionLayoutStyle.HERO_MOSAIC -> "Hero Mosaic"
    }

private fun CollectionLayoutStyle.description(): String =
    when (this) {
        CollectionLayoutStyle.HAND_FAN ->
            "Cover first on the left, with the title beside the stack."

        CollectionLayoutStyle.RIBBON_SPREAD ->
            "Every comic spreads across the width beneath the title."

        CollectionLayoutStyle.HERO_MOSAIC ->
            "A large hero cover, supporting tiles, and the title below."
    }

@Composable
private fun ComicChoiceCard(
    comic: Comic,
    selected: Boolean,
    useRadioButton: Boolean,
    onClick: () -> Unit
) {
    val shape = MaterialTheme.shapes.medium
    val selectedBorder =
        if (selected) {
            Modifier.border(
                width = 3.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = shape
            )
        } else {
            Modifier
        }

    Card(
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .then(selectedBorder)
            .clickable(onClick = onClick)
    ) {
        Column {
            Box {
                AsyncImage(
                    model = comic.coverPagePath,
                    contentDescription = comic.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                )

                Surface(
                    shape = CircleShape,
                    tonalElevation = 3.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                ) {
                    if (useRadioButton) {
                        RadioButton(
                            selected = selected,
                            onClick = onClick
                        )
                    } else {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = {
                                onClick()
                            }
                        )
                    }
                }
            }

            Text(
                text = comic.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}