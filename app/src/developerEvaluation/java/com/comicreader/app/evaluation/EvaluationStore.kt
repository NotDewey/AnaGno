package com.comicreader.app.evaluation

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.comicreader.app.data.bubble.BubbleDetectionContract
import com.comicreader.app.data.bubble.BubbleDetector
import com.comicreader.app.data.bubble.DialogueTextAnalyzer
import com.comicreader.app.data.comic.ComicPageRef
import com.comicreader.app.domain.model.Comic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class EvaluationPrediction(val id: String, val bounds: List<Float>, val mask: File)
data class EvaluationPage(
    val directory: File, val sampleId: String, val image: File,
    val width: Int, val height: Int, val predictions: List<EvaluationPrediction>
)
data class EvaluationDraft(
    val regions: List<EvaluationRegion> = emptyList(),
    val issues: Map<String, String> = emptyMap(),
    val reviewer: String = "", val rightsHolder: String = "",
    val permission: String = "", val reviewed: Boolean = false
)

class EvaluationStore(context: Context) {
    private val context = context.applicationContext
    private val root = File(this.context.noBackupFilesDir, "bubble_evaluation/pilot-v1")
    companion object {
        private val captureLock = Mutex()
        private var retainedDetector: BubbleDetector? = null
    }

    init {
        check(this.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(root.isDirectory || root.mkdirs())
    }

    fun listPages(): List<EvaluationPage> = root.listFiles().orEmpty()
        .filter { it.isDirectory && File(it, "page.json").isFile }
        .sortedBy { it.name }.map(::readPage)

    suspend fun capture(comic: Comic, pageIndex: Int, page: ComicPageRef, sourcePath: String): EvaluationPage =
        captureLock.withLock {
            withContext(Dispatchers.IO) {
                require(pageIndex >= 0 && page.index == pageIndex)
                val original = File(sourcePath)
                require(original.isFile)
                val digest = sha256(original)
                listPages().firstOrNull { it.sampleId == "page_$digest" }?.let { return@withContext it }
                check(listPages().size < 10) { "The pilot is limited to 10 distinct pages. Export and review it before expanding." }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(original.path, options)
                require(options.outWidth > 0 && options.outHeight > 0) { "Page is not a decodable image" }
                require(options.outWidth <= 16384 && options.outHeight <= 16384 &&
                    options.outWidth.toLong() * options.outHeight <= 16_777_216L) {
                    "Original page exceeds the pilot's safe full-resolution mask limit; it was not rescaled or admitted."
                }
                val extension = when (options.outMimeType) {
                    "image/png" -> "png"
                    "image/jpeg" -> "jpg"
                    "image/webp" -> "webp"
                    else -> error("Unsupported original page image type: ${options.outMimeType}")
                }
                val sampleId = "page_$digest"
                val directory = File(root, sampleId)
                check(directory.mkdir()) { "A previous incomplete capture exists; preserve it for investigation." }
                val image = File(directory, "image.$extension")
                original.copyTo(image)
                check(sha256(image) == digest)
                val metadata = JSONObject().put("schema_version", "1.0").put("sample_id", sampleId)
                    .put("image", image.name).put("width", options.outWidth).put("height", options.outHeight)
                    .put("image_sha256", digest)
                write(File(directory, "page.json"), metadata)
                val source = JSONObject().put("schema_version", "1.0").put("kind", "real").put("comic_id", comic.id)
                    .put("comic_title", comic.title).put("comic_uri", comic.uri).put("page_index", pageIndex)
                    .put("format", page.format.name).put("archive_entry", page.entryName ?: JSONObject.NULL)
                    .put("source_group", "comic_" + sha256(comic.uri.toByteArray()))
                    .put("raster_scope", if (page.format.name == "PDF") "existing_imported_pdf_page_raster" else "original_extracted_page_bytes")
                write(File(directory, "source.json"), source)
                context.assets.open("evaluation-build.json").use { input ->
                    File(directory, "build.json").outputStream().use { input.copyTo(it) }
                }
                context.assets.open("evaluation-policy.json").use { input ->
                    File(directory, "policy.json").outputStream().use { input.copyTo(it) }
                }
                val job = JSONObject().put("attempt_id", "a_$sampleId").put("sample_id", sampleId)
                    .put("mode", "warm_inference").put("expected", "valid")
                write(File(directory, "request.json"), JSONObject().put("schema_version", "1.0")
                    .put("schedule", JSONArray().put(job)).put("input_sha256", digest)
                    .put("priming_calls", 1).put("created_wall_ms", System.currentTimeMillis()))
                val captureContext = object : ContextWrapper(context) {
                    override fun getFilesDir(): File = File(root, "detector-private").apply { mkdirs() }
                    override fun getCacheDir(): File = File(root, "detector-cache").apply { mkdirs() }
                }
                val process = JSONObject().put("pid", Process.myPid()).put("start_id", processStart())
                    .put("fresh", false)
                val observations = JSONObject().put("schema_version", "1.0")
                    .put("timing_scope", "direct BubbleDetector.detect after one priming call; excludes constructor/export/UI")
                    .put("visible_latency", "unmeasured").put("peak_memory", "unmeasured")
                    .put("cache_hit", false).put("fingerprint", Build.FINGERPRINT).put("device_model", Build.MODEL)
                    .put("api", Build.VERSION.SDK_INT).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                    .put("contract", BubbleDetectionContract.MASK_VERSION)
                    .put("model_sha256", context.assets.open("models/bubble_segment.onnx").use { hashStream(it) })
                    .put("memory_before", memory()).put("process", process)
                write(File(directory, "capture.json"), observations)
                var detectorStarted: Long? = null
                var returned = false
                var constructorDuration: Long? = null
                val attempt = JSONObject().put("attempt_id", job.getString("attempt_id"))
                    .put("sample_id", sampleId).put("mode", "warm_inference")
                    .put("process", process).put("detections", JSONArray())
                    .put("runtime", JSONObject().put("timing_scope", observations.getString("timing_scope")))
                try {
                    val reused = retainedDetector != null
                    val constructorStart = SystemClock.elapsedRealtimeNanos()
                    val detector = retainedDetector ?: BubbleDetector(captureContext, DialogueTextAnalyzer()).also { retainedDetector = it }
                    constructorDuration = if (reused) 0L else SystemClock.elapsedRealtimeNanos() - constructorStart
                    observations.put("detector_reused", reused)
                    attempt.put("constructor_duration_ns", constructorDuration)
                    val primeStart = SystemClock.elapsedRealtimeNanos()
                    write(File(directory, "priming.json"), JSONObject().put("phase", "started").put("process", process))
                    detector.detect(image.path, comic.id, pageIndex)
                    write(File(directory, "priming.json"), JSONObject().put("phase", "returned")
                        .put("duration_ns", SystemClock.elapsedRealtimeNanos() - primeStart).put("process", process))
                    val progress = JSONObject(job.toString()).put("input_sha256", digest).put("process", process)
                    write(File(directory, "started.json"), progress.put("phase", "started"))
                    detectorStarted = SystemClock.elapsedRealtimeNanos()
                    val bubbles = detector.detect(image.path, comic.id, pageIndex)
                    attempt.put("duration_ns", SystemClock.elapsedRealtimeNanos() - detectorStarted)
                    returned = true
                    write(File(directory, "returned.json"), progress.put("phase", "returned"))
                    val predictions = JSONArray()
                    File(directory, "predictions").mkdir()
                    bubbles.forEachIndexed { index, bubble ->
                        val bounds = listOf(bubble.left, bubble.top, bubble.right, bubble.bottom)
                        check(bounds.all { it.isFinite() && it in 0f..1f } && bubble.right > bubble.left && bubble.bottom > bubble.top)
                        check(bubble.confidence.isFinite() && bubble.confidence in 0f..1f)
                        val mask = File(bubble.maskPath)
                        val maskInfo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(mask.path, maskInfo)
                        check(maskInfo.outMimeType == "image/png" && maskInfo.outWidth > 0 && maskInfo.outHeight > 0)
                        val id = "p%03d".format(java.util.Locale.ROOT, index)
                        val destination = File(directory, "predictions/$id.png")
                        mask.copyTo(destination)
                        predictions.put(JSONObject().put("prediction_id", id).put("bounds", JSONArray(bounds))
                            .put("mask_path", "predictions/$id.png").put("mask_sha256", sha256(destination))
                            .put("confidence", bubble.confidence).put("text", bubble.text)
                            .put("textHeightRatio", bubble.textHeightRatio).put("order", bubble.order))
                    }
                    attempt.put("detections", predictions).put("status", "success")
                } catch (failure: Exception) {
                    observations.put("failure_message", failure.message ?: failure.javaClass.simpleName)
                    val inDetector = detectorStarted != null && !returned
                    attempt.put("status", if (inDetector) "detector_failed" else "invalid_evidence")
                        .put("failure_kind", if (detectorStarted == null) "setup_or_priming_${failure.javaClass.simpleName}" else failure.javaClass.simpleName)
                    if (inDetector) attempt.put("duration_ns", SystemClock.elapsedRealtimeNanos() - detectorStarted!!)
                    write(File(directory, "attempt.json"), attempt)
                    if (failure is CancellationException) throw failure
                } finally {
                    observations.put("memory_after", memory()).put("constructor_duration_ns", constructorDuration ?: JSONObject.NULL)
                    write(File(directory, "capture.json"), observations)
                }
                write(File(directory, "attempt.json"), attempt)
                readPage(directory)
            }
        }

    fun loadDraft(page: EvaluationPage): EvaluationDraft {
        requirePage(page)
        val file = File(page.directory, "draft.json")
        if (!file.isFile) return EvaluationDraft()
        val record = JSONObject(file.readText())
        val regions = record.getJSONArray("regions").objects().map { region ->
            EvaluationRegion(region.getString("id"), region.getJSONArray("contours").objects().map { contour ->
                val points = contour.getJSONArray("points")
                EvaluationContour((0 until points.length()).map { index ->
                    val point = points.getJSONArray(index)
                    EvaluationPoint(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
                }, contour.getBoolean("subtract"))
            }, region.getBoolean("verified"))
        }
        val issues = record.getJSONObject("issues")
        return EvaluationDraft(regions, issues.keys().asSequence().associateWith { issues.getString(it) },
            record.getString("reviewer"), record.getString("rights_holder"), record.getString("permission"), record.getBoolean("reviewed"))
    }

    fun save(page: EvaluationPage, draft: EvaluationDraft) {
        requirePage(page)
        require(draft.regions.map { it.id }.distinct().size == draft.regions.size)
        draft.regions.forEach {
            require(it.id.matches(Regex("[A-Za-z0-9_-]+")))
            EvaluationGeometry.box(it, page.width, page.height)
        }
        draft.issues.forEach { (id, kind) ->
            require((id in page.predictions.map { it.id } && kind in setOf("false_positive", "incorrect_boundary")) ||
                (id.startsWith("missed:") && id.removePrefix("missed:") in draft.regions.map { it.id } && kind == "missed"))
        }
        val record = JSONObject().put("schema_version", "1.0").put("reviewer", draft.reviewer)
            .put("rights_holder", draft.rightsHolder).put("permission", draft.permission).put("reviewed", draft.reviewed)
            .put("saved_wall_ms", System.currentTimeMillis()).put("issues", JSONObject(draft.issues))
        record.put("regions", JSONArray(draft.regions.map { region ->
            JSONObject().put("id", region.id).put("verified", region.verified)
                .put("contours", JSONArray(region.contours.map { contour ->
                    JSONObject().put("subtract", contour.subtract).put("points", JSONArray(contour.points.map { JSONArray(listOf(it.x, it.y)) }))
                }))
        }))
        write(File(page.directory, "draft.json"), record)
    }

    fun export(output: OutputStream) {
        val pages = listPages()
        check(pages.isNotEmpty() && pages.size <= 10) { "Select 1–10 pages for the pilot." }
        val drafts = pages.associateWith { loadDraft(it) }
        drafts.forEach { (page, draft) ->
            check(draft.reviewed && draft.regions.all { it.verified } && draft.reviewer.isNotBlank()) {
                "${page.sampleId}: independently verify every region and the complete page, including negative regions."
            }
            check(draft.rightsHolder.isNotBlank() && draft.permission.isNotBlank()) { "Evaluation permission and rights holder are required for every page." }
            check(File(page.directory, "attempt.json").isFile) { "Interrupted capture retained; no terminal evidence for ${page.sampleId}." }
            check(JSONObject(File(page.directory, "attempt.json").readText()).getString("status") != "invalid_evidence") {
                "Invalid setup/export evidence retained for ${page.sampleId}; cannot export it as a baseline run."
            }
        }
        val temporary = File(context.noBackupFilesDir, "bubble_evaluation/export-${UUID.randomUUID()}")
        check(temporary.mkdirs())
        try {
            buildBundle(temporary, pages, drafts)
            ZipOutputStream(output).use { zip ->
                temporary.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(temporary).path }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(temporary).invariantSeparatorsPath).apply { time = 0 })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } finally {
            temporary.deleteRecursively()
        }
    }

    private fun buildBundle(destination: File, pages: List<EvaluationPage>, drafts: Map<EvaluationPage, EvaluationDraft>) {
        val samples = JSONArray()
        val rights = JSONArray()
        val attempts = JSONArray()
        val schedule = JSONArray()
        val annotationHashes = mutableListOf<Pair<String, String>>()
        val identity = JSONObject(File(pages.first().directory, "build.json").readText())
        val datasetKind = JSONObject(File(pages.first().directory, "source.json").readText()).getString("kind")
        check(datasetKind in setOf("real", "synthetic"))
        val policy = File(pages.first().directory, "policy.json")
        policy.copyTo(File(destination, "policy.json"))
        for (page in pages) {
            check(JSONObject(File(page.directory, "build.json").readText()).toString() == identity.toString()) { "Pilot mixes different builds; preserve it and use a consistent cohort." }
            check(sha256(File(page.directory, "policy.json")) == sha256(policy))
            val draft = drafts.getValue(page)
            val pageRoot = File(destination, "pages/${page.sampleId}").apply { mkdirs() }
            val localImage = File(pageRoot, page.image.name)
            page.image.copyTo(localImage)
            val metadata = JSONObject(File(page.directory, "page.json").readText())
            check(sha256(localImage) == metadata.getString("image_sha256")) { "Page bytes changed since detection." }
            val source = JSONObject(File(page.directory, "source.json").readText())
            check(source.getString("kind") == datasetKind) { "Real pages and synthetic fixtures cannot share a corpus." }
            val instances = JSONArray()
            val gt = File(pageRoot, "gt").apply { mkdirs() }
            for (region in draft.regions) {
                val mask = EvaluationGeometry.mask(region, page.width, page.height)
                val file = File(gt, "${region.id}.png")
                try { file.outputStream().use { check(mask.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { mask.recycle() }
                instances.put(JSONObject().put("id", region.id).put("box", JSONArray(EvaluationGeometry.box(region, page.width, page.height)))
                    .put("mask_path", "gt/${file.name}").put("mask_sha256", sha256(file)))
            }
            val annotation = JSONObject().put("schema_version", "1.0").put("sample_id", page.sampleId)
                .put("width", page.width).put("height", page.height).put("completeness", "mask")
                .put("instances", instances).put("groups", JSONArray()).put("ignore_regions", JSONArray())
            val annotationFile = File(pageRoot, "annotation.json")
            write(annotationFile, annotation)
            annotationHashes.add(page.sampleId to sha256(annotationFile))
            val prefix = "pages/${page.sampleId}/"
            val rightsId = "rights_${page.sampleId}"
            val permission = File(pageRoot, "permission.txt")
            permission.writeText("Rights holder: ${draft.rightsHolder}\nEvaluation permission supplied by ${draft.reviewer}:\n${draft.permission}\n", Charsets.UTF_8)
            rights.put(JSONObject().put("rights_id", rightsId).put("status", "approved").put("license", draft.permission)
                .put("holder", draft.rightsHolder).put("allowed_uses", JSONArray().put("evaluation"))
                .put("evidence", JSONObject().put("path", prefix + permission.name).put("sha256", sha256(permission))))
            samples.put(JSONObject().put("sample_id", page.sampleId).put("source_group", source.getString("source_group"))
                .put("split", "development").put("tags", JSONArray(if (draft.regions.isEmpty()) listOf("negative") else emptyList<String>()))
                .put("rights_id", rightsId).put("asset", JSONObject().put("path", prefix + localImage.name).put("sha256", sha256(localImage))
                    .put("width", page.width).put("height", page.height))
                .put("annotation", JSONObject().put("path", prefix + annotationFile.name).put("sha256", sha256(annotationFile)).put("completeness", "mask")))
            val request = JSONObject(File(page.directory, "request.json").readText())
            schedule.put(request.getJSONArray("schedule").getJSONObject(0))
            val attempt = JSONObject(File(page.directory, "attempt.json").readText())
            val predictions = attempt.getJSONArray("detections")
            File(pageRoot, "predictions").mkdir()
            predictions.objects().forEach { prediction ->
                val relative = prediction.getString("mask_path")
                val file = File(page.directory, relative)
                check(file.canonicalPath.startsWith(File(page.directory, "predictions").canonicalPath + File.separator))
                check(sha256(file) == prediction.getString("mask_sha256"))
                file.copyTo(File(pageRoot, relative))
                prediction.put("mask_path", prefix + relative)
            }
            attempt.put("detections", JSONArray(predictions.objects()))
            attempts.put(attempt)
            listOf("source.json", "draft.json", "capture.json", "request.json", "started.json", "returned.json", "priming.json", "attempt.json", "build.json").forEach { name ->
                File(page.directory, name).takeIf { it.isFile }?.copyTo(File(pageRoot, name))
            }
        }
        val manifest = JSONObject().put("schema_version", "1.0").put("dataset_id", "anagno-local-pilot10")
            .put("revision", "pilot-" + sha256(samples.toString().toByteArray(Charsets.UTF_8)))
            .put("kind", datasetKind).put("samples", samples)
        val manifestFile = File(destination, "manifest.json")
        write(manifestFile, manifest)
        write(File(destination, "rights.json"), JSONObject().put("schema_version", "1.0").put("records", rights))
        val firstCapture = JSONObject(File(pages.first().directory, "capture.json").readText())
        pages.forEach { page ->
            val capture = JSONObject(File(page.directory, "capture.json").readText())
            check(capture.getString("fingerprint") == firstCapture.getString("fingerprint") &&
                capture.getString("model_sha256") == firstCapture.getString("model_sha256")) { "Pilot mixes device or model cohorts." }
        }
        val digestPairs = JSONArray(annotationHashes.sortedBy { it.first }.map { JSONArray(listOf(it.first, it.second)) }).toString()
        val provenance = JSONObject().put("production_revision", identity.getString("production_revision"))
            .put("harness_revision", identity.getString("harness_source_digest"))
            .put("model_sha256", firstCapture.getString("model_sha256")).put("corpus_sha256", sha256(manifestFile))
            .put("annotations_sha256", sha256(digestPairs.toByteArray(Charsets.UTF_8))).put("policy_sha256", sha256(File(destination, "policy.json")))
            .put("device_fingerprint", firstCapture.getString("fingerprint")).put("device_model", firstCapture.getString("device_model"))
            .put("runtime_kind", if (Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu")) "emulator" else "physical")
            .put("build_variant", if (context.packageName.endsWith(".bubblevalidation")) "bubbleValidation" else "debug")
            .put("contract", BubbleDetectionContract.MASK_VERSION)
        write(File(destination, "run.json"), JSONObject().put("schema_version", "1.0")
            .put("run_id", "pilot-" + sha256((schedule.toString() + attempts.toString()).toByteArray(Charsets.UTF_8)))
            .put("provenance", provenance).put("schedule", schedule).put("attempts", attempts))
        write(File(destination, "capture.json"), JSONObject().put("schema_version", "1.0")
            .put("scope", "10-page development pilot; not core48 admission")
            .put("rights_review", "explicit local user attestation; independently verify before corpus admission")
            .put("groups", "unlabelled; instance metrics only").put("visible_latency", "unmeasured")
            .put("peak_memory", "unmeasured").put("timing", "warm direct calls after one explicit priming call per page")
            .put("source_group_warning", "URI-derived comic family; consolidate aliases/editions before expansion"))
    }

    private fun readPage(directory: File): EvaluationPage {
        val record = JSONObject(File(directory, "page.json").readText())
        val attemptFile = File(directory, "attempt.json")
        val predictions = if (attemptFile.isFile) JSONObject(attemptFile.readText()).optJSONArray("detections") else null
        return EvaluationPage(directory, record.getString("sample_id"), File(directory, record.getString("image")),
            record.getInt("width"), record.getInt("height"), predictions?.objects().orEmpty().map { prediction ->
                val bounds = prediction.getJSONArray("bounds")
                EvaluationPrediction(prediction.getString("prediction_id"), (0..3).map { bounds.getDouble(it).toFloat() },
                    File(directory, prediction.getString("mask_path")))
            })
    }

    private fun requirePage(page: EvaluationPage) {
        require(page.directory.parentFile?.canonicalFile == root.canonicalFile && File(page.directory, "page.json").isFile)
    }

    private fun memory(): JSONObject = JSONObject().put("physical_pss_bytes", Debug.getPss().toLong() * 1024)
        .put("java_allocated_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
        .put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize())

    private fun processStart(): String = File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19]
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun sha256(file: File): String = file.inputStream().use { hashStream(it) }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun hashStream(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun write(file: File, value: JSONObject) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(value.toString(2) + "\n", Charsets.UTF_8)
        check(temporary.renameTo(file))
    }
}
