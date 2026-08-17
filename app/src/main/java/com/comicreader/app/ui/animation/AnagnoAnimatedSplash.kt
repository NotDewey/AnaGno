package com.comicreader.app.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.comicreader.app.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AnagnoAnimatedSplash(
    onFinished: () -> Unit
) {
    /*
     * OUTER-A TRACE SPLASH
     *
     * DURING DRAW:
     *   - white background
     *   - one black continuous stroke
     *   - the stroke follows the main OUTER A
     *   - the finished logo stays completely hidden
     *
     * AFTER DRAW:
     *   - trace fades out
     *   - clean_rebuild_full fades in at the exact same visual size
     *   - splash fades away to reveal the app
     */

    val pathProgress = remember { Animatable(0f) }
    val traceAlpha = remember { Animatable(1f) }
    val logoAlpha = remember { Animatable(0f) }
    val startDotAlpha = remember { Animatable(0f) }
    val splashAlpha = remember { Animatable(1f) }

    /*
     * Same visual size as before.
     *
     * Both the Canvas and the finished vector live inside the same 120.dp
     * square. The Canvas uses the real 1189 x 1037 viewport with uniform
     * FIT scaling, so the trace does not get stretched and lines up with
     * the VectorDrawable.
     */
    val splashLogoSize = 120.dp

    /*
     * CSS/Lottie-style ease-in-out:
     * cubic-bezier(0.42, 0.0, 0.58, 1.0)
     */
    val drawEasing = CubicBezierEasing(
        0.42f,
        0.0f,
        0.58f,
        1.0f
    )

    LaunchedEffect(Unit) {
        // Start marker appears almost immediately.
        startDotAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(60)
        )

        /*
         * One uninterrupted trace:
         *
         * bottom-left
         * -> up the left leg
         * -> over the apex
         * -> down the right leg
         * -> through the characteristic right-hand bend
         * -> down into the center stem
         */
        pathProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 1550,
                easing = drawEasing
            )
        )

        /*
         * Cross-fade from the temporary trace into the untouched
         * final vector logo.
         */
        coroutineScope {
            launch {
                traceAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(250)
                )
            }

            launch {
                startDotAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(180)
                )
            }

            launch {
                logoAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(250)
                )
            }
        }

        delay(160)

        // Reveal the already-loaded app underneath.
        splashAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(220)
        )

        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = splashAlpha.value
            }
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {

        /*
         * TRACE LAYER
         *
         * The supplied VectorDrawable uses:
         * viewportWidth  = 1189
         * viewportHeight = 1037
         *
         * We preserve that aspect ratio inside the 120.dp square using
         * one uniform scale, exactly like Image(..., ContentScale.Fit).
         */
        Canvas(
            modifier = Modifier.size(splashLogoSize)
        ) {
            val viewportWidth = 1189f
            val viewportHeight = 1037f

            val scale = minOf(
                size.width / viewportWidth,
                size.height / viewportHeight
            )

            val offsetX =
                (size.width - viewportWidth * scale) / 2f

            val offsetY =
                (size.height - viewportHeight * scale) / 2f

            fun p(
                x: Float,
                y: Float
            ): Offset =
                Offset(
                    x = offsetX + x * scale,
                    y = offsetY + y * scale
                )

            /*
             * OUTER A GUIDE PATH
             *
             * This is intentionally a CENTER GUIDE rather than the filled
             * VectorDrawable pathData itself.
             *
             * The supplied third <path> is a filled compound silhouette.
             * PathMeasure on that filled outline would travel around its edges.
             * This guide instead travels through the visual center of the A,
             * producing the "draw the A with one stroke" effect.
             */
            val tracePath = Path().apply {

                // ---------------------------------------------------------
                // START — lower-left end of the outer A
                // ---------------------------------------------------------
                moveTo(
                    p(123f, 931f).x,
                    p(123f, 931f).y
                )

                // ---------------------------------------------------------
                // LEFT LEG — rise through the center of the thick stroke
                // ---------------------------------------------------------
                cubicTo(
                    p(160f, 860f).x, p(160f, 860f).y,
                    p(220f, 735f).x, p(220f, 735f).y,
                    p(276f, 664f).x, p(276f, 664f).y
                )

                cubicTo(
                    p(335f, 525f).x, p(335f, 525f).y,
                    p(430f, 300f).x, p(430f, 300f).y,
                    p(516f, 80f).x, p(516f, 80f).y
                )

                // ---------------------------------------------------------
                // APEX — smooth rounded transition across the top
                // ---------------------------------------------------------
                cubicTo(
                    p(535f, 35f).x, p(535f, 35f).y,
                    p(560f, 18f).x, p(560f, 18f).y,
                    p(590f, 18f).x, p(590f, 18f).y
                )

                cubicTo(
                    p(620f, 18f).x, p(620f, 18f).y,
                    p(646f, 38f).x, p(646f, 38f).y,
                    p(666f, 80f).x, p(666f, 80f).y
                )

                // ---------------------------------------------------------
                // RIGHT LEG — descend along the main right-hand stroke
                // ---------------------------------------------------------
                cubicTo(
                    p(720f, 185f).x, p(720f, 185f).y,
                    p(790f, 350f).x, p(790f, 350f).y,
                    p(832f, 527f).x, p(832f, 527f).y
                )

                // ---------------------------------------------------------
                // SIGNATURE BEND — curve inward instead of continuing
                // as a normal triangular A
                // ---------------------------------------------------------
                cubicTo(
                    p(843f, 555f).x, p(843f, 555f).y,
                    p(842f, 590f).x, p(842f, 590f).y,
                    p(830f, 620f).x, p(830f, 620f).y
                )

                cubicTo(
                    p(815f, 655f).x, p(815f, 655f).y,
                    p(790f, 680f).x, p(790f, 680f).y,
                    p(766f, 700f).x, p(766f, 700f).y
                )

                cubicTo(
                    p(730f, 735f).x, p(730f, 735f).y,
                    p(685f, 775f).x, p(685f, 775f).y,
                    p(654f, 809f).x, p(654f, 809f).y
                )

                // ---------------------------------------------------------
                // CENTER STEM — finish at the bottom center opening
                // ---------------------------------------------------------
                cubicTo(
                    p(625f, 845f).x, p(625f, 845f).y,
                    p(616f, 885f).x, p(616f, 885f).y,
                    p(616f, 926f).x, p(616f, 926f).y
                )
            }

            val measure = PathMeasure().apply {
                setPath(
                    path = tracePath,
                    forceClosed = false
                )
            }

            val visiblePath = Path()

            measure.getSegment(
                startDistance = 0f,
                stopDistance =
                    measure.length * pathProgress.value,
                destination = visiblePath,
                startWithMoveTo = true
            )

            /*
             * Trace thickness is expressed in the original 1189 x 1037
             * viewport and then uniformly scaled with the artwork.
             *
             * 46f gives a deliberate drawing stroke without trying to
             * completely fill the final thick A.
             */
            drawPath(
                path = visiblePath,
                color = Color.Black.copy(
                    alpha = traceAlpha.value
                ),
                style = Stroke(
                    width = 46f * scale,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            /*
             * Blue marker at the exact trace start.
             * It stays still while the black path grows away from it.
             */
            drawCircle(
                color = Color(0xFF169BFF).copy(
                    alpha = startDotAlpha.value
                ),
                radius = 12f * scale,
                center = p(
                    123f,
                    931f
                )
            )
        }

        /*
         * FINAL VECTOR REVEAL
         *
         * clean_rebuild_full stays fully hidden during the trace and fades
         * in only after the outer-A path reaches 100%.
         *
         * Because it uses the exact same 120.dp container and the Canvas
         * uses FIT scaling against the real viewport, the trace and final
         * artwork remain aligned.
         */
        Image(
            painter = painterResource(
                R.drawable.clean_rebuild_full
            ),
            contentDescription = null,
            modifier = Modifier
                .size(splashLogoSize)
                .graphicsLayer {
                    alpha = logoAlpha.value
                }
        )
    }
}