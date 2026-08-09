package com.comicreader.app.ui.reader.pagecurl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Flexible two-sided page mesh.
 *
 * This is intentionally not a cylindrical curl. The outgoing page remains one
 * continuous sheet hinged to the book edge. The complete sheet rotates while
 * the free edge receives a little additional bend.
 *
 * The centerline is integrated column by column, which preserves the length of
 * the paper and avoids the broad reversed "backside tail" produced by the old
 * cylinder model.
 */
internal class CurlMesh(
    /*
     * 60 x 16 remains visually smooth on a phone-sized page while reducing
     * per-frame vertex generation and index processing by roughly one third
     * compared with the previous 72 x 20 grid.
     */
    private val columns: Int = 60,
    private val rows: Int = 16
) {
    companion object {
        const val FLOATS_PER_VERTEX = 6
        const val STRIDE_BYTES =
            FLOATS_PER_VERTEX * 4

        /*
         * Kept safely below 90 degrees (edge-on). The per-column tangent
         * angle is this base rotation PLUS up to ~17 degrees of extra bend
         * concentrated near the free edge (see maximumExtraBend below), and
         * that extra bend peaks partway through the gesture rather than at
         * the very end. 84 degrees keeps the worst-case combined tangent
         * angle, across the whole progress range, under ~87 degrees.
         *
         * This matters because the centerline is integrated using
         * cos(tangentAngle) for the X step (see the loop below). Once a
         * column's tangent angle passes 90 degrees, cos() goes negative and
         * that column's accumulated X starts moving backwards instead of
         * monotonically toward the free edge — the sheet folds back over
         * itself for a moment instead of approaching edge-on. Combined with
         * blending, that produced a brief see-through flicker right as some
         * pages finished turning. Staying under 90 degrees everywhere avoids
         * the fold-back entirely.
         */
        private const val MAX_BASE_ROTATION_DEGREES = 84f
    }

    val vertexBuffer: FloatBuffer =
        ByteBuffer
            .allocateDirect(
                (columns + 1) *
                        (rows + 1) *
                        STRIDE_BYTES
            )
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    val indexBuffer: ShortBuffer
    val indexCount: Int

    /**
     * World-space position of the moving free edge after the latest update.
     * The renderer uses this to position the shadow on the destination page.
     */
    var freeEdgeX: Float = 0f
        private set

    /**
     * Peaks around the middle of a turn and returns to zero at both ends.
     */
    var flexAmount: Float = 0f
        private set

    init {
        val indices =
            ShortArray(
                columns *
                        rows *
                        6
            )

        var index = 0

        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val topLeft =
                    (
                            row *
                                    (columns + 1) +
                                    column
                            ).toShort()

                val bottomLeft =
                    (
                            (row + 1) *
                                    (columns + 1) +
                                    column
                            ).toShort()

                val topRight =
                    (
                            row *
                                    (columns + 1) +
                                    column +
                                    1
                            ).toShort()

                val bottomRight =
                    (
                            (row + 1) *
                                    (columns + 1) +
                                    column +
                                    1
                            ).toShort()

                /*
                 * Counter-clockwise while the untouched front of the page
                 * faces the camera.
                 */
                indices[index++] = topLeft
                indices[index++] = bottomLeft
                indices[index++] = topRight

                indices[index++] = topRight
                indices[index++] = bottomLeft
                indices[index++] = bottomRight
            }
        }

        indexBuffer =
            ByteBuffer
                .allocateDirect(
                    indices.size * 2
                )
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
                .apply {
                    put(indices)
                    position(0)
                }

        indexCount = indices.size
    }

    /**
     * @param pageWidth world-space width of the outgoing page
     * @param pageHeight world-space height of the outgoing page
     * @param progress 0 = flat, 1 = page has passed slightly beyond edge-on
     * @param curlFromRight true for a leftward swipe / next-page turn
     */
    fun update(
        pageWidth: Float,
        pageHeight: Float,
        progress: Float,
        curlFromRight: Boolean
    ) {
        val clampedProgress =
            progress.coerceIn(
                0f,
                1f
            )

        val pi =
            PI.toFloat()

        /*
         * The target effect never wraps the paper around a full cylinder.
         * It reaches just beyond edge-on, then the destination page commits.
         */
        val globalAngle =
            Math.toRadians(
                (
                        MAX_BASE_ROTATION_DEGREES *
                                smoothTurnProgress(
                                    clampedProgress
                                )
                        ).toDouble()
            ).toFloat()

        flexAmount =
            sin(
                pi *
                        clampedProgress
            ).coerceIn(
                0f,
                1f
            )

        /*
         * Extra rotation is concentrated toward the free edge. It is strongest
         * around the middle of the gesture and disappears at the beginning and
         * end so completed pages are perfectly flat.
         */
        val maximumExtraBend =
            Math.toRadians(
                (
                        17f *
                                flexAmount
                        ).toDouble()
            ).toFloat()

        val centerX =
            FloatArray(
                columns + 1
            )

        val centerDepth =
            FloatArray(
                columns + 1
            )

        val centerAngle =
            FloatArray(
                columns + 1
            )

        val segmentLength =
            pageWidth /
                    columns.toFloat()

        var accumulatedX = 0f
        var accumulatedDepth = 0f

        centerX[0] = 0f
        centerDepth[0] = 0f
        centerAngle[0] = globalAngle

        /*
         * Integrating the tangent angle preserves paper length and creates one
         * smooth sheet. A direct per-vertex rotation would stretch/compress the
         * artwork and produce visible vertical slabs.
         */
        for (column in 1..columns) {
            val midpoint =
                (
                        column -
                                0.5f
                        ) /
                        columns.toFloat()

            val edgeInfluence =
                smootherStep(
                    midpoint
                )

            val tangentAngle =
                globalAngle +
                        maximumExtraBend *
                        edgeInfluence

            accumulatedX +=
                segmentLength *
                        cos(
                            tangentAngle
                        )

            accumulatedDepth +=
                segmentLength *
                        sin(
                            tangentAngle
                        )

            centerX[column] =
                accumulatedX

            centerDepth[column] =
                accumulatedDepth

            centerAngle[column] =
                tangentAngle
        }

        val canonicalFreeEdgeX =
            accumulatedX

        freeEdgeX =
            if (curlFromRight) {
                -pageWidth / 2f +
                        canonicalFreeEdgeX
            } else {
                pageWidth / 2f -
                        canonicalFreeEdgeX
            }

        vertexBuffer.position(0)

        for (row in 0..rows) {
            val v =
                row.toFloat() /
                        rows.toFloat()

            val sourceY =
                pageHeight *
                        (
                                0.5f -
                                        v
                                )

            /*
             * Paper bow is strongest near the horizontal center and during the
             * middle of the turn. The hinge and free edge remain less compressed.
             */
            for (column in 0..columns) {
                val u =
                    column.toFloat() /
                            columns.toFloat()

                val canonicalColumn =
                    if (curlFromRight) {
                        column
                    } else {
                        columns -
                                column
                    }

                val canonicalU =
                    canonicalColumn.toFloat() /
                            columns.toFloat()

                val localX =
                    centerX[
                        canonicalColumn
                    ]

                val localDepth =
                    centerDepth[
                        canonicalColumn
                    ]

                val localAngle =
                    centerAngle[
                        canonicalColumn
                    ]

                val mappedX =
                    if (curlFromRight) {
                        -pageWidth / 2f +
                                localX
                    } else {
                        pageWidth / 2f -
                                localX
                    }

                val horizontalBow =
                    sin(
                        pi *
                                canonicalU
                    )

                val verticalScale =
                    1f -
                            0.052f *
                            flexAmount *
                            horizontalBow

                /*
                 * A tiny asymmetric lift makes the free edge read as flexible
                 * paper rather than a rigid door without changing page bounds.
                 */
                val freeEdgeLift =
                    0.012f *
                            pageHeight *
                            flexAmount *
                            canonicalU *
                            (
                                    1f -
                                            abs(
                                                2f * v -
                                                        1f
                                            )
                                    )

                val mappedY =
                    sourceY *
                            verticalScale +
                            freeEdgeLift

                val facing =
                    abs(
                        cos(
                            localAngle
                        )
                    )

                val edgeHighlight =
                    1f -
                            facing

                val light =
                    (
                            0.60f +
                                    0.37f *
                                    facing +
                                    0.09f *
                                    edgeHighlight
                            ).coerceIn(
                            0.50f,
                            1.04f
                        )

                vertexBuffer.put(
                    mappedX
                )
                vertexBuffer.put(
                    mappedY
                )
                vertexBuffer.put(
                    localDepth
                )
                vertexBuffer.put(
                    u
                )
                vertexBuffer.put(
                    v
                )
                vertexBuffer.put(
                    light
                )
            }
        }

        vertexBuffer.position(0)
        indexBuffer.position(0)
    }

    /**
     * Slightly accelerates the end of the drag while remaining directly tied
     * to the finger. This lets the sheet become nearly edge-on before commit
     * without making the beginning feel too abrupt.
     */
    private fun smoothTurnProgress(
        progress: Float
    ): Float {
        val p =
            progress.coerceIn(
                0f,
                1f
            )

        return (
                p *
                        p *
                        (
                                3f -
                                        2f *
                                        p
                                )
                ).coerceIn(
                0f,
                1f
            )
    }

    private fun smootherStep(
        value: Float
    ): Float {
        val x =
            value.coerceIn(
                0f,
                1f
            )

        return x *
                x *
                x *
                (
                        x *
                                (
                                        x *
                                                6f -
                                                15f
                                        ) +
                                10f
                        )
    }
}