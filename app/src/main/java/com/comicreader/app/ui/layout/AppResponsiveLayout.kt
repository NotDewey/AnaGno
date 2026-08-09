package com.comicreader.app.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class AppWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

@Immutable
data class AppLayoutInfo(
    val widthClass: AppWidthClass,
    val useNavigationRail: Boolean,
    val horizontalContentPadding: Dp,
    val maximumContentWidth: Dp
)

private val DefaultLayoutInfo =
    AppLayoutInfo(
        widthClass =
            AppWidthClass.COMPACT,
        useNavigationRail =
            false,
        horizontalContentPadding =
            16.dp,
        maximumContentWidth =
            600.dp
    )

val LocalAppLayoutInfo =
    staticCompositionLocalOf {
        DefaultLayoutInfo
    }

/**
 * Resolves layout behavior from the real Compose window constraints rather
 * than from a particular phone model.
 *
 * This reacts to:
 *
 * - phones and tablets
 * - split-screen and freeform windows
 * - portrait/landscape changes
 * - foldable-window resizing
 */
@Composable
fun ProvideAppLayoutInfo(
    modifier: Modifier = Modifier,
    content: @Composable (
        AppLayoutInfo
    ) -> Unit
) {
    BoxWithConstraints(
        modifier =
            modifier.fillMaxSize()
    ) {
        /*
         * A rotated phone can be wider than 600 dp without actually being a
         * tablet. Short landscape windows keep the floating bottom dock.
         */
        val isLandscapePhone =
            maxWidth > maxHeight &&
                    maxHeight < 600.dp

        val layoutInfo =
            when {
                maxWidth <
                        600.dp -> {
                    AppLayoutInfo(
                        widthClass =
                            AppWidthClass.COMPACT,
                        useNavigationRail =
                            false,
                        horizontalContentPadding =
                            16.dp,
                        maximumContentWidth =
                            600.dp
                    )
                }

                maxWidth <
                        840.dp -> {
                    AppLayoutInfo(
                        widthClass =
                            AppWidthClass.MEDIUM,
                        useNavigationRail =
                            !isLandscapePhone,
                        horizontalContentPadding =
                            24.dp,
                        maximumContentWidth =
                            1000.dp
                    )
                }

                else -> {
                    AppLayoutInfo(
                        widthClass =
                            AppWidthClass.EXPANDED,
                        useNavigationRail =
                            !isLandscapePhone,
                        horizontalContentPadding =
                            32.dp,
                        maximumContentWidth =
                            1440.dp
                    )
                }
            }

        CompositionLocalProvider(
            LocalAppLayoutInfo provides
                    layoutInfo
        ) {
            content(
                layoutInfo
            )
        }
    }
}

/**
 * Keeps phone layouts full-width while preventing individual screens from
 * stretching indefinitely on a large tablet or desktop-sized window.
 */
@Composable
fun AdaptiveContentFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val layoutInfo =
        LocalAppLayoutInfo.current

    BoxWithConstraints(
        modifier =
            modifier.fillMaxSize(),
        contentAlignment =
            Alignment.TopCenter
    ) {
        val contentWidth =
            minOf(
                maxWidth,
                layoutInfo
                    .maximumContentWidth
            )

        Box(
            modifier = Modifier
                .width(
                    contentWidth
                )
                .fillMaxHeight()
        ) {
            content()
        }
    }
}
