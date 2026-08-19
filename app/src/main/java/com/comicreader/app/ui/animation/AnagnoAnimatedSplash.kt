package com.comicreader.app.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.comicreader.app.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Real pathData for the "Outer A" mark, lifted directly from
 * clean_rebuild_full.xml (viewportWidth=1189, viewportHeight=1037).
 * It is one compound evenodd shape made of two subpaths:
 *   - the outer silhouette (the A itself)
 *   - the inner cutout (the negative-space notch near the base)
 * Splitting them lets each subpath be traced as its own stroke,
 * one after another, instead of guessing a centerline through them.
 */
private const val OUTER_SUBPATH =
    "M276.2,664L243.2,526L516.2,15C521.4,4.3 522.2,1.8 531.2,2L653.2,2C660.1,2.3 660.2,3.4 666.2,14" +
            "L907.2,472C920.2,504.9 941.7,519.7 931.2,606C924.2,649.6 914.1,668.1 887.2,709" +
            "C867.9,738.4 741.2,858 741.2,858C715.6,879.9 715.3,918.6 719.2,935L1052.2,939" +
            "C1058,938.1 1060.5,935.2 1059.2,930L932.2,699L966.2,560L1169.2,928" +
            "C1205.1,995.7 1191.8,1033.6 1113.2,1033L621.2,1036C616,1035.9 615.6,1033.2 615.2,1029" +
            "L616.2,926C616.6,875.2 628.3,835.2 654.2,809L766.2,692" +
            "C811.9,655.5 834.6,623.5 838.2,587C838.6,571.9 841.5,542.3 832.2,527L597.2,96" +
            "C592.2,85.4 588.6,85.3 583.2,96L276.2,664Z"

private const val INNER_SUBPATH =
    "M386.2,805L438.2,712L524.2,805C552.6,838.1 565.6,877.7 565.2,923L565.2,1030" +
            "C564.9,1034.2 562.6,1036.2 558.2,1036L70.2,1034C7.4,1028.6 -18.5,996.6 14.2,922" +
            "L215.2,558L250.2,698L123.2,931C119.5,936.9 119.7,940.7 128.2,940L462.2,936L462.2,908" +
            "C461.2,877.6 418.2,827.2 386.2,805Z"

@Composable
fun AnagnoAnimatedSplash(
    onFinished: () -> Unit
) {
    /*
     * OUTER-A TRACE SPLASH (real-geometry version)
     *
     * DURING DRAW:
     *   - white background
     *   - the actual outer-silhouette path strokes on first
     *   - the actual inner-cutout path strokes on right after
     *   - the finished logo stays completely hidden
     *
     * AFTER DRAW:
     *   - trace fades out
     *   - clean_rebuild_full fades in at the exact same visual size
     *   - splash fades away to reveal the app
     */

    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color.Black else Color.White
    val inkColor = if (isDarkTheme) Color.White else Color.Black

    val outerProgress = remember { Animatable(0f) }
    val innerProgress = remember { Animatable(0f) }
    val traceAlpha = remember { Animatable(1f) }
    val strokeIntroAlpha = remember { Animatable(0f) }
    val logoAlpha = remember { Animatable(0f) }
    val splashAlpha = remember { Animatable(1f) }

    val splashLogoSize = 120.dp

    // Constant velocity — no speeding up or slowing down mid-stroke,
    // so the pace reads as steady and unhurried throughout.
    val drawEasing = LinearEasing

    // Parsed once — real geometry, not a hand-drawn guide.
    val outerPath = remember { PathParser().parsePathString(OUTER_SUBPATH).toPath() }
    val innerPath = remember { PathParser().parsePathString(INNER_SUBPATH).toPath() }

    // PathMeasure.setPath() walks and segments the whole path — expensive
    // to redo every frame for geometry that never changes. Measured once
    // here instead of inside the Canvas draw scope.
    val outerMeasure = remember {
        PathMeasure().apply { setPath(outerPath, forceClosed = false) }
    }
    val innerMeasure = remember {
        PathMeasure().apply { setPath(innerPath, forceClosed = false) }
    }

    // Reused every frame instead of allocating a new Path object 60 times
    // a second — the allocation churn was landing right when the app is
    // also busy finishing cold-start work, which is exactly what showed up
    // as stutter at the start.
    val visibleOuter = remember { Path() }
    val visibleInner = remember { Path() }

    LaunchedEffect(Unit) {
        // Smooth fade-in: the white background is opaque from the very
        // first frame (never exposing the app underneath), but the stroke
        // itself gently fades to full black instead of popping in at hard
        // edges. The outer stroke's draw-on begins partway through this
        // fade so the two blend together.
        coroutineScope {
            launch {
                strokeIntroAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)
                )
            }
            launch {
                delay(150)

                // Outer silhouette draws first.
                outerProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 2200, easing = drawEasing)
                )

                // Inner cutout draws right after, no gap. Duration is scaled to
                // the outer stroke's pixel-speed (outer path ≈5526 units, inner
                // ≈2459 units) so the pen moves at a genuinely constant speed
                // throughout, not just within each individual stroke.
                innerProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 980, easing = drawEasing)
                )
            }
        }

        // Cross-fade from the trace into the untouched final vector logo.
        coroutineScope {
            launch {
                traceAlpha.animateTo(targetValue = 0f, animationSpec = tween(250))
            }
            launch {
                logoAlpha.animateTo(targetValue = 1f, animationSpec = tween(250))
            }
        }

        delay(160)

        // Reveal the already-loaded app underneath.
        splashAlpha.animateTo(targetValue = 0f, animationSpec = tween(220))

        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = splashAlpha.value }
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {

        /*
         * TRACE LAYER
         *
         * Same 1189 x 1037 viewport as the VectorDrawable, uniformly
         * scaled to fit the 120.dp square — matching ContentScale.Fit —
         * so the trace lines up exactly with the final artwork.
         */
        Canvas(modifier = Modifier.size(splashLogoSize)) {
            val viewportWidth = 1189f
            val viewportHeight = 1037f

            val scaleFactor = minOf(
                size.width / viewportWidth,
                size.height / viewportHeight
            )

            val offsetX = (size.width - viewportWidth * scaleFactor) / 2f
            val offsetY = (size.height - viewportHeight * scaleFactor) / 2f

            visibleOuter.reset()
            outerMeasure.getSegment(
                startDistance = 0f,
                stopDistance = outerMeasure.length * outerProgress.value,
                destination = visibleOuter,
                startWithMoveTo = true
            )

            visibleInner.reset()
            innerMeasure.getSegment(
                startDistance = 0f,
                stopDistance = innerMeasure.length * innerProgress.value,
                destination = visibleInner,
                startWithMoveTo = true
            )
            translate(left = offsetX, top = offsetY) {
                scale(scale = scaleFactor, pivot = Offset.Zero) {
                    val strokeStyle = Stroke(
                        width = 20f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )

                    drawPath(
                        path = visibleOuter,
                        color = inkColor.copy(alpha = traceAlpha.value * strokeIntroAlpha.value),
                        style = strokeStyle
                    )

                    drawPath(
                        path = visibleInner,
                        color = inkColor.copy(alpha = traceAlpha.value * strokeIntroAlpha.value),
                        style = strokeStyle
                    )
                }
            }
        }

        /*
         * FINAL VECTOR REVEAL
         *
         * clean_rebuild_full stays fully hidden during the trace and fades
         * in only after both subpaths finish drawing.
         */
        Image(
            painter = painterResource(R.drawable.clean_rebuild_full),
            contentDescription = null,
            colorFilter = if (isDarkTheme) ColorFilter.tint(Color.White) else null,
            modifier = Modifier
                .size(splashLogoSize)
                .graphicsLayer { alpha = logoAlpha.value }
        )
    }
}