package com.comicreader.app

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises staging only: no detector, ONNX or OCR calls. */
@RunWith(AndroidJUnit4::class)
class BubbleValidationSessionTest {
    private fun fixture(block: (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "session-regression-${UUID.randomUUID()}")
        check(root.mkdir())
        try { block(root) } finally { check(root.deleteRecursively()) }
    }

    private fun smokeArgs() = Bundle().apply {
        putString("bubbleValidationBootstrap", "synthetic_smoke")
    }

    private fun strictArgs(session: BubbleValidationSession) = smokeArgs().apply {
        putString("session", session.root.name)
    }

    private fun rejected(block: () -> Unit) {
        var failure: Exception? = null
        try { block() } catch (caught: Exception) { failure = caught }
        assertTrue("Expected staging rejection", failure != null)
    }

    private fun changeRequest(session: BubbleValidationSession, change: (JSONObject) -> Unit) {
        change(session.request)
        File(session.root, "request.json").writeText(session.request.toString())
    }

    @Test fun missingConfigurationIsRejected() = fixture { root ->
        rejected { BubbleValidationSession.initialize(root, Bundle()) }
        rejected { BubbleValidationSession.initialize(root, Bundle().apply {
            putString("bubbleValidationBootstrap", "synthetic_smoke_typo")
        }) }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun explicitBootstrapCreatesFreshChecksummedOriginalPng() = fixture { root ->
        val first = BubbleValidationSession.initialize(root, smokeArgs())
        val second = BubbleValidationSession.initialize(root, smokeArgs())
        assertTrue(first.smoke)
        assertNotEquals(first.root, second.root)
        assertTrue(first.root.name.matches(Regex("[a-f0-9]{32}")))
        assertEquals("synthetic_smoke", first.request.getString("kind"))
        assertEquals("detector_execution_only_not_baseline", first.request.getString("scope"))
        assertEquals("cold_inference", first.request.getString("mode"))
        assertEquals(1, first.request.getJSONArray("jobs").length())
        val job = first.request.getJSONArray("jobs").getJSONObject(0)
        val page = BubbleValidationSession.local(first.root, job.getString("path"))
        assertEquals(BubbleValidationSession.sha256(page), job.getString("sha256"))
        val bitmap = BitmapFactory.decodeFile(page.path)!!
        try {
            assertEquals(512, bitmap.width)
            assertEquals(512, bitmap.height)
            assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(256, 256))
        } finally { bitmap.recycle() }
        assertEquals(first.request.toString(), JSONObject(File(first.root, "request.json").readText()).toString())
    }

    @Test fun suppliedInvalidSessionsNeverBootstrap() = fixture { root ->
        for (value in listOf("", "bad", "../escape", "A".repeat(32))) {
            rejected { BubbleValidationSession.initialize(root, smokeArgs().apply { putString("session", value) }) }
        }
        rejected { BubbleValidationSession.initialize(root, smokeArgs().apply { putString("session", null) }) }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun missingStageNeverBootstraps() = fixture { root ->
        rejected { BubbleValidationSession.initialize(root, smokeArgs().apply { putString("session", "a".repeat(32)) }) }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun validExternalSessionIsReadOnlyEvenWithBootstrapFlag() = fixture { root ->
        val staged = BubbleValidationSession.initialize(root, smokeArgs())
        val before = staged.root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(staged.root).path to BubbleValidationSession.sha256(it) }
        val resolved = BubbleValidationSession.initialize(root, strictArgs(staged))
        assertFalse(resolved.smoke)
        assertEquals(staged.root, resolved.root)
        assertEquals(before, staged.root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(staged.root).path to BubbleValidationSession.sha256(it) })
    }

    @Test fun missingAndCorruptRequestsAreRejectedWithoutReplacement() = fixture { root ->
        val staged = BubbleValidationSession.initialize(root, smokeArgs())
        val request = File(staged.root, "request.json")
        assertTrue(request.delete())
        rejected { BubbleValidationSession.initialize(root, strictArgs(staged)) }
        assertFalse(request.exists())
        request.writeText("broken json")
        rejected { BubbleValidationSession.initialize(root, strictArgs(staged)) }
        assertEquals("broken json", request.readText())
    }

    @Test fun corruptInputAndHashMismatchAreRejected() = fixture { root ->
        val staged = BubbleValidationSession.initialize(root, smokeArgs())
        val page = File(staged.root, "inputs/blank.png")
        page.writeText("not a PNG")
        rejected { BubbleValidationSession.initialize(root, strictArgs(staged)) }
        changeRequest(staged) { it.getJSONArray("jobs").getJSONObject(0)
            .put("sha256", BubbleValidationSession.sha256(page)) }
        rejected { BubbleValidationSession.initialize(root, strictArgs(staged)) }
        assertEquals("not a PNG", page.readText())
    }

    @Test fun duplicateAttemptIdsAreRejected() = fixture { root ->
        val staged = BubbleValidationSession.initialize(root, smokeArgs())
        changeRequest(staged) {
            it.put("mode", "warm_inference")
            val jobs = it.getJSONArray("jobs")
            jobs.put(JSONObject(jobs.getJSONObject(0).toString()).apply { put("sample_id", "second") })
        }
        rejected { BubbleValidationSession.initialize(root, strictArgs(staged)) }
    }

    @Test fun validWarmBatchPreflightsEveryInput() = fixture { root ->
        val staged = BubbleValidationSession.initialize(root, smokeArgs())
        changeRequest(staged) {
            it.put("mode", "warm_inference")
            val jobs = it.getJSONArray("jobs")
            jobs.put(JSONObject(jobs.getJSONObject(0).toString()).apply {
                put("attempt_id", "second")
            })
        }
        assertEquals(2, BubbleValidationSession.initialize(root, strictArgs(staged))
            .request.getJSONArray("jobs").length())
        changeRequest(staged) {
            it.getJSONArray("jobs").getJSONObject(1).put("sha256", "0".repeat(64))
        }
        rejected { BubbleValidationSession.initialize(root, strictArgs(staged)) }
        assertFalse(File(staged.root, "warmup.json").exists())
        assertFalse(File(staged.root, "smoke_0.started.json").exists())
    }

    @Test fun redirectedSessionRootIsRejected() = fixture { root ->
        val staged = BubbleValidationSession.initialize(root, smokeArgs())
        val alias = File(staged.root.parentFile, "b".repeat(32))
        android.system.Os.symlink(staged.root.path, alias.path)
        try {
            rejected { BubbleValidationSession.initialize(root, smokeArgs().apply {
                putString("session", alias.name)
            }) }
        } finally { check(alias.delete()) }
        assertTrue(File(staged.root, "request.json").isFile)
    }

    @Test fun redirectedRequestIsRejectedWithoutChangingItsTarget() = fixture { root ->
        for (insideSession in listOf(false, true)) {
            val staged = BubbleValidationSession.initialize(root, smokeArgs())
            val request = File(staged.root, "request.json")
            val original = request.readText()
            val target = if (insideSession) File(staged.root, "inputs/redirect.json")
                else File(root, "outside-request.json")
            target.writeText(original)
            check(request.delete())
            android.system.Os.symlink(target.path, request.path)
            try {
                rejected { BubbleValidationSession.initialize(root, strictArgs(staged)) }
                assertEquals(original, target.readText())
            } finally { check(request.delete()) }
        }
    }

    @Test fun staleOutputsAreRejectedAndPreserved() = fixture { root ->
        for (name in listOf("smoke_0.json", "smoke_0.started.json", "smoke_0.returned.json",
            "warmup.json", "masks", "smoke_0.json.tmp")) {
            val staged = BubbleValidationSession.initialize(root, smokeArgs())
            val stale = File(staged.root, name)
            if (name == "masks") check(stale.mkdir()) else stale.writeText("preserve")
            rejected { BubbleValidationSession.initialize(root, strictArgs(staged)) }
            assertTrue(stale.exists())
            if (stale.isFile) assertEquals("preserve", stale.readText())
        }
    }

    @Test fun invalidModeJobsIdsAndInputPathsAreRejected() = fixture { root ->
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("mode", "cache_hit"); Unit },
            { it.put("jobs", org.json.JSONArray()); Unit },
            { it.getJSONArray("jobs").put(JSONObject(it.getJSONArray("jobs").getJSONObject(0).toString())); Unit },
            { it.getJSONArray("jobs").getJSONObject(0).put("attempt_id", "../escape"); Unit },
            { it.getJSONArray("jobs").getJSONObject(0).put("attempt_id", "request"); Unit },
            { it.getJSONArray("jobs").getJSONObject(0).put("attempt_id", "warmup"); Unit },
            { it.getJSONArray("jobs").getJSONObject(0).put("sample_id", ""); Unit },
            { it.getJSONArray("jobs").getJSONObject(0).put("path", "inputs/../../escape"); Unit },
            { it.getJSONArray("jobs").getJSONObject(0).put("path", "request.json"); Unit }
        )
        for (mutation in mutations) {
            val staged = BubbleValidationSession.initialize(root, smokeArgs())
            changeRequest(staged, mutation)
            rejected { BubbleValidationSession.initialize(root, strictArgs(staged)) }
        }
    }

    @Test fun nonStringRequestFieldsAreRejectedWithoutCoercion() = fixture { root ->
        for (field in listOf("attempt_id", "sample_id", "path", "sha256")) {
            val staged = BubbleValidationSession.initialize(root, smokeArgs())
            changeRequest(staged) { it.getJSONArray("jobs").getJSONObject(0).put(field, 123) }
            rejected { BubbleValidationSession.initialize(root, strictArgs(staged)) }
        }
    }

    @Test fun recordedDetectorAndExportFailuresFailAssertionsWithoutRemovingEvidence() = fixture { root ->
        for (status in listOf("detector_failed", "invalid_evidence")) {
            val staged = BubbleValidationSession.initialize(root, smokeArgs())
            val terminal = File(staged.root, "smoke_0.json")
            val negativeControl = JSONObject().put("status", status)
                .put("failure_kind", "validation_test_negative_control").toString()
            terminal.writeText(negativeControl)
            var failure: AssertionError? = null
            try {
                BubbleValidationTest().validateResults(staged, JSONObject())
            } catch (caught: AssertionError) {
                failure = caught
            }
            assertTrue("A recorded failure must fail JUnit", failure != null)
            assertEquals(negativeControl, terminal.readText())
            assertTrue(File(staged.root, "request.json").isFile)
        }
    }

    @Test fun smokeEvidenceCopyFailuresAreNotIgnoredOrOverwritten() = fixture { root ->
        val staged = BubbleValidationSession.initialize(root, smokeArgs())
        val output = File(root, "additional")
        val destination = File(output, "bubble_validation/${staged.root.name}")
        check(destination.mkdirs())
        val sentinel = File(destination, "preserve.txt").apply { writeText("preserve") }
        rejected { BubbleValidationTest().copySmokeEvidence(staged, output) }
        assertEquals("preserve", sentinel.readText())
        val blocked = File(root, "not-a-directory").apply { writeText("preserve") }
        rejected { BubbleValidationTest().copySmokeEvidence(staged, blocked) }
        assertEquals("preserve", blocked.readText())
        assertTrue(File(staged.root, "request.json").isFile)
    }
}
