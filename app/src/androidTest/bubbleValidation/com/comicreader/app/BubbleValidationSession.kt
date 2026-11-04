package com.comicreader.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Test-only staging. An explicitly supplied session always belongs to the host. */
internal data class BubbleValidationSession(
    val root: File,
    val request: JSONObject,
    val smoke: Boolean
) {
    companion object {
        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

        fun local(root: File, path: String): File {
            require(path.isNotBlank() && !path.contains('\\') && !path.contains(':'))
            require(path.split('/').none { it == "." || it == ".." || it.isEmpty() })
            return File(root, path).canonicalFile.also {
                require(it.path.startsWith(root.canonicalPath + File.separator))
            }
        }

        private fun text(record: JSONObject, key: String): String {
            val value = record.get(key)
            require(value is String) { "$key must be a string" }
            return value
        }

        fun initialize(filesDir: File, arguments: Bundle): BubbleValidationSession {
            val smoke = !arguments.containsKey("session")
            val session = if (!smoke) {
                arguments.getString("session") ?: error("Explicit session must be a string")
            } else {
                check(arguments.getString("bubbleValidationBootstrap") == "synthetic_smoke") {
                    "Explicit session required"
                }
                UUID.randomUUID().toString().replace("-", "")
            }
            require(session.matches(Regex("[a-f0-9]{32}"))) { "Invalid explicit session" }
            val base = File(filesDir, "bubble_validation").canonicalFile
            require(base.parentFile == filesDir.canonicalFile) { "Session base must remain private" }
            val root = File(base, session)
            require(root.canonicalFile == root) { "Session root must not redirect" }
            if (smoke) {
                check(base.isDirectory || base.mkdirs())
                check(root.mkdir()) { "Refusing to reuse smoke session" }
                val inputs = File(root, "inputs")
                check(inputs.mkdir())
                val page = File(inputs, "blank.png")
                val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    bitmap.recycle()
                }
                File(root, "request.json").writeText(JSONObject().apply {
                    put("kind", "synthetic_smoke")
                    put("scope", "detector_execution_only_not_baseline")
                    put("mode", "cold_inference")
                    put("jobs", JSONArray().put(JSONObject().apply {
                        put("attempt_id", "smoke_0")
                        put("sample_id", "original_blank_512")
                        put("path", "inputs/blank.png")
                        put("sha256", sha256(page))
                    }))
                }.toString(2) + "\n")
            }
            require(root.isDirectory) { "Staged session missing" }
            require(root.listFiles()!!.all { it.name == "request.json" || it.name == "inputs" }) {
                "Stale or unexpected session output"
            }
            val requestFile = local(root, "request.json")
            require(requestFile == File(root, "request.json")) { "Request must not redirect" }
            val request = JSONObject(requestFile.readText())
            val mode = text(request, "mode")
            require(mode == "cold_inference" || mode == "warm_inference")
            val jobs = request.getJSONArray("jobs")
            require(jobs.length() > 0 && (mode != "cold_inference" || jobs.length() == 1))
            val attempts = mutableSetOf<String>()
            for (index in 0 until jobs.length()) {
                val job = jobs.getJSONObject(index)
                val id = text(job, "attempt_id")
                require(id.matches(Regex("[A-Za-z0-9_-]+")) && attempts.add(id))
                require(id != "request" && id != "warmup") { "Attempt ID collides with session metadata" }
                val sample = text(job, "sample_id")
                require(sample.isNotBlank())
                val path = text(job, "path")
                require(path.startsWith("inputs/"))
                val page = local(root, path)
                require(page.isFile && sha256(page) == text(job, "sha256")) { "Input hash mismatch" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(page.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Undecodable input" }
            }
            return BubbleValidationSession(root, request, smoke)
        }
    }
}
