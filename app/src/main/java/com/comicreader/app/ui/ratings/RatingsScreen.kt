package com.comicreader.app.ui.ratings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.comicreader.app.domain.model.Comic
import com.comicreader.app.ui.components.TopSearchBar
import kotlin.math.roundToInt

@Composable
fun RatingsScreen(
    onComicClick: (Comic) -> Unit,
    viewModel: RatingsViewModel = hiltViewModel()
) {
    val state by
    viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.onSearchQueryChanged("")
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            TopSearchBar(
                title = "Ratings",
                query = state.searchQuery,
                onQueryChange =
                    viewModel::onSearchQueryChanged,
                placeholder = "Search rated comics"
            )

            when {
                state.totalRatedCount == 0 -> {
                    EmptyRatingsMessage()
                }

                state.comics.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No rated comics match your search.",
                            style =
                                MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns =
                            GridCells.Adaptive(
                                minSize = 150.dp
                            ),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            top = 8.dp,
                            end = 12.dp,
                            bottom = 112.dp
                        ),
                        horizontalArrangement =
                            Arrangement.spacedBy(12.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(16.dp)
                    ) {
                        items(
                            items = state.comics,
                            key = { comic -> comic.id }
                        ) { comic ->
                            RatedComicCard(
                                comic = comic,
                                onClick = {
                                    onComicClick(comic)
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
private fun RatedComicCard(
    comic: Comic,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = comic.coverPagePath,
            contentDescription = comic.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    MaterialTheme.colorScheme
                        .surfaceVariant
                )
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = comic.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(5.dp))

        ComicRatingRow(
            rating = comic.userRating ?: 0f
        )
    }
}

@Composable
private fun ComicRatingRow(rating: Float) {
    val roundedRating =
        rating.roundToInt().coerceIn(0, 5)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        repeat(5) { index ->
            Icon(
                imageVector =
                    if (index < roundedRating) {
                        Icons.Default.Star
                    } else {
                        Icons.Default.StarBorder
                    },
                contentDescription = null,
                tint =
                    MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
        }

        Spacer(Modifier.size(4.dp))

        Text(
            text = String.format("%.1f", rating),
            style = MaterialTheme.typography.labelMedium,
            color =
                MaterialTheme.colorScheme
                    .onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyRatingsMessage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                "No ratings yet",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Finished comics appear here only after you choose a rating.",
                style = MaterialTheme.typography.bodyMedium,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
