package com.comicreader.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val SearchTextColor = Color(0xFF1A1A1F)
private val SearchHintColor = Color(0xFF6E6E76)
private val SearchPillBackground = Color(0xFFEEEEF3)

/**
 * Reusable pill-shaped text field used by every search bar.
 *
 * This is intentionally separate from [TopSearchBar] so screens can reuse only
 * the field styling without also inheriting the horizontal title layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search",
    modifier: Modifier = Modifier,
    showSearchIcon: Boolean = true
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.heightIn(min = 56.dp),
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = SearchHintColor
            )
        },
        leadingIcon = if (showSearchIcon) {
            {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = SearchHintColor
                )
            }
        } else {
            null
        },
        singleLine = true,
        maxLines = 1,
        shape = RoundedCornerShape(50),
        textStyle = LocalTextStyle.current.copy(
            color = SearchTextColor
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = SearchPillBackground,
            unfocusedContainerColor = SearchPillBackground,
            disabledContainerColor = SearchPillBackground,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedTextColor = SearchTextColor,
            unfocusedTextColor = SearchTextColor,
            cursorColor = SearchTextColor,
            focusedPlaceholderColor = SearchHintColor,
            unfocusedPlaceholderColor = SearchHintColor,
            focusedLeadingIconColor = SearchHintColor,
            unfocusedLeadingIconColor = SearchHintColor
        )
    )
}

/**
 * Screen header: title on the left, the shared pill field filling the remaining
 * width, and an optional trailing action.
 */
@Composable
fun TopSearchBar(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search",
    showSearchIcon: Boolean = true,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp)
        )

        SearchPill(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = placeholder,
            showSearchIcon = showSearchIcon,
            modifier = Modifier.weight(1f)
        )

        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}