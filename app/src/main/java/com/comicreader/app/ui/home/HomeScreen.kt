package com.comicreader.app.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.comicreader.app.domain.model.Comic
import com.comicreader.app.ui.components.TopSearchBar
import kotlin.math.roundToInt

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun HomeScreen(
    onComicClick: (Comic) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    var isEditing by rememberSaveable {
        mutableStateOf(false)
    }
    var selectedIds by remember {
        mutableStateOf<Set<Long>>(emptySet())
    }
    var showRemoveConfirmation by remember {
        mutableStateOf(false)
    }
    var showFinishConfirmation by remember {
        mutableStateOf(false)
    }

    val selectedComics = state.comics.filter { comic ->
        comic.id in selectedIds
    }

    fun leaveEditMode() {
        isEditing = false
        selectedIds = emptySet()
    }

    LaunchedEffect(Unit) {
        viewModel.onSearchQueryChanged("")
    }

    LaunchedEffect(state.comics.map(Comic::id)) {
        selectedIds = selectedIds.intersect(
            state.comics.map(Comic::id).toSet()
        )

        if (isEditing && state.totalReadingCount == 0) {
            leaveEditMode()
        }
    }

    if (showRemoveConfirmation) {
        val itemCount = selectedComics.size

        AlertDialog(
            onDismissRequest = {
                showRemoveConfirmation = false
            },
            title = {
                Text(
                    if (itemCount == 1) {
                        "Remove from Home?"
                    } else {
                        "Remove from Home?"
                    }
                )
            },
            text = {
                Text(
                    if (itemCount == 1) {
                        "This comic will leave Continue Reading, but it will stay in your Library and keep its saved page. Opening it again will return it to Home."
                    } else {
                        "These $itemCount comics will leave Continue Reading, but they will stay in your Library and keep their saved pages. Opening them again will return them to Home."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeFromHome(selectedComics)
                        showRemoveConfirmation = false
                        leaveEditMode()
                    },
                    enabled = selectedComics.isNotEmpty()
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRemoveConfirmation = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showFinishConfirmation) {
        val itemCount = selectedComics.size

        AlertDialog(
            onDismissRequest = {
                showFinishConfirmation = false
            },
            title = {
                Text(
                    if (itemCount == 1) {
                        "Mark comic as finished?"
                    } else {
                        "Mark comics as finished?"
                    }
                )
            },
            text = {
                Text(
                    if (itemCount == 1) {
                        "It will leave Home and its Library cover will become black and white. You can rate it later."
                    } else {
                        "These $itemCount comics will leave Home and their Library covers will become black and white. You can rate them later."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.markFinished(selectedComics)
                        showFinishConfirmation = false
                        leaveEditMode()
                    },
                    enabled = selectedComics.isNotEmpty()
                ) {
                    Text("Mark finished")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showFinishConfirmation = false
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
                        IconButton(
                            onClick = {
                                leaveEditMode()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Leave edit mode"
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                showFinishConfirmation = true
                            },
                            enabled = selectedComics.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Mark selected comics as finished"
                            )
                        }

                        IconButton(
                            onClick = {
                                showRemoveConfirmation = true
                            },
                            enabled = selectedComics.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.RemoveCircleOutline,
                                contentDescription = "Remove selected comics from Home"
                            )
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (!isEditing) {
                TopSearchBar(
                    title = "Home",
                    query = state.searchQuery,
                    onQueryChange = viewModel::onSearchQueryChanged,
                    placeholder = "Search currently reading",
                    trailing = {
                        IconButton(
                            onClick = {
                                viewModel.onSearchQueryChanged("")
                                isEditing = true
                            },
                            enabled = state.totalReadingCount > 0
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Manage currently reading comics"
                            )
                        }
                    }
                )
            }

            when {
                state.totalReadingCount == 0 -> {
                    EmptyHomeMessage()
                }

                state.comics.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No currently reading comics match your search.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            top = 8.dp,
                            end = 8.dp,
                            bottom = 112.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(
                            items = state.comics,
                            key = { comic -> comic.id }
                        ) { comic ->
                            ContinueReadingComic(
                                comic = comic,
                                isEditing = isEditing,
                                isSelected = comic.id in selectedIds,
                                onClick = {
                                    onComicClick(comic)
                                },
                                onToggleSelection = {
                                    selectedIds =
                                        if (comic.id in selectedIds) {
                                            selectedIds - comic.id
                                        } else {
                                            selectedIds + comic.id
                                        }
                                },
                                onLongPress = {
                                    isEditing = true
                                    selectedIds = selectedIds + comic.id
                                }
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
private fun ContinueReadingComic(
    comic: Comic,
    isEditing: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onLongPress: () -> Unit
) {
    val progress = comic.readingProgress()
    val coverShape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
        ) {
            AsyncImage(
                model = comic.coverPagePath,
                contentDescription = comic.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(coverShape)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant
                    )
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                width = 3.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = coverShape
                            )
                        } else {
                            Modifier
                        }
                    )
            )

            if (isEditing) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = {
                        onToggleSelection()
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(2.dp)
                )
            } else {
                ReadingProgressDonut(
                    progress = progress,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = comic.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

    }
}

@Composable
private fun ReadingProgressDonut(
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

@Composable
private fun EmptyHomeMessage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                "Nothing in progress",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Open a comic from your Library and it will appear here with its reading progress.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun Comic.readingProgress(): Float {
    if (pageCount <= 0) {
        return 0f
    }

    return (
            lastReadPage.coerceIn(0, pageCount - 1) + 1
            ).toFloat() / pageCount.toFloat()
}
