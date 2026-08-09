package com.comicreader.app.ui.reader.pagecurl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.comicreader.app.ui.reader.PageNavigationRequest
import com.comicreader.app.ui.reader.ReaderPage
import com.comicreader.app.ui.reader.ReadingDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

/** Compose adapter around the TextureView-backed OpenGL page-curl surface. */
private const val PAGE_CURL_INPUT_TAG =
    "PageCurlInput"

/*
 * Avoid building the full page-identity diagnostic string during unrelated
 * Compose animations.
 */
private const val PAGE_CURL_INPUT_DIAGNOSTICS =
    false

private const val MAX_DECODED_PAGE_CACHE =
    5

private fun diagnosticPageName(
    path: String?
): String =
    path?.substringAfterLast('/')
        ?: "null"

@Composable
fun OpenGlPageCurlReader(
    pages: List<ReaderPage>,
    startPage: Int,
    readingDirection: ReadingDirection,
    navigationRequest: PageNavigationRequest?,
    onNavigationConsumed: (Long) -> Unit,
    onPageChanged: (Int) -> Unit,
    onPageNeeded: (Int) -> Unit,
    onToggleControls: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val density = LocalDensity.current
        val requestedWidth = with(density) {
            maxWidth.toPx().roundToInt()
        }.coerceAtLeast(1)
        val requestedHeight = with(density) {
            maxHeight.toPx().roundToInt()
        }.coerceAtLeast(1)

        var displayedPage by rememberSaveable(pages.size) {
            mutableIntStateOf(
                startPage.coerceIn(0, pages.lastIndex)
            )
        }
        var lastHandledTurnGeneration by remember {
            mutableStateOf(0L)
        }

        val decodedBitmapCache =
            remember {
                mutableStateMapOf<String, Bitmap>()
            }

        var hasPresentedPage by remember {
            mutableStateOf(false)
        }

        val swipeLeftDelta = if (
            readingDirection == ReadingDirection.LEFT_TO_RIGHT
        ) {
            1
        } else {
            -1
        }
        val swipeRightDelta = -swipeLeftDelta

        val swipeLeftIndex = (displayedPage + swipeLeftDelta)
            .takeIf { it in pages.indices }
        val swipeRightIndex = (displayedPage + swipeRightDelta)
            .takeIf { it in pages.indices }

        val currentPage = pages.getOrNull(displayedPage)
        val swipeLeftPage = swipeLeftIndex?.let { index -> pages.getOrNull(index) }
        val swipeRightPage = swipeRightIndex?.let { index -> pages.getOrNull(index) }

        LaunchedEffect(displayedPage, swipeLeftIndex, swipeRightIndex) {
            onPageNeeded(displayedPage)
            swipeLeftIndex?.let(onPageNeeded)
            swipeRightIndex?.let(onPageNeeded)
        }

        LaunchedEffect(navigationRequest?.id) {
            val request = navigationRequest ?: return@LaunchedEffect
            displayedPage = request.page.coerceIn(0, pages.lastIndex)
            onPageChanged(displayedPage)
            onNavigationConsumed(request.id)
        }

        val currentDecodedPage by rememberDecodedPageBitmap(
            path = currentPage?.localPath,
            pageIndex = displayedPage,
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight,
            onPageNeeded = onPageNeeded
        )
        val swipeLeftDecodedPage by rememberDecodedPageBitmap(
            path = swipeLeftPage?.localPath,
            pageIndex = swipeLeftIndex,
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight,
            onPageNeeded = onPageNeeded
        )
        val swipeRightDecodedPage by rememberDecodedPageBitmap(
            path = swipeRightPage?.localPath,
            pageIndex = swipeRightIndex,
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight,
            onPageNeeded = onPageNeeded
        )

        LaunchedEffect(
            currentDecodedPage,
            swipeLeftDecodedPage,
            swipeRightDecodedPage
        ) {
            listOfNotNull(
                currentDecodedPage,
                swipeLeftDecodedPage,
                swipeRightDecodedPage
            ).forEach { decoded ->
                if (!decoded.bitmap.isRecycled) {
                    decodedBitmapCache[decoded.path] =
                        decoded.bitmap
                }
            }

            /*
             * Keep only the active page neighborhood plus a few recently
             * decoded entries. The destination page used for the turn remains
             * available when it immediately becomes the new current page.
             */
            val activePaths =
                listOfNotNull(
                    currentPage?.localPath,
                    swipeLeftPage?.localPath,
                    swipeRightPage?.localPath
                ).toSet()

            /*
             * Three pages are actively needed: current, left and right.
             * Keeping five allows two recent fallbacks without retaining a
             * large set of full-page ARGB bitmaps on lower-memory phones.
             */
            if (
                decodedBitmapCache.size >
                MAX_DECODED_PAGE_CACHE
            ) {
                decodedBitmapCache.keys
                    .filter { key ->
                        key !in activePaths
                    }
                    .take(
                        decodedBitmapCache.size -
                                MAX_DECODED_PAGE_CACHE
                    )
                    .forEach { key ->
                        decodedBitmapCache
                            .remove(key)
                    }
            }
        }

        /*
         * A decoded bitmap can briefly outlive the ReaderPage that requested it
         * while Compose is switching pages. Only expose a bitmap when its saved
         * path still matches the page currently occupying that slot.
         */
        val currentPageBitmap =
            currentDecodedPage
                ?.takeIf { decoded ->
                    decoded.pageIndex ==
                            displayedPage &&
                            currentPage?.ref?.index ==
                            displayedPage &&
                            decoded.path ==
                            currentPage?.localPath
                }
                ?: currentPage
                    ?.takeIf { page ->
                        page.ref.index ==
                                displayedPage
                    }
                    ?.localPath
                    ?.let { path ->
                        decodedBitmapCache[path]
                            ?.takeIf { bitmap ->
                                !bitmap.isRecycled
                            }
                            ?.let { bitmap ->
                                DecodedPageBitmap(
                                    pageIndex =
                                        displayedPage,
                                    path = path,
                                    bitmap = bitmap
                                )
                            }
                    }

        val swipeLeftPageBitmap =
            swipeLeftDecodedPage
                ?.takeIf { decoded ->
                    decoded.pageIndex ==
                            swipeLeftIndex &&
                            swipeLeftPage?.ref?.index ==
                            swipeLeftIndex &&
                            decoded.path ==
                            swipeLeftPage?.localPath
                }
                ?: swipeLeftPage
                    ?.takeIf { page ->
                        page.ref.index ==
                                swipeLeftIndex
                    }
                    ?.localPath
                    ?.let { path ->
                        decodedBitmapCache[path]
                            ?.takeIf { bitmap ->
                                !bitmap.isRecycled
                            }
                            ?.let { bitmap ->
                                DecodedPageBitmap(
                                    pageIndex =
                                        requireNotNull(
                                            swipeLeftIndex
                                        ),
                                    path = path,
                                    bitmap = bitmap
                                )
                            }
                    }

        val swipeRightPageBitmap =
            swipeRightDecodedPage
                ?.takeIf { decoded ->
                    decoded.pageIndex ==
                            swipeRightIndex &&
                            swipeRightPage?.ref?.index ==
                            swipeRightIndex &&
                            decoded.path ==
                            swipeRightPage?.localPath
                }
                ?: swipeRightPage
                    ?.takeIf { page ->
                        page.ref.index ==
                                swipeRightIndex
                    }
                    ?.localPath
                    ?.let { path ->
                        decodedBitmapCache[path]
                            ?.takeIf { bitmap ->
                                !bitmap.isRecycled
                            }
                            ?.let { bitmap ->
                                DecodedPageBitmap(
                                    pageIndex =
                                        requireNotNull(
                                            swipeRightIndex
                                        ),
                                    path = path,
                                    bitmap = bitmap
                                )
                            }
                    }

        LaunchedEffect(currentPageBitmap?.path) {
            if (currentPageBitmap != null) {
                hasPresentedPage = true
            }
        }

        AndroidView(
            factory = { context ->
                PageCurlSurfaceView(context)
            },
            update = { view ->
                view.setCallbacks(
                    onTurnCommitted = { targetToken, generation ->
                        if (
                            generation > lastHandledTurnGeneration &&
                            targetToken in pages.indices
                        ) {
                            lastHandledTurnGeneration = generation
                            displayedPage = targetToken
                            onPageChanged(targetToken)
                        }
                    },
                    onMiddleTap = onToggleControls
                )

                /*
                 * Build the exact PageBitmap values first, then log that exact
                 * tuple immediately before PageCurlSurfaceView receives it.
                 *
                 * All identities must agree:
                 * - displayed/list index
                 * - ReaderPage.ref.index
                 * - decoded page index
                 * - localPath
                 */
                val outgoingCurrent =
                    currentPageBitmap
                        ?.takeIf { decoded ->
                            decoded.pageIndex ==
                                    displayedPage &&
                                    currentPage?.ref?.index ==
                                    displayedPage &&
                                    decoded.path ==
                                    currentPage?.localPath
                        }
                        ?.let { decoded ->
                            PageBitmap(
                                token =
                                    displayedPage,
                                key =
                                    decoded.path,
                                bitmap =
                                    decoded.bitmap
                            )
                        }

                val outgoingSwipeLeft =
                    swipeLeftPageBitmap
                        ?.takeIf { decoded ->
                            decoded.pageIndex ==
                                    swipeLeftIndex &&
                                    swipeLeftPage?.ref?.index ==
                                    swipeLeftIndex &&
                                    decoded.path ==
                                    swipeLeftPage?.localPath
                        }
                        ?.let { decoded ->
                            PageBitmap(
                                token =
                                    requireNotNull(
                                        swipeLeftIndex
                                    ),
                                key =
                                    decoded.path,
                                bitmap =
                                    decoded.bitmap
                            )
                        }

                val outgoingSwipeRight =
                    swipeRightPageBitmap
                        ?.takeIf { decoded ->
                            decoded.pageIndex ==
                                    swipeRightIndex &&
                                    swipeRightPage?.ref?.index ==
                                    swipeRightIndex &&
                                    decoded.path ==
                                    swipeRightPage?.localPath
                        }
                        ?.let { decoded ->
                            PageBitmap(
                                token =
                                    requireNotNull(
                                        swipeRightIndex
                                    ),
                                key =
                                    decoded.path,
                                bitmap =
                                    decoded.bitmap
                            )
                        }

                if (PAGE_CURL_INPUT_DIAGNOSTICS) {
                    Log.d(
                        PAGE_CURL_INPUT_TAG,
                        buildString {
                            append(
                                "compose current-construction "
                            )
                            append(
                                "displayedPage=$displayedPage "
                            )
                            append(
                                "currentRef=${currentPage?.ref?.index} "
                            )
                            append(
                                "currentPath=${diagnosticPageName(currentPage?.localPath)} "
                            )
                            append(
                                "decodedIndex=${currentDecodedPage?.pageIndex} "
                            )
                            append(
                                "decodedPath=${diagnosticPageName(currentDecodedPage?.path)} "
                            )
                            append(
                                "validatedIndex=${currentPageBitmap?.pageIndex} "
                            )
                            append(
                                "validatedPath=${diagnosticPageName(currentPageBitmap?.path)} "
                            )
                            append(
                                "outToken=${outgoingCurrent?.token} "
                            )
                            append(
                                "outKey=${diagnosticPageName(outgoingCurrent?.key)} "
                            )
                            append(
                                "leftIndex=$swipeLeftIndex "
                            )
                            append(
                                "leftRef=${swipeLeftPage?.ref?.index} "
                            )
                            append(
                                "leftPath=${diagnosticPageName(swipeLeftPage?.localPath)} "
                            )
                            append(
                                "leftDecodedIndex=${swipeLeftDecodedPage?.pageIndex} "
                            )
                            append(
                                "leftDecodedPath=${diagnosticPageName(swipeLeftDecodedPage?.path)} "
                            )
                            append(
                                "rightIndex=$swipeRightIndex "
                            )
                            append(
                                "rightRef=${swipeRightPage?.ref?.index} "
                            )
                            append(
                                "rightPath=${diagnosticPageName(swipeRightPage?.localPath)} "
                            )
                            append(
                                "rightDecodedIndex=${swipeRightDecodedPage?.pageIndex} "
                            )
                            append(
                                "rightDecodedPath=${diagnosticPageName(swipeRightDecodedPage?.path)} "
                            )
                            append(
                                "pagesSize=${pages.size} "
                            )
                            append(
                                "pagesIdentity=${System.identityHashCode(pages)}"
                            )
                        }
                    )
                }
                view.setPages(
                    currentToken =
                        displayedPage,
                    current =
                        outgoingCurrent,
                    swipeLeftToken =
                        swipeLeftIndex,
                    swipeLeft =
                        outgoingSwipeLeft,
                    swipeRightToken =
                        swipeRightIndex,
                    swipeRight =
                        outgoingSwipeRight
                )
            },
            modifier = Modifier.fillMaxSize()
        )

        if (
            currentPageBitmap == null &&
            !hasPresentedPage
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

private data class DecodedPageBitmap(
    val pageIndex: Int,
    val path: String,
    val bitmap: Bitmap
)

@Composable
private fun rememberDecodedPageBitmap(
    path: String?,
    pageIndex: Int?,
    requestedWidth: Int,
    requestedHeight: Int,
    onPageNeeded: (Int) -> Unit
): State<DecodedPageBitmap?> =
    produceState<DecodedPageBitmap?>(
        initialValue = null,
        key1 = path,
        key2 = requestedWidth,
        key3 = requestedHeight to pageIndex
    ) {
        /*
         * Reader pages live in a cache directory. A page can be removed and
         * recreated while Compose still holds the old path for one frame.
         * Decode from streams, catch that race, ask the ViewModel to reload
         * the page, and keep retrying the same path while the cache file is
         * rebuilt.
         */
        value = null

        if (
            path == null ||
            pageIndex == null
        ) {
            return@produceState
        }

        var reloadRequested = false

        repeat(14) { attempt ->
            val decodedBitmap =
                withContext(Dispatchers.IO) {
                    decodeSampledBitmapSafely(
                        path = path,
                        requestedWidth =
                            requestedWidth,
                        requestedHeight =
                            requestedHeight
                    )
                }

            if (decodedBitmap != null) {
                value = DecodedPageBitmap(
                    pageIndex = pageIndex,
                    path = path,
                    bitmap = decodedBitmap
                )
                return@produceState
            }

            if (!reloadRequested) {
                reloadRequested = true
                onPageNeeded(pageIndex)
            }

            delay(
                if (attempt < 4) {
                    70L
                } else {
                    150L
                }
            )
        }
    }

private fun decodeSampledBitmapSafely(
    path: String,
    requestedWidth: Int,
    requestedHeight: Int
): Bitmap? {
    val file = File(path)

    if (!file.isFile) {
        return null
    }

    return try {
        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

        /*
         * Streams avoid the noisy ENOENT logging produced by decodeFile when
         * a cache file disappears during the call.
         */
        FileInputStream(file).use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                bounds
            )
        }

        if (
            bounds.outWidth <= 0 ||
            bounds.outHeight <= 0
        ) {
            return null
        }

        var sampleSize = 1

        while (
            bounds.outWidth /
            (sampleSize * 2) >=
            requestedWidth &&
            bounds.outHeight /
            (sampleSize * 2) >=
            requestedHeight
        ) {
            sampleSize *= 2
        }

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig =
                    Bitmap.Config.ARGB_8888
            }

        /*
         * Reopen because the bounds pass consumed the first stream. If the
         * cache file vanishes here, the exception becomes a retryable miss.
         */
        val sampled =
            FileInputStream(file).use { stream ->
                BitmapFactory.decodeStream(
                    stream,
                    null,
                    options
                )
            }
                ?: return null

        /*
         * inSampleSize works in powers of two and can still return a bitmap
         * substantially larger than the actual on-screen page. Uploading that
         * excess size costs memory bandwidth, texture memory and GL time.
         *
         * Scale once on the IO dispatcher to the exact bounding box used by
         * the reader. The aspect ratio is preserved.
         */
        val scale =
            min(
                requestedWidth.toFloat() /
                        sampled.width
                            .coerceAtLeast(1),
                requestedHeight.toFloat() /
                        sampled.height
                            .coerceAtLeast(1)
            )
                .coerceAtMost(1f)

        if (scale >= 0.97f) {
            sampled
        } else {
            val scaledWidth =
                (
                        sampled.width *
                                scale
                        )
                    .roundToInt()
                    .coerceAtLeast(1)
            val scaledHeight =
                (
                        sampled.height *
                                scale
                        )
                    .roundToInt()
                    .coerceAtLeast(1)

            Bitmap.createScaledBitmap(
                sampled,
                scaledWidth,
                scaledHeight,
                true
            ).also {
                if (
                    it !== sampled &&
                    !sampled.isRecycled
                ) {
                    sampled.recycle()
                }
            }
        }
    } catch (_: FileNotFoundException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        /*
         * Also handles a partially replaced/truncated image while Skia is
         * decoding. The ViewModel regenerates it and the caller retries.
         */
        null
    }
}