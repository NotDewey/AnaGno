package com.comicreader.app

import android.graphics.BitmapFactory
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.comicreader.app.data.bubble.BubbleDetectionContract
import com.comicreader.app.data.bubble.BubbleDetector
import com.comicreader.app.data.bubble.DialogueTextAnalyzer
import com.comicreader.app.domain.model.Bubble
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class BubbleValidationTest {
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun local(root: File, path: String): File {
        require(path.isNotBlank() && !path.contains('\\') && !path.contains(':'))
        require(path.split('/').none { it == "." || it == ".." || it.isEmpty() })
        return File(root, path).canonicalFile.also {
            require(it.path.startsWith(root.canonicalPath + File.separator))
        }
    }

    private fun write(file: File, value: JSONObject) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(value.toString(2) + "\n")
        check(temporary.renameTo(file))
    }

    private fun export(root: File, attemptId: String, index: Int, bubble: Bubble): JSONObject {
        val bounds = listOf(bubble.left, bubble.top, bubble.right, bubble.bottom)
        assertTrue(bounds.all { it.isFinite() && it in 0f..1f })
        assertTrue(bubble.right > bubble.left && bubble.bottom > bubble.top)
        assertTrue(bubble.confidence.isFinite() && bubble.confidence in 0f..1f)
        assertTrue(bubble.textHeightRatio.isFinite() && bubble.textHeightRatio in 0f..1f)
        val mask = File(bubble.maskPath)
        assertTrue(mask.isFile)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(mask.path, options)
        assertEquals("image/png", options.outMimeType)
        assertTrue(options.outWidth > 0 && options.outHeight > 0)
        val relative = "masks/$attemptId-$index.png"
        val destination = local(root, relative)
        destination.parentFile!!.mkdirs()
        mask.copyTo(destination, overwrite = false)
        return JSONObject().apply {
            put("prediction_id", "$attemptId-$index")
            put("bounds", JSONArray(bounds))
            put("mask_path", relative)
            put("mask_sha256", sha256(destination))
            put("id", bubble.id)
            put("comicId", bubble.comicId)
            put("pageIndex", bubble.pageIndex)
            put("order", bubble.order)
            put("left", bubble.left)
            put("top", bubble.top)
            put("right", bubble.right)
            put("bottom", bubble.bottom)
            put("text", bubble.text)
            put("maskPath", relative)
            put("confidence", bubble.confidence)
            put("isManual", bubble.isManual)
            put("textHeightRatio", bubble.textHeightRatio)
        }
    }

    @Test
    fun captureDirectDetector() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName == "com.comicreader.app.bubblevalidation")
        assertEquals("v35_2_", BubbleDetectionContract.MASK_VERSION)
        val session = InstrumentationRegistry.getArguments().getString("session")
            ?: error("Explicit session required")
        require(session.matches(Regex("[a-f0-9]{32}")))
        val root = File(context.filesDir, "bubble_validation/$session")
        val request = JSONObject(File(root, "request.json").readText())
        val mode = request.getString("mode")
        require(mode == "cold_inference" || mode == "warm_inference")
        val jobs = request.getJSONArray("jobs")
        require(jobs.length() > 0 && (mode != "cold_inference" || jobs.length() == 1))
        val stat = File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')
        val process = JSONObject().apply {
            put("pid", Process.myPid())
            put("start_id", stat[19])
            put("fresh", mode == "cold_inference")
        }
        val constructorStart = SystemClock.elapsedRealtimeNanos()
        val detector = BubbleDetector(context, DialogueTextAnalyzer())
        val constructorDuration = SystemClock.elapsedRealtimeNanos() - constructorStart
        if (mode == "warm_inference") {
            val first = jobs.getJSONObject(0)
            val page = local(root, first.getString("path"))
            require(sha256(page) == first.getString("sha256"))
            val start = SystemClock.elapsedRealtimeNanos()
            detector.detect(page.path, 900000000L, 0)
            write(File(root, "warmup.json"), JSONObject().apply {
                put("duration_ns", SystemClock.elapsedRealtimeNanos() - start)
                put("process", process)
                put("completed", true)
            })
        }
        for (index in 0 until jobs.length()) {
            val job = jobs.getJSONObject(index)
            val attemptId = job.getString("attempt_id")
            require(attemptId.matches(Regex("[A-Za-z0-9_-]+")))
            val result = JSONObject().apply {
                put("attempt_id", attemptId)
                put("sample_id", job.getString("sample_id"))
                put("mode", mode)
                put("process", process)
                put("constructor_duration_ns", constructorDuration)
                put("detections", JSONArray())
            }
            val page = local(root, job.getString("path"))
            var started: Long? = null
            try {
                require(sha256(page) == job.getString("sha256"))
                started = SystemClock.elapsedRealtimeNanos()
                val bubbles = detector.detect(page.path, 900000000L + index, index)
                result.put("duration_ns", SystemClock.elapsedRealtimeNanos() - started)
                started = null
                val predictions = JSONArray()
                bubbles.forEachIndexed { order, bubble ->
                    predictions.put(export(root, attemptId, order, bubble))
                }
                result.put("detections", predictions)
                result.put("status", "success")
            } catch (failure: Exception) {
                if (started != null) {
                    result.put("duration_ns", SystemClock.elapsedRealtimeNanos() - started)
                    result.put("status", "detector_failed")
                } else {
                    result.put("status", "invalid_evidence")
                }
                result.put("failure_kind", failure.javaClass.simpleName)
            } catch (failure: AssertionError) {
                result.put("status", "invalid_evidence")
                result.put("failure_kind", "output_geometry_or_png")
            }
            write(File(root, "$attemptId.json"), result)
        }
    }
}
