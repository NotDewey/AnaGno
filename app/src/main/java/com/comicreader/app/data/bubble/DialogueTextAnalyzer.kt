package com.comicreader.app.data.bubble

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR used as a second opinion for the balloon segmenter.
 *
 * ONNX answers "where is a balloon-like shape?" while this class answers
 * "where is readable dialogue?". Keeping both signals lets us reject empty
 * detections, split joined balloons and recover solid narration captions.
 */
@Singleton
class DialogueTextAnalyzer @Inject constructor() {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun analyze(bitmap: Bitmap): List<DialogueTextRegion> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
        val pageArea = bitmap.width.toFloat() * bitmap.height.toFloat()
        return result.textBlocks.mapNotNull { block ->
            val bounds = block.boundingBox ?: return@mapNotNull null
            val cleanedText = block.text
                .replace(Regex("\\s+"), " ")
                .trim()
            val alphaNumericCount = cleanedText.count(Char::isLetterOrDigit)
            val area = bounds.width().toFloat() * bounds.height().toFloat()
            if (alphaNumericCount < 2 || bounds.width() < 3 || bounds.height() < 3) {
                return@mapNotNull null
            }
            if (area / pageArea !in 0.000015f..0.16f) return@mapNotNull null

            DialogueTextRegion(
                left = bounds.left.toFloat().coerceIn(0f, bitmap.width.toFloat()),
                top = bounds.top.toFloat().coerceIn(0f, bitmap.height.toFloat()),
                right = bounds.right.toFloat().coerceIn(0f, bitmap.width.toFloat()),
                bottom = bounds.bottom.toFloat().coerceIn(0f, bitmap.height.toFloat()),
                text = cleanedText,
                lineCount = block.lines.size.coerceAtLeast(1)
            )
        }.filter { it.right > it.left && it.bottom > it.top }
    }
}

data class DialogueTextRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String,
    val lineCount: Int
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val wordCount: Int get() = text.split(Regex("\\s+")).count(String::isNotBlank)

    /**
     * Page/chapter markers are layout metadata, not dialogue. Normalize the
     * small OCR confusions seen in comics (for example `ÞAY 50` for `DAY 50`)
     * before applying the exclusion.
     */
    fun isStructuralLabel(): Boolean {
        val normalized = text
            .uppercase()
            .replace('Þ', 'D')
            .replace('Ð', 'D')
            .replace(Regex("[^A-Z0-9]+"), " ")
            .trim()
        return normalized.matches(
            Regex("^(DAY|CHAPTER|ISSUE|PAGE)\\s*[0-9IVXLCDM]+$")
        )
    }

    fun isCaptionFallbackCandidate(): Boolean {
        if (isStructuralLabel()) return false
        // OCR-only regions are deliberately conservative. One-word dialogue is
        // still retained when the ONNX segmenter also sees its balloon.
        return wordCount >= 3 || lineCount >= 2 || text.length >= 18
    }

    /**
     * Lets the visual detector consider a very short sentence as a framed
     * caption. This is only semantic eligibility: the detector must still find
     * a strong rectangular edge, a quiet solid-color surface and no SFX/sign
     * evidence before it can promote the region.
     */
    fun isShortFramedCaptionCandidate(): Boolean {
        if (isStructuralLabel()) return false
        if (wordCount !in 2..4 || lineCount !in 1..2) return false
        if (text.count(Char::isLetter) < 5) return false
        val terminal = text.trimEnd().lastOrNull() ?: return false
        return terminal in ".?!…"
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        if (continuation.isActive) continuation.resume(result)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener { continuation.cancel() }
}
