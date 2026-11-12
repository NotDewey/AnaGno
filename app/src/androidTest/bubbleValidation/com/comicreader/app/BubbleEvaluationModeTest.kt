package com.comicreader.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.comicreader.app.data.comic.ComicFormat
import com.comicreader.app.data.comic.ComicPageRef
import com.comicreader.app.domain.model.Comic
import com.comicreader.app.evaluation.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Original synthetic geometry/staging fixtures ONLY. No detector, OCR or model execution.
 * Passing these checks establishes neither real pilot quality nor manual device acceptance.
 */
@RunWith(AndroidJUnit4::class)
class BubbleEvaluationModeTest {
    private val width = 4097
    private val height = 257

    private fun contour(vararg coordinates: Int, subtract: Boolean = false) = EvaluationContour(
        coordinates.toList().chunked(2).map { EvaluationPoint(it[0].toFloat(), it[1].toFloat()) }, subtract)

    private fun region(verified: Boolean = false) = EvaluationRegion("r-body-tail-hole", listOf(
        contour(10, 10, 3000, 10, 3000, 150, 10, 150),
        contour(2000, 140, 2400, 140, 2200, 240),
        contour(100, 40, 200, 40, 200, 80, 100, 80, subtract = true)
    ), verified)

    private fun rejected(message: String, block: () -> Unit): Exception {
        var failure: Exception? = null
        try { block() } catch (caught: Exception) { failure = caught }
        assertNotNull("Expected rejection: $message", failure)
        return failure!!
    }

    private fun fixture(block: (Context, File, EvaluationStore) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "evaluation-synthetic-${UUID.randomUUID()}")
        check(directory.mkdir())
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(directory, "no-backup").apply { mkdirs() }
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
        }
        try { block(context, directory, EvaluationStore(context)) }
        finally { check(directory.deleteRecursively()) }
    }

    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun write(directory: File, name: String, value: JSONObject) {
        File(directory, name).writeText(value.toString(), Charsets.UTF_8)
    }

    /** Stages invented records explicitly, never calls capture or claims an actual detector result. */
    private fun stage(context: Context, id: String, negative: Boolean = false,
                      color: Int = if (negative) Color.LTGRAY else Color.WHITE): EvaluationPage {
        val directory = File(context.noBackupFilesDir, "bubble_evaluation/pilot-v1/$id")
        check(directory.mkdir())
        val image = File(directory, "image.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        write(directory, "page.json", JSONObject().put("sample_id", id).put("image", image.name)
            .put("width", width).put("height", height).put("image_sha256", hash(image)))
        write(directory, "source.json", JSONObject().put("kind", "synthetic")
            .put("source_group", "synthetic-original-geometry").put("comic_title", "SYNTHETIC FIXTURE — NO REAL PILOT"))
        write(directory, "build.json", JSONObject().put("production_revision", "synthetic-staging-fixture")
            .put("harness_source_digest", "synthetic-staging-fixture-v1"))
        context.assets.open("evaluation-policy.json").use { input ->
            File(directory, "policy.json").outputStream().use { input.copyTo(it) }
        }
        val modelHash = context.assets.open("models/bubble_segment.onnx").use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        write(directory, "capture.json", JSONObject().put("fixture", true)
            .put("scope", "synthetic exporter regression; no detector execution or human annotation claim")
            .put("fingerprint", "synthetic-fixture-not-device-detection")
            .put("device_model", "synthetic-fixture").put("model_sha256", modelHash))
        val intent = JSONObject().put("attempt_id", "a_$id").put("sample_id", id)
            .put("mode", "warm_inference").put("expected", "valid")
        write(directory, "request.json", JSONObject().put("fixture", true).put("schedule", JSONArray().put(intent)))
        write(directory, "attempt.json", JSONObject().put("attempt_id", "a_$id").put("sample_id", id)
            .put("mode", "warm_inference").put("status", "success").put("duration_ns", 1)
            .put("detections", JSONArray()))
        return EvaluationPage(directory, id, image, width, height, emptyList())
    }

    private fun reviewed(negative: Boolean = false) = EvaluationDraft(
        regions = if (negative) emptyList() else listOf(region(true)),
        reviewer = "synthetic-fixture reviewer (automated negative control)",
        rightsHolder = "original synthetic test artwork author",
        permission = "Original generated geometric test artwork; evaluation permitted; NOT real pilot evidence.",
        reviewed = true
    )

    @Test fun originalResolutionBodyDiagonalTailAndHoleMatchIndependentPixelOracle() {
        val shape = region()
        assertFalse("Rasterization must never invent human verification", shape.verified)
        assertEquals(listOf(10, 10, 3000, 240), EvaluationGeometry.box(shape, width, height))
        val mask = EvaluationGeometry.mask(shape, width, height)
        try {
            assertEquals(width, mask.width)
            assertEquals(height, mask.height)
            val row = IntArray(width)
            var mismatches = 0
            for (y in 0 until height) {
                mask.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val cx = x + .5
                    val cy = y + .5
                    val body = cx >= 10 && cx < 3000 && cy >= 10 && cy < 150
                    val tail = cy >= 140 && cy < 240 && kotlin.math.abs(cx - 2200) < 2 * (240 - cy)
                    val hole = cx >= 100 && cx < 200 && cy >= 40 && cy < 80
                    val expected = if ((body || tail) && !hole) 255 else 0
                    if (Color.alpha(row[x]) != expected) mismatches++
                }
            }
            assertEquals("Full raster including tails, holes and background", 0, mismatches)
            assertEquals(255, Color.alpha(mask.getPixel(2200, 239)))
            assertEquals(0, Color.alpha(mask.getPixel(150, 50)))
        } finally { mask.recycle() }
        val reverse = shape.copy(contours = shape.contours.reversed().map { it.copy(points = it.points.reversed()) })
        val a = EvaluationGeometry.mask(shape, width, height)
        val b = EvaluationGeometry.mask(reverse, width, height)
        try { assertTrue("Contour order/winding must preserve union minus holes", a.sameAs(b)) }
        finally { a.recycle(); b.recycle() }
    }

    @Test fun fitMappingPreservesOriginalCornersAndTailOnOddPages() {
        for ((w, h) in listOf(4097 to 257, 257 to 4097, 6401 to 101, 6402 to 101)) {
            for ((vw, vh) in listOf(800f to 600f, 600f to 800f)) {
                val scale = minOf(vw.toDouble() / w, vh.toDouble() / h)
                val left = (vw - w * scale) / 2
                val top = (vh - h * scale) / 2
                for ((px, py) in listOf(0f to 0f, w.toFloat() to h.toFloat(), w / 2f to h / 2f,
                        (w - 1f) to (h - 1f))) {
                    val mapped = EvaluationGeometry.fitPoint((left + px * scale).toFloat(),
                        (top + py * scale).toFloat(), vw, vh, w, h)
                    assertNotNull("Original edge rejected for $w x $h in $vw x $vh", mapped)
                    assertEquals(px, mapped!!.x, .002f)
                    assertEquals(py, mapped.y, .002f)
                }
                if (left > 1) assertNull(EvaluationGeometry.fitPoint(0f, vh / 2, vw, vh, w, h))
                if (top > 1) assertNull(EvaluationGeometry.fitPoint(vw / 2, 0f, vw, vh, w, h))
            }
        }
        assertNull(EvaluationGeometry.fitPoint(Float.NaN, 0f, 800f, 600f, width, height))
        assertNull(EvaluationGeometry.fitPoint(0f, 0f, 0f, 600f, width, height))
        // Fit image occupies y=[250,750]. These touches are .01 original pixels
        // outside, beyond the .001 pixel floating-point edge tolerance.
        assertNull(EvaluationGeometry.fitPoint(500f, 249.99f, 1000f, 1000f, 1000, 500))
        assertNull(EvaluationGeometry.fitPoint(500f, 750.01f, 1000f, 1000f, 1000, 500))
        assertNull(EvaluationGeometry.fitPoint(-.01f, 500f, 1000f, 1000f, 1000, 500))
        assertNull(EvaluationGeometry.fitPoint(1000.01f, 500f, 1000f, 1000f, 1000, 500))
    }

    @Test fun invalidAndEmptyGeometryCannotBecomeVerifiedMask() {
        val bad = listOf(
            EvaluationRegion("empty", emptyList()),
            EvaluationRegion("bowtie", listOf(contour(10, 10, 30, 30, 10, 30, 30, 10))),
            EvaluationRegion("outside", listOf(contour(-1, 0, 10, 0, 10, 10))),
            EvaluationRegion("hole-only", listOf(contour(10, 10, 30, 10, 30, 30, subtract = true))),
            EvaluationRegion("erased", listOf(contour(10, 10, 30, 10, 30, 30, 10, 30),
                contour(10, 10, 30, 10, 30, 30, 10, 30, subtract = true)))
        )
        for (shape in bad) rejected(shape.id) { EvaluationGeometry.box(shape, width, height) }
    }

    @Test fun actualCanvasMappingAndNativeViewportPreserveSingleSourcePixels() = fixture { context, _, _ ->
        val page = stage(context, "synthetic-native-viewport")
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        fun expected(x: Int, y: Int) = if ((x + y) % 2 == 0) Color.MAGENTA else Color.CYAN
        try {
            val row = IntArray(width)
            for (y in 0 until height) {
                for (x in row.indices) row[x] = expected(x, y)
                source.setPixels(row, 0, width, 0, y, width, 1)
            }
            page.image.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { source.recycle() }
        val metadata = JSONObject(File(page.directory, "page.json").readText()).put("image_sha256", hash(page.image))
        write(page.directory, "page.json", metadata)
        val loaded = EvaluationCanvas.load(page)
        assertTrue("Overview should be sampled for this source", loaded.bitmap.width < page.width)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var canvas: EvaluationCanvas
        var ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            canvas = EvaluationCanvas(context)
            canvas.layout(0, 0, 512, 256)
            canvas.setPage(page, loaded)
            canvas.onInspectionChanged = { if (canvas.nativeInspection != null) ready.countDown() }
            val center = canvas.mapViewPoint(256f, 128f)!!
            assertEquals(width / 2f, center.x, .002f)
            assertEquals(height / 2f, center.y, .002f)
            assertNull("Overview letterbox must reject touches", canvas.mapViewPoint(256f, 0f))
            canvas.inspectNative()
        }
        fun verifyNativeCrop() {
            val crop = canvas.nativeInspection ?: error("Native source crop missing: ${canvas.inspectionState}")
            assertEquals(crop.bounds.width(), crop.bitmap.width)
            assertEquals(crop.bounds.height(), crop.bitmap.height)
            val row = IntArray(crop.bitmap.width)
            var mismatches = 0
            for (y in 0 until crop.bitmap.height) {
                crop.bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
                for (x in row.indices) if (row[x] != expected(x + crop.bounds.left, y + crop.bounds.top)) mismatches++
            }
            assertEquals("Decoded crop must retain original one-pixel detail", 0, mismatches)
        }
        try {
            assertTrue("Native inspect did not return source pixels within 10s", ready.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                verifyNativeCrop()
                ready = CountDownLatch(1)
                canvas.setInspectionViewport(2f, 700f, 128f)
                assertNull("Old decoded viewport must be invalidated immediately", canvas.nativeInspection)
                val center = canvas.mapViewPoint(256f, 128f)!!
                assertEquals(700f, center.x, .002f)
                assertEquals(128f, center.y, .002f)
                val offset = canvas.mapViewPoint(258f, 130f)!!
                assertEquals(701f, offset.x, .002f)
                assertEquals(129f, offset.y, .002f)
            }
            assertTrue("Panned native viewport did not decode within 10s", ready.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                verifyNativeCrop()
                val bounds = canvas.nativeInspection!!.bounds
                assertEquals(android.graphics.Rect(572, 64, 828, 192), bounds)
                canvas.fit()
                assertNull(canvas.nativeInspection)
                val center = canvas.mapViewPoint(256f, 128f)!!
                assertEquals(width / 2f, center.x, .002f)
                assertEquals(height / 2f, center.y, .002f)
            }
        } finally { instrumentation.runOnMainSync { canvas.release() } }
    }

    @Test fun nativeInspectAcceptsUnitScaleRoundingOnLandscapeAndPortraitSources() {
        // These exact source/view sizes previously rounded native scale to 0.99999994f,
        // leaving inspectNative pending forever despite requesting original pixels.
        for ((sourceWidth, sourceHeight) in listOf(6401 to 101, 2480 to 3508)) {
            fixture { context, root, _ ->
                val directory = File(root, "synthetic-native-${sourceWidth}x$sourceHeight").apply { check(mkdir()) }
                val image = File(directory, "image.png")
                val original = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
                try {
                    original.eraseColor(Color.WHITE)
                    image.outputStream().use { check(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { original.recycle() }
                // Dimensions describe the actual original PNG, never a relabelled preview.
                val page = EvaluationPage(directory, "synthetic-native-${sourceWidth}x$sourceHeight",
                    image, sourceWidth, sourceHeight, emptyList())
                val loaded = EvaluationCanvas.load(page)
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val ready = CountDownLatch(1)
                var canvas: EvaluationCanvas? = null
                try {
                    instrumentation.runOnMainSync {
                        val view = EvaluationCanvas(context)
                        canvas = view
                        view.layout(0, 0, 600, 800)
                        view.setPage(page, loaded)
                        view.onInspectionChanged = { if (view.nativeInspection != null) ready.countDown() }
                        view.inspectNative()
                    }
                    assertTrue("Native inspect must decode $sourceWidth x $sourceHeight in 600 x 800 within 10s",
                        ready.await(10, TimeUnit.SECONDS))
                    instrumentation.runOnMainSync {
                        val view = checkNotNull(canvas)
                        val crop = view.nativeInspection ?: error("Original crop missing: ${view.inspectionState}")
                        assertTrue(view.inspectionState.startsWith("Native source pixels"))
                        assertEquals(crop.bounds.width(), crop.bitmap.width)
                        assertEquals(crop.bounds.height(), crop.bitmap.height)
                        assertTrue(crop.bounds.left >= 0 && crop.bounds.top >= 0)
                        assertTrue(crop.bounds.right <= sourceWidth && crop.bounds.bottom <= sourceHeight)
                        assertTrue("Must decode visible source crop, not whole page", crop.bounds.width() in 600..602)
                        assertTrue(crop.bounds.height() in minOf(sourceHeight, 800)..minOf(sourceHeight, 802))
                        assertTrue(crop.bounds.contains(sourceWidth / 2, sourceHeight / 2))
                        val center = view.mapViewPoint(300f, 400f)!!
                        assertEquals(sourceWidth / 2f, center.x, .002f)
                        assertEquals(sourceHeight / 2f, center.y, .002f)
                        assertEquals(Color.WHITE, crop.bitmap.getPixel(0, 0))
                        assertEquals(Color.WHITE, crop.bitmap.getPixel(crop.bitmap.width - 1, crop.bitmap.height - 1))
                    }
                } finally {
                    instrumentation.runOnMainSync {
                        canvas?.release() ?: loaded.bitmap.recycle()
                    }
                }
            }
        }
    }

    @Test fun isolatedDeveloperActivityLaunchesWithoutOpeningAComic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Launch smoke is restricted to isolated validation package", context.packageName.endsWith(".bubblevalidation"))
        val intent = Intent(context, BubbleEvaluationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals("Anagno Evaluation", activity.title.toString())
                assertFalse(activity.isFinishing)
                assertNotNull(activity.findViewById<android.view.View>(android.R.id.content))
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    // Callback controls only: no provider is opened and no URI permission is granted.
    private class ImportProbe {
        val calls = mutableListOf<String>()
        val uri: Uri = Uri.parse("content://synthetic-evaluation-fixture/comic.cbz")
        val comic = Comic(id = 71, title = "SYNTHETIC import callback fixture", uri = uri.toString(),
            coverPagePath = null, pageCount = 3)

        suspend fun run(selected: Uri? = uri, supported: Boolean = true,
                        failAt: String? = null, failure: Exception? = null): Comic? {
            fun call(stage: String, actual: Uri) {
                assertEquals(uri, actual)
                calls += stage
                if (stage == failAt) throw checkNotNull(failure)
            }
            return EvaluationComicImport.importSelected(selected,
                isSupported = { call("supported", it); supported },
                persistRead = { call("persist-read", it) },
                requireReadable = { call("readable", it) },
                importComic = { call("import", it); comic })
        }
    }

    @Test fun cancelledDocumentSelectionDoesNotAccessProviderOrRepository() = runBlocking {
        val probe = ImportProbe()
        assertNull(probe.run(selected = null))
        assertTrue(probe.calls.isEmpty())
    }

    @Test fun fileUriCannotBypassDocumentSelection() {
        val probe = ImportProbe()
        val failure = rejected("file URI") {
            runBlocking { probe.run(selected = Uri.parse("file:///synthetic-fixture.cbz")) }
        }
        assertTrue(failure is IllegalArgumentException)
        assertTrue(probe.calls.isEmpty())
    }

    @Test fun unsupportedDocumentDoesNotPersistGrantOrImport() {
        val probe = ImportProbe()
        val failure = rejected("unsupported document") { runBlocking { probe.run(supported = false) } }
        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf("supported"), probe.calls)
    }

    @Test fun deniedRevokedOrUnavailableDocumentStopsBeforeRepositoryImport() {
        val order = listOf("supported", "persist-read", "readable")
        for (stage in order) {
            for (cause in listOf(SecurityException("synthetic denied/revoked permission"),
                    IOException("synthetic unavailable document"))) {
                val probe = ImportProbe()
                val failure = rejected("$stage ${cause.javaClass.simpleName}") {
                    runBlocking { probe.run(failAt = stage, failure = cause) }
                }
                assertTrue(failure is IllegalStateException)
                assertSame("Retain underlying denial/unavailability evidence", cause, failure.cause)
                assertTrue("Failure must tell user to select the document again",
                    failure.message.orEmpty().contains("select", ignoreCase = true))
                assertEquals(order.take(order.indexOf(stage) + 1), probe.calls)
                assertFalse(probe.calls.contains("import"))
            }
        }
    }

    @Test fun importCancellationPropagatesWithoutFurtherCallbacks() {
        val order = listOf("supported", "persist-read", "readable", "import")
        for (stage in order) {
            val probe = ImportProbe()
            val cancelled = CancellationException("synthetic callback cancellation")
            val failure = rejected("cancel at $stage") {
                runBlocking { probe.run(failAt = stage, failure = cancelled) }
            }
            assertSame(cancelled, failure)
            assertEquals(order.take(order.indexOf(stage) + 1), probe.calls)
        }
    }

    @Test fun selectedDocumentIsCheckedBeforeImportAndReturnsActualComic() = runBlocking {
        val probe = ImportProbe()
        assertSame(probe.comic, probe.run())
        assertEquals(listOf("supported", "persist-read", "readable", "import"), probe.calls)
    }

    @Test fun reopeningAndDraftRoundtripPreserveGeometryAndExplicitReview() = fixture { context, _, store ->
        val page = stage(context, "synthetic-body-tail-hole")
        assertEquals(EvaluationDraft(), store.loadDraft(page))
        store.save(page, reviewed().copy(reviewed = false, issues = mapOf("missed:r-body-tail-hole" to "missed")))
        val reopened = EvaluationStore(context)
        val restored = reopened.loadDraft(reopened.listPages().single())
        assertEquals(region(true), restored.regions.single())
        assertFalse(restored.reviewed)
        assertEquals("missed", restored.issues["missed:r-body-tail-hole"])
        rejected("unknown prediction issue") { store.save(page, restored.copy(issues = mapOf("p999" to "false_positive"))) }
        rejected("unknown missed instance") { store.save(page, restored.copy(issues = mapOf("missed:unknown" to "missed"))) }
    }

    @Test fun exportRequiresEveryRightsAndReviewGateBeforeWritingBytes() = fixture { context, _, store ->
        val page = stage(context, "synthetic-body-tail-hole")
        val valid = reviewed()
        for (draft in listOf(EvaluationDraft(), valid.copy(reviewed = false),
                valid.copy(regions = listOf(region(false))), valid.copy(reviewer = " "),
                valid.copy(rightsHolder = " "), valid.copy(permission = " "))) {
            store.save(page, draft)
            val output = ByteArrayOutputStream()
            rejected("rights/review gate") { store.export(output) }
            assertEquals("Rejected exports must not leak original images", 0, output.size())
            assertEquals(draft, store.loadDraft(page))
        }
        store.save(page, valid)
        val negative = stage(context, "synthetic-negative", negative = true)
        store.save(negative, reviewed(negative = true).copy(reviewed = false))
        val pendingNegative = ByteArrayOutputStream()
        rejected("all pages including negatives require complete review") { store.export(pendingNegative) }
        assertEquals(0, pendingNegative.size())
        store.save(negative, reviewed(negative = true))
        val attempt = File(page.directory, "attempt.json")
        val original = attempt.readText()
        write(page.directory, "attempt.json", JSONObject(original).put("status", "invalid_evidence").put("failure_kind", "fixture"))
        rejected("invalid evidence") { store.export(ByteArrayOutputStream()) }
        assertTrue(attempt.delete())
        rejected("interrupted capture") { store.export(ByteArrayOutputStream()) }
        assertFalse(attempt.exists())
    }

    @Test fun capRejectsEleventhBeforeDecodeOrDetectorAndReopensDuplicate() = fixture { context, root, store ->
        val page = stage(context, "synthetic-first")
        // Remaining directories are cap-control records, not corpus or detector evidence.
        for (index in 1..9) {
            val directory = File(page.directory.parentFile, "synthetic-cap-$index").apply { check(mkdir()) }
            write(directory, "page.json", JSONObject().put("sample_id", directory.name)
                .put("image", "not-decoded-fixture.png").put("width", 1).put("height", 1))
        }
        val comic = Comic(title = "synthetic-fixture", uri = "fixture://no-library", coverPagePath = null, pageCount = 1)
        val ref = ComicPageRef(0, ComicFormat.CBZ, "fixture.png")
        val eleventh = File(root, "not-an-image").apply { writeText("synthetic cap control, must never decode") }
        val failure = rejected("ten-page cap") { runBlocking { store.capture(comic, 0, ref, eleventh.path) } }
        assertTrue("Must reject at cap, before invalid raster decode", failure.message.orEmpty().contains("10 distinct"))
        assertEquals(10, store.listPages().size)
        val identity = "page_${hash(page.image)}"
        val duplicateDir = File(page.directory.parentFile, identity)
        assertTrue(page.directory.renameTo(duplicateDir))
        val metadata = JSONObject(File(duplicateDir, "page.json").readText()).put("sample_id", identity)
        write(duplicateDir, "page.json", metadata)
        val duplicate = runBlocking { store.capture(comic, 0, ref, File(duplicateDir, "image.png").path) }
        assertEquals(identity, duplicate.sampleId)
        assertEquals(10, store.listPages().size)
        assertFalse(File(duplicateDir, "started.json").exists())
    }

    @Test fun exporterPublishesSyntheticBundleForHostRoundtripIncludingFailure() = fixture { context, _, store ->
        val positive = stage(context, "synthetic-body-tail-hole")
        val negative = stage(context, "synthetic-negative", negative = true)
        val predictionPage = stage(context, "synthetic-false-positive", negative = true, color = Color.GRAY)
        val predictionFile = File(predictionPage.directory, "predictions/p000.png")
        check(predictionFile.parentFile!!.mkdir())
        val cutout = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        try {
            // Explicit invented prediction with transparent background and an interior hole.
            for (y in 1..4) for (x in 1..6) {
                if (x != 3 || y != 2) cutout.setPixel(x, y, Color.WHITE)
            }
            predictionFile.outputStream().use { check(cutout.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { cutout.recycle() }
        val originalPrediction = predictionFile.readBytes()
        val originalPredictionHash = hash(predictionFile)
        val predictionAttempt = JSONObject(File(predictionPage.directory, "attempt.json").readText())
        predictionAttempt.put("detections", JSONArray().put(JSONObject().put("prediction_id", "p000")
            .put("bounds", JSONArray(listOf(.25f, .25f, .5f, .5f)))
            .put("mask_path", "predictions/p000.png").put("mask_sha256", originalPredictionHash)))
        write(predictionPage.directory, "attempt.json", predictionAttempt)
        store.save(positive, reviewed())
        store.save(negative, reviewed(negative = true))
        val reopenedPredictionPage = store.listPages().single { it.sampleId == predictionPage.sampleId }
        assertEquals("p000", reopenedPredictionPage.predictions.single().id)
        store.save(reopenedPredictionPage, reviewed(negative = true).copy(issues = mapOf("p000" to "false_positive")))
        val attemptFile = File(positive.directory, "attempt.json")
        val attempt = JSONObject(attemptFile.readText()).put("status", "detector_failed")
            .put("failure_kind", "synthetic-negative-control-no-detector-invoked")
        attempt.remove("duration_ns")
        write(positive.directory, "attempt.json", attempt)
        val output = ByteArrayOutputStream()
        store.export(output)
        val repeated = ByteArrayOutputStream()
        store.export(repeated)
        assertArrayEquals("Repeated export of unchanged fixtures must be byte-identical", output.toByteArray(), repeated.toByteArray())
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertFalse("Duplicate ZIP member", entries.containsKey(entry.name))
                entries[entry.name] = zip.readBytes()
            }
        }
        val manifest = JSONObject(String(entries.getValue("manifest.json"), Charsets.UTF_8))
        assertEquals("Never relabel fixture export real", "synthetic", manifest.getString("kind"))
        val run = JSONObject(String(entries.getValue("run.json"), Charsets.UTF_8))
        assertEquals(3, run.getJSONArray("schedule").length())
        val attempts = run.getJSONArray("attempts")
        val failed = (0 until attempts.length()).map { attempts.getJSONObject(it) }
            .single { it.getString("sample_id") == positive.sampleId }
        assertEquals("detector_failed", failed.getString("status"))
        assertFalse(failed.has("duration_ns"))
        val annotation = JSONObject(String(entries.getValue("pages/${positive.sampleId}/annotation.json"), Charsets.UTF_8))
        assertEquals(1, annotation.getJSONArray("instances").length())
        val empty = JSONObject(String(entries.getValue("pages/${negative.sampleId}/annotation.json"), Charsets.UTF_8))
        assertEquals(0, empty.getJSONArray("instances").length())
        val successful = (0 until attempts.length()).map { attempts.getJSONObject(it) }
            .single { it.getString("sample_id") == predictionPage.sampleId }
        assertEquals("success", successful.getString("status"))
        val prediction = successful.getJSONArray("detections").getJSONObject(0)
        val exportedPath = "pages/${predictionPage.sampleId}/predictions/p000.png"
        assertEquals(exportedPath, prediction.getString("mask_path"))
        assertEquals(originalPredictionHash, prediction.getString("mask_sha256"))
        assertArrayEquals("False-positive annotation must preserve captured prediction bytes",
            originalPrediction, entries.getValue(exportedPath))
        assertArrayEquals(originalPrediction, predictionFile.readBytes())
        assertEquals("false_positive", store.loadDraft(reopenedPredictionPage).issues["p000"])
        val additional = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        check(!additional.isNullOrBlank()) { "BLOCKED: additionalTestOutputDir required to retain native exporter ZIP for host validation" }
        val destination = File(additional, "bubble_evaluation/synthetic-evaluation.zip")
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
        check(!destination.exists()) { "Preserve existing native exporter evidence" }
        destination.writeBytes(output.toByteArray())
        val evidence = Bundle().apply {
            putString("bubbleEvaluationSyntheticZip", destination.absolutePath)
            putString("bubbleEvaluationSyntheticZipSha256", hash(destination))
            putString("bubbleEvaluationScope", "synthetic-fixture-only; real pilot and manual device checks BLOCKED")
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence)
    }
}
