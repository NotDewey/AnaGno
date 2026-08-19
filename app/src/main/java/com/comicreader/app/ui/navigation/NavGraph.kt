package com.comicreader.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.comicreader.app.ui.collections.CollectionsScreen
import com.comicreader.app.ui.components.ComicReaderBottomBar
import com.comicreader.app.ui.components.ComicReaderNavigationRail
import com.comicreader.app.ui.components.ContextualDockAction
import com.comicreader.app.ui.haptics.AppHaptics
import com.comicreader.app.ui.layout.AdaptiveContentFrame
import com.comicreader.app.ui.layout.ProvideAppLayoutInfo
import com.comicreader.app.ui.library.LibraryScreen
import com.comicreader.app.ui.ratings.RatingsScreen
import com.comicreader.app.ui.reader.ReaderScreen
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze

private object Routes {
    const val LIBRARY =
        "library"
    const val COLLECTIONS =
        "collections"
    const val RATINGS =
        "ratings"
    const val READER =
        "reader/{comicId}"

    fun reader(
        comicId: Long
    ) =
        "reader/$comicId"

    val bottomNavRoutes =
        setOf(
            LIBRARY,
            COLLECTIONS,
            RATINGS
        )
}

@OptIn(
    ExperimentalLayoutApi::class
)
@Composable
fun ComicReaderNavHost(
    navController:
    NavHostController =
        rememberNavController()
) {
    val backStackEntry by
    navController
        .currentBackStackEntryAsState()

    val currentRoute =
        backStackEntry
            ?.destination
            ?.route

    val showAppNavigation =
        currentRoute in
                Routes.bottomNavRoutes

    val hapticView =
        LocalView.current

    val openComic:
                (Long) -> Unit =
        remember(
            navController,
            hapticView
        ) {
            { comicId ->
                AppHaptics.comicOpen(
                    hapticView
                )

                navController.navigate(
                    Routes.reader(
                        comicId
                    )
                )
            }
        }

    var contextualDockAction by
    remember {
        mutableStateOf<
                ContextualDockAction?
                >(
            null
        )
    }

    val updateContextualDockAction:
                (ContextualDockAction?) -> Unit =
        remember {
            {
                    action ->
                contextualDockAction =
                    action
            }
        }

    val activeContextualDockAction =
        contextualDockAction
            ?.takeIf { action ->
                action.route ==
                        currentRoute
            }

    ProvideAppLayoutInfo {
            layoutInfo ->

        val showNavigationRail =
            showAppNavigation &&
                    layoutInfo
                        .useNavigationRail

        val showBottomDock =
            showAppNavigation &&
                    !layoutInfo
                        .useNavigationRail

        val navigationRailWidth =
            if (
                showNavigationRail
            ) {
                80.dp
            } else {
                0.dp
            }

        val hazeState =
            remember {
                HazeState()
            }

        val sourceModifier =
            if (
                showBottomDock
            ) {
                Modifier.haze(
                    state =
                        hazeState,
                    style =
                        HazeStyle(
                            tint =
                                MaterialTheme
                                    .colorScheme
                                    .surface
                                    .copy(
                                        alpha =
                                            0.58f
                                    ),
                            blurRadius =
                                24.dp,
                            noiseFactor =
                                0.035f
                        )
                )
            } else {
                Modifier
            }

        Box(
            modifier =
                Modifier.fillMaxSize()
        ) {
            if (
                showNavigationRail
            ) {
                Box(
                    modifier = Modifier
                        .align(
                            Alignment.CenterStart
                        )
                        .fillMaxSize()
                ) {
                    ComicReaderNavigationRail(
                        navController =
                            navController
                    )
                }
            }

            /*
             * The Haze source is restricted to the actual app-content area
             * above the system navigation bar. This prevents the light
             * navigation-bar boundary from being sampled into the glass dock.
             *
             * The floating dock remains a sibling outside this source, so its
             * icons and selection animation stay sharp.
             */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets
                            .navigationBars
                            .only(
                                WindowInsetsSides
                                    .Bottom
                            )
                    )
                    .then(
                        sourceModifier
                    )
            ) {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start =
                                navigationRailWidth
                        ),
                    contentWindowInsets =
                        WindowInsets(
                            0,
                            0,
                            0,
                            0
                        )
                ) { contentPadding ->
                    NavHost(
                        navController =
                            navController,
                        startDestination =
                            Routes.LIBRARY,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                contentPadding
                            )
                            .consumeWindowInsets(
                                contentPadding
                            )
                    ) {
                        composable(
                            Routes.LIBRARY
                        ) {
                            AdaptiveContentFrame {
                                LibraryScreen(
                                    onComicClick = {
                                            comic ->
                                        openComic(
                                            comic.id
                                        )
                                    },
                                    onContextualActionChanged =
                                        updateContextualDockAction
                                )
                            }
                        }

                        composable(
                            Routes.COLLECTIONS
                        ) {
                            AdaptiveContentFrame {
                                CollectionsScreen(
                                    onComicClick = {
                                            comic ->
                                        openComic(
                                            comic.id
                                        )
                                    },
                                    onContextualActionChanged =
                                        updateContextualDockAction
                                )
                            }
                        }

                        composable(
                            Routes.RATINGS
                        ) {
                            AdaptiveContentFrame {
                                RatingsScreen(
                                    onComicClick = {
                                            comic ->
                                        openComic(
                                            comic.id
                                        )
                                    }
                                )
                            }
                        }

                        composable(
                            route =
                                Routes.READER,
                            arguments =
                                listOf(
                                    navArgument(
                                        "comicId"
                                    ) {
                                        type =
                                            NavType
                                                .LongType
                                    }
                                )
                        ) {
                            ReaderScreen(
                                onBack = {
                                    navController
                                        .popBackStack()
                                }
                            )
                        }
                    }
                }
            }

            if (
                showBottomDock
            ) {
                ComicReaderBottomBar(
                    navController =
                        navController,
                    hazeState =
                        hazeState,
                    contextualAction =
                        activeContextualDockAction,
                    modifier =
                        Modifier.align(
                            Alignment.BottomCenter
                        )
                )
            }

            if (
                showNavigationRail &&
                activeContextualDockAction !=
                null
            ) {
                FloatingActionButton(
                    onClick =
                        activeContextualDockAction
                            .onClick,
                    modifier = Modifier
                        .align(
                            Alignment.BottomEnd
                        )
                        .navigationBarsPadding()
                        .padding(
                            24.dp
                        )
                ) {
                    Icon(
                        imageVector =
                            Icons.Default.Add,
                        contentDescription =
                            activeContextualDockAction
                                .contentDescription
                    )
                }
            }
        }
    }
}
