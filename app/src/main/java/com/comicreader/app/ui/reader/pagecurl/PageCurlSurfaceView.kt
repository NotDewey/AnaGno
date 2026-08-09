package com.comicreader.app.ui.reader.pagecurl

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.TextureView
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import com.comicreader.app.ui.haptics.AppHaptics
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Gesture and transaction owner for the OpenGL page curl.
 *
 * A turn is deliberately locked from the first accepted drag until Compose
 * confirms the committed destination page. This prevents decoded-page updates,
 * recompositions, or stale callbacks from replacing textures halfway through a
 * curl or starting a second animation on the page that was just reached.
 */
internal class PageCurlSurfaceView(
    context: Context
) : TextureView(context),
    TextureView.SurfaceTextureListener {
    private enum class TurnPhase {
        IDLE,
        DRAGGING,
        SETTLING,
        AWAITING_CONFIRMATION
    }

    private data class PageSet(
        val currentToken: Int,
        val current: PageBitmap?,
        val swipeLeftToken: Int?,
        val swipeLeft: PageBitmap?,
        val swipeRightToken: Int?,
        val swipeRight: PageBitmap?
    )

    private val pageRenderer =
        PageCurlRenderer()
    private val renderDriver =
        PageCurlTextureRenderDriver(
            pageRenderer
        )
    private val touchSlop =
        ViewConfiguration
            .get(context)
            .scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var dragProgress = 0f
    private var touchArmed = false
    private var velocityTracker: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    private var phase = TurnPhase.IDLE
    private var activeSide: CurlSide? = null
    private var generationCounter = System.nanoTime()
    private var activeGeneration = -1L
    private var expectedCommittedToken: Int? = null

    private var appliedPages: PageSet? = null
    private var pendingPages: PageSet? = null

    private var onTurnCommitted: ((targetToken: Int, generation: Long) -> Unit)? = null
    private var onMiddleTap: (() -> Unit)? = null

    init {
        /*
         * TextureView participates in the normal View/Compose composition
         * hierarchy. Unlike GLSurfaceView, it does not create a separate
         * surface window beneath the ModalBottomSheet.
         */
        surfaceTextureListener =
            this
        isOpaque =
            true
        isFocusable =
            true
        isClickable =
            true
    }

    private fun queueEvent(
        event: () -> Unit
    ) {
        renderDriver.queueEvent(
            event
        )
    }

    private fun requestRender() {
        renderDriver.requestRender()
    }

    override fun onSurfaceTextureAvailable(
        surface: SurfaceTexture,
        width: Int,
        height: Int
    ) {
        renderDriver.attachSurface(
            surfaceTexture = surface,
            width = width,
            height = height
        )
    }

    override fun onSurfaceTextureSizeChanged(
        surface: SurfaceTexture,
        width: Int,
        height: Int
    ) {
        renderDriver.resize(
            width = width,
            height = height
        )
    }

    override fun onSurfaceTextureDestroyed(
        surface: SurfaceTexture
    ): Boolean {
        renderDriver.detachSurfaceBlocking()
        return true
    }

    override fun onSurfaceTextureUpdated(
        surface: SurfaceTexture
    ) = Unit

    private fun setPhase(newPhase: TurnPhase, reason: String) {
        Log.d(
            TAG,
            "phase ${phase} -> $newPhase ($reason) " +
                    "activeGeneration=$activeGeneration expectedCommittedToken=$expectedCommittedToken"
        )
        phase = newPhase
    }

    fun setCallbacks(
        onTurnCommitted: (targetToken: Int, generation: Long) -> Unit,
        onMiddleTap: () -> Unit
    ) {
        this.onTurnCommitted = onTurnCommitted
        this.onMiddleTap = onMiddleTap
    }

    fun setPages(
        currentToken: Int,
        current: PageBitmap?,
        swipeLeftToken: Int?,
        swipeLeft: PageBitmap?,
        swipeRightToken: Int?,
        swipeRight: PageBitmap?
    ) {
        val incoming = PageSet(
            currentToken = currentToken,
            current = current,
            swipeLeftToken = swipeLeftToken,
            swipeLeft = swipeLeft,
            swipeRightToken = swipeRightToken,
            swipeRight = swipeRight
        )

        if (HIGH_FREQUENCY_DIAGNOSTICS) {
            Log.d(
                TAG,
                "view.setPages IN phase=$phase currentToken=$currentToken " +
                        "current.token=${current?.token} current.key=${current?.key} " +
                        "swipeLeftToken=$swipeLeftToken swipeLeft.token=${swipeLeft?.token} " +
                        "swipeRightToken=$swipeRightToken swipeRight.token=${swipeRight?.token} " +
                        "expectedCommittedToken=$expectedCommittedToken"
            )
        }

        when (phase) {
            TurnPhase.IDLE -> {
                applyPageSet(
                    retainMatchingBitmaps(
                        incoming
                    )
                )
            }

            TurnPhase.AWAITING_CONFIRMATION -> {
                if (currentToken == expectedCommittedToken) {
                    finishCommitConfirmation(
                        retainMatchingBitmaps(
                            incoming
                        )
                    )
                } else {
                    // Ignore the stale pre-commit page set, but remember the
                    // newest value in case Compose is still finishing its state
                    // update.
                    Log.d(
                        TAG,
                        "view.setPages DEFERRED (awaiting confirmation of " +
                                "$expectedCommittedToken, got $currentToken) -> pendingPages"
                    )
                    pendingPages = incoming
                }
            }

            TurnPhase.DRAGGING,
            TurnPhase.SETTLING -> {
                val activeToken = appliedPages?.currentToken

                if (activeToken != null && currentToken != activeToken) {
                    // A progress scrubber/bookmark jump is an explicit external
                    // navigation. It is allowed to cancel the locked turn.
                    Log.d(
                        TAG,
                        "view.setPages EXTERNAL NAV during $phase " +
                                "activeToken=$activeToken incomingToken=$currentToken"
                    )
                    cancelTurnForExternalNavigation(incoming)
                } else {
                    // Bitmap decoding may finish while the finger is down. Keep
                    // those updates pending; the active current and destination
                    // textures stay frozen for the whole transaction.
                    pendingPages = incoming
                }
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (phase != TurnPhase.IDLE) {
                    touchArmed = false
                    return true
                }

                animator?.cancel()
                downX = event.x
                downY = event.y
                dragProgress = 0f
                touchArmed = true
                activeSide = null
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also {
                    it.addMovement(event)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touchArmed || phase == TurnPhase.AWAITING_CONFIRMATION) {
                    return true
                }

                velocityTracker?.addMovement(event)
                val dx = event.x - downX
                val dy = event.y - downY

                if (phase == TurnPhase.IDLE) {
                    if (
                        abs(dx) <= touchSlop ||
                        abs(dx) <= abs(dy) * 1.12f
                    ) {
                        return true
                    }

                    val requestedSide = if (dx < 0f) {
                        CurlSide.RIGHT_EDGE
                    } else {
                        CurlSide.LEFT_EDGE
                    }

                    val currentBitmap =
                        appliedPages?.current
                    val targetToken =
                        targetTokenFor(requestedSide)
                    val targetBitmap =
                        targetBitmapFor(requestedSide)

                    if (
                        currentBitmap == null ||
                        targetToken == null ||
                        targetBitmap == null
                    ) {
                        // Destination texture is not ready. Do not begin a curl
                        // that would reveal a black/loading page.
                        Log.d(
                            TAG,
                            "beginLockedTurn SKIPPED - target not ready " +
                                    "side=$requestedSide targetToken=$targetToken " +
                                    "currentBitmap=${currentBitmap != null} " +
                                    "targetBitmap=${targetBitmap != null}"
                        )
                        return true
                    }

                    beginLockedTurn(
                        side = requestedSide,
                        targetToken = targetToken
                    )
                }

                if (phase != TurnPhase.DRAGGING) {
                    return true
                }

                val side = activeSide ?: return true
                val signedDistance = when (side) {
                    CurlSide.RIGHT_EDGE -> -dx
                    CurlSide.LEFT_EDGE -> dx
                }

                // Reversing the finger toward the starting point reverses the
                // same curl. Crossing the start does not select another page;
                // a new ACTION_DOWN is required for a new turn.
                dragProgress = (
                        signedDistance / width.coerceAtLeast(1)
                        ).coerceIn(0f, 1f)

                updateRendererCurl(
                    progress = dragProgress,
                    side = side,
                    generation = activeGeneration
                )
                return true
            }

            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val velocityX = velocityTracker?.xVelocity ?: 0f

                if (phase == TurnPhase.DRAGGING) {
                    activeSide?.let { side ->
                        val velocityCompletes = when (side) {
                            CurlSide.RIGHT_EDGE -> velocityX < -900f
                            CurlSide.LEFT_EDGE -> velocityX > 900f
                        }

                        val complete =
                            dragProgress >= 0.24f || velocityCompletes

                        animateCurl(
                            side = side,
                            targetProgress = if (complete) 1f else 0f,
                            commit = complete,
                            generation = activeGeneration
                        )
                    }
                } else if (phase == TurnPhase.IDLE && touchArmed) {
                    val moved = abs(event.x - downX) + abs(event.y - downY)
                    if (moved <= touchSlop * 1.5f) {
                        performClick()
                        val fraction = event.x / width.coerceAtLeast(1)
                        if (fraction in 0.35f..0.65f) {
                            onMiddleTap?.invoke()
                        }
                    }
                }

                finishTouchTracking()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (phase == TurnPhase.DRAGGING) {
                    activeSide?.let { side ->
                        animateCurl(
                            side = side,
                            targetProgress = 0f,
                            commit = false,
                            generation = activeGeneration
                        )
                    }
                }
                finishTouchTracking()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        velocityTracker?.recycle()
        velocityTracker =
            null
        renderDriver.shutdown()
        super.onDetachedFromWindow()
    }

    private fun beginLockedTurn(
        side: CurlSide,
        targetToken: Int
    ) {
        generationCounter += 1L
        activeGeneration = generationCounter
        val generation = activeGeneration
        activeSide = side
        expectedCommittedToken = targetToken
        setPhase(TurnPhase.DRAGGING, "beginLockedTurn side=$side targetToken=$targetToken generation=$generation")

        queueEvent {
            pageRenderer.beginTurn(
                side = side,
                generation = generation
            )
        }
        requestRender()
    }

    private fun updateRendererCurl(
        progress: Float,
        side: CurlSide,
        generation: Long
    ) {
        queueEvent {
            pageRenderer.setCurl(
                progress = progress,
                side = side,
                generation = generation
            )
        }
        requestRender()
    }

    private fun animateCurl(
        side: CurlSide,
        targetProgress: Float,
        commit: Boolean,
        generation: Long
    ) {
        animator?.cancel()
        setPhase(TurnPhase.SETTLING, "animateCurl commit=$commit targetProgress=$targetProgress generation=$generation")

        animator = ValueAnimator.ofFloat(
            dragProgress,
            targetProgress
        ).apply {
            duration = if (commit) 250L else 180L
            interpolator = DecelerateInterpolator()

            addUpdateListener { animation ->
                dragProgress = animation.animatedValue as Float
                updateRendererCurl(
                    progress = dragProgress,
                    side = side,
                    generation = generation
                )
            }

            addListener(object : AnimatorListenerAdapter() {
                private var wasCancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    wasCancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (wasCancelled || generation != activeGeneration) {
                        Log.d(
                            TAG,
                            "animateCurl onAnimationEnd IGNORED wasCancelled=$wasCancelled " +
                                    "generation=$generation activeGeneration=$activeGeneration"
                        )
                        return
                    }

                    if (commit) {
                        val targetToken =
                            expectedCommittedToken

                        Log.d(
                            TAG,
                            "animateCurl onAnimationEnd COMMITTING targetToken=$targetToken generation=$generation"
                        )

                        queueEvent {
                            val committed =
                                pageRenderer.commitTurn(
                                    side = side,
                                    generation =
                                        generation,
                                    requestAnotherFrame = {
                                        requestRender()
                                    },
                                    onPresented = {
                                        post {
                                            if (
                                                generation ==
                                                activeGeneration &&
                                                targetToken !=
                                                null
                                            ) {
                                                /*
                                                 * OpenGL has now drawn the
                                                 * promoted page in two complete
                                                 * frames. Only now may Compose
                                                 * change its current-page state.
                                                 */
                                                Log.d(
                                                    TAG,
                                                    "onPresented FIRING targetToken=$targetToken generation=$generation " +
                                                            "appliedPages.currentToken(before)=${appliedPages?.currentToken}"
                                                )

                                                promoteAppliedPageAfterCommit(
                                                    targetToken =
                                                        targetToken
                                                )

                                                setPhase(
                                                    TurnPhase.AWAITING_CONFIRMATION,
                                                    "onPresented targetToken=$targetToken"
                                                )

                                                dragProgress =
                                                    0f
                                                activeSide =
                                                    null

                                                Log.d(
                                                    TAG,
                                                    "onTurnCommitted -> Compose targetToken=$targetToken generation=$generation"
                                                )

                                                /*
                                                 * Fire only after OpenGL has
                                                 * presented the committed page.
                                                 * Cancelled curls never vibrate.
                                                 */
                                                AppHaptics.pageTurn(
                                                    this@PageCurlSurfaceView
                                                )

                                                onTurnCommitted
                                                    ?.invoke(
                                                        targetToken,
                                                        generation
                                                    )

                                                scheduleConfirmationFallback(
                                                    targetToken =
                                                        targetToken,
                                                    generation =
                                                        generation
                                                )
                                            } else {
                                                Log.d(
                                                    TAG,
                                                    "onPresented SKIPPED generation mismatch or null token " +
                                                            "generation=$generation activeGeneration=$activeGeneration " +
                                                            "targetToken=$targetToken"
                                                )
                                            }
                                        }
                                    }
                                )

                            if (committed) {
                                requestRender()
                            } else {
                                Log.d(
                                    TAG,
                                    "commitTurn returned FALSE, falling back to cancel " +
                                            "generation=$generation targetToken=$targetToken"
                                )
                                post {
                                    finishCancelledTurn(
                                        generation
                                    )
                                }
                            }
                        }
                    } else {
                        queueEvent {
                            pageRenderer.cancelTurn(generation)
                        }
                        requestRender()
                        finishCancelledTurn(generation)
                    }
                }
            })

            start()
        }
    }

    private fun promoteAppliedPageAfterCommit(
        targetToken: Int
    ) {
        val previous = appliedPages ?: return
        val promotedCurrent = when (activeSide) {
            CurlSide.RIGHT_EDGE -> previous.swipeLeft
            CurlSide.LEFT_EDGE -> previous.swipeRight
            null -> null
        }

        Log.d(
            TAG,
            "promoteAppliedPageAfterCommit targetToken=$targetToken " +
                    "promotedCurrent.token=${promotedCurrent?.token} " +
                    "promotedCurrent.key=${promotedCurrent?.key} " +
                    "(from activeSide=$activeSide, previous.swipeLeft.token=${previous.swipeLeft?.token}, " +
                    "previous.swipeRight.token=${previous.swipeRight?.token})"
        )

        appliedPages = PageSet(
            currentToken = targetToken,
            current = promotedCurrent,
            swipeLeftToken = null,
            swipeLeft = null,
            swipeRightToken = null,
            swipeRight = null
        )
    }

    private fun scheduleConfirmationFallback(
        targetToken: Int,
        generation: Long
    ) {
        postDelayed({
            if (
                phase == TurnPhase.AWAITING_CONFIRMATION &&
                activeGeneration == generation &&
                expectedCommittedToken == targetToken
            ) {
                val pending = pendingPages
                Log.d(
                    TAG,
                    "scheduleConfirmationFallback FIRING targetToken=$targetToken " +
                            "pending.currentToken=${pending?.currentToken}"
                )
                if (pending?.currentToken == targetToken) {
                    finishCommitConfirmation(
                        retainMatchingBitmaps(
                            pending
                        )
                    )
                } else {
                    /*
                     * Keep the renderer's atomically promoted target visible
                     * and unlock input even if Compose confirmation is delayed.
                     */
                    Log.d(
                        TAG,
                        "scheduleConfirmationFallback FORCE-RELEASE (Compose never confirmed) " +
                                "targetToken=$targetToken generation=$generation"
                    )
                    queueEvent {
                        pageRenderer.releaseCommittedHold(
                            token =
                                targetToken,
                            generation =
                                generation
                        )
                    }

                    setPhase(TurnPhase.IDLE, "scheduleConfirmationFallback force-release")
                    activeGeneration = -1L
                    expectedCommittedToken = null
                    pendingPages = null
                }
            }
        }, 1_200L)
    }

    private fun finishCommitConfirmation(
        confirmedPages: PageSet
    ) {
        val retainedPages =
            retainMatchingBitmaps(
                confirmedPages
            )

        val confirmedToken =
            expectedCommittedToken
        val confirmedGeneration =
            activeGeneration

        Log.d(
            TAG,
            "finishCommitConfirmation confirmedToken=$confirmedToken " +
                    "generation=$confirmedGeneration " +
                    "retained.current.token=${retainedPages.current?.token} " +
                    "retained.swipeLeft.token=${retainedPages.swipeLeft?.token} " +
                    "retained.swipeRight.token=${retainedPages.swipeRight?.token}"
        )

        queueEvent {
            pageRenderer.releaseCommittedHold(
                token = confirmedToken,
                generation =
                    confirmedGeneration
            )
        }

        setPhase(TurnPhase.IDLE, "finishCommitConfirmation confirmedToken=$confirmedToken")
        activeGeneration = -1L
        expectedCommittedToken = null
        pendingPages = null

        applyPageSet(
            retainedPages
        )
    }

    private fun finishCancelledTurn(generation: Long) {
        if (generation != activeGeneration) {
            return
        }

        setPhase(TurnPhase.IDLE, "finishCancelledTurn generation=$generation")
        activeGeneration = -1L
        activeSide = null
        expectedCommittedToken = null
        dragProgress = 0f

        val pending = pendingPages
        pendingPages = null
        if (pending != null) {
            applyPageSet(
                retainMatchingBitmaps(
                    pending
                )
            )
        }
    }

    private fun cancelTurnForExternalNavigation(incoming: PageSet) {
        val generation = activeGeneration
        animator?.cancel()
        generationCounter += 1L
        activeGeneration = generationCounter

        queueEvent {
            pageRenderer.cancelTurn(generation)
        }

        setPhase(TurnPhase.IDLE, "cancelTurnForExternalNavigation generation=$generation")
        activeGeneration = -1L
        activeSide = null
        expectedCommittedToken = null
        dragProgress = 0f
        pendingPages = null
        applyPageSet(incoming)
    }

    private fun retainMatchingBitmaps(
        incoming: PageSet
    ): PageSet {
        val previous =
            appliedPages
                ?: return incoming

        return incoming.copy(
            current =
                incoming.current
                    ?: previous.current
                        ?.takeIf {
                            previous.currentToken ==
                                    incoming.currentToken
                        },
            swipeLeft =
                incoming.swipeLeft
                    ?: previous.swipeLeft
                        ?.takeIf {
                            previous.swipeLeftToken ==
                                    incoming.swipeLeftToken
                        },
            swipeRight =
                incoming.swipeRight
                    ?: previous.swipeRight
                        ?.takeIf {
                            previous.swipeRightToken ==
                                    incoming.swipeRightToken
                        }
        )
    }

    private fun applyPageSet(pageSet: PageSet) {
        /*
         * AndroidView.update can run repeatedly while unrelated Compose UI
         * animates above this TextureView. Ignore an identical page set so
         * the GL thread remains idle during reader-chrome animations.
         */
        if (pageSet == appliedPages) {
            return
        }

        val tokenChanged =
            appliedPages?.currentToken !=
                    pageSet.currentToken

        appliedPages = pageSet

        if (HIGH_FREQUENCY_DIAGNOSTICS) {
            Log.d(
                TAG,
                "applyPageSet currentToken=${pageSet.currentToken} tokenChanged=$tokenChanged " +
                        "current.token=${pageSet.current?.token} " +
                        "swipeLeft.token=${pageSet.swipeLeft?.token} " +
                        "swipeRight.token=${pageSet.swipeRight?.token}"
            )
        }

        queueEvent {
            pageRenderer.setPages(
                currentToken = pageSet.currentToken,
                currentPage = pageSet.current,
                swipeLeftToken = pageSet.swipeLeftToken,
                swipeLeftPage = pageSet.swipeLeft,
                swipeRightToken = pageSet.swipeRightToken,
                swipeRightPage = pageSet.swipeRight
            )

            if (tokenChanged) {
                pageRenderer.resetCurl()
            }
        }

        requestRender()
    }

    private fun targetTokenFor(side: CurlSide): Int? = when (side) {
        CurlSide.RIGHT_EDGE -> appliedPages?.swipeLeftToken
        CurlSide.LEFT_EDGE -> appliedPages?.swipeRightToken
    }

    private fun targetBitmapFor(side: CurlSide): PageBitmap? = when (side) {
        CurlSide.RIGHT_EDGE -> appliedPages?.swipeLeft
        CurlSide.LEFT_EDGE -> appliedPages?.swipeRight
    }

    private fun finishTouchTracking() {
        touchArmed = false
        velocityTracker?.recycle()
        velocityTracker = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    companion object {
        private const val TAG =
            "PageCurl"

        /*
         * These logs can execute on every AndroidView update. Transaction and
         * phase logs remain enabled elsewhere.
         */
        private const val HIGH_FREQUENCY_DIAGNOSTICS =
            false
    }
}

/**
 * Small render-when-dirty EGL driver for [TextureView].
 *
 * All [PageCurlRenderer] calls remain serialized on one dedicated render
 * thread, preserving the same transaction ordering previously provided by
 * GLSurfaceView.queueEvent/requestRender.
 */
private class PageCurlTextureRenderDriver(
    private val renderer: PageCurlRenderer
) {
    private val thread =
        HandlerThread(
            "PageCurlTextureRenderer"
        ).apply {
            start()
        }

    private val handler =
        Handler(
            thread.looper
        )

    @Volatile
    private var shuttingDown =
        false

    private var eglDisplay: EGLDisplay =
        EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext =
        EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface =
        EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? =
        null

    private var viewportWidth =
        1
    private var viewportHeight =
        1
    private var surfaceAttached =
        false
    private var pendingRender =
        false

    fun queueEvent(
        event: () -> Unit
    ) {
        if (shuttingDown) {
            return
        }

        handler.post {
            if (!shuttingDown) {
                event()
            }
        }
    }

    fun requestRender() {
        if (shuttingDown) {
            return
        }

        /*
         * Do not coalesce these posts. queueEvent() followed by requestRender()
         * must preserve FIFO ordering so the newest curl/page state is always
         * applied before its corresponding frame is drawn.
         */
        handler.post {
            if (
                shuttingDown
            ) {
                return@post
            }

            if (surfaceAttached) {
                drawFrame()
            } else {
                pendingRender =
                    true
            }
        }
    }

    fun attachSurface(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int
    ) {
        if (shuttingDown) {
            return
        }

        handler.post {
            if (shuttingDown) {
                return@post
            }

            releaseEgl()

            try {
                viewportWidth =
                    width.coerceAtLeast(1)
                viewportHeight =
                    height.coerceAtLeast(1)

                initializeEgl(
                    surfaceTexture
                )

                renderer.onSurfaceCreated(
                    null,
                    null
                )
                renderer.onSurfaceChanged(
                    null,
                    viewportWidth,
                    viewportHeight
                )

                surfaceAttached =
                    true
                drawFrame()

                if (pendingRender) {
                    pendingRender =
                        false
                    drawFrame()
                }
            } catch (error: Throwable) {
                Log.e(
                    TAG,
                    "Unable to initialize TextureView EGL renderer",
                    error
                )
                releaseEgl()
            }
        }
    }

    fun resize(
        width: Int,
        height: Int
    ) {
        if (shuttingDown) {
            return
        }

        handler.post {
            viewportWidth =
                width.coerceAtLeast(1)
            viewportHeight =
                height.coerceAtLeast(1)

            if (surfaceAttached) {
                renderer.onSurfaceChanged(
                    null,
                    viewportWidth,
                    viewportHeight
                )
                drawFrame()
            }
        }
    }

    fun detachSurfaceBlocking() {
        if (shuttingDown) {
            return
        }

        val latch =
            CountDownLatch(1)

        handler.post {
            releaseEgl()
            latch.countDown()
        }

        try {
            latch.await(
                750L,
                TimeUnit.MILLISECONDS
            )
        } catch (_: InterruptedException) {
            Thread.currentThread()
                .interrupt()
        }
    }

    fun shutdown() {
        if (shuttingDown) {
            return
        }

        shuttingDown =
            true

        val latch =
            CountDownLatch(1)

        handler.post {
            releaseEgl()
            latch.countDown()
        }

        try {
            latch.await(
                750L,
                TimeUnit.MILLISECONDS
            )
        } catch (_: InterruptedException) {
            Thread.currentThread()
                .interrupt()
        }

        thread.quitSafely()
    }

    private fun initializeEgl(
        surfaceTexture: SurfaceTexture
    ) {
        eglDisplay =
            EGL14.eglGetDisplay(
                EGL14.EGL_DEFAULT_DISPLAY
            )

        check(
            eglDisplay !=
                    EGL14.EGL_NO_DISPLAY
        ) {
            "eglGetDisplay failed: ${eglError()}"
        }

        val versions =
            IntArray(2)

        check(
            EGL14.eglInitialize(
                eglDisplay,
                versions,
                0,
                versions,
                1
            )
        ) {
            "eglInitialize failed: ${eglError()}"
        }

        val configAttributes =
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_DEPTH_SIZE,
                16,
                EGL14.EGL_NONE
            )

        val configs =
            arrayOfNulls<EGLConfig>(
                1
            )
        val configCount =
            IntArray(1)

        check(
            EGL14.eglChooseConfig(
                eglDisplay,
                configAttributes,
                0,
                configs,
                0,
                configs.size,
                configCount,
                0
            ) &&
                    configCount[0] > 0
        ) {
            "eglChooseConfig failed: ${eglError()}"
        }

        eglConfig =
            requireNotNull(
                configs[0]
            )

        val contextAttributes =
            intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION,
                2,
                EGL14.EGL_NONE
            )

        eglContext =
            EGL14.eglCreateContext(
                eglDisplay,
                eglConfig,
                EGL14.EGL_NO_CONTEXT,
                contextAttributes,
                0
            )

        check(
            eglContext !=
                    EGL14.EGL_NO_CONTEXT
        ) {
            "eglCreateContext failed: ${eglError()}"
        }

        val surfaceAttributes =
            intArrayOf(
                EGL14.EGL_NONE
            )

        eglSurface =
            EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                surfaceTexture,
                surfaceAttributes,
                0
            )

        check(
            eglSurface !=
                    EGL14.EGL_NO_SURFACE
        ) {
            "eglCreateWindowSurface failed: ${eglError()}"
        }

        check(
            EGL14.eglMakeCurrent(
                eglDisplay,
                eglSurface,
                eglSurface,
                eglContext
            )
        ) {
            "eglMakeCurrent failed: ${eglError()}"
        }

        EGL14.eglSwapInterval(
            eglDisplay,
            1
        )
    }

    private fun drawFrame() {
        if (
            !surfaceAttached ||
            eglDisplay ==
            EGL14.EGL_NO_DISPLAY ||
            eglSurface ==
            EGL14.EGL_NO_SURFACE
        ) {
            pendingRender =
                true
            return
        }

        renderer.onDrawFrame(
            null
        )

        if (
            !EGL14.eglSwapBuffers(
                eglDisplay,
                eglSurface
            )
        ) {
            Log.e(
                TAG,
                "eglSwapBuffers failed: ${eglError()}"
            )
        }
    }

    private fun releaseEgl() {
        if (
            eglDisplay !=
            EGL14.EGL_NO_DISPLAY
        ) {
            if (
                eglContext !=
                EGL14.EGL_NO_CONTEXT &&
                eglSurface !=
                EGL14.EGL_NO_SURFACE
            ) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    eglSurface,
                    eglSurface,
                    eglContext
                )

                renderer.onSurfaceDestroyed()
            }

            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )

            if (
                eglSurface !=
                EGL14.EGL_NO_SURFACE
            ) {
                EGL14.eglDestroySurface(
                    eglDisplay,
                    eglSurface
                )
            }

            if (
                eglContext !=
                EGL14.EGL_NO_CONTEXT
            ) {
                EGL14.eglDestroyContext(
                    eglDisplay,
                    eglContext
                )
            }

            EGL14.eglTerminate(
                eglDisplay
            )
        }

        eglDisplay =
            EGL14.EGL_NO_DISPLAY
        eglContext =
            EGL14.EGL_NO_CONTEXT
        eglSurface =
            EGL14.EGL_NO_SURFACE
        eglConfig =
            null
        surfaceAttached =
            false
    }

    private fun eglError(): String =
        "0x${EGL14.eglGetError().toString(16)}"

    companion object {
        private const val TAG =
            "PageCurlTexture"
    }
}

