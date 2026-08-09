package com.comicreader.app.ui.reader.pagecurl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tan

internal data class PageBitmap(
    val token: Int,
    val key: String,
    val bitmap: Bitmap
)

internal enum class CurlSide {
    RIGHT_EDGE,
    LEFT_EDGE
}

/**
 * OpenGL ES renderer for a flexible hinged page flip.
 *
 * Draw order:
 * 1. destination page, flat
 * 2. moving shadow over the destination page
 * 3. outgoing page front faces
 * 4. the naturally visible backside of that same flexible sheet
 *
 * Depth testing is only ever enabled for step 3/4 against each other (the
 * curling sheet occluding its own front/back faces). Steps 1 and 2 are
 * plain painter's-order draws with depth testing off — the curl mesh's
 * depth range is a large fraction of the page width (it's the integral of
 * sin(angle) across the sheet), so a fixed depth offset on a flat quad is
 * either always in front of it or always behind it and can never track the
 * curl progressively. Painter's order avoids that mismatch entirely.
 */
internal class PageCurlRenderer : GLSurfaceView.Renderer {
    private data class TextureSlot(
        var token: Int? = null,
        var key: String? = null,
        var bitmap: Bitmap? = null,
        var textureId: Int = 0,
        var aspectRatio: Float = 1f
    )

    private data class CommitPresentation(
        val token: Int,
        val generation: Long,
        var framesRemaining: Int,
        val requestAnotherFrame: () -> Unit,
        val onPresented: () -> Unit
    )

    private val current = TextureSlot()
    private val swipeLeftTarget = TextureSlot()
    private val swipeRightTarget = TextureSlot()

    private val curlMesh = CurlMesh()

    private var program = 0
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var lightLocation = -1
    private var mvpLocation = -1
    private var textureLocation = -1
    private var backsideLocation = -1
    private var modeLocation = -1
    private var alphaLocation = -1
    private var gradientDirectionLocation = -1

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var viewportAspect = 1f

    private val fieldOfViewDegrees = 34f
    private val cameraZ = 3.35f
    private var visibleWorldHeight = 2f
    private var visibleWorldWidth = 2f

    private var progress = 0f
    private var curlSide = CurlSide.RIGHT_EDGE
    private var activeTurnGeneration = -1L

    /*
     * After a successful turn, keep the promoted texture immutable until:
     * 1. it has been rendered in two complete OpenGL frames, and
     * 2. Compose confirms the same page token.
     *
     * This prevents one stale page-set update from replacing the freshly
     * landed page for a single frame.
     */
    private var committedHoldToken: Int? = null
    private var committedHoldGeneration = -1L
    private var pendingCommitPresentation: CommitPresentation? = null

    private var surfaceReady = false

    private val flatVertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(4 * CurlMesh.STRIDE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val flatIndexBuffer: ShortBuffer = ByteBuffer
        .allocateDirect(6 * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply {
            put(shortArrayOf(0, 1, 2, 2, 1, 3))
            position(0)
        }

    fun setPages(
        currentToken: Int,
        currentPage: PageBitmap?,
        swipeLeftToken: Int?,
        swipeLeftPage: PageBitmap?,
        swipeRightToken: Int?,
        swipeRightPage: PageBitmap?
    ) {
        /*
         * A null bitmap is a temporary decoder/cache state, not an instruction
         * to throw away a texture that is already visible.
         *
         * During the post-commit hold, the renderer's atomically promoted
         * texture is the only allowed current page. Stale Compose updates are
         * ignored until the matching token is confirmed.
         */
        val heldToken =
            committedHoldToken

        val expectedCurrentToken =
            heldToken
                ?: currentToken

        val acceptedCurrentPage =
            if (
                heldToken == null ||
                currentPage?.token == heldToken
            ) {
                currentPage
            } else {
                null
            }

        Log.d(
            TAG,
            "setPages IN currentToken=$currentToken heldToken=$heldToken " +
                    "currentPage.token=${currentPage?.token} " +
                    "swipeLeftToken=$swipeLeftToken swipeLeftPage.token=${swipeLeftPage?.token} " +
                    "swipeRightToken=$swipeRightToken swipeRightPage.token=${swipeRightPage?.token}"
        )

        updateSlot(
            slot = current,
            expectedToken = expectedCurrentToken,
            page = acceptedCurrentPage,
            preserveDifferentTokenWhenMissing = true,
            slotName = "current"
        )
        updateSlot(
            slot = swipeLeftTarget,
            expectedToken = swipeLeftToken,
            page = swipeLeftPage,
            preserveDifferentTokenWhenMissing = false,
            slotName = "swipeLeft"
        )
        updateSlot(
            slot = swipeRightTarget,
            expectedToken = swipeRightToken,
            page = swipeRightPage,
            preserveDifferentTokenWhenMissing = false,
            slotName = "swipeRight"
        )
    }

    fun beginTurn(
        side: CurlSide,
        generation: Long
    ) {
        activeTurnGeneration = generation
        curlSide = side
        progress = 0f
    }

    fun setCurl(
        progress: Float,
        side: CurlSide,
        generation: Long
    ) {
        if (
            generation != activeTurnGeneration ||
            side != curlSide
        ) {
            return
        }

        this.progress = progress.coerceIn(0f, 1f)
    }

    fun resetCurl() {
        progress = 0f
        activeTurnGeneration = -1L
    }

    fun cancelTurn(generation: Long) {
        if (generation != activeTurnGeneration) {
            return
        }

        progress = 0f
        activeTurnGeneration = -1L
    }

    /**
     * Atomically promotes the locked destination texture and clears every
     * piece of curl state before Compose is notified of the page change.
     */
    fun commitTurn(
        side: CurlSide,
        generation: Long,
        requestAnotherFrame: () -> Unit,
        onPresented: () -> Unit
    ): Boolean {
        if (
            generation != activeTurnGeneration ||
            side != curlSide
        ) {
            Log.d(
                TAG,
                "commitTurn REJECT stale generation=$generation " +
                        "activeTurnGeneration=$activeTurnGeneration side=$side curlSide=$curlSide"
            )
            return false
        }

        val target =
            if (
                side ==
                CurlSide.RIGHT_EDGE
            ) {
                swipeLeftTarget
            } else {
                swipeRightTarget
            }

        val targetToken =
            target.token

        if (
            target.textureId == 0 ||
            targetToken == null
        ) {
            Log.d(
                TAG,
                "commitTurn ABORT target not ready textureId=${target.textureId} " +
                        "targetToken=$targetToken side=$side generation=$generation"
            )
            progress = 0f
            activeTurnGeneration = -1L
            return false
        }

        Log.d(
            TAG,
            "commitTurn OK generation=$generation side=$side " +
                    "oldCurrentToken=${current.token} oldCurrentTextureId=${current.textureId} " +
                    "newCurrentToken=$targetToken newCurrentTextureId=${target.textureId}"
        )

        deleteTexture(
            current.textureId
        )

        current.token =
            targetToken
        current.key =
            target.key
        current.bitmap =
            target.bitmap
        current.textureId =
            target.textureId
        current.aspectRatio =
            target.aspectRatio

        target.token = null
        target.key = null
        target.bitmap = null
        target.textureId = 0
        target.aspectRatio = 1f

        /*
         * Reset the curl before any page-state callback. The promoted texture
         * is now the only page the renderer may show.
         */
        progress = 0f
        activeTurnGeneration = -1L

        committedHoldToken =
            targetToken
        committedHoldGeneration =
            generation

        pendingCommitPresentation =
            CommitPresentation(
                token = targetToken,
                generation = generation,
                framesRemaining = 2,
                requestAnotherFrame =
                    requestAnotherFrame,
                onPresented =
                    onPresented
            )

        return true
    }

    fun releaseCommittedHold(
        token: Int?,
        generation: Long
    ) {
        if (
            generation !=
            committedHoldGeneration
        ) {
            Log.d(
                TAG,
                "releaseCommittedHold IGNORED stale generation=$generation " +
                        "committedHoldGeneration=$committedHoldGeneration token=$token"
            )
            return
        }

        if (
            token != null &&
            token != committedHoldToken
        ) {
            Log.d(
                TAG,
                "releaseCommittedHold IGNORED token mismatch token=$token " +
                        "committedHoldToken=$committedHoldToken"
            )
            return
        }

        Log.d(
            TAG,
            "releaseCommittedHold OK token=$token generation=$generation " +
                    "(was committedHoldToken=$committedHoldToken)"
        )

        committedHoldToken = null
        committedHoldGeneration = -1L
        pendingCommitPresentation = null
    }

    /**
     * Called by the TextureView EGL owner before its context is destroyed.
     *
     * Page identities and source bitmaps remain in their slots so the next
     * surface can recreate the same textures. Only context-owned GL handles
     * are cleared.
     */
    fun onSurfaceDestroyed() {
        if (!surfaceReady) {
            return
        }

        deleteTexture(
            current.textureId
        )
        deleteTexture(
            swipeLeftTarget.textureId
        )
        deleteTexture(
            swipeRightTarget.textureId
        )

        current.textureId =
            0
        swipeLeftTarget.textureId =
            0
        swipeRightTarget.textureId =
            0

        if (program != 0) {
            GLES20.glDeleteProgram(
                program
            )
            program =
                0
        }

        surfaceReady =
            false
    }

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?
    ) {
        surfaceReady = true
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        lightLocation = GLES20.glGetAttribLocation(program, "aLight")
        mvpLocation = GLES20.glGetUniformLocation(program, "uMvp")
        textureLocation = GLES20.glGetUniformLocation(program, "uTexture")
        backsideLocation = GLES20.glGetUniformLocation(program, "uBackside")
        modeLocation = GLES20.glGetUniformLocation(program, "uMode")
        alphaLocation = GLES20.glGetUniformLocation(program, "uAlpha")
        gradientDirectionLocation = GLES20.glGetUniformLocation(
            program,
            "uGradientDirection"
        )

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        // Blending is only ever turned on immediately around the shadow
        // draw (see drawPageTurnShadow) and turned back off right after.
        // Everything else drawn by this renderer — the flat pages and both
        // passes of the curl mesh — is fully opaque, so leaving blending
        // enabled for those draws is pure risk (translucency artifacts at
        // grazing/near-edge-on angles) with zero benefit.
        GLES20.glBlendFunc(
            GLES20.GL_SRC_ALPHA,
            GLES20.GL_ONE_MINUS_SRC_ALPHA
        )
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glFrontFace(GLES20.GL_CCW)

        recreateTexture(current)
        recreateTexture(swipeLeftTarget)
        recreateTexture(swipeRightTarget)
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int
    ) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        viewportAspect = viewportWidth.toFloat() / viewportHeight

        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)

        Matrix.perspectiveM(
            projectionMatrix,
            0,
            fieldOfViewDegrees,
            viewportAspect,
            0.1f,
            12f
        )

        Matrix.setLookAtM(
            viewMatrix,
            0,
            0f,
            0f,
            cameraZ,
            0f,
            0f,
            0f,
            0f,
            1f,
            0f
        )

        Matrix.multiplyMM(
            viewProjectionMatrix,
            0,
            projectionMatrix,
            0,
            viewMatrix,
            0
        )

        visibleWorldHeight = 2f *
                tan(Math.toRadians((fieldOfViewDegrees / 2f).toDouble())).toFloat() *
                cameraZ
        visibleWorldWidth = visibleWorldHeight * viewportAspect
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(
            GLES20.GL_COLOR_BUFFER_BIT or
                    GLES20.GL_DEPTH_BUFFER_BIT
        )

        if (program == 0 || current.textureId == 0) {
            return
        }

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(
            mvpLocation,
            1,
            false,
            viewProjectionMatrix,
            0
        )
        GLES20.glUniform1i(textureLocation, 0)

        val target = when (curlSide) {
            CurlSide.RIGHT_EDGE -> swipeLeftTarget
            CurlSide.LEFT_EDGE -> swipeRightTarget
        }

        /*
         * Flat layers (destination page, shadow) are plain painter's-order
         * draws. They must never depth-test against each other or against
         * the curl mesh: the curl mesh's depth range is a large fraction of
         * the page width, so any fixed depth offset given to a flat quad is
         * either always in front of it or always behind it, never tracking
         * the curl progressively. Depth testing is switched on again only
         * for the curl mesh's own front/back self-occlusion pass below.
         */
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        if (
            progress <= 0.001f ||
            target.textureId == 0
        ) {
            drawFlatPage(
                slot = current,
                depth = 0f,
                alpha = 1f
            )

            advanceCommitPresentation()
            return
        }

        /*
         * ValueAnimator produces one last render request before
         * onAnimationEnd commits the destination texture. Because CurlMesh
         * intentionally stops below 90 degrees, drawing that last mesh frame
         * leaves a narrow lit backside/shadow seam for one or two frames.
         *
         * The destination page is already the complete flat background at
         * this point, so finish the final 1.5% as that destination-only frame.
         * commitTurn still performs the atomic texture promotion and the
         * existing two-frame presentation barrier immediately afterward.
         */
        if (
            progress >=
            FINAL_FLAT_HANDOFF_PROGRESS
        ) {
            drawFlatPage(
                slot = target,
                depth = 0f,
                alpha = 1f
            )
            return
        }

        drawFlatPage(
            slot = target,
            depth = 0f,
            alpha = 1f
        )

        val currentSize =
            fittedPageSize(
                current.aspectRatio
            )

        curlMesh.update(
            pageWidth =
                currentSize.first,
            pageHeight =
                currentSize.second,
            progress =
                progress,
            curlFromRight =
                curlSide ==
                        CurlSide.RIGHT_EDGE
        )

        drawPageTurnShadow(
            currentWidth =
                currentSize.first,
            targetAspect =
                target.aspectRatio,
            progress =
                progress,
            side =
                curlSide,
            freeEdgeX =
                curlMesh.freeEdgeX,
            flexAmount =
                curlMesh.flexAmount
        )

        bindTexture(current.textureId)
        bindMesh(curlMesh.vertexBuffer)

        GLES20.glUniform1i(modeLocation, MODE_TEXTURE)
        GLES20.glUniform1f(alphaLocation, 1f)

        /*
         * The curl mesh's own front/back faces DO need depth testing against
         * each other -- this is what makes the folded sheet occlude itself
         * correctly instead of showing accordion artifacts. Nothing else has
         * written to the depth buffer this frame, so this comparison is
         * clean and isolated to the curling sheet alone.
         */
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // Front of the outgoing page.
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glUniform1i(backsideLocation, 0)
        curlMesh.indexBuffer.position(0)
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            curlMesh.indexCount,
            GLES20.GL_UNSIGNED_SHORT,
            curlMesh.indexBuffer
        )

        // Back of the same sheet. Culling provides correct surface separation
        // and depth testing prevents the repeated/accordion artifacts.
        GLES20.glCullFace(GLES20.GL_FRONT)
        GLES20.glUniform1i(backsideLocation, 1)
        curlMesh.indexBuffer.position(0)
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            curlMesh.indexCount,
            GLES20.GL_UNSIGNED_SHORT,
            curlMesh.indexBuffer
        )

        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun advanceCommitPresentation() {
        val presentation =
            pendingCommitPresentation
                ?: return

        if (
            progress > 0.001f ||
            current.token !=
            presentation.token ||
            committedHoldGeneration !=
            presentation.generation
        ) {
            return
        }

        presentation.framesRemaining -= 1

        Log.d(
            TAG,
            "advanceCommitPresentation token=${presentation.token} " +
                    "generation=${presentation.generation} " +
                    "framesRemaining=${presentation.framesRemaining} " +
                    "current.textureId=${current.textureId}"
        )

        if (
            presentation.framesRemaining > 0
        ) {
            presentation.requestAnotherFrame()
            return
        }

        pendingCommitPresentation = null
        Log.d(
            TAG,
            "advanceCommitPresentation FIRING onPresented token=${presentation.token} " +
                    "generation=${presentation.generation}"
        )
        presentation.onPresented()
    }

    private fun drawFlatPage(
        slot: TextureSlot,
        depth: Float,
        alpha: Float
    ) {
        if (slot.textureId == 0) return

        val (width, height) = fittedPageSize(slot.aspectRatio)
        fillFlatVertices(
            centerX = 0f,
            width = width,
            height = height,
            depth = depth,
            reverseGradient = false
        )

        bindTexture(slot.textureId)
        bindMesh(flatVertexBuffer)

        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUniform1i(modeLocation, MODE_TEXTURE)
        GLES20.glUniform1i(backsideLocation, 0)
        GLES20.glUniform1f(alphaLocation, alpha)
        flatIndexBuffer.position(0)
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            6,
            GLES20.GL_UNSIGNED_SHORT,
            flatIndexBuffer
        )
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    private fun drawPageTurnShadow(
        currentWidth: Float,
        targetAspect: Float,
        progress: Float,
        side: CurlSide,
        freeEdgeX: Float,
        flexAmount: Float
    ) {
        val (_, targetHeight) =
            fittedPageSize(
                targetAspect
            )

        /*
         * The shadow follows the projected free edge of the flexible sheet.
         * It becomes broadest when the page has the most curvature.
         */
        val shadowWidth =
            currentWidth *
                    (
                            0.030f +
                                    0.082f *
                                    flexAmount
                            )

        val shadowCenter =
            if (
                side ==
                CurlSide.RIGHT_EDGE
            ) {
                freeEdgeX -
                        shadowWidth /
                        2f
            } else {
                freeEdgeX +
                        shadowWidth /
                        2f
            }

        fillFlatVertices(
            centerX =
                shadowCenter,
            width =
                shadowWidth,
            height =
                targetHeight,
            depth =
                0f,
            reverseGradient =
                side ==
                        CurlSide.LEFT_EDGE
        )

        bindMesh(
            flatVertexBuffer
        )

        GLES20.glDisable(
            GLES20.GL_CULL_FACE
        )

        // Only the shadow quad needs blending — it's the sole translucent
        // draw in this renderer.
        GLES20.glEnable(
            GLES20.GL_BLEND
        )

        GLES20.glUniform1i(
            modeLocation,
            MODE_SHADOW
        )

        GLES20.glUniform1i(
            backsideLocation,
            0
        )

        GLES20.glUniform1f(
            alphaLocation,
            (
                    0.10f +
                            0.30f *
                            flexAmount
                    ) *
                    (
                            1f -
                                    0.18f *
                                    progress
                            )
        )

        GLES20.glUniform1f(
            gradientDirectionLocation,
            if (
                side ==
                CurlSide.RIGHT_EDGE
            ) {
                1f
            } else {
                -1f
            }
        )

        flatIndexBuffer.position(0)

        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            6,
            GLES20.GL_UNSIGNED_SHORT,
            flatIndexBuffer
        )

        GLES20.glDisable(
            GLES20.GL_BLEND
        )

        GLES20.glEnable(
            GLES20.GL_CULL_FACE
        )
    }

    private fun fittedPageSize(aspectRatio: Float): Pair<Float, Float> {
        val safeAspect = aspectRatio.coerceAtLeast(0.08f)
        return if (viewportAspect > safeAspect) {
            val height = visibleWorldHeight
            height * safeAspect to height
        } else {
            val width = visibleWorldWidth
            width to width / safeAspect
        }
    }

    private fun fillFlatVertices(
        centerX: Float,
        width: Float,
        height: Float,
        depth: Float,
        reverseGradient: Boolean
    ) {
        val left = centerX - width / 2f
        val right = centerX + width / 2f
        val top = height / 2f
        val bottom = -height / 2f

        val leftU = if (reverseGradient) 1f else 0f
        val rightU = if (reverseGradient) 0f else 1f

        flatVertexBuffer.position(0)
        putVertex(flatVertexBuffer, left, top, depth, leftU, 0f, 1f)
        putVertex(flatVertexBuffer, left, bottom, depth, leftU, 1f, 1f)
        putVertex(flatVertexBuffer, right, top, depth, rightU, 0f, 1f)
        putVertex(flatVertexBuffer, right, bottom, depth, rightU, 1f, 1f)
        flatVertexBuffer.position(0)
    }

    private fun bindMesh(buffer: FloatBuffer) {
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(
            positionLocation,
            3,
            GLES20.GL_FLOAT,
            false,
            CurlMesh.STRIDE_BYTES,
            buffer
        )

        buffer.position(3)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(
            texCoordLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            CurlMesh.STRIDE_BYTES,
            buffer
        )

        buffer.position(5)
        GLES20.glEnableVertexAttribArray(lightLocation)
        GLES20.glVertexAttribPointer(
            lightLocation,
            1,
            GLES20.GL_FLOAT,
            false,
            CurlMesh.STRIDE_BYTES,
            buffer
        )

        buffer.position(0)
    }

    private fun bindTexture(textureId: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
    }

    private fun updateSlot(
        slot: TextureSlot,
        expectedToken: Int?,
        page: PageBitmap?,
        preserveDifferentTokenWhenMissing: Boolean,
        slotName: String
    ) {
        val validPage =
            page?.takeIf { candidate ->
                candidate.token == expectedToken
            }

        if (validPage == null) {
            /*
             * If the same page is temporarily missing, keep its uploaded
             * texture. This is the common handoff case after a completed turn.
             */
            if (expectedToken != null && slot.token == expectedToken) {
                Log.d(
                    TAG,
                    "updateSlot[$slotName] KEEP (missing but matches) " +
                            "slot.token=${slot.token} slot.textureId=${slot.textureId} " +
                            "expectedToken=$expectedToken"
                )
                return
            }

            /*
             * For the current page, keep the last valid texture while an
             * explicit jump or cache regeneration is still loading. This avoids
             * replacing a readable page with black/loading chrome.
             */
            if (preserveDifferentTokenWhenMissing) {
                Log.d(
                    TAG,
                    "updateSlot[$slotName] PRESERVE-DIFFERENT (stale texture kept) " +
                            "slot.token=${slot.token} slot.textureId=${slot.textureId} " +
                            "expectedToken=$expectedToken"
                )
                return
            }

            Log.d(
                TAG,
                "updateSlot[$slotName] CLEAR " +
                        "slot.token=${slot.token} slot.textureId=${slot.textureId} " +
                        "expectedToken=$expectedToken"
            )
            clearSlot(slot)
            return
        }

        if (
            slot.token == validPage.token &&
            slot.key == validPage.key &&
            slot.textureId != 0
        ) {
            Log.d(
                TAG,
                "updateSlot[$slotName] NOOP (already current) " +
                        "token=${slot.token} textureId=${slot.textureId}"
            )
            return
        }

        Log.d(
            TAG,
            "updateSlot[$slotName] RECREATE " +
                    "oldToken=${slot.token} oldTextureId=${slot.textureId} " +
                    "newToken=${validPage.token} newKey=${validPage.key} " +
                    "bitmapRecycled=${validPage.bitmap.isRecycled}"
        )

        deleteTexture(slot.textureId)
        slot.textureId = 0
        slot.token = validPage.token
        slot.key = validPage.key
        slot.bitmap = validPage.bitmap
        slot.aspectRatio =
            validPage.bitmap.width.toFloat() /
                    validPage.bitmap.height.toFloat().coerceAtLeast(1f)

        if (surfaceReady) {
            recreateTexture(slot)
        }

        Log.d(
            TAG,
            "updateSlot[$slotName] RECREATE-DONE " +
                    "token=${slot.token} newTextureId=${slot.textureId}"
        )
    }

    private fun clearSlot(slot: TextureSlot) {
        deleteTexture(slot.textureId)
        slot.token = null
        slot.key = null
        slot.bitmap = null
        slot.textureId = 0
        slot.aspectRatio = 1f
    }

    private fun recreateTexture(slot: TextureSlot) {
        val bitmap = slot.bitmap ?: return
        slot.textureId = uploadTexture(bitmap)
        slot.aspectRatio = bitmap.width.toFloat() /
                bitmap.height.toFloat().coerceAtLeast(1f)
    }

    private fun uploadTexture(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLUtils.texImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            bitmap,
            0
        )
        return id
    }

    private fun deleteTexture(textureId: Int) {
        if (!surfaceReady || textureId == 0) return
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    private fun putVertex(
        buffer: FloatBuffer,
        x: Float,
        y: Float,
        z: Float,
        u: Float,
        v: Float,
        light: Float
    ) {
        buffer.put(x)
        buffer.put(y)
        buffer.put(z)
        buffer.put(u)
        buffer.put(v)
        buffer.put(light)
    }

    private fun createProgram(
        vertexSource: String,
        fragmentSource: String
    ): Int {
        val vertexShader = compileShader(
            GLES20.GL_VERTEX_SHADER,
            vertexSource
        )
        val fragmentShader = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            fragmentSource
        )

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linked = IntArray(1)
        GLES20.glGetProgramiv(
            program,
            GLES20.GL_LINK_STATUS,
            linked,
            0
        )

        if (linked[0] == 0) {
            val error = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("Page-curl program link failed: $error")
        }

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun compileShader(
        type: Int,
        source: String
    ): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(
            shader,
            GLES20.GL_COMPILE_STATUS,
            compiled,
            0
        )

        if (compiled[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Page-curl shader compile failed: $error")
        }

        return shader
    }

    companion object {
        private const val TAG = "PageCurl"
        private const val MODE_TEXTURE = 0
        private const val MODE_SHADOW = 1

        /*
         * GLSurfaceView and ValueAnimator are not frame-locked. With the old
         * 0.985 cutoff, the final progress update could be queued together
         * with commitTurn before OpenGL ever presented that threshold frame.
         * The last frame actually shown was therefore often around 0.94-0.97,
         * leaving one thin light-gray/white page sliver.
         *
         * At this point the destination page is already fully exposed and the
         * outgoing sheet is almost edge-on, so drawing only the destination
         * removes the one-frame seam without changing the visible body of the
         * curl or the atomic commit transaction.
         */
        private const val FINAL_FLAT_HANDOFF_PROGRESS =
            0.94f

        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            attribute float aLight;
            varying vec2 vTexCoord;
            varying float vLight;

            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
                vLight = aLight;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;

            uniform sampler2D uTexture;
            uniform int uBackside;
            uniform int uMode;
            uniform float uAlpha;
            uniform float uGradientDirection;
            varying vec2 vTexCoord;
            varying float vLight;

            void main() {
                if (uMode == 1) {
                    float axis = uGradientDirection > 0.0
                        ? vTexCoord.x
                        : 1.0 - vTexCoord.x;
                    float shadow = smoothstep(0.0, 1.0, axis);
                    gl_FragColor = vec4(0.0, 0.0, 0.0, shadow * uAlpha);
                    return;
                }

                vec4 color;

                if (uBackside == 1) {
                    /*
                     * Back of printed paper: warm stock with minimal,
                     * desaturated mirrored print-through. The hinged mesh only
                     * reveals this naturally when the sheet passes edge-on.
                     */
                    vec2 mirroredUv = vec2(
                        1.0 - vTexCoord.x,
                        vTexCoord.y
                    );
                    vec3 ink = texture2D(
                        uTexture,
                        mirroredUv
                    ).rgb;
                    float gray = dot(
                        ink,
                        vec3(0.299, 0.587, 0.114)
                    );
                    vec3 desaturatedInk = mix(
                        vec3(gray),
                        ink,
                        0.16
                    );
                    vec3 paper = vec3(
                        0.965,
                        0.953,
                        0.918
                    );
                    vec3 printedPaper = mix(
                        paper,
                        desaturatedInk,
                        0.060
                    );
                    float paperLight = clamp(
                        vLight,
                        0.54,
                        1.04
                    );
                    float softShade =
                        0.76 +
                        0.24 * paperLight;
                    color = vec4(
                        printedPaper * softShade,
                        1.0
                    );
                } else {
                    color = texture2D(uTexture, vTexCoord);
                    color.rgb *= vLight;
                }

                color.a *= uAlpha;
                gl_FragColor = color;
            }
        """
    }
}