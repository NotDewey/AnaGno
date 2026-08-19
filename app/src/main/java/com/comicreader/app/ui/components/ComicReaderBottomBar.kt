package com.comicreader.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.delay
import kotlin.math.abs

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
)

data class ContextualDockAction(
    val route: String,
    val contentDescription: String,
    val onClick: () -> Unit
)

val bottomNavItems =
    listOf(
        BottomNavItem(
            "library",
            "Library",
            Icons.Outlined.MenuBook,
            Icons.Filled.MenuBook
        ),
        BottomNavItem(
            "collections",
            "Collections",
            Icons.Outlined.CollectionsBookmark,
            /*
             * Keep the selected state outlined too. The moving selection
             * pill, darker tint, and icon scale already communicate that the
             * tab is active without turning the icon into a heavy solid mass.
             */
            Icons.Outlined.CollectionsBookmark
        ),
        BottomNavItem(
            "ratings",
            "Ratings",
            Icons.Outlined.StarBorder,
            Icons.Filled.Star
        )
    )

@Composable
fun ComicReaderBottomBar(
    navController: NavHostController,
    hazeState: HazeState,
    contextualAction: ContextualDockAction? = null,
    modifier: Modifier = Modifier
) {
    val backStackEntry by
    navController
        .currentBackStackEntryAsState()

    val currentDestination =
        backStackEntry
            ?.destination

    val currentRoute =
        currentDestination
            ?.route

    val selectedIndex =
        bottomNavItems
            .indexOfFirst { item ->
                currentDestination
                    ?.hierarchy
                    ?.any {
                        it.route ==
                                item.route
                    } ==
                        true
            }
            .coerceAtLeast(0)

    val activeContextualAction =
        contextualAction
            ?.takeIf { action ->
                action.route ==
                        currentRoute
            }

    /*
     * Preserve the last action while its bubble retracts, so the exit
     * animation never loses its icon or click callback halfway through.
     */
    var renderedAction by
    remember {
        mutableStateOf<
                ContextualDockAction?
                >(
            null
        )
    }

    var shouldRevealAction by
    remember {
        mutableStateOf(
            false
        )
    }

    LaunchedEffect(
        activeContextualAction
    ) {
        if (
            activeContextualAction !=
            null
        ) {
            renderedAction =
                activeContextualAction
        }
    }

    /*
     * The action remains hidden for five seconds after entering Library or
     * Collections. It then separates from the main pill like a glass droplet.
     */
    LaunchedEffect(
        currentRoute,
        activeContextualAction !=
                null
    ) {
        shouldRevealAction =
            false

        if (
            activeContextualAction !=
            null
        ) {
            delay(
                5_000L
            )

            shouldRevealAction =
                true
        }
    }

    val separationProgress =
        remember {
            Animatable(
                0f
            )
        }

    LaunchedEffect(
        shouldRevealAction,
        activeContextualAction
    ) {
        separationProgress
            .animateTo(
                targetValue =
                    if (
                        shouldRevealAction
                    ) {
                        1f
                    } else {
                        0f
                    },
                animationSpec =
                    tween(
                        durationMillis =
                            if (
                                shouldRevealAction
                            ) {
                                680
                            } else {
                                300
                            },
                        easing =
                            FastOutSlowInEasing
                    )
            )

        if (
            !shouldRevealAction &&
            activeContextualAction ==
            null
        ) {
            renderedAction =
                null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                horizontal = 16.dp
            )
            .padding(
                bottom = 20.dp
            ),
        contentAlignment =
            Alignment.Center
    ) {
        BoxWithConstraints(
            modifier =
                Modifier.fillMaxWidth()
        ) {
            val progress =
                separationProgress
                    .value
                    .coerceIn(
                        0f,
                        1f
                    )

            val dockHeight =
                68.dp

            val actionSize =
                68.dp

            val actionGap =
                10.dp

            val baseMainWidth =
                maxWidth *
                        0.75f

            /*
             * Most phones can keep the full 75% pill. Very narrow windows
             * shrink it only enough to preserve the action bubble and gap.
             */
            val separatedMainWidth =
                minOf(
                    baseMainWidth,
                    (
                            maxWidth -
                                    actionSize -
                                    actionGap
                            )
                        .coerceAtLeast(
                            maxWidth *
                                    0.60f
                        )
                )

            val initialMainX =
                (
                        maxWidth -
                                baseMainWidth
                        ) / 2

            val finalGroupWidth =
                separatedMainWidth +
                        actionGap +
                        actionSize

            val finalGroupX =
                (
                        maxWidth -
                                finalGroupWidth
                        ) / 2

            val mainWidth =
                lerpDockDp(
                    start =
                        baseMainWidth,
                    end =
                        separatedMainWidth,
                    fraction =
                        progress
                )

            val mainX =
                lerpDockDp(
                    start =
                        initialMainX,
                    end =
                        finalGroupX,
                    fraction =
                        progress
                )

            /*
             * The action starts partially inside the main pill, then pulls
             * outward while the main pill slides left.
             */
            val initialActionX =
                initialMainX +
                        baseMainWidth -
                        50.dp

            val finalActionX =
                finalGroupX +
                        separatedMainWidth +
                        actionGap

            val actionX =
                lerpDockDp(
                    start =
                        initialActionX,
                    end =
                        finalActionX,
                    fraction =
                        progress
                )

            val actionAlpha =
                (
                        (
                                progress -
                                        0.08f
                                ) /
                                0.72f
                        )
                    .coerceIn(
                        0f,
                        1f
                    )

            val actionScale =
                0.34f +
                        0.66f *
                        progress

            /*
             * A temporary bridge makes the split feel like a drop of water
             * stretching away from the larger glass surface.
             */
            val bridgeAlpha =
                (
                        1f -
                                abs(
                                    progress *
                                            2f -
                                            1f
                                )
                        )
                    .coerceIn(
                        0f,
                        1f
                    ) *
                        0.78f

            val mainRight =
                mainX +
                        mainWidth

            val bridgeLeft =
                mainRight -
                        11.dp

            val bridgeRight =
                actionX +
                        11.dp

            val bridgeWidth =
                (
                        bridgeRight -
                                bridgeLeft
                        )
                    .coerceAtLeast(
                        0.dp
                    )

            if (
                renderedAction !=
                null &&
                bridgeWidth >
                0.dp
            ) {
                Box(
                    modifier = Modifier
                        .offset(
                            x =
                                bridgeLeft,
                            y =
                                (
                                        dockHeight -
                                                28.dp
                                        ) / 2
                        )
                        .width(
                            bridgeWidth
                        )
                        .height(
                            28.dp
                        )
                        .graphicsLayer {
                            alpha =
                                bridgeAlpha
                        }
                        .clip(
                            RoundedCornerShape(
                                50
                            )
                        )
                        .background(
                            MaterialTheme
                                .colorScheme
                                .surfaceVariant
                                .copy(
                                    alpha =
                                        if (
                                            isSystemInDarkTheme()
                                        ) {
                                            0.72f
                                        } else {
                                            0.94f
                                        }
                                )
                        )
                )
            }

            GlassNavigationSurface(
                hazeState =
                    hazeState,
                modifier = Modifier
                    .offset(
                        x =
                            mainX
                    )
                    .width(
                        mainWidth
                    )
                    .height(
                        dockHeight
                    )
            ) {
                BottomNavigationItems(
                    navController =
                        navController,
                    selectedIndex =
                        selectedIndex
                )
            }

            renderedAction
                ?.let { action ->
                    /*
                     * Contextual action bubble.
                     *
                     * Light mode keeps the original Haze/glass implementation.
                     *
                     * On some Samsung devices the detached Haze child can render
                     * its dark glass body while dropping/occluding the child icon.
                     * In dark mode we therefore keep the exact same geometry,
                     * position, alpha and scale animation, but render this ONE
                     * detached bubble as a normal Compose surface instead of a
                     * Haze child. The main navigation dock still uses Haze.
                     */
                    if (
                        isSystemInDarkTheme()
                    ) {
                        Box(
                            modifier = Modifier
                                .offset(
                                    x =
                                        actionX
                                )
                                .size(
                                    actionSize
                                )
                                .graphicsLayer {
                                    alpha =
                                        actionAlpha
                                    scaleX =
                                        actionScale
                                    scaleY =
                                        actionScale
                                }
                                .zIndex(
                                    20f
                                )
                                .shadow(
                                    elevation =
                                        14.dp,
                                    shape =
                                        RoundedCornerShape(
                                            30.dp
                                        ),
                                    clip =
                                        false,
                                    ambientColor =
                                        Color.Black.copy(
                                            alpha =
                                                0.16f
                                        ),
                                    spotColor =
                                        Color.Black.copy(
                                            alpha =
                                                0.20f
                                        )
                                )
                                .clip(
                                    RoundedCornerShape(
                                        30.dp
                                    )
                                )
                                .background(
                                    MaterialTheme
                                        .colorScheme
                                        .surfaceVariant
                                        .copy(
                                            alpha =
                                                0.96f
                                        )
                                )
                                .border(
                                    width =
                                        0.75.dp,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurface
                                            .copy(
                                                alpha =
                                                    0.10f
                                            ),
                                    shape =
                                        RoundedCornerShape(
                                            30.dp
                                        )
                                ),
                            contentAlignment =
                                Alignment.Center
                        ) {
                            IconButton(
                                onClick =
                                    action.onClick,
                                enabled =
                                    activeContextualAction !=
                                            null &&
                                            actionAlpha >
                                            0.15f,
                                modifier =
                                    Modifier.size(
                                        58.dp
                                    )
                            ) {
                                Icon(
                                    imageVector =
                                        Icons.Default.Add,
                                    contentDescription =
                                        action
                                            .contentDescription,
                                    tint =
                                        Color.White,
                                    modifier = Modifier
                                        .size(
                                            30.dp
                                        )
                                        .graphicsLayer {
                                            val iconScale =
                                                0.76f +
                                                        actionAlpha *
                                                        0.24f

                                            scaleX =
                                                iconScale
                                            scaleY =
                                                iconScale
                                        }
                                )
                            }
                        }
                    } else {
                        GlassNavigationSurface(
                            hazeState =
                                hazeState,
                            accented =
                                true,
                            modifier = Modifier
                                .offset(
                                    x =
                                        actionX
                                )
                                .size(
                                    actionSize
                                )
                                .graphicsLayer {
                                    alpha =
                                        actionAlpha
                                    scaleX =
                                        actionScale
                                    scaleY =
                                        actionScale
                                }
                        ) {
                            Box(
                                modifier =
                                    Modifier.fillMaxSize(),
                                contentAlignment =
                                    Alignment.Center
                            ) {
                                IconButton(
                                    onClick =
                                        action.onClick,
                                    enabled =
                                        activeContextualAction !=
                                                null &&
                                                actionAlpha >
                                                0.15f,
                                    modifier =
                                        Modifier.size(
                                            58.dp
                                        )
                                ) {
                                    Icon(
                                        imageVector =
                                            Icons.Default.Add,
                                        contentDescription =
                                            action
                                                .contentDescription,
                                        tint =
                                            MaterialTheme
                                                .colorScheme
                                                .onPrimaryContainer,
                                        modifier = Modifier
                                            .size(
                                                30.dp
                                            )
                                            .graphicsLayer {
                                                val iconScale =
                                                    0.76f +
                                                            actionAlpha *
                                                            0.24f

                                                scaleX =
                                                    iconScale
                                                scaleY =
                                                    iconScale
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun BottomNavigationItems(
    navController: NavHostController,
    selectedIndex: Int
) {
    BoxWithConstraints(
        modifier =
            Modifier.fillMaxSize()
    ) {
        val itemWidth =
            maxWidth /
                    bottomNavItems.size

        val indicatorSize =
            46.dp

        val indicatorOffset by
        animateDpAsState(
            targetValue =
                itemWidth *
                        selectedIndex +
                        (
                                itemWidth -
                                        indicatorSize
                                ) / 2,
            animationSpec =
                spring(
                    dampingRatio =
                        0.78f,
                    stiffness =
                        430f
                ),
            label =
                "Bottom dock indicator"
        )

        val indicatorY =
            (
                    maxHeight -
                            indicatorSize
                    ) / 2

        Box(
            modifier = Modifier
                .offset(
                    x =
                        indicatorOffset,
                    y =
                        indicatorY
                )
                .size(
                    indicatorSize
                )
                .clip(
                    RoundedCornerShape(
                        18.dp
                    )
                )
                .background(
                    MaterialTheme
                        .colorScheme
                        .onSurface
                        .copy(
                            alpha =
                                if (
                                    isSystemInDarkTheme()
                                ) {
                                    0.13f
                                } else {
                                    0.08f
                                }
                        )
                )
                .border(
                    width =
                        0.75.dp,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurface
                            .copy(
                                alpha =
                                    0.08f
                            ),
                    shape =
                        RoundedCornerShape(
                            18.dp
                        )
                )
        )

        val buttonSize =
            56.dp

        val buttonY =
            (
                    maxHeight -
                            buttonSize
                    ) / 2

        bottomNavItems
            .forEachIndexed {
                    index,
                    item ->

                val selected =
                    index ==
                            selectedIndex

                val buttonX =
                    itemWidth *
                            index +
                            (
                                    itemWidth -
                                            buttonSize
                                    ) / 2

                val iconScale by
                animateFloatAsState(
                    targetValue =
                        if (
                            selected
                        ) {
                            1.10f
                        } else {
                            1f
                        },
                    animationSpec =
                        spring(
                            dampingRatio =
                                0.72f,
                            stiffness =
                                520f
                        ),
                    label =
                        "${item.label} scale"
                )

                val iconColor by
                animateColorAsState(
                    targetValue =
                        if (
                            selected
                        ) {
                            MaterialTheme
                                .colorScheme
                                .onSurface
                        } else {
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                                .copy(
                                    alpha =
                                        0.82f
                                )
                        },
                    label =
                        "${item.label} color"
                )

                IconButton(
                    onClick = {
                        navigateToBottomDestination(
                            navController =
                                navController,
                            route =
                                item.route
                        )
                    },
                    modifier = Modifier
                        .offset(
                            x =
                                buttonX,
                            y =
                                buttonY
                        )
                        .size(
                            buttonSize
                        )
                ) {
                    Icon(
                        imageVector =
                            if (
                                selected
                            ) {
                                item.selectedIcon
                            } else {
                                item.icon
                            },
                        contentDescription =
                            item.label,
                        tint =
                            iconColor,
                        modifier = Modifier
                            .size(
                                24.dp
                            )
                            .graphicsLayer {
                                scaleX =
                                    iconScale
                                scaleY =
                                    iconScale
                            }
                    )
                }
            }
    }
}

private fun lerpDockDp(
    start: Dp,
    end: Dp,
    fraction: Float
): Dp =
    Dp(
        start.value +
                (
                        end.value -
                                start.value
                        ) *
                fraction
    )

@Composable
fun GlassNavigationSurface(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    accented: Boolean = false,
    shape: Shape =
        RoundedCornerShape(
            30.dp
        ),
    content:
    @Composable
    BoxScope.() -> Unit
) {
    val darkTheme =
        isSystemInDarkTheme()

    val surfaceColor =
        MaterialTheme
            .colorScheme
            .surface

    val hazeTint =
        surfaceColor.copy(
            alpha =
                if (
                    darkTheme
                ) {
                    0.74f
                } else {
                    0.58f
                }
        )

    val fallbackTint =
        surfaceColor.copy(
            alpha =
                if (
                    darkTheme
                ) {
                    0.42f
                } else {
                    0.25f
                }
        )

    Box(
        modifier = modifier
            .shadow(
                elevation =
                    14.dp,
                shape =
                    shape,
                clip =
                    false,
                ambientColor =
                    Color.Black.copy(
                        alpha =
                            0.16f
                    ),
                spotColor =
                    Color.Black.copy(
                        alpha =
                            0.20f
                    )
            )
            .clip(
                shape
            )
    ) {
        /*
         * Samsung's light navigation-bar boundary is still being sampled by
         * Haze as a bright horizontal line. Dark mode does not show the issue.
         *
         * Dark theme keeps real backdrop blur. Light theme uses one uniform,
         * translucent glass fill with no backdrop sampling, masks or gradient
         * edges. This preserves the glass appearance while removing the
         * device-specific white strip completely.
         */
        if (
            darkTheme
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeChild(
                        state =
                            hazeState,
                        shape =
                            shape,
                        style =
                            HazeStyle(
                                tint =
                                    hazeTint,
                                blurRadius =
                                    24.dp,
                                noiseFactor =
                                    0.035f
                            )
                    )
                    .background(
                        fallbackTint
                    )
                    .background(
                        Color.White.copy(
                            alpha =
                                0.025f
                        )
                    )
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                            .copy(
                                alpha =
                                    0.94f
                            )
                    )
            )
        }

        if (
            accented
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme
                            .colorScheme
                            .primaryContainer
                            .copy(
                                alpha =
                                    if (
                                        darkTheme
                                    ) {
                                        0.58f
                                    } else {
                                        0.86f
                                    }
                            )
                    )
            )
        }

        content()
    }
}

private fun navigateToBottomDestination(
    navController: NavHostController,
    route: String
) {
    navController.navigate(
        route
    ) {
        popUpTo(
            navController
                .graph
                .findStartDestination()
                .id
        ) {
            saveState =
                true
        }

        launchSingleTop =
            true
        restoreState =
            true
    }
}

@Composable
fun ComicReaderNavigationRail(
    navController: NavHostController
) {
    val backStackEntry by
    navController
        .currentBackStackEntryAsState()

    val currentDestination =
        backStackEntry
            ?.destination

    NavigationRail(
        containerColor =
            MaterialTheme
                .colorScheme
                .surface
    ) {
        Spacer(
            Modifier.height(
                12.dp
            )
        )

        bottomNavItems
            .forEach { item ->
                val selected =
                    currentDestination
                        ?.hierarchy
                        ?.any {
                            it.route ==
                                    item.route
                        } ==
                            true

                NavigationRailItem(
                    selected =
                        selected,
                    onClick = {
                        navigateToBottomDestination(
                            navController =
                                navController,
                            route =
                                item.route
                        )
                    },
                    icon = {
                        Icon(
                            imageVector =
                                if (
                                    selected
                                ) {
                                    item.selectedIcon
                                } else {
                                    item.icon
                                },
                            contentDescription =
                                item.label
                        )
                    },
                    label =
                        null,
                    alwaysShowLabel =
                        false
                )
            }
    }
}
