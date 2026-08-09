package com.comicreader.app.ui.collections

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.comicreader.app.domain.model.Comic
import com.comicreader.app.domain.model.ComicCollection
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@Composable
fun CollectionDetailScreen(
    onBack: () -> Unit,
    onComicClick: (Comic) -> Unit,
    viewModel: CollectionDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    when {
        state.isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        state.collection == null -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Collection not found",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        onClick = onBack,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            "Go back",
                            modifier = Modifier.padding(
                                horizontal = 22.dp,
                                vertical = 12.dp
                            )
                        )
                    }
                }
            }
        }

        else -> {
            CollectionCarousel(
                collection = checkNotNull(state.collection),
                onBack = onBack,
                onComicClick = onComicClick
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionCarousel(
    collection: ComicCollection,
    onBack: () -> Unit,
    onComicClick: (Comic) -> Unit
) {
    val comics = collection.comics

    if (comics.isEmpty()) {
        EmptyCollectionDetail(
            collectionName = collection.name,
            onBack = onBack
        )
        return
    }

    val initialCoverIndex = remember(
        collection.id,
        collection.coverComicId,
        comics
    ) {
        comics.indexOfFirst { comic ->
            comic.id == collection.coverComicId
        }.takeIf { index -> index >= 0 } ?: 0
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { comics.size }
    )
    val coroutineScope = rememberCoroutineScope()
    var positionedOnCover by rememberSaveable(collection.id) {
        mutableStateOf(false)
    }

    LaunchedEffect(initialCoverIndex, comics.size) {
        if (!positionedOnCover && comics.isNotEmpty()) {
            pagerState.scrollToPage(
                initialCoverIndex.coerceIn(0, comics.lastIndex)
            )
            positionedOnCover = true
        }
    }

    val selectedComic =
        comics.getOrNull(pagerState.currentPage)
            ?: comics.first()

    Box(modifier = Modifier.fillMaxSize()) {
        BlurredComicBackground(
            coverPath = selectedComic.coverPagePath
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            CollectionDetailHeader(
                collection = collection,
                onBack = onBack
            )

            Spacer(Modifier.height(14.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    horizontal = 58.dp,
                    vertical = 12.dp
                ),
                pageSpacing = 8.dp,
                verticalAlignment = Alignment.CenterVertically
            ) { page ->
                val pageOffset =
                    (
                        (pagerState.currentPage - page) +
                            pagerState.currentPageOffsetFraction
                        ).coerceIn(-1f, 1f)

                FloatingComicCard(
                    comic = comics[page],
                    pageOffset = pageOffset,
                    isCentered = page == pagerState.currentPage,
                    onClick = {
                        if (page == pagerState.currentPage) {
                            onComicClick(comics[page])
                        } else {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(page)
                            }
                        }
                    }
                )
            }

            SelectedComicDetails(
                comic = selectedComic,
                currentIndex = pagerState.currentPage,
                totalCount = comics.size
            )

            Text(
                text = "Swipe to browse · Tap the centered comic to read",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.78f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 24.dp,
                        end = 24.dp,
                        top = 8.dp,
                        bottom = 18.dp
                    )
            )
        }
    }
}

@Composable
private fun BlurredComicBackground(
    coverPath: String?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Crossfade(
            targetState = coverPath,
            label = "collection-background"
        ) { path ->
            AsyncImage(
                model = path,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.18f
                        scaleY = 1.18f
                    }
                    .blur(32.dp)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.56f))
        )
    }
}

@Composable
private fun CollectionDetailHeader(
    collection: ComicCollection,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 8.dp,
                end = 20.dp,
                top = 4.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = collection.name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${collection.comics.size} comic" +
                    if (collection.comics.size == 1) "" else "s",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.76f)
            )
        }
    }
}

@Composable
private fun FloatingComicCard(
    comic: Comic,
    pageOffset: Float,
    isCentered: Boolean,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val absoluteOffset = pageOffset.absoluteValue

    val infiniteTransition =
        rememberInfiniteTransition(label = "comic-float")

    val floatingOffset by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2200,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "comic-float-offset"
    )

    val horizontalShift = with(density) {
        22.dp.toPx()
    }
    val sideDrop = with(density) {
        16.dp.toPx()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1f - absoluteOffset),
        contentAlignment = Alignment.Center
    ) {
        val shape = RoundedCornerShape(14.dp)

        AsyncImage(
            model = comic.coverPagePath,
            contentDescription = comic.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 290.dp)
                .aspectRatio(2f / 3f)
                .graphicsLayer {
                    rotationY = -pageOffset * 32f
                    scaleX = 1f - absoluteOffset * 0.18f
                    scaleY = 1f - absoluteOffset * 0.18f
                    translationX = -pageOffset * horizontalShift
                    translationY =
                        absoluteOffset * sideDrop +
                            if (isCentered) floatingOffset else 0f
                    alpha = 1f - absoluteOffset * 0.32f
                    cameraDistance = 12f * density.density
                }
                .shadow(
                    elevation = if (isCentered) 22.dp else 10.dp,
                    shape = shape
                )
                .clip(shape)
                .clickable(onClick = onClick)
        )
    }
}

@Composable
private fun SelectedComicDetails(
    comic: Comic,
    currentIndex: Int,
    totalCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = comic.title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        comic.series
            ?.takeIf { series -> series.isNotBlank() }
            ?.let { series ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = series,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.76f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

        Spacer(Modifier.height(8.dp))

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
}

@Composable
private fun EmptyCollectionDetail(
    collectionName: String,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }

            Text(
                text = collectionName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
