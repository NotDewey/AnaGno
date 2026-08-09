package com.comicreader.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC9FF),
    background = Color(0xFF0E0E10),
    surface = Color(0xFF1A1A1D),
    onBackground = Color(0xFFE7E7E9),
    onSurface = Color(0xFFE7E7E9)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5FA3),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun ComicReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    ApplyAppWindowAppearance(
        darkTheme =
            darkTheme
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
private fun ApplyAppWindowAppearance(
    darkTheme: Boolean
) {
    val view =
        LocalView.current

    if (view.isInEditMode) {
        return
    }

    SideEffect {
        val activity =
            view.context
                .findActivity()
                ?: return@SideEffect

        val window =
            activity.window

        /*
         * The whole app uses one edge-to-edge policy. Screens consume insets
         * through Compose instead of changing the Android window's fitted
         * content area.
         */
        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        window.statusBarColor =
            AndroidColor.TRANSPARENT
        window.navigationBarColor =
            AndroidColor.TRANSPARENT

        WindowCompat
            .getInsetsController(
                window,
                view
            )
            .apply {
                isAppearanceLightStatusBars =
                    !darkTheme
                isAppearanceLightNavigationBars =
                    !darkTheme
            }
    }
}

private tailrec fun Context.findActivity():
        Activity? =
    when (this) {
        is Activity ->
            this

        is ContextWrapper ->
            baseContext.findActivity()

        else ->
            null
    }