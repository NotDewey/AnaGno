package com.comicreader.app.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.comicreader.app.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AnagnoAnimatedSplash(
    onFinished: () -> Unit
) {
    /*
     * V5 — one continuous trim-path animation.
     *
     * DURING DRAW:
     *   - white background
     *   - one black continuous stroke only
     *   - NO partial/full Anagno artwork underneath
     *
     * AFTER DRAW:
     *   - trace fades out
     *   - exact high-resolution Anagno artwork cross-fades in
     */

    val pathProgress = remember { Animatable(0f) }
    val traceAlpha = remember { Animatable(1f) }
    val logoAlpha = remember { Animatable(0f) }
    val startDotAlpha = remember { Animatable(0f) }
    val splashAlpha = remember { Animatable(1f) }

    val logoBitmap = ImageBitmap.imageResource(
        id = R.drawable.anagno_logo_tight_clean
    )

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
        // Blue start marker appears.
        startDotAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(100)
        )

        delay(80)

        /*
         * ONE uninterrupted trim animation:
         * outer route -> center transition -> inner loop.
         */
        pathProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 1550,
                easing = drawEasing
            )
        )

        /*
         * Final 250 ms cross-fade.
         * Trace disappears while the untouched complete logo appears.
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
         * Everything uses the SAME 250.dp drawing box so the trace
         * and final logo sit in exactly the same visual position.
         */
        Canvas(
            modifier = Modifier.size(250.dp)
        ) {
            /*
             * Coordinate system taken directly from the annotated
             * 357x338 reference image.
             */
            val sx = size.width / 357f
            val sy = size.height / 338f

            fun p(x: Float, y: Float) =
                Offset(x * sx, y * sy)

            /*
             * SINGLE CONTINUOUS PATH
             *
             * Start at blue dot:
             *   1) left along outer base
             *   2) around outer A
             *   3) back to center gap
             *   4) smoothly up the RIGHT inner loop
             *   5) over inner apex
             *   6) down LEFT inner loop
             *
             * No second Path, no second animation, no pause.
             */
            val tracePath = Path().apply {

                // ---------------------------------------------------------
                // START — blue dot
                // ---------------------------------------------------------
                moveTo(
                    p(152f, 267f).x,
                    p(152f, 267f).y
                )

                // ---------------------------------------------------------
                // PHASE 1 — OUTER ROUTE
                // Move LEFT along lower-left base.
                // ---------------------------------------------------------
                cubicTo(
                    p(143f, 268f).x, p(143f, 268f).y,
                    p(133f, 268f).x, p(133f, 268f).y,
                    p(123f, 267f).x, p(123f, 267f).y
                )

                cubicTo(
                    p(92f, 273f).x, p(92f, 273f).y,
                    p(57f, 274f).x, p(57f, 274f).y,
                    p(39f, 267f).x, p(39f, 267f).y
                )

                // Rounded bottom-left corner.
                cubicTo(
                    p(34f, 264f).x, p(34f, 264f).y,
                    p(35f, 257f).x, p(35f, 257f).y,
                    p(40f, 248f).x, p(40f, 248f).y
                )

                // Left diagonal upward.
                cubicTo(
                    p(64f, 201f).x, p(64f, 201f).y,
                    p(97f, 135f).x, p(97f, 135f).y,
                    p(130f, 75f).x, p(130f, 75f).y
                )

                cubicTo(
                    p(146f, 46f).x, p(146f, 46f).y,
                    p(157f, 30f).x, p(157f, 30f).y,
                    p(165f, 30f).x, p(165f, 30f).y
                )

                // Rounded outer apex.
                cubicTo(
                    p(174f, 30f).x, p(174f, 30f).y,
                    p(184f, 48f).x, p(184f, 48f).y,
                    p(198f, 74f).x, p(198f, 74f).y
                )

                // Right diagonal downward.
                cubicTo(
                    p(229f, 133f).x, p(229f, 133f).y,
                    p(264f, 204f).x, p(264f, 204f).y,
                    p(299f, 256f).x, p(299f, 256f).y
                )

                // Rounded bottom-right corner.
                cubicTo(
                    p(305f, 265f).x, p(305f, 265f).y,
                    p(302f, 270f).x, p(302f, 270f).y,
                    p(294f, 272f).x, p(294f, 272f).y
                )

                // Move LEFT along right base toward center gap.
                cubicTo(
                    p(264f, 276f).x, p(264f, 276f).y,
                    p(219f, 276f).x, p(219f, 276f).y,
                    p(188f, 275f).x, p(188f, 275f).y
                )

                // ---------------------------------------------------------
                // TRANSITION — outer route into inner loop.
                //
                // The trace stays continuous and rises directly into the
                // right-hand inner stem.
                // ---------------------------------------------------------
                cubicTo(
                    p(188f, 265f).x, p(188f, 265f).y,
                    p(187f, 253f).x, p(187f, 253f).y,
                    p(190f, 243f).x, p(190f, 243f).y
                )

                // ---------------------------------------------------------
                // PHASE 2 — INNER LOOP
                // RIGHT SIDE FIRST, exactly as requested.
                // ---------------------------------------------------------
                cubicTo(
                    p(194f, 231f).x, p(194f, 231f).y,
                    p(206f, 221f).x, p(206f, 221f).y,
                    p(218f, 213f).x, p(218f, 213f).y
                )

                cubicTo(
                    p(230f, 204f).x, p(230f, 204f).y,
                    p(235f, 193f).x, p(235f, 193f).y,
                    p(235f, 180f).x, p(235f, 180f).y
                )

                // Continue up the right side of inner alpha/omega loop.
                cubicTo(
                    p(233f, 160f).x, p(233f, 160f).y,
                    p(218f, 125f).x, p(218f, 125f).y,
                    p(200f, 88f).x, p(200f, 88f).y
                )

                cubicTo(
                    p(187f, 63f).x, p(187f, 63f).y,
                    p(177f, 50f).x, p(177f, 50f).y,
                    p(170f, 50f).x, p(170f, 50f).y
                )

                // Rounded inner apex.
                cubicTo(
                    p(163f, 50f).x, p(163f, 50f).y,
                    p(154f, 64f).x, p(154f, 64f).y,
                    p(143f, 84f).x, p(143f, 84f).y
                )

                // Down LEFT side of inner loop.
                cubicTo(
                    p(126f, 116f).x, p(126f, 116f).y,
                    p(109f, 153f).x, p(109f, 153f).y,
                    p(107f, 177f).x, p(107f, 177f).y
                )

                cubicTo(
                    p(105f, 197f).x, p(105f, 197f).y,
                    p(115f, 209f).x, p(115f, 209f).y,
                    p(129f, 218f).x, p(129f, 218f).y
                )

                // Finish near bottom inner region.
                cubicTo(
                    p(143f, 227f).x, p(143f, 227f).y,
                    p(150f, 238f).x, p(150f, 238f).y,
                    p(152f, 246f).x, p(152f, 246f).y
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
             * ONLY THIS STROKE is visible while the animation runs.
             *
             * It is intentionally independent of the full bitmap:
             * the bitmap remains 100% hidden until progress reaches 100%.
             */
            drawPath(
                path = visiblePath,
                color = Color.Black.copy(
                    alpha = traceAlpha.value
                ),
                style = Stroke(
                    width = 7.2f * sx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            /*
             * Blue dot marks the exact start position.
             *
             * It does NOT travel; your spec describes it as the start
             * point, so the stroke simply grows away from it.
             */
            drawCircle(
                color = Color(0xFF169BFF).copy(
                    alpha = startDotAlpha.value
                ),
                radius = 4.6f * sx,
                center = p(152f, 267f)
            )
        }

        /*
         * PHASE 3 — FULL-ELEMENT REVEAL
         *
         * This complete image is completely invisible during the trim.
         * It only cross-fades in AFTER pathProgress has reached 100%.
         */
        Canvas(
            modifier = Modifier
                .size(250.dp)
                .graphicsLayer {
                    alpha = logoAlpha.value
                }
        ) {
            drawImage(
                image = logoBitmap,
                dstSize = IntSize(
                    width = size.width.roundToInt(),
                    height = size.height.roundToInt()
                )
            )
        }
    }
}
