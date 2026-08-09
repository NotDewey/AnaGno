package com.comicreader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.ripple.LocalRippleTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material.ripple.RippleTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.comicreader.app.ui.navigation.ComicReaderNavHost
import com.comicreader.app.ui.theme.ComicReaderTheme
import dagger.hilt.android.AndroidEntryPoint
import com.comicreader.app.ui.animation.AnagnoAnimatedSplash

/**
 * Removes the rectangular/square press flash that Compose ripple indications
 * can show around clickable surfaces.
 *
 * Interaction behavior is unchanged: taps, long-presses, selected states and
 * haptics still work. Only the visual ripple/highlight is made transparent.
 */
private object NoVisibleRippleTheme : RippleTheme {

    @Composable
    override fun defaultColor(): Color =
        Color.Transparent

    @Composable
    override fun rippleAlpha(): RippleAlpha =
        RippleAlpha(
            draggedAlpha = 0f,
            focusedAlpha = 0f,
            hoveredAlpha = 0f,
            pressedAlpha = 0f
        )
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {

            ComicReaderTheme {

                CompositionLocalProvider(
                    LocalRippleTheme provides NoVisibleRippleTheme
                ) {

                    var showAnagnoIntro by remember {
                        mutableStateOf(true)
                    }

                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {

                        Box(
                            modifier = Modifier.fillMaxSize()
                        ) {

                            // The actual app loads immediately underneath.
                            ComicReaderNavHost()

                            // Animated Anagno intro sits on top.
                            if (showAnagnoIntro) {

                                AnagnoAnimatedSplash(
                                    onFinished = {
                                        showAnagnoIntro = false
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