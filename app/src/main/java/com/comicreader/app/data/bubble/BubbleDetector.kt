package com.comicreader.app.data.bubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.comicreader.app.domain.model.Bubble
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val MODEL_ASSET = "models/bubble_segment.onnx"
// The bundled bubble_segment.onnx model has a fixed 1024 x 1024 input.
private const val INPUT_SIZE = 1024
private const val MASK_CHANNELS = 32
private const val CONFIDENCE_THRESHOLD = 0.35f
private const val IOU_THRESHOLD = 0.30f
private const val MASK_THRESHOLD = 0.50f
private const val MAX_PAGE_DIMENSION = 3200
private const val MIN_NORMALIZED_AREA = 0.00025f
private const val CUTOUT_PADDING_RATIO = 0.08f
private const val PARTITION_MAX_DIMENSION = 640
private const val CONNECTED_GROUP_CORE_THRESHOLD = 0.52f
private const val CONNECTED_GROUP_BRIDGE_THRESHOLD = 0.44f
private const val NATIVE_SEED_MASK_THRESHOLD = 0.40f
private const val NATIVE_COMPONENT_SUPPORT_THRESHOLD = 0.10f
private const val NATIVE_GUARD_CORE_THRESHOLD = 0.28f
private const val NATIVE_GUARD_LOOSE_THRESHOLD = 0.14f
private const val NATIVE_FENCE_CLOSE_LINE_RATIO = 0.22f
private const val NATIVE_FENCE_OVERLAP_LINE_RATIO = 0.16f
private const val FINAL_ALPHA_CORE_THRESHOLD = 0.30f
private const val FINAL_ALPHA_LOOSE_THRESHOLD = 0.18f
private const val FINAL_ALPHA_MARGIN_LINE_RATIO = 0.40f
private const val FINAL_ALPHA_VISIBLE_THRESHOLD = 24
private const val FINAL_ALPHA_SOLID_THRESHOLD = 160
private const val OCR_ALPHA_REQUIRED_COVERAGE = 0.985f
private const val NATIVE_BALLOON_RECOVERY_THRESHOLD = 0.56f
private const val MODEL_ONLY_RECOVERY_CONFIDENCE = 0.56f
private const val MODEL_ONLY_STRONG_CONFIDENCE = 0.82f
private const val FULL_LOBE_CLOSE_LINE_RATIO = 0.18f
private const val GUARDED_BOUNDARY_MIN_EVIDENCE = 0.46f
private const val CAPTION_MIN_FRAME_EVIDENCE = 0.28f
private const val CAPTION_MIN_SURFACE_EVIDENCE = 0.48f
private const val EDGE_CAPTION_MIN_FRAME_EVIDENCE = 0.14f
private const val EDGE_CAPTION_MIN_SURFACE_EVIDENCE = 0.68f
private const val EDGE_CAPTION_PAGE_MARGIN_RATIO = 0.075f
private const val SHORT_CAPTION_MIN_FRAME_EVIDENCE = 0.56f
private const val SHORT_CAPTION_MIN_SURFACE_EVIDENCE = 0.62f
private const val SHORT_CAPTION_MAX_SHAPE_CONFIDENCE = 0.34f
private const val LOCAL_OCR_RECOVERY_MIN_SHAPE_CONFIDENCE = 0.70f
private const val MIN_EXACT_SPEECH_QUALITY = 0.70f
private const val MIN_EXACT_CAPTION_QUALITY = 0.66f
private const val HARD_GATE_TEXT_PLATE = 0.992f
private const val HARD_GATE_MODEL_PRECISION = 0.48f
private const val HARD_GATE_MODEL_RECALL = 0.55f
private const val HARD_GATE_SURFACE_QUALITY = 0.40f
private const val HARD_GATE_BOUNDARY_EVIDENCE = 0.42f
private const val HARD_GATE_MAX_HOLE_RATIO = 0.035f
private const val HARD_GATE_MIN_CROP_SAFETY = 0.70f
private const val MASK_CACHE_VERSION = BubbleDetectionContract.MASK_VERSION
private const val DIAGNOSTIC_TAG = BubbleDetectionContract.DIAGNOSTIC_TAG

/**
 * Hybrid on-device dialogue detector. YOLO finds balloon-shaped objects while
 * ML Kit OCR validates their contents, splits joined balloons and recovers solid
 * narration captions missed by the shape model.
 *
 * Detection runs on a downsampled copy of the page (capped at MAX_PAGE_DIMENSION)
 * for speed, but the saved bubble cutout is re-extracted from the original,
 * full-resolution file via BitmapRegionDecoder so Bubble Zoom never has to
 * upscale a blurry asset.
 */
@Singleton
class BubbleDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textAnalyzer: DialogueTextAnalyzer
) {
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val session: OrtSession by lazy {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            context.assets.open(MODEL_ASSET).use { model ->
                environment.createSession(model.readBytes(), options)
            }
        }
    }

    suspend fun detect(pagePath: String, comicId: Long, pageIndex: Int): List<Bubble> =
        withContext(Dispatchers.Default) {
            val source = decodeSampledBitmap(pagePath)
                ?: error("Couldn't decode this page for Bubble Zoom")
            try {
                // OCR and ONNX are independent read-only consumers of `source`.
                // Starting OCR first hides most of its cost behind ONNX inference.
                val textRegionsDeferred = async {
                    runCatching { textAnalyzer.analyze(source) }.getOrDefault(emptyList())
                }
                val letterbox = createLetterbox(source)
                val input = bitmapToTensor(letterbox.bitmap)
                letterbox.bitmap.recycle()

                val shapeDetections = OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(input),
                    longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
                ).use { tensor ->
                    session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                        val predictionTensor = result[0] as OnnxTensor
                        val prototypeTensor = result[1] as OnnxTensor
                        decodeDetections(
                            predictionTensor.floatBuffer,
                            predictionTensor.info.shape,
                            prototypeTensor.floatBuffer,
                            prototypeTensor.info.shape,
                            source,
                            letterbox
                        )
                    }
                }
                val textRegions = textRegionsDeferred.await()
                logStage(
                    stage = "PROPOSALS",
                    outcome = "INFO",
                    detail = "model=${shapeDetections.size}, ocr=${textRegions.size}, classes=" +
                            shapeDetections.groupingBy { it.modelClass }
                                .eachCount()
                                .entries
                                .joinToString(",") { "${it.key}:${it.value}" }
                                .ifBlank { "none" }
                )
                val detections = buildHybridDetections(
                    shapeDetections = shapeDetections,
                    textRegions = textRegions,
                    source = source
                )
                logStage(
                    stage = "HYBRID",
                    outcome = "INFO",
                    detail = "accepted=${detections.size}"
                )

                // One region decoder reused across every bubble on this page,
                // instead of reopening the source file per bubble.
                val regionDecoder = runCatching {
                    BitmapRegionDecoder.newInstance(pagePath, false)
                }.getOrNull()
                val cacheGeneration = System.currentTimeMillis()
                val edgePartitionCache = mutableMapOf<Int, EdgePartition?>()

                try {
                    var exactTextMasks = 0
                    var fallbackTextMasks = 0
                    var modelOnlyMasks = 0
                    val savedBubbles = orderForReading(detections)
                        .mapIndexedNotNull { order, detected ->
                            val savedCutout = saveMaskedCutout(
                                regionDecoder = regionDecoder,
                                source = source,
                                detection = detected,
                                comicId = comicId,
                                pageIndex = pageIndex,
                                order = order,
                                cacheGeneration = cacheGeneration,
                                edgePartitionCache = edgePartitionCache
                            ) ?: return@mapIndexedNotNull null
                            if (detected.textRegion != null) {
                                if (savedCutout.isPresentationFallback) fallbackTextMasks++
                                else exactTextMasks++
                            } else {
                                modelOnlyMasks++
                            }
                            Bubble(
                                comicId = comicId,
                                pageIndex = pageIndex,
                                order = order,
                                left = savedCutout.left / source.width,
                                top = savedCutout.top / source.height,
                                right = savedCutout.right / source.width,
                                bottom = savedCutout.bottom / source.height,
                                text = detected.textRegion?.text.orEmpty(),
                                maskPath = savedCutout.path,
                                confidence = if (savedCutout.isPresentationFallback) {
                                    min(detected.confidence, 0.40f)
                                } else {
                                    detected.confidence
                                }
                            )
                        }
                    val recognizedItems = detections.count { it.textRegion != null }
                    val unresolvedItems = (
                            recognizedItems - exactTextMasks - fallbackTextMasks
                            ).coerceAtLeast(0)
                    logStage(
                        stage = "COVERAGE_SUMMARY",
                        outcome = if (unresolvedItems == 0) "COMPLETE" else "PARTIAL",
                        detail = "ocrBlocks=${textRegions.size}, recognizedItems=$recognizedItems, " +
                                "exact=$exactTextMasks, fallback=$fallbackTextMasks, " +
                                "unresolved=$unresolvedItems, modelOnly=$modelOnlyMasks"
                    )
                    logStage(
                        stage = "FIDELITY_SUMMARY",
                        outcome = if (unresolvedItems == 0) "AUDITED" else "PARTIAL",
                        detail = "exact=$exactTextMasks, presentationFallback=$fallbackTextMasks, " +
                                "modelOnly=$modelOnlyMasks; inspect HARD_GATES and SOURCE_CONNECTIVITY " +
                                "independently from OCR coverage"
                    )
                    savedBubbles
                } finally {
                    regionDecoder?.recycle()
                }
            } finally {
                source.recycle()
            }
        }

    private fun decodeDetections(
        predictions: FloatBuffer,
        predictionShape: LongArray,
        prototypes: FloatBuffer,
        prototypeShape: LongArray,
        source: Bitmap,
        letterbox: Letterbox
    ): List<DetectedBubble> {
        val channels = predictionShape[1].toInt()
        val anchors = predictionShape[2].toInt()
        require(channels >= 5 + MASK_CHANNELS) { "Unexpected balloon model output" }
        val classCount = channels - 4 - MASK_CHANNELS
        require(classCount >= 1) { "Balloon model must expose at least one class" }

        require(prototypeShape.size == 4) { "Unexpected balloon prototype output" }
        val prototypeHeight = prototypeShape[2].toInt()
        val prototypeWidth = prototypeShape[3].toInt()
        val prototypeValues = FloatArray(MASK_CHANNELS * prototypeHeight * prototypeWidth)
        prototypes.duplicate().apply { rewind() }.get(prototypeValues)
        fun prediction(channel: Int, anchor: Int): Float = predictions.get(channel * anchors + anchor)
        val candidates = ArrayList<DetectedBubble>()
        for (anchor in 0 until anchors) {
            var classIndex = 0
            var confidence = prediction(4, anchor)
            for (candidateClass in 1 until classCount) {
                val candidateConfidence = prediction(4 + candidateClass, anchor)
                if (candidateConfidence > confidence) {
                    confidence = candidateConfidence
                    classIndex = candidateClass
                }
            }
            if (confidence < CONFIDENCE_THRESHOLD) continue

            val centerX = prediction(0, anchor)
            val centerY = prediction(1, anchor)
            val width = prediction(2, anchor)
            val height = prediction(3, anchor)
            val left = ((centerX - width / 2f - letterbox.padX) / letterbox.scale)
                .coerceIn(0f, source.width.toFloat())
            val top = ((centerY - height / 2f - letterbox.padY) / letterbox.scale)
                .coerceIn(0f, source.height.toFloat())
            val right = ((centerX + width / 2f - letterbox.padX) / letterbox.scale)
                .coerceIn(0f, source.width.toFloat())
            val bottom = ((centerY + height / 2f - letterbox.padY) / letterbox.scale)
                .coerceIn(0f, source.height.toFloat())
            val normalizedArea = (right - left) * (bottom - top) / (source.width * source.height)
            if (right <= left || bottom <= top || normalizedArea < MIN_NORMALIZED_AREA) continue

            val coefficients = FloatArray(MASK_CHANNELS) { channel ->
                prediction(4 + classCount + channel, anchor)
            }
            candidates += DetectedBubble(
                left, top, right, bottom, confidence, coefficients,
                prototypeValues, prototypeWidth, prototypeHeight, letterbox,
                modelClass = decodeModelClass(classIndex, classCount)
            )
        }
        return nonMaximumSuppression(candidates)
    }

    private fun decodeModelClass(index: Int, classCount: Int): BubbleModelClass {
        if (classCount == 1) return BubbleModelClass.GENERIC
        return when (index) {
            0 -> BubbleModelClass.SPEECH
            1 -> BubbleModelClass.THOUGHT
            2 -> BubbleModelClass.SHOUT
            3 -> BubbleModelClass.WHISPER
            4 -> BubbleModelClass.ELECTRONIC
            5 -> BubbleModelClass.CAPTION
            else -> BubbleModelClass.UNKNOWN
        }
    }

    /** Combines segmentation shapes with OCR and builds logical dialogue groups. */
    private fun buildHybridDetections(
        shapeDetections: List<DetectedBubble>,
        textRegions: List<DialogueTextRegion>,
        source: Bitmap
    ): List<DetectedBubble> {
        val assignments = IntArray(shapeDetections.size)
        val regionsByShape = linkedMapOf<Int, MutableList<DialogueTextRegion>>()
        val decisionsByRegion = linkedMapOf<DialogueTextRegion, CandidateDecision>()
        val fallbackCandidates = mutableListOf<FallbackCandidate>()
        val hybrid = mutableListOf<DetectedBubble>()

        textRegions.forEach { region ->
            val scoredMatch = shapeDetections.indices
                .map { index -> index to textMatchScore(shapeDetections[index], region) }
                .filter { it.second >= 0.42f }
                .maxByOrNull { it.second }
            val matchedDetection = scoredMatch?.let { shapeDetections[it.first] }
            val decision = classifyTextCandidate(
                region = region,
                detection = matchedDetection,
                matchScore = scoredMatch?.second ?: 0f,
                source = source
            )
            logStage(
                stage = "CLASSIFICATION",
                outcome = if (decision.accepted) "ACCEPT" else "REJECT",
                detail = "${decision.kind} score=${formatScore(decision.confidence)}: " +
                        "${decision.reason}",
                text = region.text
            )

            val automaticCoverage = !decision.accepted &&
                    decision.kind == CandidateKind.UNKNOWN &&
                    isAutomaticPresentationCandidate(region, matchedDetection)
            if (!decision.accepted && !automaticCoverage) {
                return@forEach
            }
            val effectiveDecision = if (automaticCoverage) {
                CandidateDecision(
                    kind = CandidateKind.SPEECH_BALLOON,
                    accepted = true,
                    confidence = max(decision.confidence, 0.30f),
                    reason = "automatic OCR coverage fallback"
                ).also {
                    logStage(
                        stage = "OCR_COVERAGE",
                        outcome = "RECOVER",
                        detail = "modelMatched=${matchedDetection != null}, " +
                                "words=${region.wordCount}, lines=${region.lineCount}",
                        text = region.text
                    )
                }
            } else {
                decision
            }
            decisionsByRegion[region] = effectiveDecision
            if (scoredMatch != null) {
                assignments[scoredMatch.first]++
                regionsByShape.getOrPut(scoredMatch.first) { mutableListOf() }.add(region)
            } else if (
                effectiveDecision.kind == CandidateKind.CAPTION ||
                effectiveDecision.kind == CandidateKind.SPEECH_BALLOON
            ) {
                fallbackCandidates += FallbackCandidate(region, effectiveDecision)
            }
        }

        // Preserve the artist's connected balloon chains as one reading item.
        // Only visually disconnected components inside a broad ONNX mask are
        // separated; OCR blocks are no longer assumed to be individual bubbles.
        regionsByShape.forEach { (shapeIndex, regions) ->
            val uniqueRegions = suppressTextRegionDuplicates(regions)
            val connectedGroups = groupConnectedTextRegions(
                detection = shapeDetections[shapeIndex],
                regions = uniqueRegions,
                source = source
            )
            logStage(
                stage = "GROUPING",
                outcome = "INFO",
                detail = "shape=$shapeIndex regions=${uniqueRegions.size}, groups=${connectedGroups.size}",
                text = uniqueRegions.joinToString(" ") { it.text }
            )
            val groupAnchors = connectedGroups.map(::mergeTextRegions)
            connectedGroups.forEachIndexed { groupIndex, group ->
                val groupAnchor = groupAnchors[groupIndex]
                val groupKind = if (group.all { region ->
                        decisionsByRegion[region]?.kind == CandidateKind.CAPTION
                    }
                ) {
                    CandidateKind.CAPTION
                } else {
                    CandidateKind.SPEECH_BALLOON
                }
                if (groupKind == CandidateKind.CAPTION) {
                    // The ONNX model is trained to propose speech-balloon
                    // shapes. When OCR independently confirms a rectangular
                    // narration caption, inheriting that proposal's mask can
                    // pull adjacent panel artwork into the cutout and make the
                    // otherwise-correct caption fail FINAL_ALPHA as
                    // `caption-busy-and-broad`. Rebuild confirmed captions
                    // from their native printed surface instead. Keep the
                    // original OCR blocks so text-alpha validation still
                    // audits every recognized line, not only their union box.
                    hybrid += ocrFallbackDetection(
                        region = groupAnchor,
                        source = source,
                        enclosureBounds = null,
                        candidateKind = CandidateKind.CAPTION
                    ).copy(
                        textRegion = groupAnchor,
                        textRegions = group
                    )
                    logStage(
                        stage = "CAPTION_MASK",
                        outcome = "NATIVE_SURFACE",
                        detail = "shape=$shapeIndex group=$groupIndex regions=${group.size}",
                        text = group.joinToString(" ") { it.text }
                    )
                } else {
                    // Speech balloons retain the model mask, ownership
                    // partition and V28 connected-lobe recovery unchanged.
                    hybrid += shapeDetections[shapeIndex].copy(
                        textRegion = groupAnchor,
                        textRegions = group,
                        partitionGroupId = shapeIndex,
                        partitionRegions = groupAnchors,
                        partitionRegionGroups = connectedGroups,
                        partitionRegionIndex = groupIndex,
                        candidateKind = groupKind
                    )
                }
            }
        }

        fallbackCandidates.forEach { fallback ->
            hybrid += ocrFallbackDetection(
                region = fallback.region,
                source = source,
                enclosureBounds = fallback.decision.enclosureBounds,
                candidateKind = fallback.decision.kind
            )
        }

        // OCR occasionally misses tiny or stylized text. Strong quiet proposals
        // retain the established path; medium-confidence proposals now need an
        // independently closed model contour before they can be recovered.
        shapeDetections.forEachIndexed { index, detection ->
            if (assignments[index] != 0) return@forEachIndexed
            val quietInterior = hasQuietDialogueInterior(detection, source)
            val closedModelContour = hasClosedModelContour(detection)
            val strongAcceptance = detection.confidence >= MODEL_ONLY_STRONG_CONFIDENCE &&
                    quietInterior
            val safeRecovery = detection.confidence >= MODEL_ONLY_RECOVERY_CONFIDENCE &&
                    quietInterior &&
                    closedModelContour
            val accepted = strongAcceptance || safeRecovery
            if (accepted) {
                logStage(
                    stage = "MODEL_ONLY",
                    outcome = "ACCEPT",
                    detail = "confidence=${formatScore(detection.confidence)}, " +
                            "quiet=$quietInterior, closed=$closedModelContour"
                )
                hybrid += detection
            } else {
                logStage(
                    stage = "MODEL_ONLY",
                    outcome = "REJECT",
                    detail = "confidence=${formatScore(detection.confidence)}, " +
                            "quiet=$quietInterior, closed=$closedModelContour"
                )
            }
        }

        return suppressHybridDuplicates(hybrid)
    }

    /**
     * Classifies OCR before any cutout is rendered. Geometry remains the main
     * signal: text must either belong to a model-supported balloon or sit
     * inside a native rectangular caption surface. Lightweight text semantics
     * are used only to reject obvious signs and vocal/SFX fragments when that
     * enclosure evidence is absent.
     */
    private fun classifyTextCandidate(
        region: DialogueTextRegion,
        detection: DetectedBubble?,
        matchScore: Float,
        source: Bitmap
    ): CandidateDecision {
        val rectangleEvidence = rectangularCaptionEvidence(region, source)
        val captionText = region.isCaptionFallbackCandidate()
        val shortCaptionText = region.isShortFramedCaptionCandidate()
        val structuralLabel = region.isStructuralLabel()
        val environmentalLabel = looksLikeEnvironmentalLabel(region)
        val soundEffect = looksLikeSoundEffect(region)
        val editorialOrDecorativeText = looksLikeEditorialOrDecorativeText(region)
        val captionSurfaceEvidence = if (captionText || shortCaptionText) {
            solidCaptionSurfaceEvidence(region, source)
        } else 0f
        val pageEdgeAligned =
            region.left <= source.width * EDGE_CAPTION_PAGE_MARGIN_RATIO ||
                    region.right >= source.width * (1f - EDGE_CAPTION_PAGE_MARGIN_RATIO) ||
                    region.top <= source.height * EDGE_CAPTION_PAGE_MARGIN_RATIO ||
                    region.bottom >= source.height * (1f - EDGE_CAPTION_PAGE_MARGIN_RATIO)
        // A caption printed against the page/panel boundary can lose one or
        // two measurable frame sides even though its blue surface remains
        // unmistakably solid. Keep this recovery narrow: it is only available
        // to substantial multi-line narration at a page edge.
        val edgeCaptionSurfaceConfirmed =
            captionText &&
                    region.wordCount >= 8 &&
                    region.lineCount >= 2 &&
                    pageEdgeAligned &&
                    rectangleEvidence >= EDGE_CAPTION_MIN_FRAME_EVIDENCE &&
                    captionSurfaceEvidence >= EDGE_CAPTION_MIN_SURFACE_EVIDENCE
        val standardCaptionSurfaceConfirmed =
            captionText &&
                    ((rectangleEvidence >= CAPTION_MIN_FRAME_EVIDENCE &&
                            captionSurfaceEvidence >= CAPTION_MIN_SURFACE_EVIDENCE) ||
                            edgeCaptionSurfaceConfirmed)
        // Short captions require substantially stronger visual proof and must
        // not replace a confidently model-supported speech balloon. This makes
        // the path additive for labels such as "THE BRACELETS." while leaving
        // established speech detections untouched.
        val shortCaptionSurfaceConfirmed =
            shortCaptionText &&
                    !structuralLabel &&
                    !environmentalLabel &&
                    !soundEffect &&
                    !editorialOrDecorativeText &&
                    rectangleEvidence >= SHORT_CAPTION_MIN_FRAME_EVIDENCE &&
                    captionSurfaceEvidence >= SHORT_CAPTION_MIN_SURFACE_EVIDENCE &&
                    (detection == null ||
                            detection.confidence <= SHORT_CAPTION_MAX_SHAPE_CONFIDENCE)
        val captionSurfaceConfirmed =
            standardCaptionSurfaceConfirmed || shortCaptionSurfaceConfirmed
        // A narration box can have one interrupted or low-contrast printed
        // edge. Straight-frame evidence therefore remains mandatory, but a
        // stable solid-color surface can recover the score instead of asking
        // the box to resemble a closed speech balloon.
        val captionEvidence = if (captionSurfaceConfirmed) {
            max(
                rectangleEvidence,
                rectangleEvidence * 0.45f + captionSurfaceEvidence * 0.55f
            )
        } else rectangleEvidence
        val needsNativeBalloonRecovery = detection == null ||
                (detection.confidence < 0.45f && matchScore < 0.60f)
        val balloonEnclosure = if (needsNativeBalloonRecovery) {
            nativeBalloonEnclosureEvidence(region, source)
        } else null
        val balloonEvidence = balloonEnclosure?.score ?: 0f
        val shapeConfidence = detection?.confidence ?: 0f
        val quietSupportedShape = detection != null &&
                detection.confidence >= 0.24f &&
                hasQuietDialogueInterior(detection, source)
        val candidateConfidence = max(
            max(captionEvidence * 0.92f, balloonEvidence * 0.90f),
            max(
                matchScore * 0.52f +
                        shapeConfidence * 0.34f +
                        (if (quietSupportedShape) 0.14f else 0f),
                shapeConfidence * 0.68f +
                        captionEvidence * 0.24f +
                        (if (quietSupportedShape) 0.08f else 0f)
            )
        ).coerceIn(0f, 1f)

        if (structuralLabel) {
            return CandidateDecision(
                CandidateKind.ENVIRONMENT,
                false,
                candidateConfidence,
                "structural page label"
            )
        }

        if (editorialOrDecorativeText && rectangleEvidence < 0.76f &&
            balloonEvidence < 0.76f &&
            (detection == null || detection.confidence < 0.74f)
        ) {
            return CandidateDecision(
                CandidateKind.ENVIRONMENT,
                false,
                candidateConfidence,
                "editorial/decorative text without strong enclosure"
            )
        }

        if (environmentalLabel && rectangleEvidence < 0.72f &&
            balloonEvidence < 0.72f &&
            (detection == null || detection.confidence < 0.68f)
        ) {
            return CandidateDecision(
                CandidateKind.ENVIRONMENT,
                false,
                candidateConfidence,
                "sign-like text without enclosure; frame=${formatScore(rectangleEvidence)}, " +
                        "balloon=${formatScore(balloonEvidence)}"
            )
        }
        if (soundEffect && rectangleEvidence < 0.62f &&
            balloonEvidence < 0.70f &&
            (detection == null || detection.confidence < 0.76f)
        ) {
            return CandidateDecision(
                CandidateKind.SOUND_EFFECT,
                false,
                candidateConfidence,
                "short/stylized effect without reliable enclosure"
            )
        }

        if (detection != null) {
            val strongShape = detection.confidence >= 0.45f
            val strongMatch = matchScore >= 0.60f
            val confirmed = strongShape || strongMatch ||
                    (quietSupportedShape && detection.confidence >= 0.30f) ||
                    captionEvidence >= 0.52f ||
                    balloonEvidence >= 0.62f
            val recoverable = candidateConfidence >= 0.44f &&
                    (
                            matchScore >= 0.42f ||
                                    detection.confidence >= 0.24f ||
                                    captionEvidence >= 0.44f ||
                                    balloonEvidence >= NATIVE_BALLOON_RECOVERY_THRESHOLD
                            )
            val standardCaptionPreferred =
                captionText &&
                        (standardCaptionSurfaceConfirmed || captionEvidence >= 0.62f) &&
                        captionEvidence >= balloonEvidence * 0.86f
            val shortCaptionPreferred =
                shortCaptionSurfaceConfirmed &&
                        captionEvidence >= balloonEvidence * 0.86f
            val kind = if (standardCaptionPreferred || shortCaptionPreferred) {
                CandidateKind.CAPTION
            } else {
                CandidateKind.SPEECH_BALLOON
            }
            if (confirmed || recoverable) {
                return CandidateDecision(
                    kind,
                    true,
                    candidateConfidence,
                    "${if (confirmed) "confirmed" else "confidence recovery"}; " +
                            "shape=${formatScore(detection.confidence)}, " +
                            "match=${formatScore(matchScore)}, frame=${formatScore(rectangleEvidence)}, " +
                            "surface=${formatScore(captionSurfaceEvidence)}, " +
                            "caption=${formatScore(captionEvidence)}, " +
                            "balloon=${formatScore(balloonEvidence)}, " +
                            "edgeFrame=$edgeCaptionSurfaceConfirmed, " +
                            "shortFrame=$shortCaptionSurfaceConfirmed"
                )
            }
            return CandidateDecision(
                CandidateKind.UNKNOWN,
                false,
                candidateConfidence,
                "weak balloon and enclosure evidence"
            )
        }

        val standardOcrCaption =
            captionText &&
                    (rectangleEvidence >= 0.44f || standardCaptionSurfaceConfirmed) &&
                    captionEvidence >= balloonEvidence * 0.86f
        val shortOcrCaption =
            shortCaptionSurfaceConfirmed &&
                    captionEvidence >= balloonEvidence * 0.86f
        return if (standardOcrCaption || shortOcrCaption
        ) {
            CandidateDecision(
                CandidateKind.CAPTION,
                true,
                candidateConfidence,
                "OCR-first ${if (shortOcrCaption) "short-framed" else if (captionEvidence >= 0.52f) "confirmed" else "recovered"} " +
                        "caption; frame=${formatScore(rectangleEvidence)}, " +
                        "surface=${formatScore(captionSurfaceEvidence)}, " +
                        "edgeFrame=$edgeCaptionSurfaceConfirmed, " +
                        "combined=${formatScore(captionEvidence)}"
            )
        } else if (
            !environmentalLabel &&
            !soundEffect &&
            balloonEvidence >= NATIVE_BALLOON_RECOVERY_THRESHOLD
        ) {
            CandidateDecision(
                CandidateKind.SPEECH_BALLOON,
                true,
                candidateConfidence,
                "OCR-first closed balloon (${formatScore(balloonEvidence)})",
                enclosureBounds = balloonEnclosure?.bounds
            )
        } else {
            CandidateDecision(
                CandidateKind.UNKNOWN,
                false,
                candidateConfidence,
                "OCR text has no reliable enclosure; frame=${formatScore(rectangleEvidence)}, " +
                        "surface=${formatScore(captionSurfaceEvidence)}, " +
                        "balloon=${formatScore(balloonEvidence)}"
            )
        }
    }

    /**
     * Measures whether OCR sits on a quiet, solid-color printed surface.
     *
     * The sample ring deliberately sits just outside the OCR rectangle, where
     * letter pixels cannot dominate the histogram. This is the second half of
     * caption recovery: it never accepts text on its own, and is only used when
     * [rectangularCaptionEvidence] also finds aligned frame structure.
     */
    private fun solidCaptionSurfaceEvidence(
        region: DialogueTextRegion,
        source: Bitmap
    ): Float {
        if (source.width < 8 || source.height < 8) return 0f
        val lineHeight = (region.height / region.lineCount.coerceAtLeast(1))
            .coerceAtLeast(3f)
        fun evidenceAt(paddingRatio: Float): Float {
            // Search several narrow rings. A single large ring can cross the
            // real caption edge and become dominated by the panel beside it.
            val paddingX = max(
                max(2f, lineHeight * paddingRatio),
                region.width * 0.012f
            ).roundToInt()
            val paddingY = max(2f, lineHeight * paddingRatio).roundToInt()
            val left = (region.left.roundToInt() - paddingX)
                .coerceIn(0, source.width - 1)
            val top = (region.top.roundToInt() - paddingY)
                .coerceIn(0, source.height - 1)
            val right = (region.right.roundToInt() + paddingX)
                .coerceIn(left + 1, source.width)
            val bottom = (region.bottom.roundToInt() + paddingY)
                .coerceIn(top + 1, source.height)
            val textLeft = region.left.roundToInt().coerceIn(left, right)
            val textTop = region.top.roundToInt().coerceIn(top, bottom)
            val textRight = region.right.roundToInt().coerceIn(textLeft, right)
            val textBottom = region.bottom.roundToInt().coerceIn(textTop, bottom)
            val sampleStep = max(1, max(right - left, bottom - top) / 180)

            val ringHistogram = IntArray(4096)
            var ringSamples = 0
            var y = top
            while (y < bottom) {
                var x = left
                while (x < right) {
                    val outsideText = x < textLeft || x >= textRight ||
                            y < textTop || y >= textBottom
                    if (outsideText) {
                        val color = source.getPixel(x, y)
                        val key = ((Color.red(color) shr 4) shl 8) or
                                ((Color.green(color) shr 4) shl 4) or
                                (Color.blue(color) shr 4)
                        ringHistogram[key]++
                        ringSamples++
                    }
                    x += sampleStep
                }
                y += sampleStep
            }
            if (ringSamples < 20) return 0f
            val dominantKey = ringHistogram.indices.maxByOrNull { ringHistogram[it] }
                ?: return 0f
            val dominantColor = Color.rgb(
                ((dominantKey shr 8) and 0xF) * 16 + 8,
                ((dominantKey shr 4) and 0xF) * 16 + 8,
                (dominantKey and 0xF) * 16 + 8
            )

            var bodySamples = 0
            var bodyMatches = 0
            var ringMatches = 0
            y = top
            while (y < bottom) {
                var x = left
                while (x < right) {
                    val color = source.getPixel(x, y)
                    val matchesSurface = colorDistance(color, dominantColor) <= 58
                    val outsideText = x < textLeft || x >= textRight ||
                            y < textTop || y >= textBottom
                    bodySamples++
                    if (matchesSurface) {
                        bodyMatches++
                        if (outsideText) ringMatches++
                    }
                    x += sampleStep
                }
                y += sampleStep
            }
            val bodyRatio = bodyMatches.toFloat() / bodySamples.coerceAtLeast(1)
            val ringRatio = ringMatches.toFloat() / ringSamples
            val dominantRatio = ringHistogram[dominantKey].toFloat() / ringSamples
            if (bodyRatio < 0.30f || ringRatio < 0.40f || dominantRatio < 0.16f) {
                return 0f
            }
            return (
                    ringRatio * 0.58f +
                            bodyRatio * 0.27f +
                            (dominantRatio / 0.55f).coerceIn(0f, 1f) * 0.15f
                    ).coerceIn(0f, 1f)
        }

        return floatArrayOf(0.15f, 0.25f, 0.40f, 0.55f)
            .maxOf(::evidenceAt)
    }

    /**
     * Searches several bands around OCR for a native rectangular enclosure.
     * A real caption usually produces long, aligned edge responses on at least
     * three sides. Letter strokes, textured scenery and painted signs produce
     * short or inconsistent responses and therefore score much lower.
     */
    private fun rectangularCaptionEvidence(
        region: DialogueTextRegion,
        source: Bitmap
    ): Float {
        if (source.width < 4 || source.height < 4) return 0f
        val lineHeight = (region.height / region.lineCount.coerceAtLeast(1)).coerceAtLeast(3f)
        val expansions = floatArrayOf(0.35f, 0.55f, 0.80f, 1.10f, 1.45f)
        var best = 0f

        fun gradientAt(x: Int, y: Int): Int {
            val safeX = x.coerceIn(1, source.width - 2)
            val safeY = y.coerceIn(1, source.height - 2)
            val horizontal = colorDistance(
                source.getPixel(safeX - 1, safeY),
                source.getPixel(safeX + 1, safeY)
            )
            val vertical = colorDistance(
                source.getPixel(safeX, safeY - 1),
                source.getPixel(safeX, safeY + 1)
            )
            return max(horizontal, vertical)
        }

        fun horizontalSideScore(y: Int, left: Int, right: Int): Float {
            if (right - left < 6) return 0f
            val step = max(1, (right - left) / 72)
            var samples = 0
            var moderate = 0
            var strong = 0
            var x = left
            while (x <= right) {
                val gradient = gradientAt(x, y)
                if (gradient >= 42) moderate++
                if (gradient >= 82) strong++
                samples++
                x += step
            }
            return if (samples == 0) 0f else
                (moderate.toFloat() / samples) * 0.65f +
                        (strong.toFloat() / samples) * 0.35f
        }

        fun verticalSideScore(x: Int, top: Int, bottom: Int): Float {
            if (bottom - top < 6) return 0f
            val step = max(1, (bottom - top) / 72)
            var samples = 0
            var moderate = 0
            var strong = 0
            var y = top
            while (y <= bottom) {
                val gradient = gradientAt(x, y)
                if (gradient >= 42) moderate++
                if (gradient >= 82) strong++
                samples++
                y += step
            }
            return if (samples == 0) 0f else
                (moderate.toFloat() / samples) * 0.65f +
                        (strong.toFloat() / samples) * 0.35f
        }

        expansions.forEach { expansion ->
            val paddingY = max(3, (lineHeight * expansion).roundToInt())
            val paddingX = max(
                paddingY,
                (region.width * (0.10f + expansion * 0.10f)).roundToInt()
            )
            val left = (region.left.roundToInt() - paddingX).coerceIn(1, source.width - 2)
            val top = (region.top.roundToInt() - paddingY).coerceIn(1, source.height - 2)
            val right = (region.right.roundToInt() + paddingX).coerceIn(left + 1, source.width - 2)
            val bottom = (region.bottom.roundToInt() + paddingY)
                .coerceIn(top + 1, source.height - 2)
            val sideScores = floatArrayOf(
                horizontalSideScore(top, left, right),
                horizontalSideScore(bottom, left, right),
                verticalSideScore(left, top, bottom),
                verticalSideScore(right, top, bottom)
            ).sortedDescending()
            // Three aligned sides are sufficient because tails, gutters or
            // overlapping artwork often interrupt one legitimate caption edge.
            val threeSideScore = (
                    sideScores[0] * 0.38f +
                            sideScores[1] * 0.34f +
                            sideScores[2] * 0.28f
                    ).coerceIn(0f, 1f)
            best = max(best, threeSideScore)
        }
        return best
    }

    /**
     * Looks for a closed, curved native-ink ring around OCR that the ONNX model
     * either missed or localized too loosely. Each radial sample searches a
     * narrow band, which tolerates hand-drawn and irregular balloons without
     * confusing a single underline, wall edge or letter stroke for enclosure.
     */
    private fun nativeBalloonEnclosureEvidence(
        region: DialogueTextRegion,
        source: Bitmap
    ): NativeEnclosureEvidence? {
        if (source.width < 12 || source.height < 12) return null
        val lineHeight = (region.height / region.lineCount.coerceAtLeast(1)).coerceAtLeast(3f)
        val centerX = region.centerX
        val centerY = region.centerY
        val expansions = arrayOf(
            0.55f to 0.50f,
            0.85f to 0.72f,
            1.15f to 0.95f,
            1.55f to 1.25f,
            2.00f to 1.60f
        )
        var best: NativeEnclosureEvidence? = null

        fun gradientAt(x: Int, y: Int): Int {
            val safeX = x.coerceIn(1, source.width - 2)
            val safeY = y.coerceIn(1, source.height - 2)
            val horizontal = colorDistance(
                source.getPixel(safeX - 1, safeY),
                source.getPixel(safeX + 1, safeY)
            )
            val vertical = colorDistance(
                source.getPixel(safeX, safeY - 1),
                source.getPixel(safeX, safeY + 1)
            )
            return max(horizontal, vertical)
        }

        expansions.forEach { (horizontalExpansion, verticalExpansion) ->
            val radiusX = region.width * 0.5f + max(
                lineHeight * horizontalExpansion,
                region.width * 0.10f
            )
            val radiusY = region.height * 0.5f + lineHeight * verticalExpansion
            if (radiusX < 4f || radiusY < 4f) return@forEach
            val left = centerX - radiusX
            val top = centerY - radiusY
            val right = centerX + radiusX
            val bottom = centerY + radiusY
            // A cropped ring is not evidence of a closed balloon.
            if (left < 2f || top < 2f || right > source.width - 3f ||
                bottom > source.height - 3f
            ) return@forEach

            val samples = 96
            val band = (lineHeight * 0.22f).roundToInt().coerceIn(2, 10)
            var moderate = 0
            var strong = 0
            val quadrantModerate = IntArray(4)
            val quadrantSamples = IntArray(4)
            repeat(samples) { sample ->
                val angle = 2.0 * PI * sample / samples
                val cosAngle = cos(angle).toFloat()
                val sinAngle = sin(angle).toFloat()
                var strongestGradient = 0
                for (offset in -band..band) {
                    val x = (centerX + cosAngle * (radiusX + offset)).roundToInt()
                    val y = (centerY + sinAngle * (radiusY + offset)).roundToInt()
                    strongestGradient = max(strongestGradient, gradientAt(x, y))
                }
                val quadrant = (sample * 4 / samples).coerceIn(0, 3)
                quadrantSamples[quadrant]++
                if (strongestGradient >= 44) {
                    moderate++
                    quadrantModerate[quadrant]++
                }
                if (strongestGradient >= 84) strong++
            }

            // A real balloon surface is usually much quieter than the artwork
            // outside it. This also stops circular logos and busy signs from
            // passing on outline geometry alone.
            val interiorHistogram = IntArray(4096)
            var interiorSamples = 0
            val interiorStep = max(1, (max(radiusX, radiusY) / 28f).roundToInt())
            var y = (top + radiusY * 0.20f).roundToInt()
            val interiorBottom = (bottom - radiusY * 0.20f).roundToInt()
            while (y <= interiorBottom) {
                var x = (left + radiusX * 0.20f).roundToInt()
                val interiorRight = (right - radiusX * 0.20f).roundToInt()
                while (x <= interiorRight) {
                    val normalizedX = (x - centerX) / radiusX
                    val normalizedY = (y - centerY) / radiusY
                    if (normalizedX * normalizedX + normalizedY * normalizedY <= 0.72f * 0.72f) {
                        val color = source.getPixel(x, y)
                        val key = ((Color.red(color) shr 4) shl 8) or
                                ((Color.green(color) shr 4) shl 4) or
                                (Color.blue(color) shr 4)
                        interiorHistogram[key]++
                        interiorSamples++
                    }
                    x += interiorStep
                }
                y += interiorStep
            }
            val quietRatio = if (interiorSamples > 0) {
                (interiorHistogram.maxOrNull() ?: 0).toFloat() / interiorSamples
            } else 0f
            val moderateRatio = moderate.toFloat() / samples
            val strongRatio = strong.toFloat() / samples
            val weakestQuadrant = quadrantModerate.indices.minOf { quadrant ->
                quadrantModerate[quadrant].toFloat() /
                        quadrantSamples[quadrant].coerceAtLeast(1)
            }
            if (moderateRatio < 0.42f || weakestQuadrant < 0.25f || quietRatio < 0.13f) {
                return@forEach
            }
            val score = (
                    moderateRatio * 0.48f +
                            strongRatio * 0.22f +
                            weakestQuadrant * 0.20f +
                            quietRatio.coerceAtMost(0.55f) / 0.55f * 0.10f
                    ).coerceIn(0f, 1f)
            val evidence = NativeEnclosureEvidence(
                score = score,
                bounds = SourceBounds(left, top, right, bottom)
            )
            if (best == null || evidence.score > best!!.score) best = evidence
        }
        return best
    }

    /** Safe recovery for model proposals whose OCR text was missed entirely. */
    private fun hasClosedModelContour(detection: DetectedBubble): Boolean {
        val mask = detection.createProbabilityMask() ?: return false
        val samplesPerAxis = 32
        var interiorSamples = 0
        var interiorSupported = 0
        var centerSamples = 0
        var centerSupported = 0
        var edgeSamples = 0
        var edgeSupported = 0
        for (yIndex in 0 until samplesPerAxis) {
            val yRatio = (yIndex + 0.5f) / samplesPerAxis
            val sourceY = detection.top + detection.height * yRatio
            for (xIndex in 0 until samplesPerAxis) {
                val xRatio = (xIndex + 0.5f) / samplesPerAxis
                val sourceX = detection.left + detection.width * xRatio
                val probability = detection.maskProbability(mask, sourceX, sourceY)
                val onEdge = xIndex <= 1 || yIndex <= 1 ||
                        xIndex >= samplesPerAxis - 2 || yIndex >= samplesPerAxis - 2
                if (onEdge) {
                    edgeSamples++
                    if (probability >= 0.44f) edgeSupported++
                } else {
                    interiorSamples++
                    if (probability >= 0.44f) interiorSupported++
                    if (xRatio in 0.28f..0.72f && yRatio in 0.28f..0.72f) {
                        centerSamples++
                        if (probability >= 0.44f) centerSupported++
                    }
                }
            }
        }
        val interiorRatio = interiorSupported.toFloat() / interiorSamples.coerceAtLeast(1)
        val centerRatio = centerSupported.toFloat() / centerSamples.coerceAtLeast(1)
        val edgeRatio = edgeSupported.toFloat() / edgeSamples.coerceAtLeast(1)
        return interiorRatio in 0.24f..0.92f &&
                centerRatio >= 0.38f &&
                edgeRatio <= 0.52f &&
                centerRatio >= edgeRatio + 0.10f
    }

    private fun looksLikeEnvironmentalLabel(region: DialogueTextRegion): Boolean {
        val text = region.text.trim()
        val words = text.uppercase()
            .split(Regex("[^A-Z0-9]+"))
            .filter(String::isNotBlank)
        if (words.isEmpty() || words.size > 5 || region.lineCount > 2) return false
        val letters = text.count(Char::isLetter)
        val uppercaseLetters = text.count(Char::isUpperCase)
        val mostlyUppercase = letters >= 4 && uppercaseLetters.toFloat() / letters >= 0.88f
        val hasDialoguePunctuation = text.any { it in "?!.,\"'…" }
        val signWords = setOf(
            "CITY", "HALL", "STREET", "AVENUE", "ROAD", "PRISON",
            "PENITENTIARY", "HOSPITAL", "POLICE", "STATION", "WAREHOUSE",
            "BUILDING", "LAB", "LABS", "ENTER", "EXIT", "WELCOME", "HOTEL",
            "MARINE", "MARINES", "ARMY", "NAVY", "AIRFORCE", "MILITARY",
            "SHERIFF", "SECURITY", "FIRE", "AMBULANCE"
        )
        val compact = words.joinToString("")
        val fuzzyPolice = compact == "OCE" || compact.endsWith("POLICE") ||
                (compact.length in 4..7 && compact.endsWith("LICE"))
        return !hasDialoguePunctuation &&
                ((mostlyUppercase && words.any(signWords::contains)) || fuzzyPolice)
    }

    /**
     * Rejects publishing furniture and page furniture before the high-recall
     * fallback can promote it. OCR commonly confuses DAY 27 as "ÞAY Z7" and
     * TO BE CONTINUED as "TO B$ KONTINU", so this intentionally uses a small
     * confusable-normalized vocabulary rather than exact strings.
     */
    private fun looksLikeEditorialOrDecorativeText(region: DialogueTextRegion): Boolean {
        val upper = region.text.uppercase()
        val normalized = upper
            .replace(0x00DE.toChar(), 'D')
            .replace('$', 'E')
            .replace('0', 'O')
            .replace(Regex("[^A-Z0-9]+"), " ")
            .trim()
        val compact = normalized.replace(" ", "")
        val dayMarker = Regex("^DAY[0-9Z]{1,4}$").matches(compact)
        val continuedMarker = compact.startsWith("TOBE") &&
                (compact.contains("CONTINU") || compact.contains("KONTINU"))
        val creditWords = setOf(
            "VARIANT", "COVER", "CREDITS", "WRITER", "WRITTEN", "ARTIST",
            "PENCILS", "INKS", "COLORS", "COLORIST", "LETTERS", "LETTERER",
            "EDITOR", "PRESENTS", "SPECIAL", "COPYRIGHT"
        )
        val words = normalized.split(' ').filter(String::isNotBlank)
        val creditHits = words.count(creditWords::contains)
        val publisherToken = words.any {
            it in setOf("DC", "MARVEL", "IMAGE", "DARKHORSE", "BOOM")
        }
        val copyrightLike = upper.contains(0x00A9.toChar()) || upper.contains("(C)") ||
                upper.contains("TM &")
        return dayMarker || continuedMarker || copyrightLike ||
                (creditHits >= 2) ||
                (creditHits >= 1 && publisherToken) ||
                (creditHits >= 1 && region.wordCount >= 5 && region.lineCount >= 2)
    }

    private fun looksLikeSoundEffect(region: DialogueTextRegion): Boolean {
        val compact = region.text.uppercase().replace(Regex("[^A-Z]"), "")
        if (compact.isBlank() || compact.length > 18 || region.wordCount > 3) return false
        val knownEffects = setOf(
            "ACK", "AGH", "AH", "AHH", "ARGH", "BAM", "BOOM", "BANG",
            "CRASH", "GASP", "GRR", "HA", "HEH", "HNG", "NGH", "OOF",
            "POW", "RAH", "RAHH", "UGH", "UNH", "WHAM", "WHUMP", "TWOK"
        )
        val repeatedRun = Regex("(.)\\1{2,}").containsMatchIn(compact)
        return compact in knownEffects || repeatedRun
    }

    /**
     * High-recall admission gate for the presentation pipeline.
     *
     * Classification remains responsible for identifying exact captions and
     * speech balloons. This gate is used only after that classifier returns
     * UNKNOWN. Known page labels, signs and sound effects stay rejected, while
     * language-like OCR is allowed to continue to mask extraction. A single
     * unpunctuated word needs independent model support; an OCR-only fragment
     * needs sentence punctuation, multiple words or multiple lines. This keeps
     * the fallback general without reintroducing the tiny "AA" hallucination.
     */
    private fun isAutomaticPresentationCandidate(
        region: DialogueTextRegion,
        matchedDetection: DetectedBubble?
    ): Boolean {
        if (region.isStructuralLabel() ||
            looksLikeEnvironmentalLabel(region) ||
            looksLikeSoundEffect(region) ||
            looksLikeEditorialOrDecorativeText(region)
        ) {
            return false
        }
        val trimmed = region.text.trim()
        val letters = trimmed.count(Char::isLetter)
        val terminalPunctuation = trimmed.lastOrNull()?.let { it in ".?!…" } == true
        if (letters < 2 || (letters < 3 && matchedDetection == null)) return false
        val readableCharacters = trimmed.count { it.isLetterOrDigit() || it.isWhitespace() || it in ".,?!…'\"-—:" }
        if (readableCharacters.toFloat() / trimmed.length.coerceAtLeast(1) < 0.68f) return false

        val languageLike = region.wordCount >= 2 ||
                region.lineCount >= 2 ||
                (terminalPunctuation && trimmed.length >= 3)
        val modelBackedSingleWord = matchedDetection != null &&
                matchedDetection.confidence >= CONFIDENCE_THRESHOLD &&
                letters >= 3
        return languageLike || modelBackedSingleWord
    }

    private fun hasQuietDialogueInterior(
        detection: DetectedBubble,
        source: Bitmap
    ): Boolean {
        val left = (detection.left + detection.width * 0.16f).roundToInt()
            .coerceIn(0, source.width - 1)
        val top = (detection.top + detection.height * 0.16f).roundToInt()
            .coerceIn(0, source.height - 1)
        val right = (detection.right - detection.width * 0.16f).roundToInt()
            .coerceIn(left + 1, source.width)
        val bottom = (detection.bottom - detection.height * 0.16f).roundToInt()
            .coerceIn(top + 1, source.height)
        val histogram = IntArray(4096)
        var samples = 0
        val step = max(1, max(right - left, bottom - top) / 96)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val color = source.getPixel(x, y)
                val key = ((Color.red(color) shr 4) shl 8) or
                        ((Color.green(color) shr 4) shl 4) or
                        (Color.blue(color) shr 4)
                histogram[key]++
                samples++
                x += step
            }
            y += step
        }
        if (samples < 16) return false
        val dominantRatio = (histogram.maxOrNull() ?: 0).toFloat() / samples
        return dominantRatio >= 0.24f
    }

    private fun formatScore(value: Float): String =
        ((value * 100f).roundToInt() / 100f).toString()

    private fun logStage(
        stage: String,
        outcome: String,
        detail: String,
        text: String = ""
    ) {
        val textSuffix = if (text.isBlank()) "" else "; text=\"${text.take(80)}\""
        Log.d(DIAGNOSTIC_TAG, "stage=$stage outcome=$outcome $detail$textSuffix")
    }

    private fun suppressTextRegionDuplicates(
        regions: List<DialogueTextRegion>
    ): List<DialogueTextRegion> {
        val selected = mutableListOf<DialogueTextRegion>()
        regions.sortedWith(
            compareByDescending<DialogueTextRegion> { it.text.length }
                .thenByDescending { it.width * it.height }
        ).forEach { candidate ->
            val normalizedCandidate = normalizedText(candidate.text)
            val duplicate = selected.any { existing ->
                val normalizedExisting = normalizedText(existing.text)
                textRegionIoU(candidate, existing) > 0.56f ||
                        (normalizedCandidate.length >= 3 &&
                                normalizedCandidate == normalizedExisting)
            }
            if (!duplicate) selected += candidate
        }
        return selected
    }

    /**
     * Groups OCR blocks only when they share a stable, high-confidence mask
     * component. The bridge mask is eroded before it can join two markers, so
     * thin model noise and white panel gutters cannot masquerade as a speech
     * balloon connector.
     */
    private fun groupConnectedTextRegions(
        detection: DetectedBubble,
        regions: List<DialogueTextRegion>,
        source: Bitmap
    ): List<List<DialogueTextRegion>> {
        if (regions.size <= 1) return regions.map { listOf(it) }
        val raster = createShapeRaster(source, detection) ?: return regions.map { listOf(it) }
        val probabilityMask = detection.createProbabilityMask() ?: return regions.map { listOf(it) }
        val scaleX = raster.width / (raster.bounds.right - raster.bounds.left).coerceAtLeast(1f)
        val scaleY = raster.height / (raster.bounds.bottom - raster.bounds.top).coerceAtLeast(1f)
        val coreShape = BooleanArray(raster.pixels.size)
        val bridgeShape = BooleanArray(raster.pixels.size)
        for (index in raster.pixels.indices) {
            val x = index % raster.width
            val y = index / raster.width
            val sourceX = raster.bounds.left + (x + 0.5f) / scaleX
            val sourceY = raster.bounds.top + (y + 0.5f) / scaleY
            val probability = detection.maskProbability(probabilityMask, sourceX, sourceY)
            coreShape[index] = probability >= CONNECTED_GROUP_CORE_THRESHOLD
            bridgeShape[index] = probability >= CONNECTED_GROUP_BRIDGE_THRESHOLD
        }
        val stableBridgeShape = erodeBinaryMask(bridgeShape, raster.width, raster.height)
        val coreLabels = labelConnectedComponents(coreShape, raster.width, raster.height)
        val bridgeLabels = labelConnectedComponents(
            stableBridgeShape,
            raster.width,
            raster.height
        )

        val occupiedSeeds = BooleanArray(raster.pixels.size)
        val markers = regions.map { region ->
            findPartitionMarker(
                pixels = raster.pixels,
                width = raster.width,
                height = raster.height,
                bounds = raster.bounds,
                region = region,
                occupied = occupiedSeeds,
                allowed = bridgeShape
            )?.also { occupiedSeeds[it.index] = true }
        }
        val componentMembership = markers.map { marker ->
            if (marker == null) ComponentMembership()
            else ComponentMembership(
                core = nearestComponentLabel(marker.index, coreLabels, raster.width, raster.height),
                bridge = nearestComponentLabel(
                    marker.index,
                    bridgeLabels,
                    raster.width,
                    raster.height
                )
            )
        }
        // A coarse ONNX component can span two nearby white balloons. Confirm
        // that their native fill pixels are genuinely connected before they
        // are allowed to become one dialogue item.
        val nativeComponents = markers.map { marker ->
            marker?.let {
                traceFillComponent(
                    pixels = raster.pixels,
                    width = raster.width,
                    height = raster.height,
                    marker = it,
                    allowed = bridgeShape
                )
            }
        }
        val nativeContainments = nativeComponents.map { component ->
            component?.let { sourceSurfaceContainment(it, raster.width, raster.height) }
        }

        val parent = IntArray(regions.size) { it }
        fun rootOf(value: Int): Int {
            var root = value
            while (parent[root] != root) root = parent[root]
            var current = value
            while (parent[current] != current) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }
        fun join(first: Int, second: Int) {
            val firstRoot = rootOf(first)
            val secondRoot = rootOf(second)
            if (firstRoot != secondRoot) parent[secondRoot] = firstRoot
        }

        markers.forEachIndexed { firstIndex, firstMarker ->
            if (firstMarker == null) return@forEachIndexed
            for (secondIndex in firstIndex + 1 until markers.size) {
                val secondMarker = markers[secondIndex] ?: continue
                val firstMembership = componentMembership[firstIndex]
                val secondMembership = componentMembership[secondIndex]
                val sharesCore = firstMembership.core >= 0 &&
                        firstMembership.core == secondMembership.core
                val sharesStableBridge = firstMembership.bridge >= 0 &&
                        firstMembership.bridge == secondMembership.bridge
                val sharesNativeFill = nativeComponents[firstIndex]
                    ?.getOrNull(secondMarker.index) == true ||
                        nativeComponents[secondIndex]?.getOrNull(firstMarker.index) == true
                val sharedSurface = when {
                    nativeComponents[firstIndex]?.getOrNull(secondMarker.index) == true ->
                        nativeComponents[firstIndex]
                    nativeComponents[secondIndex]?.getOrNull(firstMarker.index) == true ->
                        nativeComponents[secondIndex]
                    else -> null
                }
                val containment = when (sharedSurface) {
                    nativeComponents[firstIndex] -> nativeContainments[firstIndex]
                    nativeComponents[secondIndex] -> nativeContainments[secondIndex]
                    else -> null
                } ?: SourceConnectivity(false, 0, 0, 1f)
                val colorsCompatible = fillColorsCompatible(
                    firstMarker.dominantColor,
                    secondMarker.dominantColor
                )
                val adjacent = regionsLikelyAdjacent(
                    regions[firstIndex],
                    regions[secondIndex]
                )
                val joins = sharesNativeFill && containment.enclosed &&
                        (sharesCore || sharesStableBridge) && colorsCompatible && adjacent
                logStage(
                    stage = "SOURCE_CONNECTIVITY",
                    outcome = if (joins) "JOIN" else "SEPARATE",
                    detail = "pair=$firstIndex:$secondIndex, native=$sharesNativeFill, " +
                            "enclosed=${containment.enclosed}, sides=${containment.touchedSides}, " +
                            "edge=${formatScore(containment.edgeContactRatio)}, " +
                            "core=$sharesCore, bridge=$sharesStableBridge, colors=$colorsCompatible, " +
                            "adjacent=$adjacent",
                    text = "${regions[firstIndex].text} | ${regions[secondIndex].text}"
                )
                if (joins) {
                    join(firstIndex, secondIndex)
                }
            }
        }

        val groups = linkedMapOf<Int, MutableList<DialogueTextRegion>>()
        regions.forEachIndexed { index, region ->
            groups.getOrPut(rootOf(index)) { mutableListOf() }.add(region)
        }
        return groups.values.map { group ->
            group.sortedWith(compareBy<DialogueTextRegion>({ it.top }, { it.left }))
        }
    }

    /**
     * Rejects a native fill that is really the page/panel background. A real
     * shared balloon surface normally closes inside the padded proposal. Broad
     * white scenery often reaches several raster edges and was the main cause
     * of V31 joining two independent dialogue elements.
     */
    private fun sourceSurfaceContainment(
        component: BooleanArray,
        width: Int,
        height: Int
    ): SourceConnectivity {
        if (component.size != width * height || component.none { it }) {
            return SourceConnectivity(false, 0, 0, 1f)
        }
        var componentPixels = 0
        var edgeContacts = 0
        var top = false
        var bottom = false
        var left = false
        var right = false
        component.forEachIndexed { index, present ->
            if (!present) return@forEachIndexed
            componentPixels++
            val x = index % width
            val y = index / width
            if (y == 0) {
                top = true
                edgeContacts++
            }
            if (y == height - 1) {
                bottom = true
                edgeContacts++
            }
            if (x == 0) {
                left = true
                edgeContacts++
            }
            if (x == width - 1) {
                right = true
                edgeContacts++
            }
        }
        val touchedSides = listOf(top, bottom, left, right).count { it }
        val edgeRatio = edgeContacts.toFloat() / componentPixels.coerceAtLeast(1)
        val maximumEdgeContacts = max(8, (sqrt(componentPixels.toFloat()) * 0.28f).roundToInt())
        val enclosed = touchedSides <= 1 && edgeContacts <= maximumEdgeContacts && edgeRatio <= 0.025f
        return SourceConnectivity(enclosed, touchedSides, edgeContacts, edgeRatio)
    }

    /** Removes one-pixel and diagonal-only bridges while preserving real necks. */
    private fun erodeBinaryMask(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val eroded = BooleanArray(mask.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if (!mask[index]) continue
                var survives = true
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (!mask[(y + dy) * width + x + dx]) {
                            survives = false
                            break
                        }
                    }
                    if (!survives) break
                }
                eroded[index] = survives
            }
        }
        return eroded
    }

    private fun labelConnectedComponents(
        mask: BooleanArray,
        width: Int,
        height: Int
    ): IntArray {
        val labels = IntArray(mask.size) { -1 }
        val queue = IntArray(mask.size)
        var nextLabel = 0
        for (startIndex in mask.indices) {
            if (!mask[startIndex] || labels[startIndex] >= 0) continue
            var queueStart = 0
            var queueEnd = 0
            queue[queueEnd++] = startIndex
            labels[startIndex] = nextLabel
            while (queueStart < queueEnd) {
                val index = queue[queueStart++]
                val x = index % width
                val y = index / width
                fun enqueue(next: Int) {
                    if (mask[next] && labels[next] < 0) {
                        labels[next] = nextLabel
                        queue[queueEnd++] = next
                    }
                }
                if (x > 0) enqueue(index - 1)
                if (x + 1 < width) enqueue(index + 1)
                if (y > 0) enqueue(index - width)
                if (y + 1 < height) enqueue(index + width)
            }
            nextLabel++
        }
        return labels
    }

    private fun nearestComponentLabel(
        markerIndex: Int,
        labels: IntArray,
        width: Int,
        height: Int
    ): Int {
        if (markerIndex !in labels.indices) return -1
        if (labels[markerIndex] >= 0) return labels[markerIndex]
        val markerX = markerIndex % width
        val markerY = markerIndex / width
        for (radius in 1..8) {
            var bestLabel = -1
            var bestDistance = Int.MAX_VALUE
            val left = (markerX - radius).coerceAtLeast(0)
            val right = (markerX + radius).coerceAtMost(width - 1)
            val top = (markerY - radius).coerceAtLeast(0)
            val bottom = (markerY + radius).coerceAtMost(height - 1)
            for (y in top..bottom) {
                for (x in left..right) {
                    val label = labels[y * width + x]
                    if (label < 0) continue
                    val dx = x - markerX
                    val dy = y - markerY
                    val distance = dx * dx + dy * dy
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestLabel = label
                    }
                }
            }
            if (bestLabel >= 0) return bestLabel
        }
        return -1
    }

    private fun fillColorsCompatible(first: Int, second: Int): Boolean {
        val red = Color.red(first) - Color.red(second)
        val green = Color.green(first) - Color.green(second)
        val blue = Color.blue(first) - Color.blue(second)
        val distanceSquared = red * red + green * green + blue * blue
        if (distanceSquared <= 92 * 92) return true
        val firstLuminance = (Color.red(first) * 299 + Color.green(first) * 587 +
                Color.blue(first) * 114) / 1000
        val secondLuminance = (Color.red(second) * 299 + Color.green(second) * 587 +
                Color.blue(second) * 114) / 1000
        return firstLuminance >= 190 && secondLuminance >= 190 &&
                abs(firstLuminance - secondLuminance) <= 48
    }

    private fun traceFillComponent(
        pixels: IntArray,
        width: Int,
        height: Int,
        marker: PartitionMarker,
        allowed: BooleanArray
    ): BooleanArray {
        val dominantRed = Color.red(marker.dominantColor)
        val dominantGreen = Color.green(marker.dominantColor)
        val dominantBlue = Color.blue(marker.dominantColor)
        val luminance = (dominantRed * 299 + dominantGreen * 587 + dominantBlue * 114) / 1000
        val tolerance = when {
            luminance >= 205 -> 88
            luminance <= 55 -> 52
            else -> 68
        }
        val toleranceSquared = tolerance * tolerance
        fun isEligible(index: Int): Boolean {
            if (!allowed[index]) return false
            val color = pixels[index]
            val red = Color.red(color) - dominantRed
            val green = Color.green(color) - dominantGreen
            val blue = Color.blue(color) - dominantBlue
            return red * red + green * green + blue * blue <= toleranceSquared
        }

        val connected = BooleanArray(pixels.size)
        if (!isEligible(marker.index)) return connected
        val queue = IntArray(pixels.size)
        var start = 0
        var end = 0
        queue[end++] = marker.index
        connected[marker.index] = true
        while (start < end) {
            val index = queue[start++]
            val x = index % width
            val y = index / width
            fun enqueue(next: Int) {
                if (!connected[next] && isEligible(next)) {
                    connected[next] = true
                    queue[end++] = next
                }
            }
            if (x > 0) enqueue(index - 1)
            if (x + 1 < width) enqueue(index + 1)
            if (y > 0) enqueue(index - width)
            if (y + 1 < height) enqueue(index + width)
        }
        return connected
    }

    /** Prevents a broad or slightly noisy model mask from joining distant text. */
    private fun regionsLikelyAdjacent(
        first: DialogueTextRegion,
        second: DialogueTextRegion
    ): Boolean {
        val horizontalGap = (max(first.left, second.left) - min(first.right, second.right))
            .coerceAtLeast(0f)
        val verticalGap = (max(first.top, second.top) - min(first.bottom, second.bottom))
            .coerceAtLeast(0f)
        val referenceWidth = max(first.width, second.width).coerceAtLeast(1f)
        val referenceHeight = max(first.height, second.height).coerceAtLeast(1f)
        return horizontalGap <= referenceWidth * 0.70f &&
                verticalGap <= referenceHeight * 1.35f
    }

    private fun mergeTextRegions(regions: List<DialogueTextRegion>): DialogueTextRegion {
        val ordered = regions.sortedWith(compareBy<DialogueTextRegion>({ it.top }, { it.left }))
        return DialogueTextRegion(
            left = ordered.minOf { it.left },
            top = ordered.minOf { it.top },
            right = ordered.maxOf { it.right },
            bottom = ordered.maxOf { it.bottom },
            text = ordered.joinToString("\n") { it.text },
            lineCount = ordered.sumOf { it.lineCount }
        )
    }

    private fun textMatchScore(
        detection: DetectedBubble,
        region: DialogueTextRegion
    ): Float {
        val intersectionWidth = (min(detection.right, region.right) -
                max(detection.left, region.left)).coerceAtLeast(0f)
        val intersectionHeight = (min(detection.bottom, region.bottom) -
                max(detection.top, region.top)).coerceAtLeast(0f)
        val coveredText = intersectionWidth * intersectionHeight /
                (region.width * region.height).coerceAtLeast(1f)
        val paddingX = detection.width * 0.08f
        val paddingY = detection.height * 0.08f
        val centerInside = region.centerX in (detection.left - paddingX)..(detection.right + paddingX) &&
                region.centerY in (detection.top - paddingY)..(detection.bottom + paddingY)
        return coveredText + if (centerInside) 0.35f else 0f
    }

    private fun ocrFallbackDetection(
        region: DialogueTextRegion,
        source: Bitmap,
        enclosureBounds: SourceBounds?,
        candidateKind: CandidateKind
    ): DetectedBubble {
        val paddingX = max(region.width * 0.48f, region.height * 0.65f)
        val paddingY = max(region.height * 0.55f, 6f)
        val fallbackLeft = (region.left - paddingX).coerceIn(0f, source.width - 1f)
        val fallbackTop = (region.top - paddingY).coerceIn(0f, source.height - 1f)
        val fallbackRight = (region.right + paddingX).coerceIn(1f, source.width.toFloat())
        val fallbackBottom = (region.bottom + paddingY).coerceIn(1f, source.height.toFloat())
        val enclosurePaddingX = enclosureBounds?.let { (it.right - it.left) * 0.08f } ?: 0f
        val enclosurePaddingY = enclosureBounds?.let { (it.bottom - it.top) * 0.08f } ?: 0f
        return DetectedBubble(
            left = (enclosureBounds?.left?.minus(enclosurePaddingX) ?: fallbackLeft)
                .coerceIn(0f, source.width - 1f),
            top = (enclosureBounds?.top?.minus(enclosurePaddingY) ?: fallbackTop)
                .coerceIn(0f, source.height - 1f),
            right = (enclosureBounds?.right?.plus(enclosurePaddingX) ?: fallbackRight)
                .coerceIn(1f, source.width.toFloat()),
            bottom = (enclosureBounds?.bottom?.plus(enclosurePaddingY) ?: fallbackBottom)
                .coerceIn(1f, source.height.toFloat()),
            confidence = 0.46f,
            textRegion = region,
            textRegions = listOf(region),
            isOcrFallback = true,
            candidateKind = candidateKind
        )
    }

    private fun suppressHybridDuplicates(
        candidates: List<DetectedBubble>
    ): List<DetectedBubble> {
        val selected = mutableListOf<DetectedBubble>()
        candidates.sortedWith(
            compareByDescending<DetectedBubble> { it.textRegion?.text?.length ?: 0 }
                .thenByDescending { it.confidence }
        ).forEach { candidate ->
            val duplicate = selected.any { existing ->
                val a = candidate.textRegion
                val b = existing.textRegion
                when {
                    a != null && b != null -> {
                        textRegionIoU(a, b) > 0.58f ||
                                (normalizedText(a.text) == normalizedText(b.text) &&
                                        normalizedText(a.text).length >= 3)
                    }
                    a == null && b == null -> intersectionOverUnion(existing, candidate) > 0.65f
                    else -> false
                }
            }
            if (!duplicate) selected += candidate
        }
        return selected
    }

    private fun textRegionIoU(a: DialogueTextRegion, b: DialogueTextRegion): Float {
        val intersectionWidth = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        val intersectionHeight = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
        val intersection = intersectionWidth * intersectionHeight
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun normalizedText(text: String): String = text
        .lowercase()
        .filter(Char::isLetterOrDigit)

    private fun orderForReading(candidates: List<DetectedBubble>): List<DetectedBubble> {
        val rows = mutableListOf<MutableList<DetectedBubble>>()
        candidates.sortedWith(compareBy({ it.anchorCenterY }, { it.anchorLeft })).forEach { candidate ->
            val row = rows.firstOrNull { existingRow ->
                existingRow.any { existing -> sameTextRow(existing, candidate) }
            }
            if (row == null) rows += mutableListOf(candidate) else row += candidate
        }
        return rows.sortedBy { row -> row.minOf(DetectedBubble::anchorTop) }
            .flatMap { row -> row.sortedBy(DetectedBubble::anchorLeft) }
    }

    private fun sameTextRow(a: DetectedBubble, b: DetectedBubble): Boolean {
        val overlap = (min(a.anchorBottom, b.anchorBottom) -
                max(a.anchorTop, b.anchorTop)).coerceAtLeast(0f)
        val smallerHeight = min(a.anchorHeight, b.anchorHeight).coerceAtLeast(1f)
        val centerDifference = kotlin.math.abs(a.anchorCenterY - b.anchorCenterY)
        return overlap / smallerHeight >= 0.22f || centerDifference <= smallerHeight * 0.55f
    }

    private fun nonMaximumSuppression(candidates: List<DetectedBubble>): List<DetectedBubble> {
        val selected = ArrayList<DetectedBubble>()
        candidates.sortedByDescending(DetectedBubble::confidence).forEach { candidate ->
            if (selected.none { existing ->
                    val areaRatio = min(existing.area, candidate.area) /
                            max(existing.area, candidate.area).coerceAtLeast(1f)
                    intersectionOverUnion(existing, candidate) > IOU_THRESHOLD && areaRatio > 0.72f
                }
            ) {
                selected += candidate
            }
        }
        return selected
    }

    private fun intersectionOverUnion(a: DetectedBubble, b: DetectedBubble): Float {
        val intersectionWidth = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        val intersectionHeight = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
        val intersection = intersectionWidth * intersectionHeight
        val union = (a.right - a.left) * (a.bottom - a.top) +
                (b.right - b.left) * (b.bottom - b.top) - intersection
        return if (union > 0f) intersection / union else 0f
    }

    /**
     * Cuts the alpha-masked balloon out of the *original* file at native
     * resolution (via [regionDecoder]) rather than out of the downsampled
     * [source] bitmap used for inference. Falls back to cropping [source]
     * directly if the file couldn't be opened for region decoding (e.g.
     * unsupported format for BitmapRegionDecoder).
     */
    private fun saveMaskedCutout(
        regionDecoder: BitmapRegionDecoder?,
        source: Bitmap,
        detection: DetectedBubble,
        comicId: Long,
        pageIndex: Int,
        order: Int,
        cacheGeneration: Long,
        edgePartitionCache: MutableMap<Int, EdgePartition?>
    ): SavedCutout? {
        val extracted = extractCutout(regionDecoder, source, detection) ?: return null
        val cutout = extracted.bitmap
        val bounds = extracted.bounds
        val width = cutout.width
        val height = cutout.height
        val upscaleX = width / (bounds.right - bounds.left).coerceAtLeast(1f)
        val upscaleY = height / (bounds.bottom - bounds.top).coerceAtLeast(1f)

        val pixels = IntArray(width * height)
        cutout.getPixels(pixels, 0, width, 0, 0, width, height)
        val probabilityMask = detection.createProbabilityMask()
        val ownershipMask = createEdgeOwnershipMask(
            source = source,
            detection = detection,
            cutoutWidth = width,
            cutoutHeight = height,
            cutoutBounds = bounds,
            edgePartitionCache = edgePartitionCache
        )
        val refinementRegions = detection.textRegions.ifEmpty {
            val anchor = detection.textRegion
            if (anchor != null) listOf(anchor) else emptyList()
        }
        val exactCandidates = mutableListOf<AlphaCandidate>()
        var nativeRefinedResult: RefinedAlphaResult? = null
        if (refinementRegions.isNotEmpty()) {
            nativeRefinedResult = createTextRefinedAlpha(
                pixels = pixels,
                width = width,
                height = height,
                sourceBounds = bounds,
                detection = detection,
                probabilityMask = probabilityMask,
                textRegions = refinementRegions,
                ownershipMask = ownershipMask
            )
            nativeRefinedResult?.let { refined ->
                exactCandidates += AlphaCandidate(
                    strategy = MaskStrategy.NATIVE_REFINED,
                    alpha = refined.alpha,
                    boundarySnapped = refined.allBoundariesSnapped
                )
            }
        }
        if (probabilityMask != null) {
            createModelOnlySolidAlpha(
                width = width,
                height = height,
                sourceBounds = bounds,
                detection = detection,
                probabilityMask = probabilityMask,
                ownershipMask = ownershipMask,
                textRegions = refinementRegions
            )?.let { alpha ->
                exactCandidates += AlphaCandidate(MaskStrategy.MODEL_SOLID, alpha)
            }
            exactCandidates += AlphaCandidate(
                strategy = MaskStrategy.RAW_MODEL,
                alpha = IntArray(width * height) { pixelIndex ->
                    if (ownershipMask?.get(pixelIndex) == false) {
                        0
                    } else {
                        val x = pixelIndex % width
                        val y = pixelIndex / width
                        val sourceX = bounds.left + (x + 0.5f) / upscaleX
                        val sourceY = bounds.top + (y + 0.5f) / upscaleY
                        smoothAlpha(detection.maskProbability(probabilityMask, sourceX, sourceY))
                    }
                }
            )
        }

        if (refinementRegions.isNotEmpty()) {
            val native = exactCandidates.firstOrNull {
                it.strategy == MaskStrategy.NATIVE_REFINED
            }?.alpha
            val modelSolid = exactCandidates.firstOrNull {
                it.strategy == MaskStrategy.MODEL_SOLID
            }?.alpha
            val rawModel = exactCandidates.firstOrNull {
                it.strategy == MaskStrategy.RAW_MODEL
            }?.alpha
            createTopologyRepairedAlpha(
                nativeAlpha = native,
                modelSolidAlpha = modelSolid,
                rawModelAlpha = rawModel,
                pixels = pixels,
                width = width,
                height = height,
                sourceBounds = bounds,
                textRegions = refinementRegions
            )?.let { alpha ->
                exactCandidates += AlphaCandidate(
                    strategy = MaskStrategy.HYBRID_TOPOLOGY,
                    alpha = alpha,
                    boundarySnapped = nativeRefinedResult?.allBoundariesSnapped == true
                )
            }
        }

        val protectedCandidates = exactCandidates.map { candidate ->
            candidate.copy(
                alpha = protectTextSurfaceAlpha(
                    alpha = candidate.alpha,
                    pixels = pixels,
                    width = width,
                    height = height,
                    sourceBounds = bounds,
                    textRegions = refinementRegions
                )
            )
        }
        val selectedExact = protectedCandidates.mapNotNull { candidate ->
            val valid = passesFinalAlphaValidation(
                alpha = candidate.alpha,
                pixels = pixels,
                width = width,
                height = height,
                sourceBounds = bounds,
                detection = detection,
                probabilityMask = probabilityMask,
                textRegions = refinementRegions
            )
            if (!valid) {
                logStage(
                    stage = "MASK_CANDIDATE",
                    outcome = "REJECT",
                    detail = "strategy=${candidate.strategy}",
                    text = detection.textRegion?.text.orEmpty()
                )
                null
            } else {
                val score = scoreExactMaskCandidate(
                    alpha = candidate.alpha,
                    pixels = pixels,
                    width = width,
                    height = height,
                    sourceBounds = bounds,
                    detection = detection,
                    probabilityMask = probabilityMask,
                    textRegions = refinementRegions,
                    strategy = candidate.strategy,
                    boundarySnapped = candidate.boundarySnapped
                )
                val minimumQuality = if (detection.candidateKind == CandidateKind.CAPTION) {
                    MIN_EXACT_CAPTION_QUALITY
                } else {
                    MIN_EXACT_SPEECH_QUALITY
                }
                logStage(
                    stage = "MASK_CANDIDATE",
                    outcome = if (score.hardGatesPassed && score.total >= minimumQuality) {
                        "VALID"
                    } else {
                        "LOW_QUALITY"
                    },
                    detail = "strategy=${candidate.strategy}, quality=${formatScore(score.total)}, " +
                            score.detail,
                    text = detection.textRegion?.text.orEmpty()
                )
                candidate.copy(score = score.total).takeIf {
                    score.hardGatesPassed && score.total >= minimumQuality
                }
            }
        }.maxByOrNull(AlphaCandidate::score)

        val polishedFallback = if (
            selectedExact == null &&
            refinementRegions.isNotEmpty() &&
            canUsePolishedPresentationFallback(detection)
        ) {
            createPolishedPresentationAlpha(
                width = width,
                height = height,
                sourceBounds = bounds,
                textRegions = refinementRegions
            )?.takeIf { alpha ->
                passesPresentationFallbackIntegrity(
                    alpha = alpha,
                    width = width,
                    height = height,
                    sourceBounds = bounds,
                    textRegions = refinementRegions
                )
            }
        } else {
            null
        }
        val isPresentationFallback = selectedExact == null && polishedFallback != null
        val selectedStrategy = selectedExact?.strategy ?: MaskStrategy.POLISHED_PAGE_CONTEXT
        val finalAlpha = selectedExact?.alpha ?: polishedFallback
        if (finalAlpha == null) {
            logStage(
                stage = "MASK_SELECTION",
                outcome = "UNRESOLVED",
                detail = "candidates=${protectedCandidates.size}, fallbackEligible=${canUsePolishedPresentationFallback(detection)}",
                text = detection.textRegion?.text.orEmpty()
            )
            cutout.recycle()
            return null
        }
        logStage(
            stage = if (isPresentationFallback) "AUTOMATIC_FALLBACK" else "MASK_SELECTION",
            outcome = "SELECTED",
            detail = "strategy=$selectedStrategy" +
                    (selectedExact?.let { ", quality=${formatScore(it.score)}" } ?: ""),
            text = detection.textRegion?.text.orEmpty()
        )

        var opaquePixels = 0
        var opaqueLeft = width
        var opaqueTop = height
        var opaqueRight = -1
        var opaqueBottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixelIndex = y * width + x
                val alpha = finalAlpha[pixelIndex]
                if (alpha > 8) {
                    opaquePixels++
                    opaqueLeft = min(opaqueLeft, x)
                    opaqueTop = min(opaqueTop, y)
                    opaqueRight = max(opaqueRight, x)
                    opaqueBottom = max(opaqueBottom, y)
                }
                var outputRgb = pixels[pixelIndex] and 0x00FFFFFF
                // The translucent one-pixel fringe must borrow color from the
                // bubble edge, never from the panel pixel just outside it.
                // Otherwise antialiasing alone can create a visible background
                // halo even when the binary bubble mask is correct.
                if (alpha in 1..223) {
                    var edgeNeighbor = -1
                    neighborLoop@ for (dy in -1..1) {
                        for (dx in -1..1) {
                            val targetX = x + dx
                            val targetY = y + dy
                            if (targetX !in 0 until width || targetY !in 0 until height) continue
                            val targetIndex = targetY * width + targetX
                            if (finalAlpha[targetIndex] >= 224) {
                                edgeNeighbor = targetIndex
                                break@neighborLoop
                            }
                        }
                    }
                    if (edgeNeighbor >= 0) {
                        outputRgb = pixels[edgeNeighbor] and 0x00FFFFFF
                    }
                }
                pixels[pixelIndex] = outputRgb or (alpha shl 24)
            }
        }
        val minimumVisibleRatio = if (isPresentationFallback) 0.002f else 0.025f
        if (opaquePixels < width * height * minimumVisibleRatio ||
            opaqueRight < opaqueLeft || opaqueBottom < opaqueTop
        ) {
            logStage(
                stage = "FINAL_ALPHA",
                outcome = "REJECT",
                detail = "kind=${detection.candidateKind}, post-validation-visible=$opaquePixels/${width * height}",
                text = detection.textRegion?.text.orEmpty()
            )
            cutout.recycle()
            return null
        }

        val fullMasked = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        fullMasked.setPixels(pixels, 0, width, 0, 0, width, height)
        cutout.recycle()

        // Transparent padding made the UI believe several balloons were much
        // larger than their visible artwork. Trim to the actual alpha contour.
        val trimPadding = 3
        val trimLeft = (opaqueLeft - trimPadding).coerceAtLeast(0)
        val trimTop = (opaqueTop - trimPadding).coerceAtLeast(0)
        val trimRight = (opaqueRight + trimPadding + 1).coerceAtMost(width)
        val trimBottom = (opaqueBottom + trimPadding + 1).coerceAtMost(height)
        val masked = if (trimLeft > 0 || trimTop > 0 || trimRight < width || trimBottom < height) {
            Bitmap.createBitmap(
                fullMasked,
                trimLeft,
                trimTop,
                trimRight - trimLeft,
                trimBottom - trimTop
            ).also { fullMasked.recycle() }
        } else fullMasked

        val visibleBounds = SourceBounds(
            left = bounds.left + trimLeft / upscaleX,
            top = bounds.top + trimTop / upscaleY,
            right = bounds.left + trimRight / upscaleX,
            bottom = bounds.top + trimBottom / upscaleY
        )

        val directory = File(context.filesDir, "bubble_masks/$comicId").apply { mkdirs() }
        val output = File(
            directory,
            "${MASK_CACHE_VERSION}page_${pageIndex}_${order}_$cacheGeneration.png"
        )
        FileOutputStream(output).use { stream ->
            // The cutout already comes from the original page. Saving those
            // native pixels avoids a second bilinear resize before Compose.
            masked.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        masked.recycle()
        logStage(
            stage = "FINAL_ALPHA",
            outcome = "SAVED",
            detail = "page=$pageIndex order=$order",
            text = detection.textRegion?.text.orEmpty()
        )
        return SavedCutout(
            path = output.absolutePath,
            left = visibleBounds.left,
            top = visibleBounds.top,
            right = visibleBounds.right,
            bottom = visibleBounds.bottom,
            isPresentationFallback = isPresentationFallback,
            strategy = selectedStrategy
        )
    }

    /**
     * Protects the printed surface around OCR glyphs, not only the glyph box.
     * V31 could report OCR coverage=1.0 while carving transparent notches into
     * the white plate immediately around a letter. The small color-gated band
     * below keeps those native pixels opaque without drawing a rectangular
     * patch across unrelated artwork.
     */
    private fun protectTextSurfaceAlpha(
        alpha: IntArray,
        pixels: IntArray,
        width: Int,
        height: Int,
        sourceBounds: SourceBounds,
        textRegions: List<DialogueTextRegion>
    ): IntArray {
        if (alpha.size != width * height || pixels.size != alpha.size || textRegions.isEmpty()) {
            return alpha
        }
        val result = alpha.copyOf()
        val scaleX = width / (sourceBounds.right - sourceBounds.left).coerceAtLeast(1f)
        val scaleY = height / (sourceBounds.bottom - sourceBounds.top).coerceAtLeast(1f)
        var restored = 0
        textRegions.forEach { region ->
            val left = ((region.left - sourceBounds.left) * scaleX).roundToInt()
                .coerceIn(0, width - 1)
            val top = ((region.top - sourceBounds.top) * scaleY).roundToInt()
                .coerceIn(0, height - 1)
            val right = ((region.right - sourceBounds.left) * scaleX).roundToInt()
                .coerceIn(left + 1, width)
            val bottom = ((region.bottom - sourceBounds.top) * scaleY).roundToInt()
                .coerceIn(top + 1, height)
            val lineHeight = (bottom - top).toFloat() / region.lineCount.coerceAtLeast(1)
            val padding = (lineHeight * 0.22f).roundToInt().coerceIn(2, 8)
            val sampleLeft = (left - padding).coerceAtLeast(0)
            val sampleTop = (top - padding).coerceAtLeast(0)
            val sampleRight = (right + padding).coerceAtMost(width)
            val sampleBottom = (bottom + padding).coerceAtMost(height)
            val histogram = IntArray(4096)
            for (y in sampleTop until sampleBottom) {
                for (x in sampleLeft until sampleRight) {
                    val color = pixels[y * width + x]
                    val key = ((Color.red(color) shr 4) shl 8) or
                            ((Color.green(color) shr 4) shl 4) or
                            (Color.blue(color) shr 4)
                    histogram[key]++
                }
            }
            val dominantKey = histogram.indices.maxByOrNull(histogram::get) ?: return@forEach
            val dominant = Color.rgb(
                (((dominantKey shr 8) and 0xF) shl 4) + 8,
                (((dominantKey shr 4) and 0xF) shl 4) + 8,
                ((dominantKey and 0xF) shl 4) + 8
            )
            val toleranceSquared = 76 * 76
            for (y in sampleTop until sampleBottom) {
                for (x in sampleLeft until sampleRight) {
                    val index = y * width + x
                    val insideOcr = x in left until right && y in top until bottom
                    val surfaceCompatible = colorDistanceSquared(pixels[index], dominant) <=
                            toleranceSquared
                    if (insideOcr || surfaceCompatible) {
                        if (result[index] < 255) restored++
                        result[index] = 255
                    }
                }
            }
        }
        if (restored > 0) {
            logStage(
                stage = "TEXT_PLATE",
                outcome = "PROTECTED",
                detail = "pixels=$restored, regions=${textRegions.size}"
            )
        }
        return result
    }

    /**
     * Scores only masks that already passed the strict final-alpha audit.
     * The score lets native tracing, a solid model component and the raw model
     * matte compete on compactness, crop safety and segmentation support rather
     * than relying on whichever implementation happened to run first.
     */
    private fun scoreExactMaskCandidate(
        alpha: IntArray,
        pixels: IntArray,
        width: Int,
        height: Int,
        sourceBounds: SourceBounds,
        detection: DetectedBubble,
        probabilityMask: FloatArray?,
        textRegions: List<DialogueTextRegion>,
        strategy: MaskStrategy,
        boundarySnapped: Boolean
    ): MaskQuality {
        if (alpha.size != width * height || pixels.size != alpha.size || alpha.isEmpty()) {
            return MaskQuality(0f, "invalid-size")
        }
        var visible = 0
        var solid = 0
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        var boundaryHits = 0
        for (index in alpha.indices) {
            if (alpha[index] < FINAL_ALPHA_VISIBLE_THRESHOLD) continue
            visible++
            val x = index % width
            val y = index / width
            left = min(left, x)
            top = min(top, y)
            right = max(right, x)
            bottom = max(bottom, y)
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) boundaryHits++
            if (alpha[index] >= FINAL_ALPHA_SOLID_THRESHOLD) solid++
        }
        if (visible == 0 || solid == 0 || right < left || bottom < top) {
            return MaskQuality(0f, "empty")
        }

        val scaleX = width / (sourceBounds.right - sourceBounds.left).coerceAtLeast(1f)
        val scaleY = height / (sourceBounds.bottom - sourceBounds.top).coerceAtLeast(1f)
        val mappedText = textRegions.map { region ->
            val mappedLeft = ((region.left - sourceBounds.left) * scaleX).roundToInt()
                .coerceIn(0, width - 1)
            val mappedTop = ((region.top - sourceBounds.top) * scaleY).roundToInt()
                .coerceIn(0, height - 1)
            val mappedRight = ((region.right - sourceBounds.left) * scaleX).roundToInt()
                .coerceIn(mappedLeft + 1, width)
            val mappedBottom = ((region.bottom - sourceBounds.top) * scaleY).roundToInt()
                .coerceIn(mappedTop + 1, height)
            Rect(mappedLeft, mappedTop, mappedRight, mappedBottom)
        }
        val textArea = mappedText.sumOf { rect ->
            rect.width().toLong() * rect.height().toLong()
        }.coerceAtLeast(1L).toFloat()
        var protectedTextPixels = 0
        mappedText.forEach { rect ->
            for (y in rect.top until rect.bottom) {
                for (x in rect.left until rect.right) {
                    if (alpha[y * width + x] >= FINAL_ALPHA_SOLID_THRESHOLD) {
                        protectedTextPixels++
                    }
                }
            }
        }
        val textProtection = protectedTextPixels / textArea
        var platePixels = 0
        var protectedPlatePixels = 0
        mappedText.forEach { rect ->
            val linePadding = max(2, (rect.height() * 0.10f).roundToInt()).coerceAtMost(8)
            val plateLeft = (rect.left - linePadding).coerceAtLeast(0)
            val plateTop = (rect.top - linePadding).coerceAtLeast(0)
            val plateRight = (rect.right + linePadding).coerceAtMost(width)
            val plateBottom = (rect.bottom + linePadding).coerceAtMost(height)
            val plateHistogram = IntArray(4096)
            for (y in plateTop until plateBottom) {
                for (x in plateLeft until plateRight) {
                    val color = pixels[y * width + x]
                    val key = ((Color.red(color) shr 4) shl 8) or
                            ((Color.green(color) shr 4) shl 4) or
                            (Color.blue(color) shr 4)
                    plateHistogram[key]++
                }
            }
            val plateKey = plateHistogram.indices.maxByOrNull(plateHistogram::get) ?: 0
            val plateColor = Color.rgb(
                (((plateKey shr 8) and 0xF) shl 4) + 8,
                (((plateKey shr 4) and 0xF) shl 4) + 8,
                ((plateKey and 0xF) shl 4) + 8
            )
            for (y in plateTop until plateBottom) {
                for (x in plateLeft until plateRight) {
                    val index = y * width + x
                    val insideOcr = x in rect.left until rect.right &&
                            y in rect.top until rect.bottom
                    if (!insideOcr && colorDistanceSquared(pixels[index], plateColor) > 76 * 76) {
                        continue
                    }
                    platePixels++
                    if (alpha[index] >= FINAL_ALPHA_SOLID_THRESHOLD) {
                        protectedPlatePixels++
                    }
                }
            }
        }
        val textPlateProtection = if (platePixels == 0) 1f else {
            protectedPlatePixels.toFloat() / platePixels
        }
        val visibleToText = if (textRegions.isEmpty()) 4f else visible / textArea
        val compactness = when {
            visibleToText < 1f -> visibleToText
            visibleToText <= 7f -> 1f
            visibleToText <= 15f -> 1f - (visibleToText - 7f) / 10f
            else -> 0.12f
        }.coerceIn(0f, 1f)
        val boundarySafety = (1f - boundaryHits.toFloat() / visible.coerceAtLeast(1) * 8f)
            .coerceIn(0f, 1f)

        val binary = BooleanArray(alpha.size) { alpha[it] >= FINAL_ALPHA_VISIBLE_THRESHOLD }
        val componentLabels = labelConnectedComponents(binary, width, height)
        val componentCount = (componentLabels.maxOrNull() ?: -1) + 1
        val componentSizes = IntArray(componentCount.coerceAtLeast(0))
        componentLabels.forEach { label -> if (label >= 0) componentSizes[label]++ }
        val meaningfulFloor = max(12, (visible * 0.012f).roundToInt())
        val meaningfulComponents = componentSizes.count { it >= meaningfulFloor }
        val allowedComponents = max(1, textRegions.size)
        val topology = when {
            meaningfulComponents <= allowedComponents -> 1f
            else -> (1f - (meaningfulComponents - allowedComponents) * 0.22f)
                .coerceIn(0f, 1f)
        }

        // Count only holes enclosed by the candidate silhouette. Transparent
        // seams inside connected balloons are therefore penalized directly.
        val exterior = BooleanArray(alpha.size)
        val queue = IntArray(alpha.size)
        var queueStart = 0
        var queueEnd = 0
        fun enqueueExterior(index: Int) {
            if (index !in exterior.indices || exterior[index] || binary[index]) return
            exterior[index] = true
            queue[queueEnd++] = index
        }
        for (x in left..right) {
            enqueueExterior(top * width + x)
            enqueueExterior(bottom * width + x)
        }
        for (y in top..bottom) {
            enqueueExterior(y * width + left)
            enqueueExterior(y * width + right)
        }
        while (queueStart < queueEnd) {
            val index = queue[queueStart++]
            val x = index % width
            val y = index / width
            if (x > left) enqueueExterior(index - 1)
            if (x < right) enqueueExterior(index + 1)
            if (y > top) enqueueExterior(index - width)
            if (y < bottom) enqueueExterior(index + width)
        }
        var holePixels = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val index = y * width + x
                if (!binary[index] && !exterior[index]) holePixels++
            }
        }
        val holeRatio = holePixels.toFloat() / visible.coerceAtLeast(1)
        val holeFreedom = (1f - holeRatio * 7f).coerceIn(0f, 1f)

        val (modelPrecision, modelRecall) = if (probabilityMask == null) {
            0.78f to 0.78f
        } else {
            var supported = 0
            var supportedAvailable = 0
            var supportedCaptured = 0
            var sampledSolid = 0
            val sampleStep = max(1, max(width, height) / 320)
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val index = y * width + x
                    val sourceX = sourceBounds.left + (x + 0.5f) / scaleX
                    val sourceY = sourceBounds.top + (y + 0.5f) / scaleY
                    val supportedByModel = detection.maskProbability(
                        probabilityMask,
                        sourceX,
                        sourceY
                    ) >= FINAL_ALPHA_LOOSE_THRESHOLD
                    if (alpha[index] >= FINAL_ALPHA_SOLID_THRESHOLD) sampledSolid++
                    if (supportedByModel) {
                        supportedAvailable++
                        if (alpha[index] >= FINAL_ALPHA_VISIBLE_THRESHOLD) supportedCaptured++
                        if (alpha[index] >= FINAL_ALPHA_SOLID_THRESHOLD) supported++
                    }
                    x += sampleStep
                }
                y += sampleStep
            }
            (supported.toFloat() / sampledSolid.coerceAtLeast(1)) to
                    (supportedCaptured.toFloat() / supportedAvailable.coerceAtLeast(1))
        }

        val textExclusion = BooleanArray(alpha.size)
        mappedText.forEach { rect ->
            val padX = max(2, (rect.width() * 0.14f).roundToInt())
            val padY = max(2, (rect.height() * 0.14f).roundToInt())
            for (y in (rect.top - padY).coerceAtLeast(0) until
                    (rect.bottom + padY).coerceAtMost(height)) {
                for (x in (rect.left - padX).coerceAtLeast(0) until
                        (rect.right + padX).coerceAtMost(width)) {
                    textExclusion[y * width + x] = true
                }
            }
        }
        val histogram = IntArray(4096)
        var surfaceSamples = 0
        var comparisons = 0
        var strongEdges = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val index = y * width + x
                if (!binary[index] || textExclusion[index]) continue
                val color = pixels[index]
                val key = ((Color.red(color) shr 4) shl 8) or
                        ((Color.green(color) shr 4) shl 4) or
                        (Color.blue(color) shr 4)
                histogram[key]++
                surfaceSamples++
                if (x < right && binary[index + 1] && !textExclusion[index + 1]) {
                    comparisons++
                    if (colorDistance(color, pixels[index + 1]) >= 96) strongEdges++
                }
                if (y < bottom && binary[index + width] && !textExclusion[index + width]) {
                    comparisons++
                    if (colorDistance(color, pixels[index + width]) >= 96) strongEdges++
                }
            }
        }
        val dominantRatio = if (surfaceSamples > 0) {
            (histogram.maxOrNull() ?: 0).toFloat() / surfaceSamples
        } else 1f
        val dominantKey = histogram.indices.maxByOrNull(histogram::get) ?: 0
        val dominantSurfaceColor = Color.rgb(
            (((dominantKey shr 8) and 0xF) shl 4) + 8,
            (((dominantKey shr 4) and 0xF) shl 4) + 8,
            ((dominantKey and 0xF) shl 4) + 8
        )
        val strongEdgeRatio = strongEdges.toFloat() / comparisons.coerceAtLeast(1)
        val colorQuietness = ((dominantRatio - 0.08f) / 0.28f).coerceIn(0f, 1f)
        val edgeQuietness = (1f - strongEdgeRatio / 0.24f).coerceIn(0f, 1f)
        val surfaceQuality = colorQuietness * 0.55f + edgeQuietness * 0.45f

        var boundaryPixels = 0
        var evidencedBoundaryPixels = 0
        var nativeInkBoundaryPixels = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val index = y * width + x
                if (!binary[index]) continue
                val isBoundary = x == 0 || y == 0 || x == width - 1 || y == height - 1 ||
                        !binary[index - 1] || !binary[index + 1] ||
                        !binary[index - width] || !binary[index + width]
                if (!isBoundary) continue
                boundaryPixels++
                val nativeInk = colorDistance(pixels[index], dominantSurfaceColor) >= 42
                val modelEvidence = probabilityMask?.let { mask ->
                    val sourceX = sourceBounds.left + (x + 0.5f) / scaleX
                    val sourceY = sourceBounds.top + (y + 0.5f) / scaleY
                    detection.maskProbability(mask, sourceX, sourceY) >= FINAL_ALPHA_CORE_THRESHOLD
                } ?: false
                if (nativeInk) nativeInkBoundaryPixels++
                if (nativeInk || modelEvidence) evidencedBoundaryPixels++
            }
        }
        val boundaryEvidence = evidencedBoundaryPixels.toFloat() /
                boundaryPixels.coerceAtLeast(1)
        val nativeBoundaryEvidence = nativeInkBoundaryPixels.toFloat() /
                boundaryPixels.coerceAtLeast(1)

        val strategyPrior = when (strategy) {
            MaskStrategy.NATIVE_REFINED -> 0.72f
            MaskStrategy.MODEL_SOLID -> 0.70f
            MaskStrategy.RAW_MODEL -> 0.58f
            MaskStrategy.HYBRID_TOPOLOGY -> 0.76f
            MaskStrategy.POLISHED_PAGE_CONTEXT -> 0f
        }
        val total = (
                textProtection.coerceIn(0f, 1f) * 0.12f +
                        topology * 0.15f +
                        holeFreedom * 0.15f +
                        modelPrecision.coerceIn(0f, 1f) * 0.20f +
                        modelRecall.coerceIn(0f, 1f) * 0.10f +
                        surfaceQuality * 0.18f +
                        compactness * 0.05f +
                        boundarySafety * 0.03f +
                        strategyPrior * 0.02f
                ).coerceIn(0f, 1f)
        val isCaption = detection.candidateKind == CandidateKind.CAPTION
        val failedGates = mutableListOf<String>()
        if (textPlateProtection < HARD_GATE_TEXT_PLATE) failedGates += "text-plate"
        if (meaningfulComponents != 1) failedGates += "topology"
        if (holeRatio > HARD_GATE_MAX_HOLE_RATIO) failedGates += "open-or-enclosed-gap"
        if (!isCaption && probabilityMask != null &&
            modelPrecision < HARD_GATE_MODEL_PRECISION
        ) failedGates += "leakage"
        if (!isCaption && probabilityMask != null &&
            modelRecall < HARD_GATE_MODEL_RECALL
        ) failedGates += "model-recall"
        if (surfaceQuality < if (isCaption) 0.34f else HARD_GATE_SURFACE_QUALITY) {
            failedGates += "surface"
        }
        if (!isCaption && compactness < 0.28f) failedGates += "too-broad"
        if (boundarySafety < HARD_GATE_MIN_CROP_SAFETY) failedGates += "crop-boundary"
        if (!isCaption && boundaryEvidence < HARD_GATE_BOUNDARY_EVIDENCE) {
            failedGates += "boundary-evidence"
        }
        if (!isCaption &&
            strategy in setOf(MaskStrategy.NATIVE_REFINED, MaskStrategy.HYBRID_TOPOLOGY) &&
            !boundarySnapped
        ) failedGates += "native-snap"
        if (!isCaption &&
            strategy in setOf(MaskStrategy.MODEL_SOLID, MaskStrategy.RAW_MODEL) &&
            nativeBoundaryEvidence < 0.18f
        ) failedGates += "native-contour"
        val hardGatesPassed = failedGates.isEmpty()
        logStage(
            stage = "HARD_GATES",
            outcome = if (hardGatesPassed) "PASS" else "FAIL",
            detail = "strategy=$strategy, failed=${failedGates.ifEmpty { listOf("none") }.joinToString("+")}, " +
                    "plate=${formatScore(textPlateProtection)}, boundary=${formatScore(boundaryEvidence)}, " +
                    "nativeBoundary=${formatScore(nativeBoundaryEvidence)}, " +
                    "surface=${formatScore(surfaceQuality)}, modelP=${formatScore(modelPrecision)}, " +
                    "modelR=${formatScore(modelRecall)}, holes=${formatScore(holeRatio)}, " +
                    "components=$meaningfulComponents, snapped=$boundarySnapped",
            text = detection.textRegion?.text.orEmpty()
        )
        return MaskQuality(
            total = total,
            detail = "ocr=${formatScore(textProtection)}, plate=${formatScore(textPlateProtection)}, " +
                    "topology=$meaningfulComponents/$allowedComponents, " +
                    "holes=${formatScore(holeRatio)}, modelP=${formatScore(modelPrecision)}, " +
                    "modelR=${formatScore(modelRecall)}, surface=${formatScore(surfaceQuality)}, " +
                    "boundary=${formatScore(boundaryEvidence)}, nativeBoundary=${formatScore(nativeBoundaryEvidence)}, " +
                    "cropSafe=${formatScore(boundarySafety)}, " +
                    "gates=${failedGates.ifEmpty { listOf("PASS") }.joinToString("+")}",
            hardGatesPassed = hardGatesPassed,
            failedGates = failedGates
        )
    }

    /**
     * Builds a fourth candidate from the strongest parts of the native and ML
     * strategies. It is deliberately still only a candidate: V32's hard gates
     * can reject it. The repair closes narrow and open ownership seams, makes
     * OCR surfaces opaque, fills enclosed transparent holes and follows a
     * short, model-backed path to recover thin tails.
     */
    private fun createTopologyRepairedAlpha(
        nativeAlpha: IntArray?,
        modelSolidAlpha: IntArray?,
        rawModelAlpha: IntArray?,
        pixels: IntArray,
        width: Int,
        height: Int,
        sourceBounds: SourceBounds,
        textRegions: List<DialogueTextRegion>
    ): IntArray? {
        if (width < 4 || height < 4 || textRegions.isEmpty()) return null
        val expectedSize = width * height
        if (pixels.size != expectedSize) return null
        val base = when {
            nativeAlpha?.size == expectedSize -> nativeAlpha
            modelSolidAlpha?.size == expectedSize -> modelSolidAlpha
            else -> return null
        }
        val result = base.copyOf()
        val originalVisible = base.count { it >= FINAL_ALPHA_VISIBLE_THRESHOLD }
        if (originalVisible < 16) return null

        // Model-solid lobes are safe enough to complete a native candidate,
        // but they never overwrite sharper native antialiasing.
        if (modelSolidAlpha?.size == expectedSize) {
            for (index in result.indices) {
                if (modelSolidAlpha[index] > result[index]) {
                    result[index] = modelSolidAlpha[index]
                }
            }
        }

        val scaleX = width / (sourceBounds.right - sourceBounds.left).coerceAtLeast(1f)
        val scaleY = height / (sourceBounds.bottom - sourceBounds.top).coerceAtLeast(1f)
        val mappedText = textRegions.map { region ->
            val left = ((region.left - sourceBounds.left) * scaleX).roundToInt()
                .coerceIn(0, width - 1)
            val top = ((region.top - sourceBounds.top) * scaleY).roundToInt()
                .coerceIn(0, height - 1)
            val right = ((region.right - sourceBounds.left) * scaleX).roundToInt()
                .coerceIn(left + 1, width)
            val bottom = ((region.bottom - sourceBounds.top) * scaleY).roundToInt()
                .coerceIn(top + 1, height)
            Rect(left, top, right, bottom)
        }
        val lineHeights = textRegions.zip(mappedText).map { (region, rect) ->
            rect.height().toFloat() / region.lineCount.coerceAtLeast(1)
        }.sorted()
        val medianLineHeight = lineHeights[lineHeights.size / 2].coerceAtLeast(4f)
        val surfaceHistogram = IntArray(4096)
        mappedText.forEach { rect ->
            val padding = (medianLineHeight * 0.35f).roundToInt().coerceIn(3, 14)
            for (y in (rect.top - padding).coerceAtLeast(0) until
                    (rect.bottom + padding).coerceAtMost(height)) {
                for (x in (rect.left - padding).coerceAtLeast(0) until
                        (rect.right + padding).coerceAtMost(width)) {
                    val color = pixels[y * width + x]
                    val key = ((Color.red(color) shr 4) shl 8) or
                            ((Color.green(color) shr 4) shl 4) or
                            (Color.blue(color) shr 4)
                    surfaceHistogram[key]++
                }
            }
        }
        val surfaceKey = surfaceHistogram.indices.maxByOrNull(surfaceHistogram::get) ?: 0
        val surfaceColor = Color.rgb(
            (((surfaceKey shr 8) and 0xF) shl 4) + 8,
            (((surfaceKey shr 4) and 0xF) shl 4) + 8,
            ((surfaceKey and 0xF) shl 4) + 8
        )
        fun surfaceCompatible(index: Int): Boolean =
            colorDistanceSquared(pixels[index], surfaceColor) <= 80 * 80

        // Close only one- to four-pixel seams. This repairs watershed cracks
        // without welding two genuinely separate balloons together.
        var topology = BooleanArray(expectedSize) {
            result[it] >= FINAL_ALPHA_VISIBLE_THRESHOLD
        }
        val seamRadius = (medianLineHeight * 0.07f).roundToInt().coerceIn(1, 4)
        repeat(seamRadius) {
            val dilated = topology.copyOf()
            for (index in topology.indices) {
                if (!topology[index]) continue
                val x = index % width
                val y = index / width
                if (x > 0) dilated[index - 1] = true
                if (x + 1 < width) dilated[index + 1] = true
                if (y > 0) dilated[index - width] = true
                if (y + 1 < height) dilated[index + width] = true
            }
            topology = dilated
        }
        repeat(seamRadius) {
            val eroded = topology.copyOf()
            for (index in topology.indices) {
                if (!topology[index]) continue
                val x = index % width
                val y = index / width
                if (x == 0 || x == width - 1 || y == 0 || y == height - 1 ||
                    !topology[index - 1] || !topology[index + 1] ||
                    !topology[index - width] || !topology[index + width]
                ) {
                    eroded[index] = false
                }
            }
            topology = eroded
        }
        for (index in result.indices) {
            if (topology[index] && result[index] < 224) result[index] = 255
        }

        // Repair open ownership seams between OCR lobes. Unlike enclosed-hole
        // filling, this can seal a transparent channel that still reaches the
        // crop exterior. Only pixels on the learned balloon surface and inside
        // the soft model are eligible, so the corridor cannot paint across a
        // panel gutter or an unrelated object.
        var openSeamPixels = 0
        if (mappedText.size > 1 && rawModelAlpha?.size == expectedSize) {
            val connected = mutableSetOf(0)
            val remaining = mappedText.indices.drop(1).toMutableSet()
            while (remaining.isNotEmpty()) {
                var bestFrom = 0
                var bestTo = remaining.first()
                var bestDistance = Float.MAX_VALUE
                connected.forEach { from ->
                    val fromRect = mappedText[from]
                    val fromX = (fromRect.left + fromRect.right) * 0.5f
                    val fromY = (fromRect.top + fromRect.bottom) * 0.5f
                    remaining.forEach { to ->
                        val toRect = mappedText[to]
                        val dx = fromX - (toRect.left + toRect.right) * 0.5f
                        val dy = fromY - (toRect.top + toRect.bottom) * 0.5f
                        val distance = dx * dx + dy * dy
                        if (distance < bestDistance) {
                            bestDistance = distance
                            bestFrom = from
                            bestTo = to
                        }
                    }
                }
                val first = mappedText[bestFrom]
                val second = mappedText[bestTo]
                val x0 = (first.left + first.right) / 2
                val y0 = (first.top + first.bottom) / 2
                val x1 = (second.left + second.right) / 2
                val y1 = (second.top + second.bottom) / 2
                val steps = max(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1)
                val corridorRadius = (medianLineHeight * 0.36f).roundToInt().coerceIn(2, 10)
                for (step in 0..steps) {
                    val centerX = x0 + (x1 - x0) * step / steps
                    val centerY = y0 + (y1 - y0) * step / steps
                    for (dy in -corridorRadius..corridorRadius) {
                        for (dx in -corridorRadius..corridorRadius) {
                            if (dx * dx + dy * dy > corridorRadius * corridorRadius) continue
                            val x = centerX + dx
                            val y = centerY + dy
                            if (x !in 0 until width || y !in 0 until height) continue
                            val index = y * width + x
                            if (result[index] < FINAL_ALPHA_VISIBLE_THRESHOLD &&
                                rawModelAlpha[index] >= 40 && surfaceCompatible(index)
                            ) {
                                result[index] = 255
                                openSeamPixels++
                            }
                        }
                    }
                }
                connected += bestTo
                remaining -= bestTo
            }
        }

        // Trace a tail as a short, thin continuation from the accepted body.
        // V31 seeded its search from every opaque pixel and required a fairly
        // strong model value, which produced tail=0 on all 240 attempts. V32
        // seeds only the true silhouette boundary, permits low-confidence model
        // support, and requires either native ink or matching balloon paper.
        var tailPixels = 0
        if (rawModelAlpha?.size == expectedSize) {
            val body = BooleanArray(expectedSize) {
                result[it] >= FINAL_ALPHA_VISIBLE_THRESHOLD
            }
            val eligible = BooleanArray(expectedSize) { index ->
                !body[index] && rawModelAlpha[index] >= 10 &&
                        (colorDistance(pixels[index], surfaceColor) >= 44 ||
                                surfaceCompatible(index))
            }
            val distance = IntArray(expectedSize) { Int.MAX_VALUE }
            val queue = IntArray(expectedSize)
            var queueStart = 0
            var queueEnd = 0
            for (index in result.indices) {
                if (!body[index]) continue
                val x = index % width
                val y = index / width
                val boundary = x == 0 || y == 0 || x == width - 1 || y == height - 1 ||
                        !body[index - 1] || !body[index + 1] ||
                        !body[index - width] || !body[index + width]
                if (boundary) {
                    distance[index] = 0
                    queue[queueEnd++] = index
                }
            }
            val maximumTailDistance = (medianLineHeight * 1.85f).roundToInt()
                .coerceIn(10, 64)
            fun enqueue(index: Int, nextDistance: Int) {
                if (index !in result.indices || nextDistance >= distance[index] ||
                    nextDistance > maximumTailDistance || !eligible[index]
                ) return
                distance[index] = nextDistance
                queue[queueEnd++] = index
            }
            while (queueStart < queueEnd) {
                val index = queue[queueStart++]
                val nextDistance = distance[index] + 1
                val x = index % width
                val y = index / width
                if (x > 0) enqueue(index - 1, nextDistance)
                if (x + 1 < width) enqueue(index + 1, nextDistance)
                if (y > 0) enqueue(index - width, nextDistance)
                if (y + 1 < height) enqueue(index + width, nextDistance)
            }
            val maximumTailPixels = max(
                24,
                min(originalVisible / 9, (medianLineHeight * medianLineHeight * 3.5f).roundToInt())
            )
            val tracedSeeds = mutableListOf<Int>()
            for (queueIndex in 0 until queueEnd) {
                val index = queue[queueIndex]
                if (distance[index] in 1..maximumTailDistance && eligible[index]) {
                    tracedSeeds += index
                    if (tracedSeeds.size >= maximumTailPixels) break
                }
            }
            for (index in tracedSeeds) {
                result[index] = max(result[index], rawModelAlpha[index].coerceAtLeast(96))
                tailPixels++
            }
            // Give the recovered ink path its native paper interior. The
            // radius is deliberately tiny and remains model/surface gated.
            val tailPlateRadius = (medianLineHeight * 0.10f).roundToInt().coerceIn(1, 3)
            tracedSeeds.forEach { seed ->
                val seedX = seed % width
                val seedY = seed / width
                for (dy in -tailPlateRadius..tailPlateRadius) {
                    for (dx in -tailPlateRadius..tailPlateRadius) {
                        val x = seedX + dx
                        val y = seedY + dy
                        if (x !in 0 until width || y !in 0 until height) continue
                        val index = y * width + x
                        if (rawModelAlpha[index] >= 6 && surfaceCompatible(index) &&
                            result[index] < FINAL_ALPHA_VISIBLE_THRESHOLD
                        ) {
                            result[index] = 224
                            tailPixels++
                        }
                    }
                }
            }
        }

        // OCR is content, never segmentation noise. Preserve a tiny safety
        // band so alpha erosion cannot soften glyph edges when magnified.
        val textSafety = (medianLineHeight * 0.10f).roundToInt().coerceIn(1, 4)
        mappedText.forEach { rect ->
            val left = (rect.left - textSafety).coerceAtLeast(0)
            val top = (rect.top - textSafety).coerceAtLeast(0)
            val right = (rect.right + textSafety).coerceAtMost(width)
            val bottom = (rect.bottom + textSafety).coerceAtMost(height)
            for (y in top until bottom) {
                for (x in left until right) result[y * width + x] = 255
            }
        }

        // Fill transparent areas that cannot reach the crop exterior. These
        // are holes inside a lobe, including the blank ownership seams seen in
        // connected balloons. Pixel colors remain untouched; only alpha heals.
        val visibleMask = BooleanArray(expectedSize) {
            result[it] >= FINAL_ALPHA_VISIBLE_THRESHOLD
        }
        val exterior = BooleanArray(expectedSize)
        val exteriorQueue = IntArray(expectedSize)
        var exteriorStart = 0
        var exteriorEnd = 0
        fun enqueueExterior(index: Int) {
            if (index !in exterior.indices || exterior[index] || visibleMask[index]) return
            exterior[index] = true
            exteriorQueue[exteriorEnd++] = index
        }
        for (x in 0 until width) {
            enqueueExterior(x)
            enqueueExterior((height - 1) * width + x)
        }
        for (y in 0 until height) {
            enqueueExterior(y * width)
            enqueueExterior(y * width + width - 1)
        }
        while (exteriorStart < exteriorEnd) {
            val index = exteriorQueue[exteriorStart++]
            val x = index % width
            val y = index / width
            if (x > 0) enqueueExterior(index - 1)
            if (x + 1 < width) enqueueExterior(index + 1)
            if (y > 0) enqueueExterior(index - width)
            if (y + 1 < height) enqueueExterior(index + width)
        }
        var filledHoles = 0
        for (index in result.indices) {
            if (!visibleMask[index] && !exterior[index]) {
                result[index] = 255
                filledHoles++
            }
        }

        val repairedVisible = result.count { it >= FINAL_ALPHA_VISIBLE_THRESHOLD }
        val textArea = mappedText.sumOf { it.width() * it.height() }
        val maximumGrowth = originalVisible * 2 + textArea + 256
        if (repairedVisible > maximumGrowth) {
            logStage(
                stage = "TOPOLOGY_REPAIR",
                outcome = "REJECT",
                detail = "growth=${repairedVisible - originalVisible}/$maximumGrowth, " +
                        "tail=$tailPixels, seams=$openSeamPixels, holes=$filledHoles"
            )
            return null
        }
        logStage(
            stage = "TOPOLOGY_REPAIR",
            outcome = "READY",
            detail = "seamRadius=$seamRadius, tail=$tailPixels, openSeams=$openSeamPixels, " +
                    "holes=$filledHoles, " +
                    "growth=${repairedVisible - originalVisible}"
        )
        return result
    }

    /**
     * Last-resort presentation for real OCR that has no trustworthy exact
     * segmentation. It keeps a small amount of native page context around the
     * words and uses a softly feathered rounded edge. The reader therefore
     * shows a deliberate magnified excerpt instead of a missing item or a hard
     * debug rectangle.
     */
    private fun createPolishedPresentationAlpha(
        width: Int,
        height: Int,
        sourceBounds: SourceBounds,
        textRegions: List<DialogueTextRegion>
    ): IntArray? {
        if (width < 4 || height < 4 || textRegions.isEmpty()) return null
        val scaleX = width / (sourceBounds.right - sourceBounds.left).coerceAtLeast(1f)
        val scaleY = height / (sourceBounds.bottom - sourceBounds.top).coerceAtLeast(1f)
        val mapped = textRegions.map { region ->
            val left = ((region.left - sourceBounds.left) * scaleX).roundToInt()
                .coerceIn(0, width - 1)
            val top = ((region.top - sourceBounds.top) * scaleY).roundToInt()
                .coerceIn(0, height - 1)
            val right = ((region.right - sourceBounds.left) * scaleX).roundToInt()
                .coerceIn(left + 1, width)
            val bottom = ((region.bottom - sourceBounds.top) * scaleY).roundToInt()
                .coerceIn(top + 1, height)
            Rect(left, top, right, bottom)
        }
        val lineHeights = textRegions.zip(mapped).map { (region, bounds) ->
            bounds.height().toFloat() / region.lineCount.coerceAtLeast(1)
        }.sorted()
        val medianLineHeight = lineHeights[lineHeights.size / 2].coerceAtLeast(4f)
        val textLeft = mapped.minOf { it.left }
        val textTop = mapped.minOf { it.top }
        val textRight = mapped.maxOf { it.right }
        val textBottom = mapped.maxOf { it.bottom }
        val textWidth = (textRight - textLeft).coerceAtLeast(1)
        val textHeight = (textBottom - textTop).coerceAtLeast(1)
        val paddingX = max(
            (medianLineHeight * 0.90f).roundToInt(),
            (textWidth * 0.10f).roundToInt()
        ).coerceAtLeast(4)
        val paddingY = max(
            (medianLineHeight * 0.65f).roundToInt(),
            (textHeight * 0.12f).roundToInt()
        ).coerceAtLeast(4)
        val left = (textLeft - paddingX).coerceAtLeast(0)
        val top = (textTop - paddingY).coerceAtLeast(0)
        val right = (textRight + paddingX).coerceAtMost(width)
        val bottom = (textBottom + paddingY).coerceAtMost(height)
        if (right - left < 3 || bottom - top < 3) return null

        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        val halfWidth = (right - left) / 2f
        val halfHeight = (bottom - top) / 2f
        val radius = max(
            medianLineHeight * 0.48f,
            min(halfWidth, halfHeight) * 0.18f
        ).coerceAtMost(min(halfWidth, halfHeight) - 0.5f).coerceAtLeast(1f)
        val feather = (medianLineHeight * 0.10f).coerceIn(1.25f, 5f)
        val alpha = IntArray(width * height)
        for (y in top until bottom) {
            for (x in left until right) {
                val qx = abs((x + 0.5f) - centerX) - (halfWidth - radius)
                val qy = abs((y + 0.5f) - centerY) - (halfHeight - radius)
                val outside = sqrt(
                    max(qx, 0f) * max(qx, 0f) +
                            max(qy, 0f) * max(qy, 0f)
                )
                val inside = min(max(qx, qy), 0f)
                val signedDistance = outside + inside - radius
                val coverage = ((feather - signedDistance) / (feather * 2f))
                    .coerceIn(0f, 1f)
                alpha[y * width + x] = (coverage * 255f).roundToInt()
            }
        }
        // OCR pixels are the non-negotiable content of this fallback. Keep the
        // whole recognized block opaque even when it sits close to a crop edge.
        mapped.forEach { bounds ->
            for (y in bounds.top until bounds.bottom) {
                for (x in bounds.left until bounds.right) {
                    alpha[y * width + x] = 255
                }
            }
        }
        return alpha
    }

    private fun passesPresentationFallbackIntegrity(
        alpha: IntArray,
        width: Int,
        height: Int,
        sourceBounds: SourceBounds,
        textRegions: List<DialogueTextRegion>
    ): Boolean {
        if (alpha.size != width * height || textRegions.isEmpty()) return false
        val visible = alpha.count { it >= FINAL_ALPHA_VISIBLE_THRESHOLD }
        if (visible < 24) return false
        val scaleX = width / (sourceBounds.right - sourceBounds.left).coerceAtLeast(1f)
        val scaleY = height / (sourceBounds.bottom - sourceBounds.top).coerceAtLeast(1f)
        val preservesText = textRegions.all { region ->
            val left = ((region.left - sourceBounds.left) * scaleX).roundToInt()
                .coerceIn(0, width - 1)
            val top = ((region.top - sourceBounds.top) * scaleY).roundToInt()
                .coerceIn(0, height - 1)
            val right = ((region.right - sourceBounds.left) * scaleX).roundToInt()
                .coerceIn(left + 1, width)
            val bottom = ((region.bottom - sourceBounds.top) * scaleY).roundToInt()
                .coerceIn(top + 1, height)
            var solid = 0
            val total = (right - left) * (bottom - top)
            for (y in top until bottom) {
                for (x in left until right) {
                    if (alpha[y * width + x] >= FINAL_ALPHA_SOLID_THRESHOLD) solid++
                }
            }
            solid.toFloat() / total.coerceAtLeast(1) >= OCR_ALPHA_REQUIRED_COVERAGE
        }
        logStage(
            stage = "FALLBACK_INTEGRITY",
            outcome = if (preservesText) "VALID" else "REJECT",
            detail = "visible=$visible/${alpha.size}, regions=${textRegions.size}"
        )
        return preservesText
    }

    private fun canUsePolishedPresentationFallback(detection: DetectedBubble): Boolean {
        val region = detection.textRegion ?: return false
        if (region.isStructuralLabel() ||
            looksLikeEnvironmentalLabel(region) ||
            looksLikeSoundEffect(region) ||
            looksLikeEditorialOrDecorativeText(region)
        ) {
            return false
        }
        val trimmed = region.text.trim()
        val letters = trimmed.count(Char::isLetter)
        val terminalPunctuation = trimmed.lastOrNull()?.let { it in ".?!…" } == true
        return letters >= 3 ||
                (letters >= 2 && terminalPunctuation && detection.coefficients != null)
    }

    /**
     * Model-only recovery is used when OCR misses every glyph in a small
     * balloon. Convert every OCR-anchored closed model component into solid
     * lobes before rendering so that this recall path cannot reintroduce
     * transparent holes or discard half of a connected balloon.
     */
    private fun createModelOnlySolidAlpha(
        width: Int,
        height: Int,
        sourceBounds: SourceBounds,
        detection: DetectedBubble,
        probabilityMask: FloatArray,
        ownershipMask: BooleanArray?,
        textRegions: List<DialogueTextRegion>
    ): IntArray? {
        if (width < 4 || height < 4) return null
        val scaleX = width / (sourceBounds.right - sourceBounds.left).coerceAtLeast(1f)
        val scaleY = height / (sourceBounds.bottom - sourceBounds.top).coerceAtLeast(1f)
        val probabilities = FloatArray(width * height)
        val core = BooleanArray(probabilities.size)
        for (index in probabilities.indices) {
            if (ownershipMask?.get(index) == false) continue
            val x = index % width
            val y = index / width
            val sourceX = sourceBounds.left + (x + 0.5f) / scaleX
            val sourceY = sourceBounds.top + (y + 0.5f) / scaleY
            val probability = detection.maskProbability(probabilityMask, sourceX, sourceY)
            probabilities[index] = probability
            core[index] = probability >= MASK_THRESHOLD
        }
        val labels = labelConnectedComponents(core, width, height)
        val labelCount = (labels.maxOrNull() ?: -1) + 1
        if (labelCount <= 0) return null
        val sizes = IntArray(labelCount)
        labels.forEach { label -> if (label >= 0) sizes[label]++ }
        val largestLabel = sizes.indices.maxByOrNull(sizes::get) ?: return null
        if (sizes[largestLabel] < 24) return null

        // V30 kept only the largest model component. That silently cut off a
        // second lobe whenever one logical connected balloon produced several
        // components. V31 keeps every component anchored by one of the OCR
        // regions, falling back to the largest only when OCR cannot anchor one.
        val selectedLabels = BooleanArray(labelCount)
        textRegions.forEach { region ->
            val left = ((region.left - sourceBounds.left) * scaleX).roundToInt()
                .coerceIn(0, width - 1)
            val top = ((region.top - sourceBounds.top) * scaleY).roundToInt()
                .coerceIn(0, height - 1)
            val right = ((region.right - sourceBounds.left) * scaleX).roundToInt()
                .coerceIn(left + 1, width)
            val bottom = ((region.bottom - sourceBounds.top) * scaleY).roundToInt()
                .coerceIn(top + 1, height)
            val overlaps = IntArray(labelCount)
            for (y in top until bottom) {
                for (x in left until right) {
                    val label = labels[y * width + x]
                    if (label >= 0) overlaps[label]++
                }
            }
            val anchored = overlaps.indices.maxByOrNull(overlaps::get)
            if (anchored != null && overlaps[anchored] > 0 && sizes[anchored] >= 12) {
                selectedLabels[anchored] = true
            }
        }
        if (selectedLabels.none { it }) selectedLabels[largestLabel] = true

        // Grow only the selected core through model-supported neighbors. This
        // keeps disconnected scenery out even when it shares the same proposal.
        val component = BooleanArray(core.size)
        val queue = IntArray(core.size)
        var queueStart = 0
        var queueEnd = 0
        for (index in labels.indices) {
            val label = labels[index]
            if (label >= 0 && selectedLabels[label]) {
                component[index] = true
                queue[queueEnd++] = index
            }
        }
        fun enqueue(index: Int) {
            if (index !in component.indices || component[index] ||
                ownershipMask?.get(index) == false || probabilities[index] < 0.30f
            ) return
            component[index] = true
            queue[queueEnd++] = index
        }
        while (queueStart < queueEnd) {
            val index = queue[queueStart++]
            val x = index % width
            val y = index / width
            if (x > 0) enqueue(index - 1)
            if (x + 1 < width) enqueue(index + 1)
            if (y > 0) enqueue(index - width)
            if (y + 1 < height) enqueue(index + width)
        }

        var left = width
        var top = height
        var right = -1
        var bottom = -1
        component.forEachIndexed { index, present ->
            if (!present) return@forEachIndexed
            val x = index % width
            val y = index / width
            left = min(left, x)
            top = min(top, y)
            right = max(right, x)
            bottom = max(bottom, y)
        }
        if (right < left || bottom < top) return null

        val exterior = BooleanArray(component.size)
        queueStart = 0
        queueEnd = 0
        fun enqueueExterior(index: Int) {
            if (index !in component.indices || exterior[index] || component[index]) return
            val x = index % width
            val y = index / width
            if (x !in left..right || y !in top..bottom) return
            exterior[index] = true
            queue[queueEnd++] = index
        }
        for (x in left..right) {
            enqueueExterior(top * width + x)
            enqueueExterior(bottom * width + x)
        }
        for (y in top..bottom) {
            enqueueExterior(y * width + left)
            enqueueExterior(y * width + right)
        }
        while (queueStart < queueEnd) {
            val index = queue[queueStart++]
            val x = index % width
            val y = index / width
            if (x > left) enqueueExterior(index - 1)
            if (x < right) enqueueExterior(index + 1)
            if (y > top) enqueueExterior(index - width)
            if (y < bottom) enqueueExterior(index + width)
        }
        val solid = BooleanArray(component.size) { index ->
            val x = index % width
            val y = index / width
            component[index] ||
                    (x in left..right && y in top..bottom &&
                            ownershipMask?.get(index) != false && !exterior[index])
        }
        val componentSize = component.count { it }
        val solidSize = solid.count { it }
        if (solidSize > componentSize * 2.20f + 64f) {
            logStage(
                stage = "MODEL_ONLY_MASK",
                outcome = "REJECT",
                detail = "interior-growth=${solidSize - componentSize}"
            )
            return null
        }
        logStage(
            stage = "MODEL_ONLY_MASK",
            outcome = "ACCEPT",
            detail = "components=${selectedLabels.count { it }}, component=$componentSize, " +
                    "filled=${solidSize - componentSize}"
        )
        return IntArray(solid.size) { index -> if (solid[index]) 255 else 0 }
    }

    /**
     * Mandatory last-line validation for the exact alpha that will be saved.
     *
     * Earlier checks judge individual native traces, guards and fallbacks. A
     * broad panel-shaped result can still survive when those pieces are merged,
     * so this pass audits the finished cutout instead. Rejection is deliberately
     * compound: an irregular or rectangular balloon remains valid unless it is
     * also implausibly broad, crop-clipped or visually busy like panel artwork.
     */
    private fun passesFinalAlphaValidation(
        alpha: IntArray,
        pixels: IntArray,
        width: Int,
        height: Int,
        sourceBounds: SourceBounds,
        detection: DetectedBubble,
        probabilityMask: FloatArray?,
        textRegions: List<DialogueTextRegion>
    ): Boolean {
        val isCaption = detection.candidateKind == CandidateKind.CAPTION
        fun reject(reason: String): Boolean {
            logStage(
                stage = "FINAL_ALPHA",
                outcome = "REJECT",
                detail = "kind=${detection.candidateKind}, $reason",
                text = detection.textRegion?.text.orEmpty()
            )
            return false
        }
        if (alpha.size != width * height || pixels.size != alpha.size) {
            return reject("invalid-buffer-size")
        }

        var visiblePixels = 0
        var solidPixels = 0
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (index in alpha.indices) {
            if (alpha[index] < FINAL_ALPHA_VISIBLE_THRESHOLD) continue
            visiblePixels++
            val x = index % width
            val y = index / width
            left = min(left, x)
            top = min(top, y)
            right = max(right, x)
            bottom = max(bottom, y)
            if (alpha[index] >= FINAL_ALPHA_SOLID_THRESHOLD) solidPixels++
        }
        if (visiblePixels == 0 || solidPixels == 0 || right < left || bottom < top) {
            return reject("empty-or-non-solid-mask")
        }

        val cropArea = width.toFloat() * height.toFloat()
        val boxWidth = right - left + 1
        val boxHeight = bottom - top + 1
        val boxArea = boxWidth.toFloat() * boxHeight.toFloat()
        val cropCoverage = visiblePixels / cropArea
        val boxCoverage = visiblePixels / boxArea.coerceAtLeast(1f)
        val boxToCrop = boxArea / cropArea
        val aspectRatio = max(
            boxWidth.toFloat() / boxHeight.coerceAtLeast(1),
            boxHeight.toFloat() / boxWidth.coerceAtLeast(1)
        )

        val scaleX = width / (sourceBounds.right - sourceBounds.left).coerceAtLeast(1f)
        val scaleY = height / (sourceBounds.bottom - sourceBounds.top).coerceAtLeast(1f)
        val mappedTextBounds = textRegions.map { region ->
            val mappedLeft = ((region.left - sourceBounds.left) * scaleX)
                .roundToInt().coerceIn(0, width - 1)
            val mappedTop = ((region.top - sourceBounds.top) * scaleY)
                .roundToInt().coerceIn(0, height - 1)
            val mappedRight = ((region.right - sourceBounds.left) * scaleX)
                .roundToInt().coerceIn(mappedLeft + 1, width)
            val mappedBottom = ((region.bottom - sourceBounds.top) * scaleY)
                .roundToInt().coerceIn(mappedTop + 1, height)
            Rect(mappedLeft, mappedTop, mappedRight, mappedBottom)
        }
        for (bounds in mappedTextBounds) {
            var textPixels = 0
            var solidTextPixels = 0
            for (y in bounds.top until bounds.bottom) {
                for (x in bounds.left until bounds.right) {
                    textPixels++
                    if (alpha[y * width + x] >= FINAL_ALPHA_SOLID_THRESHOLD) {
                        solidTextPixels++
                    }
                }
            }
            val textCoverage = solidTextPixels.toFloat() / textPixels.coerceAtLeast(1)
            if (textCoverage < OCR_ALPHA_REQUIRED_COVERAGE) {
                return reject("text-alpha=${formatScore(textCoverage)}")
            }
        }
        val textArea = mappedTextBounds.sumOf { bounds ->
            bounds.width().toLong() * bounds.height().toLong()
        }.coerceAtLeast(1L).toFloat()
        val visibleToText = if (mappedTextBounds.isEmpty()) {
            1f
        } else {
            visiblePixels / textArea
        }
        val boxToText = if (mappedTextBounds.isEmpty()) {
            1f
        } else {
            boxArea / textArea
        }

        fun sideContactCount(): Pair<Int, Int> {
            var touchedSides = 0
            var longContactSides = 0
            fun register(contact: Int, span: Int) {
                if (contact >= max(3, (span * 0.025f).roundToInt())) touchedSides++
                if (contact >= max(6, (span * 0.18f).roundToInt())) longContactSides++
            }
            var topContact = 0
            var bottomContact = 0
            for (x in 0 until width) {
                if (alpha[x] >= FINAL_ALPHA_VISIBLE_THRESHOLD) topContact++
                if (alpha[(height - 1) * width + x] >= FINAL_ALPHA_VISIBLE_THRESHOLD) {
                    bottomContact++
                }
            }
            var leftContact = 0
            var rightContact = 0
            for (y in 0 until height) {
                if (alpha[y * width] >= FINAL_ALPHA_VISIBLE_THRESHOLD) leftContact++
                if (alpha[y * width + width - 1] >= FINAL_ALPHA_VISIBLE_THRESHOLD) {
                    rightContact++
                }
            }
            register(topContact, width)
            register(bottomContact, width)
            register(leftContact, height)
            register(rightContact, height)
            return touchedSides to longContactSides
        }

        val (touchedSides, longContactSides) = sideContactCount()
        val broadRelativeToText = mappedTextBounds.isNotEmpty() &&
                (visibleToText > 13f || boxToText > 18f)
        val cropDominating = cropCoverage > 0.62f || boxToCrop > 0.82f
        val stronglyCropClipped = touchedSides >= 3 ||
                (longContactSides >= 2 && boxToCrop > 0.42f)
        if (!isCaption && stronglyCropClipped &&
            (cropCoverage > 0.34f || broadRelativeToText)
        ) {
            return reject(
                "crop-clipped; touched=$touchedSides, long=$longContactSides, " +
                        "crop=${formatScore(cropCoverage)}, boxToText=${formatScore(boxToText)}"
            )
        }

        // Ignore a padded band around OCR while measuring visual complexity.
        // This removes letters and their antialiasing from the background test.
        val textExclusionMask = BooleanArray(alpha.size)
        mappedTextBounds.forEach { bounds ->
            val paddingX = max(3, (bounds.width() * 0.16f).roundToInt())
            val paddingY = max(3, (bounds.height() * 0.16f).roundToInt())
            val exclusionLeft = (bounds.left - paddingX).coerceAtLeast(0)
            val exclusionTop = (bounds.top - paddingY).coerceAtLeast(0)
            val exclusionRight = (bounds.right + paddingX).coerceAtMost(width)
            val exclusionBottom = (bounds.bottom + paddingY).coerceAtMost(height)
            for (y in exclusionTop until exclusionBottom) {
                for (x in exclusionLeft until exclusionRight) {
                    textExclusionMask[y * width + x] = true
                }
            }
        }
        fun insidePaddedText(x: Int, y: Int): Boolean = textExclusionMask[y * width + x]

        val colorHistogram = IntArray(4096)
        var sampledPixels = 0
        var localComparisons = 0
        var strongLocalEdges = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val index = y * width + x
                if (alpha[index] < FINAL_ALPHA_VISIBLE_THRESHOLD || insidePaddedText(x, y)) {
                    continue
                }
                val color = pixels[index]
                val key = ((Color.red(color) shr 4) shl 8) or
                        ((Color.green(color) shr 4) shl 4) or
                        (Color.blue(color) shr 4)
                colorHistogram[key]++
                sampledPixels++
                if (x + 1 <= right && alpha[index + 1] >= FINAL_ALPHA_VISIBLE_THRESHOLD &&
                    !insidePaddedText(x + 1, y)
                ) {
                    localComparisons++
                    if (colorDistance(color, pixels[index + 1]) >= 96) strongLocalEdges++
                }
                if (y + 1 <= bottom && alpha[index + width] >= FINAL_ALPHA_VISIBLE_THRESHOLD &&
                    !insidePaddedText(x, y + 1)
                ) {
                    localComparisons++
                    if (colorDistance(color, pixels[index + width]) >= 96) strongLocalEdges++
                }
            }
        }
        val dominantColorRatio = if (sampledPixels > 0) {
            (colorHistogram.maxOrNull() ?: 0).toFloat() / sampledPixels
        } else {
            1f
        }
        val strongEdgeRatio = if (localComparisons > 0) {
            strongLocalEdges.toFloat() / localComparisons
        } else {
            0f
        }
        val visuallyBusy = sampledPixels >= 96 &&
                dominantColorRatio < 0.18f &&
                strongEdgeRatio > 0.16f
        val extremelyBusy = sampledPixels >= 96 &&
                dominantColorRatio < 0.11f &&
                strongEdgeRatio > 0.22f
        val stripLike = aspectRatio > 2.65f &&
                boxCoverage > 0.66f &&
                (boxToCrop > 0.42f || broadRelativeToText)
        val compactOcr = detection.textRegion?.text.orEmpty()
            .uppercase()
            .filter(Char::isLetterOrDigit)
        val tinyOcrFragment = compactOcr.length in 1..2 && textRegions.size == 1

        if (isCaption) {
            // Wide narration rectangles are expected to look strip-like and to
            // touch a tight OCR fallback crop. They are accepted only while the
            // saved surface remains color-quiet and reasonably close to its OCR
            // text, which is the signature that separates a caption box from a
            // cropped piece of panel artwork.
            if (mappedTextBounds.isEmpty()) return reject("caption-without-ocr")
            if (extremelyBusy) {
                return reject(
                    "caption-extremely-busy; dominant=${formatScore(dominantColorRatio)}, " +
                            "edges=${formatScore(strongEdgeRatio)}"
                )
            }
            if (visuallyBusy && (cropDominating || broadRelativeToText)) {
                return reject(
                    "caption-busy-and-broad; dominant=${formatScore(dominantColorRatio)}, " +
                            "edges=${formatScore(strongEdgeRatio)}, " +
                            "boxToText=${formatScore(boxToText)}"
                )
            }
            if (boxToText > 15f || visibleToText > 12f) {
                return reject(
                    "caption-too-broad; visibleToText=${formatScore(visibleToText)}, " +
                            "boxToText=${formatScore(boxToText)}"
                )
            }
            if (stronglyCropClipped &&
                (dominantColorRatio < 0.16f || strongEdgeRatio > 0.18f)
            ) {
                return reject(
                    "caption-clipped-and-noisy; touched=$touchedSides, " +
                            "dominant=${formatScore(dominantColorRatio)}, " +
                            "edges=${formatScore(strongEdgeRatio)}"
                )
            }
        } else {
            // Tiny OCR hallucinations inside architecture or stained glass can
            // occasionally acquire a large native enclosure. Real two-letter
            // dialogue remains valid when its balloon surface is quiet; reject
            // only the compound signature observed on the false "AA" result.
            if (tinyOcrFragment &&
                boxToText > 10f &&
                dominantColorRatio < 0.18f &&
                (cropCoverage > 0.55f || broadRelativeToText)
            ) {
                return reject(
                    "tiny-ocr-huge-busy; chars=${compactOcr.length}, " +
                            "boxToText=${formatScore(boxToText)}, " +
                            "dominant=${formatScore(dominantColorRatio)}"
                )
            }
            if (visuallyBusy && (cropDominating || broadRelativeToText || stripLike)) {
                return reject(
                    "busy-background; dominant=${formatScore(dominantColorRatio)}, " +
                            "edges=${formatScore(strongEdgeRatio)}"
                )
            }
            if (extremelyBusy && boxToCrop > 0.30f) {
                return reject("extremely-busy; boxToCrop=${formatScore(boxToCrop)}")
            }
            if (stripLike && longContactSides > 0 && mappedTextBounds.isNotEmpty()) {
                return reject(
                    "speech-mask-strip; aspect=${formatScore(aspectRatio)}, " +
                            "long-contact=$longContactSides"
                )
            }
        }

        if (probabilityMask != null) {
            var modelSupportedPixels = 0
            val supportThreshold = FINAL_ALPHA_LOOSE_THRESHOLD
            for (index in alpha.indices) {
                if (alpha[index] < FINAL_ALPHA_SOLID_THRESHOLD) continue
                val x = index % width
                val y = index / width
                val sourceX = sourceBounds.left + (x + 0.5f) / scaleX
                val sourceY = sourceBounds.top + (y + 0.5f) / scaleY
                if (detection.maskProbability(probabilityMask, sourceX, sourceY) >=
                    supportThreshold
                ) {
                    modelSupportedPixels++
                }
            }
            val supportRatio = modelSupportedPixels.toFloat() / solidPixels.coerceAtLeast(1)
            if (supportRatio < 0.30f &&
                (visuallyBusy || (!isCaption && (cropDominating || broadRelativeToText)))
            ) {
                return reject(
                    "weak-model-support=${formatScore(supportRatio)}, " +
                            "busy=$visuallyBusy, cropDominating=$cropDominating"
                )
            }
        }

        logStage(
            stage = "FINAL_ALPHA",
            outcome = "VALIDATED",
            detail = "kind=${detection.candidateKind}, crop=${formatScore(cropCoverage)}, " +
                    "boxToText=${formatScore(boxToText)}, " +
                    "dominant=${formatScore(dominantColorRatio)}, " +
                    "edges=${formatScore(strongEdgeRatio)}",
            text = detection.textRegion?.text.orEmpty()
        )
        return true
    }

    /**
     * Builds a dual mask at the original page resolution.
     *
     * The native-color component supplies the crisp artist-drawn contour while
     * a softly expanded ML mask acts only as a guard rail. Every distinct lobe
     * is closed and hole-filled independently before the lobes are merged. This
     * keeps panel artwork outside the cutout and keeps the original white paper
     * behind dark lettering fully opaque.
     */
    private fun createTextRefinedAlpha(
        pixels: IntArray,
        width: Int,
        height: Int,
        sourceBounds: SourceBounds,
        detection: DetectedBubble,
        probabilityMask: FloatArray?,
        textRegions: List<DialogueTextRegion>,
        ownershipMask: BooleanArray?
    ): RefinedAlphaResult? {
        if (width < 4 || height < 4 || textRegions.isEmpty()) return null
        val scaleX = width / (sourceBounds.right - sourceBounds.left).coerceAtLeast(1f)
        val scaleY = height / (sourceBounds.bottom - sourceBounds.top).coerceAtLeast(1f)
        fun mappedBounds(region: DialogueTextRegion): Rect {
            val left = ((region.left - sourceBounds.left) * scaleX).roundToInt()
                .coerceIn(0, width - 1)
            val top = ((region.top - sourceBounds.top) * scaleY).roundToInt()
                .coerceIn(0, height - 1)
            val right = ((region.right - sourceBounds.left) * scaleX).roundToInt()
                .coerceIn(left + 1, width)
            val bottom = ((region.bottom - sourceBounds.top) * scaleY).roundToInt()
                .coerceIn(top + 1, height)
            return Rect(left, top, right, bottom)
        }

        val mappedTextBounds = textRegions.map(::mappedBounds)
        val lineHeights = textRegions.zip(mappedTextBounds)
            .map { (region, bounds) ->
                bounds.height().toFloat() / region.lineCount.coerceAtLeast(1)
            }
            .sorted()
        val medianLineHeight = lineHeights[lineHeights.size / 2].coerceAtLeast(4f)

        // The model never becomes the visible edge. It supplies a low-frequency
        // safety region around the native contour, so a broken ink outline
        // cannot let the color flood wander into the surrounding panel.
        val modelConfidence = ByteArray(width * height)
        if (probabilityMask == null) {
            modelConfidence.fill(0xFF.toByte())
        } else {
            for (index in modelConfidence.indices) {
                val x = index % width
                val y = index / width
                val sourceX = sourceBounds.left + (x + 0.5f) / scaleX
                val sourceY = sourceBounds.top + (y + 0.5f) / scaleY
                val probability = detection.maskProbability(probabilityMask, sourceX, sourceY)
                modelConfidence[index] = (probability.coerceIn(0f, 1f) * 255f)
                    .roundToInt()
                    .toByte()
            }
        }
        fun confidenceAt(index: Int): Int = modelConfidence[index].toInt() and 0xFF
        val seedThreshold = (NATIVE_SEED_MASK_THRESHOLD * 255f).roundToInt()
        val supportThreshold = (NATIVE_COMPONENT_SUPPORT_THRESHOLD * 255f).roundToInt()
        // OCR pixels unambiguously belong to this dialogue item. Never let an
        // imperfect watershed ownership boundary punch holes through letters.
        val ocrCore = BooleanArray(width * height)
        mappedTextBounds.forEach { bounds ->
            for (y in bounds.top until bounds.bottom) {
                for (x in bounds.left until bounds.right) {
                    ocrCore[y * width + x] = true
                }
            }
        }
        val nativeAllowed = BooleanArray(width * height) { index ->
            ownershipMask?.get(index) != false || ocrCore[index]
        }

        // A two-pass chamfer distance field gives a nearly circular expansion
        // in O(pixel count). Costs are stored in thirds of a native pixel.
        fun nativeDistanceField(source: BooleanArray, allowed: BooleanArray): IntArray {
            val far = 1_000_000
            val distance = IntArray(source.size) { index ->
                if (source[index]) 0 else far
            }
            fun relax(index: Int, neighbor: Int, cost: Int) {
                if (neighbor !in distance.indices || !allowed[neighbor]) return
                distance[index] = min(distance[index], distance[neighbor] + cost)
            }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    if (!allowed[index] || distance[index] == 0) continue
                    if (x > 0) relax(index, index - 1, 3)
                    if (y > 0) relax(index, index - width, 3)
                    if (x > 0 && y > 0) relax(index, index - width - 1, 4)
                    if (x + 1 < width && y > 0) relax(index, index - width + 1, 4)
                }
            }
            for (y in height - 1 downTo 0) {
                for (x in width - 1 downTo 0) {
                    val index = y * width + x
                    if (!allowed[index] || distance[index] == 0) continue
                    if (x + 1 < width) relax(index, index + 1, 3)
                    if (y + 1 < height) relax(index, index + width, 3)
                    if (x + 1 < width && y + 1 < height) {
                        relax(index, index + width + 1, 4)
                    }
                    if (x > 0 && y + 1 < height) relax(index, index + width - 1, 4)
                }
            }
            return distance
        }

        val modelCore = probabilityMask?.let {
            val guardCoreThreshold = (NATIVE_GUARD_CORE_THRESHOLD * 255f).roundToInt()
            BooleanArray(width * height) { index ->
                nativeAllowed[index] && confidenceAt(index) >= guardCoreThreshold
            }
        }
        if (probabilityMask != null && modelCore?.none { it } != false) return null

        val nativeGuard = if (modelCore != null) {
            val guardLooseThreshold = (NATIVE_GUARD_LOOSE_THRESHOLD * 255f).roundToInt()
            val distanceFromModel = nativeDistanceField(modelCore, nativeAllowed)
            val guardRadius = (medianLineHeight * 0.90f).roundToInt().coerceIn(7, 48)
            BooleanArray(width * height) { index ->
                nativeAllowed[index] &&
                        (confidenceAt(index) >= guardLooseThreshold ||
                                distanceFromModel[index] <= guardRadius * 3)
            }
        } else {
            // OCR-only captions have no segmentation prototype. Their already
            // conservative detection crop becomes the guard, tightened around
            // the recognized text so a matching panel color cannot fill the
            // entire crop.
            val fallbackGuard = BooleanArray(width * height)
            mappedTextBounds.forEach { bounds ->
                val paddingX = max(
                    (bounds.width() * 0.72f).roundToInt(),
                    (medianLineHeight * 2.4f).roundToInt()
                )
                val paddingY = max(
                    (bounds.height() * 0.72f).roundToInt(),
                    (medianLineHeight * 1.8f).roundToInt()
                )
                val left = (bounds.left - paddingX).coerceAtLeast(0)
                val top = (bounds.top - paddingY).coerceAtLeast(0)
                val right = (bounds.right + paddingX).coerceAtMost(width)
                val bottom = (bounds.bottom + paddingY).coerceAtMost(height)
                for (y in top until bottom) {
                    val row = y * width
                    for (x in left until right) {
                        val index = row + x
                        if (nativeAllowed[index]) fallbackGuard[index] = true
                    }
                }
            }
            fallbackGuard
        }

        /** Reconnects small confidence gaps without turning the ML edge into the visible edge. */
        fun closeCoreMask(source: BooleanArray, radius: Int): BooleanArray {
            if (radius <= 0) return source.copyOf()
            val distanceFromCore = nativeDistanceField(source, nativeAllowed)
            val dilated = BooleanArray(source.size) { index ->
                nativeAllowed[index] && distanceFromCore[index] <= radius * 3
            }
            val outside = BooleanArray(source.size) { index ->
                !nativeAllowed[index] || !dilated[index]
            }
            val unrestricted = BooleanArray(source.size) { true }
            val distanceFromOutside = nativeDistanceField(outside, unrestricted)
            return BooleanArray(source.size) { index ->
                nativeAllowed[index] && dilated[index] &&
                        distanceFromOutside[index] > radius * 3
            }
        }

        // Raw ONNX cores often fragment around letters and thin connectors.
        // Close those small gaps first, then discard specks that are far too
        // small to represent a useful dialogue lobe.
        val stableCoreLabels: IntArray
        val componentGuards: List<BooleanArray>
        val componentDistances: List<IntArray>
        if (modelCore != null) {
            val closeRadius = (medianLineHeight * NATIVE_FENCE_CLOSE_LINE_RATIO)
                .roundToInt().coerceIn(2, 12)
            val closedCore = closeCoreMask(modelCore, closeRadius)
            val preliminaryLabels = labelConnectedComponents(closedCore, width, height)
            val preliminaryCount = (preliminaryLabels.maxOrNull() ?: -1) + 1
            val componentSizes = IntArray(preliminaryCount)
            preliminaryLabels.forEach { label ->
                if (label >= 0) componentSizes[label]++
            }
            val minimumCoreSize = max(
                16,
                (medianLineHeight * medianLineHeight * 0.06f).roundToInt()
            )
            val cleanedCore = BooleanArray(closedCore.size) { index ->
                val label = preliminaryLabels[index]
                label >= 0 && componentSizes[label] >= minimumCoreSize
            }
            val stableCore = if (cleanedCore.any { it }) cleanedCore else modelCore
            stableCoreLabels = labelConnectedComponents(stableCore, width, height)
            val stableLabelCount = (stableCoreLabels.maxOrNull() ?: -1) + 1
            if (stableLabelCount <= 0) return null

            // Each stable core receives the portion of V14's original union
            // guard that is nearest to it. A small overlap avoids an artificial
            // seam if two valid lobes meet at a thin connector.
            val distancesByLabel = List(stableLabelCount) { label ->
                val component = BooleanArray(stableCore.size) { index ->
                    stableCoreLabels[index] == label
                }
                nativeDistanceField(component, nativeAllowed)
            }
            val overlap = (medianLineHeight * NATIVE_FENCE_OVERLAP_LINE_RATIO)
                .roundToInt().coerceIn(2, 10) * 3
            val guards = List(stableLabelCount) { BooleanArray(nativeGuard.size) }
            for (index in nativeGuard.indices) {
                if (!nativeGuard[index]) continue
                var nearestDistance = Int.MAX_VALUE
                for (label in 0 until stableLabelCount) {
                    nearestDistance = min(nearestDistance, distancesByLabel[label][index])
                }
                for (label in 0 until stableLabelCount) {
                    if (distancesByLabel[label][index] <= nearestDistance + overlap) {
                        guards[label][index] = true
                    }
                }
            }
            componentGuards = guards
            componentDistances = distancesByLabel
        } else {
            stableCoreLabels = IntArray(nativeGuard.size) { index ->
                if (nativeGuard[index]) 0 else -1
            }
            componentGuards = listOf(nativeGuard)
            componentDistances = listOf(IntArray(nativeGuard.size))
        }
        val markerAllowed = BooleanArray(width * height) { index ->
            nativeGuard[index] || ocrCore[index]
        }
        val seedAllowed = BooleanArray(width * height) { index ->
            nativeGuard[index] && confidenceAt(index) >= seedThreshold
        }

        // Give every OCR region a native fill seed. Regions whose seeds resolve
        // to the same fill component are one lobe; visually separate lobes are
        // processed independently and merged only after their interiors are
        // solid. This is what preserves connected dialogue without one broad
        // rectangular matte.
        val occupiedSeeds = BooleanArray(width * height)
        val markers = textRegions.map { region ->
            val marker = findPartitionMarker(
                pixels = pixels,
                width = width,
                height = height,
                bounds = sourceBounds,
                region = region,
                occupied = occupiedSeeds,
                allowed = seedAllowed
            ) ?: findPartitionMarker(
                pixels = pixels,
                width = width,
                height = height,
                bounds = sourceBounds,
                region = region,
                occupied = occupiedSeeds,
                allowed = markerAllowed
            )
            marker?.also { occupiedSeeds[it.index] = true }
        }
        if (markers.any { it == null }) return null

        fun nearestStableComponent(index: Int): Int {
            if (componentGuards.size == 1) return 0
            val direct = stableCoreLabels.getOrNull(index) ?: -1
            if (direct >= 0) return direct
            var bestLabel = 0
            var bestDistance = Int.MAX_VALUE
            componentDistances.forEachIndexed { label, distance ->
                if (distance[index] < bestDistance) {
                    bestDistance = distance[index]
                    bestLabel = label
                }
            }
            return bestLabel
        }
        val markerLabels = markers.map { marker ->
            nearestStableComponent(marker?.index ?: 0)
        }

        val lobeMasks = mutableListOf<BooleanArray>()
        val lobeRegions = mutableListOf<MutableList<Int>>()
        val lobeLabels = mutableListOf<Int>()
        val lobeGuards = mutableListOf<BooleanArray>()
        val lobeFillColors = mutableListOf<Int>()
        val lobeMarkers = mutableListOf<PartitionMarker>()
        markers.forEachIndexed { regionIndex, marker ->
            val resolvedMarker = marker ?: return@forEachIndexed
            val markerLabel = markerLabels[regionIndex]
            val markerGuard = componentGuards.getOrNull(markerLabel) ?: nativeGuard
            val existingLobe = lobeMasks.indices.indexOfFirst { lobeIndex ->
                lobeLabels[lobeIndex] == markerLabel &&
                        lobeMasks[lobeIndex].getOrNull(resolvedMarker.index) == true
            }
            if (existingLobe >= 0) {
                lobeRegions[existingLobe] += regionIndex
            } else {
                val component = traceFillComponent(
                    pixels = pixels,
                    width = width,
                    height = height,
                    marker = resolvedMarker,
                    allowed = markerGuard
                )
                lobeMasks += component
                lobeRegions += mutableListOf(regionIndex)
                lobeLabels += markerLabel
                lobeGuards += markerGuard
                lobeFillColors += resolvedMarker.dominantColor
                lobeMarkers += resolvedMarker
            }
        }

        // Flood the transparent exterior from every guard boundary. Anything
        // transparent that remains unreachable is a genuine enclosed interior
        // hole (normally letters and antialiased ink) and must stay opaque.
        fun fillEnclosedInterior(mask: BooleanArray, guard: BooleanArray): BooleanArray {
            val exterior = BooleanArray(mask.size)
            val queue = IntArray(mask.size)
            var queueStart = 0
            var queueEnd = 0
            fun enqueue(index: Int) {
                if (index !in mask.indices || exterior[index] || mask[index] || !guard[index]) {
                    return
                }
                exterior[index] = true
                queue[queueEnd++] = index
            }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    if (!guard[index] || mask[index]) continue
                    val touchesGuardBoundary = x == 0 || y == 0 || x == width - 1 || y == height - 1 ||
                            (x > 0 && !guard[index - 1]) ||
                            (x + 1 < width && !guard[index + 1]) ||
                            (y > 0 && !guard[index - width]) ||
                            (y + 1 < height && !guard[index + width])
                    if (touchesGuardBoundary) enqueue(index)
                }
            }
            while (queueStart < queueEnd) {
                val index = queue[queueStart++]
                val x = index % width
                val y = index / width
                if (x > 0) enqueue(index - 1)
                if (x + 1 < width) enqueue(index + 1)
                if (y > 0) enqueue(index - width)
                if (y + 1 < height) enqueue(index + width)
            }
            return BooleanArray(mask.size) { index ->
                mask[index] || (guard[index] && !exterior[index])
            }
        }

        /**
         * Seals small openings in an accepted lobe contour before exterior
         * flooding. Unlike OCR repair, this is driven only by the lobe's own
         * geometry, so a word missed by ML Kit cannot leave a transparent tunnel
         * from the page background into the balloon interior.
         */
        fun closeSmallContourGaps(
            mask: BooleanArray,
            guard: BooleanArray,
            radius: Int
        ): BooleanArray {
            if (radius <= 0) return mask.copyOf()
            val distanceFromMask = nativeDistanceField(mask, guard)
            val dilated = BooleanArray(mask.size) { index ->
                guard[index] && distanceFromMask[index] <= radius * 3
            }
            val unrestricted = BooleanArray(mask.size) { true }
            val outsideDilated = BooleanArray(mask.size) { index ->
                !guard[index] || !dilated[index]
            }
            val distanceFromOutside = nativeDistanceField(outsideDilated, unrestricted)
            return BooleanArray(mask.size) { index ->
                mask[index] ||
                        (guard[index] && dilated[index] &&
                                distanceFromOutside[index] > radius * 3)
            }
        }

        // Grow each exact native fill just enough to retain its printed outline.
        val outlinePasses = (medianLineHeight * 0.11f).roundToInt().coerceIn(2, 12)
        val textSafetyRadius = (medianLineHeight * 0.68f).roundToInt()
            .coerceIn(outlinePasses, 36)
        val mergedAlpha = IntArray(width * height)
        val finalAlphaGuard = BooleanArray(width * height)
        var visiblePixels = 0
        fun mergeAlpha(index: Int, alpha: Int) {
            val boundedAlpha = alpha.coerceIn(0, 255)
            if (mergedAlpha[index] <= 8 && boundedAlpha > 8) visiblePixels++
            if (boundedAlpha > mergedAlpha[index]) mergedAlpha[index] = boundedAlpha
        }
        fun mergeSolidModelFallback(
            guard: BooleanArray,
            regionIndices: List<Int>
        ): Boolean {
            // A failed native trace no longer renders the soft model alpha
            // literally. Grow only the model component that owns this lobe,
            // seal its contour and fill it completely before compositing.
            val fallbackThreshold = (0.44f * 255f).roundToInt()
            val eligible = BooleanArray(guard.size) { index ->
                guard[index] && confidenceAt(index) >= fallbackThreshold
            }
            val component = BooleanArray(guard.size)
            val queue = IntArray(guard.size)
            var queueStart = 0
            var queueEnd = 0
            regionIndices.forEach { regionIndex ->
                val bounds = mappedTextBounds[regionIndex]
                for (y in bounds.top until bounds.bottom) {
                    for (x in bounds.left until bounds.right) {
                        val index = y * width + x
                        if (eligible[index] && !component[index]) {
                            component[index] = true
                            queue[queueEnd++] = index
                        }
                    }
                }
            }
            if (queueEnd == 0) return false
            fun enqueue(index: Int) {
                if (index !in component.indices || component[index] || !eligible[index]) return
                component[index] = true
                queue[queueEnd++] = index
            }
            while (queueStart < queueEnd) {
                val index = queue[queueStart++]
                val x = index % width
                val y = index / width
                if (x > 0) enqueue(index - 1)
                if (x + 1 < width) enqueue(index + 1)
                if (y > 0) enqueue(index - width)
                if (y + 1 < height) enqueue(index + width)
            }
            val componentSize = component.count { it }
            if (componentSize < 24) return false
            val closeRadius = (medianLineHeight * FULL_LOBE_CLOSE_LINE_RATIO)
                .roundToInt()
                .coerceIn(2, 14)
            val contourClosed = closeSmallContourGaps(component, guard, closeRadius)
            val repaired = fillEnclosedInterior(contourClosed, guard)
            regionIndices.forEach { regionIndex ->
                val bounds = mappedTextBounds[regionIndex]
                for (y in bounds.top until bounds.bottom) {
                    for (x in bounds.left until bounds.right) {
                        val index = y * width + x
                        if (guard[index]) repaired[index] = true
                    }
                }
            }
            val finalSolid = fillEnclosedInterior(repaired, guard)
            val finalSize = finalSolid.count { it }
            val textArea = regionIndices.sumOf { index ->
                mappedTextBounds[index].let { it.width() * it.height() }
            }
            val maximumGrowth = max(
                textArea + 96,
                (componentSize * 0.55f).roundToInt() + 96
            )
            if (finalSize - componentSize > maximumGrowth) return false
            finalSolid.forEachIndexed { index, present ->
                if (present && guard[index]) mergeAlpha(index, 255)
            }
            return true
        }

        /**
         * Recovers a small OCR-confirmed lobe from a broad ONNX proposal by
         * using the already-computed watershed ownership map as a virtual
         * boundary. This is intentionally limited to shared proposals: it can
         * separate a touching lobe such as "SURE." from its larger neighbour,
         * but it cannot promote an unrelated OCR fragment or textless shape.
         */
        fun mergeOwnershipPartitionRecovery(
            marker: PartitionMarker,
            regionIndices: List<Int>
        ): Boolean {
            val recoveryText = regionIndices.joinToString(" ") { textRegions[it].text }
            fun reject(reason: String): Boolean {
                logStage(
                    stage = "OWNERSHIP_RECOVERY",
                    outcome = "REJECT",
                    detail = reason,
                    text = recoveryText
                )
                return false
            }

            val ownership = ownershipMask ?: return reject("no-partition")
            if (probabilityMask == null) return reject("no-model-mask")
            if (detection.confidence < LOCAL_OCR_RECOVERY_MIN_SHAPE_CONFIDENCE) {
                return reject("shape=${formatScore(detection.confidence)}")
            }
            if (regionIndices.size != 1) return reject("regions=${regionIndices.size}")

            val textBounds = mappedTextBounds[regionIndices.first()]
            val textWidth = textBounds.width().coerceAtLeast(1)
            val textHeight = textBounds.height().coerceAtLeast(1)
            val paddingX = max(
                (textWidth * 1.85f).roundToInt(),
                (medianLineHeight * 3.2f).roundToInt()
            )
            val paddingY = max(
                (textHeight * 2.45f).roundToInt(),
                (medianLineHeight * 3.0f).roundToInt()
            )
            val localLeft = (textBounds.left - paddingX).coerceAtLeast(0)
            val localTop = (textBounds.top - paddingY).coerceAtLeast(0)
            val localRight = (textBounds.right + paddingX).coerceAtMost(width)
            val localBottom = (textBounds.bottom + paddingY).coerceAtMost(height)
            if (localRight - localLeft < 6 || localBottom - localTop < 6) {
                return reject("window-too-small")
            }

            // OCR pixels are always retained, even if the watershed seam runs
            // through an antialiased letter. Everything else must belong to
            // this dialogue item's side of the shared proposal.
            val ownerGuard = BooleanArray(width * height)
            for (y in localTop until localBottom) {
                val row = y * width
                for (x in localLeft until localRight) {
                    val index = row + x
                    ownerGuard[index] = ownership[index] || ocrCore[index]
                }
            }
            if (ownerGuard.none { it }) return reject("empty-partition")

            val coreThreshold = (0.30f * 255f).roundToInt()
            val looseThreshold = (0.14f * 255f).roundToInt()
            val modelCore = BooleanArray(ownerGuard.size) { index ->
                ownerGuard[index] && confidenceAt(index) >= coreThreshold
            }
            if (modelCore.none { it }) return reject("empty-model-core")

            // Bridge only the one-pixel-scale confidence gaps produced by
            // letters and antialiasing. V27 expanded this by three generous
            // bridge radii, which let a highly supported shared proposal grow
            // all the way to the artificial recovery window before the
            // ownership seam could help.
            val distanceFromCore = nativeDistanceField(modelCore, ownerGuard)
            val bridgeRadius = (medianLineHeight * 0.14f).roundToInt().coerceIn(1, 6)
            val eligible = BooleanArray(ownerGuard.size) { index ->
                ownerGuard[index] &&
                        (confidenceAt(index) >= looseThreshold ||
                                ocrCore[index] ||
                                distanceFromCore[index] <= bridgeRadius)
            }

            var seed = marker.index.takeIf { it in eligible.indices && eligible[it] } ?: -1
            if (seed < 0) {
                val searchX = max(3, (textWidth * 0.55f).roundToInt())
                val searchY = max(3, (textHeight * 0.75f).roundToInt())
                val left = (textBounds.left - searchX).coerceAtLeast(localLeft)
                val top = (textBounds.top - searchY).coerceAtLeast(localTop)
                val right = (textBounds.right + searchX).coerceAtMost(localRight)
                val bottom = (textBounds.bottom + searchY).coerceAtMost(localBottom)
                var bestConfidence = -1
                for (y in top until bottom) {
                    for (x in left until right) {
                        val index = y * width + x
                        val confidence = confidenceAt(index)
                        if (eligible[index] && confidence > bestConfidence) {
                            seed = index
                            bestConfidence = confidence
                        }
                    }
                }
            }
            if (seed < 0) return reject("no-owned-seed")

            val component = BooleanArray(ownerGuard.size)
            val queue = IntArray(ownerGuard.size)
            var queueStart = 0
            var queueEnd = 0
            component[seed] = true
            queue[queueEnd++] = seed
            fun enqueue(index: Int) {
                if (index !in component.indices || component[index] || !eligible[index]) return
                component[index] = true
                queue[queueEnd++] = index
            }
            while (queueStart < queueEnd) {
                val index = queue[queueStart++]
                val x = index % width
                val y = index / width
                if (x > localLeft) enqueue(index - 1)
                if (x + 1 < localRight) enqueue(index + 1)
                if (y > localTop) enqueue(index - width)
                if (y + 1 < localBottom) enqueue(index + width)
            }

            val componentSize = queueEnd
            val localArea = (localRight - localLeft) * (localBottom - localTop)
            val componentCoverage = componentSize.toFloat() / localArea.coerceAtLeast(1)
            if (componentSize < 24 || componentCoverage !in 0.025f..0.74f) {
                return reject(
                    "component=$componentSize coverage=${formatScore(componentCoverage)}"
                )
            }

            var supportedPixels = 0
            var windowBoundaryHits = 0
            for (index in component.indices) {
                if (!component[index]) continue
                if (confidenceAt(index) >= looseThreshold) supportedPixels++
                val x = index % width
                val y = index / width
                if (x == localLeft || x == localRight - 1 ||
                    y == localTop || y == localBottom - 1
                ) {
                    windowBoundaryHits++
                }
            }
            val supportRatio = supportedPixels.toFloat() / componentSize.coerceAtLeast(1)
            val maximumWindowHits = max(12, (sqrt(componentSize.toFloat()) * 0.45f).roundToInt())
            // A shared balloon is allowed to meet the local window where it
            // touches its neighbour, but only when nearly the entire recovered
            // component is still backed by the model. This is the exact V27
            // SURE. signature (support=0.93); escaped panel fills have much
            // weaker support and continue to fail this gate.
            val stronglyModelOwned = supportRatio >= 0.72f
            if (supportRatio < 0.10f ||
                (windowBoundaryHits > maximumWindowHits && !stronglyModelOwned)
            ) {
                return reject(
                    "support=${formatScore(supportRatio)}, " +
                            "window-boundary=$windowBoundaryHits/$maximumWindowHits"
                )
            }

            val distanceFromComponent = nativeDistanceField(component, ownerGuard)
            val outlined = BooleanArray(component.size) { index ->
                ownerGuard[index] && distanceFromComponent[index] <= outlinePasses * 3
            }
            for (y in textBounds.top until textBounds.bottom) {
                for (x in textBounds.left until textBounds.right) {
                    val index = y * width + x
                    if (ownerGuard[index]) outlined[index] = true
                }
            }
            val closeRadius = (medianLineHeight * FULL_LOBE_CLOSE_LINE_RATIO)
                .roundToInt()
                .coerceIn(2, 12)
            val contourClosed = closeSmallContourGaps(outlined, ownerGuard, closeRadius)
            val solid = fillEnclosedInterior(contourClosed, ownerGuard)
            val solidSize = solid.count { it }
            val maximumGrowth = max(
                textWidth * textHeight + 128,
                (componentSize * 0.95f).roundToInt() + 128
            )
            val solidCoverage = solidSize.toFloat() / localArea.coerceAtLeast(1)
            if (solidSize - componentSize > maximumGrowth || solidCoverage > 0.80f) {
                return reject(
                    "interior-growth=${solidSize - componentSize}/$maximumGrowth, " +
                            "coverage=${formatScore(solidCoverage)}"
                )
            }

            for (index in solid.indices) {
                if (ownerGuard[index]) finalAlphaGuard[index] = true
                if (solid[index]) mergeAlpha(index, 255)
            }
            logStage(
                stage = "OWNERSHIP_RECOVERY",
                outcome = "ACCEPT",
                detail = "component=$componentSize, coverage=${formatScore(solidCoverage)}, " +
                        "support=${formatScore(supportRatio)}, " +
                        "window-boundary=$windowBoundaryHits/$maximumWindowHits, seam=virtual",
                text = recoveryText
            )
            return true
        }

        /**
         * Last-resort recovery for an isolated OCR-confirmed balloon. Shared
         * proposals try ownership recovery first; this native-color trace is
         * retained for standalone lobes that genuinely have a closed contour.
         */
        fun mergeLocalOcrContourRecovery(
            marker: PartitionMarker,
            regionIndices: List<Int>
        ): Boolean {
            val recoveryText = regionIndices.joinToString(" ") { textRegions[it].text }
            fun reject(reason: String): Boolean {
                logStage(
                    stage = "LOCAL_OCR_RECOVERY",
                    outcome = "REJECT",
                    detail = reason,
                    text = recoveryText
                )
                return false
            }

            if (detection.confidence < LOCAL_OCR_RECOVERY_MIN_SHAPE_CONFIDENCE) {
                return reject("shape=${formatScore(detection.confidence)}")
            }
            // Connected/multi-block dialogue remains owned by the established
            // lobe pipeline. This path is intentionally for one small survivor.
            if (regionIndices.size != 1) return reject("regions=${regionIndices.size}")

            val textBounds = mappedTextBounds[regionIndices.first()]
            val textWidth = textBounds.width().coerceAtLeast(1)
            val textHeight = textBounds.height().coerceAtLeast(1)
            // Leave enough room for the complete oval and its tail. V25's
            // smaller rectangular window could itself cut across a valid
            // bubble before the native outline had a chance to stop the fill.
            val paddingX = max(
                (textWidth * 1.55f).roundToInt(),
                (medianLineHeight * 3.0f).roundToInt()
            )
            val paddingY = max(
                (textHeight * 2.15f).roundToInt(),
                (medianLineHeight * 2.8f).roundToInt()
            )
            val localLeft = (textBounds.left - paddingX).coerceAtLeast(0)
            val localTop = (textBounds.top - paddingY).coerceAtLeast(0)
            val localRight = (textBounds.right + paddingX).coerceAtMost(width)
            val localBottom = (textBounds.bottom + paddingY).coerceAtMost(height)
            if (localRight - localLeft < 6 || localBottom - localTop < 6) {
                return reject("window-too-small")
            }

            val localGuard = BooleanArray(width * height)
            for (y in localTop until localBottom) {
                val row = y * width
                for (x in localLeft until localRight) localGuard[row + x] = true
            }
            if (marker.index !in localGuard.indices || !localGuard[marker.index]) {
                return reject("marker-outside-window")
            }

            val localArea = (localRight - localLeft) * (localBottom - localTop)
            val looseSupportThreshold = (NATIVE_GUARD_LOOSE_THRESHOLD * 255f).roundToInt()

            data class LocalTrace(
                val component: BooleanArray,
                val tolerance: Int,
                val size: Int,
                val coverage: Float,
                val boundaryHits: Int,
                val maximumBoundaryHits: Int,
                val supportRatio: Float
            )

            /**
             * The general native trace deliberately uses a broad white-paper
             * tolerance. On the church page that tolerance crossed the thin
             * outline around "SURE." and flooded into the pale stone artwork.
             * Retry locally from strict to moderate tolerances and keep only a
             * component that surrounds the OCR box and closes before the
             * artificial window boundary.
             */
            fun traceAtTolerance(tolerance: Int): LocalTrace? {
                val dominantRed = Color.red(marker.dominantColor)
                val dominantGreen = Color.green(marker.dominantColor)
                val dominantBlue = Color.blue(marker.dominantColor)
                val toleranceSquared = tolerance * tolerance
                fun eligible(index: Int): Boolean {
                    if (!localGuard[index]) return false
                    val color = pixels[index]
                    val red = Color.red(color) - dominantRed
                    val green = Color.green(color) - dominantGreen
                    val blue = Color.blue(color) - dominantBlue
                    return red * red + green * green + blue * blue <= toleranceSquared
                }

                if (!eligible(marker.index)) return null
                val traced = BooleanArray(pixels.size)
                val queue = IntArray(pixels.size)
                var queueStart = 0
                var queueEnd = 0
                traced[marker.index] = true
                queue[queueEnd++] = marker.index
                var minX = marker.index % width
                var maxX = minX
                var minY = marker.index / width
                var maxY = minY
                var boundaryHits = 0
                var supportedPixels = 0
                fun enqueue(index: Int) {
                    if (traced[index] || !eligible(index)) return
                    traced[index] = true
                    queue[queueEnd++] = index
                }
                while (queueStart < queueEnd) {
                    val index = queue[queueStart++]
                    val x = index % width
                    val y = index / width
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                    if (x == localLeft || x == localRight - 1 ||
                        y == localTop || y == localBottom - 1
                    ) {
                        boundaryHits++
                    }
                    if (confidenceAt(index) >= looseSupportThreshold) supportedPixels++
                    if (x > localLeft) enqueue(index - 1)
                    if (x + 1 < localRight) enqueue(index + 1)
                    if (y > localTop) enqueue(index - width)
                    if (y + 1 < localBottom) enqueue(index + width)
                }

                val size = queueEnd
                if (size < 24) return null
                val coverage = size.toFloat() / localArea.coerceAtLeast(1)
                if (coverage !in 0.04f..0.78f) return null
                // A tiny white island beside one letter is not a recovered
                // balloon. The native component must wrap the OCR rectangle.
                val surroundsText = minX <= textBounds.left + 2 &&
                        maxX >= textBounds.right - 3 &&
                        minY <= textBounds.top + 2 &&
                        maxY >= textBounds.bottom - 3
                if (!surroundsText) return null
                val maximumBoundaryHits = max(
                    8,
                    (sqrt(size.toFloat()) * 0.32f).roundToInt()
                )
                val supportRatio = supportedPixels.toFloat() / size.coerceAtLeast(1)
                return LocalTrace(
                    component = traced,
                    tolerance = tolerance,
                    size = size,
                    coverage = coverage,
                    boundaryHits = boundaryHits,
                    maximumBoundaryHits = maximumBoundaryHits,
                    supportRatio = supportRatio
                )
            }

            val localTrace = listOf(36, 48, 60, 72)
                .asSequence()
                .mapNotNull(::traceAtTolerance)
                .firstOrNull { trace ->
                    trace.boundaryHits <= trace.maximumBoundaryHits &&
                            trace.supportRatio >= 0.06f
                }
                ?: return reject("strict-contour-open; tolerances=36/48/60/72")
            val component = localTrace.component
            val componentSize = localTrace.size
            val componentCoverage = localTrace.coverage
            val boundaryHits = localTrace.boundaryHits
            val supportRatio = localTrace.supportRatio

            val distanceFromFill = nativeDistanceField(component, localGuard)
            val outlined = BooleanArray(component.size) { index ->
                localGuard[index] && distanceFromFill[index] <= outlinePasses * 3
            }
            // The contour decides the outer shape. OCR only restores the
            // interior pixels that letters removed from the paper-color fill.
            for (y in textBounds.top until textBounds.bottom) {
                for (x in textBounds.left until textBounds.right) {
                    outlined[y * width + x] = true
                }
            }
            val closeRadius = (medianLineHeight * FULL_LOBE_CLOSE_LINE_RATIO)
                .roundToInt()
                .coerceIn(2, 12)
            val contourClosed = closeSmallContourGaps(outlined, localGuard, closeRadius)
            val solid = fillEnclosedInterior(contourClosed, localGuard)
            val solidSize = solid.count { it }
            val maximumGrowth = max(
                textWidth * textHeight + 96,
                (componentSize * 0.90f).roundToInt() + 96
            )
            if (solidSize - componentSize > maximumGrowth ||
                solidSize.toFloat() / localArea.coerceAtLeast(1) > 0.84f
            ) {
                return reject(
                    "interior-growth=${solidSize - componentSize}, limit=$maximumGrowth"
                )
            }

            for (index in solid.indices) {
                if (localGuard[index]) finalAlphaGuard[index] = true
                if (solid[index]) mergeAlpha(index, 255)
            }
            logStage(
                stage = "LOCAL_OCR_RECOVERY",
                outcome = "ACCEPT",
                detail = "component=$componentSize, coverage=${formatScore(componentCoverage)}, " +
                        "boundary=$boundaryHits, support=${formatScore(supportRatio)}, " +
                        "tolerance=${localTrace.tolerance}",
                text = recoveryText
            )
            return true
        }

        fun touchesGuardBoundary(index: Int, guard: BooleanArray): Boolean {
            val x = index % width
            val y = index / width
            return x == 0 || y == 0 || x == width - 1 || y == height - 1 ||
                    (x > 0 && !guard[index - 1]) ||
                    (x + 1 < width && !guard[index + 1]) ||
                    (y > 0 && !guard[index - width]) ||
                    (y + 1 < height && !guard[index + width])
        }

        fun nativeInkContrast(index: Int, fillColor: Int): Int {
            val pixel = pixels[index]
            val fillDistance = colorDistance(pixel, fillColor)
            val x = index % width
            val y = index / width
            var localEdge = 0
            if (x > 0) localEdge = max(localEdge, colorDistance(pixel, pixels[index - 1]))
            if (x + 1 < width) {
                localEdge = max(localEdge, colorDistance(pixel, pixels[index + 1]))
            }
            if (y > 0) localEdge = max(localEdge, colorDistance(pixel, pixels[index - width]))
            if (y + 1 < height) {
                localEdge = max(localEdge, colorDistance(pixel, pixels[index + width]))
            }
            return max(fillDistance, localEdge * 2)
        }

        /**
         * Detection and grouping need a forgiving guard so thin connectors are
         * not lost. The visible cutout does not. Build a second, tighter fence
         * from model-supported bubble pixels and use it only for final rendering.
         * This keeps the transparent gaps between connected lobes and prevents
         * nearby panel art from becoming part of the enlarged PNG.
         */
        fun createBubbleOnlyGuard(groupingGuard: BooleanArray): BooleanArray {
            if (probabilityMask == null) return groupingGuard
            val coreThreshold = (FINAL_ALPHA_CORE_THRESHOLD * 255f).roundToInt()
            val looseThreshold = (FINAL_ALPHA_LOOSE_THRESHOLD * 255f).roundToInt()
            val renderCore = BooleanArray(groupingGuard.size) { index ->
                groupingGuard[index] && confidenceAt(index) >= coreThreshold
            }
            if (renderCore.none { it }) {
                return BooleanArray(groupingGuard.size) { index ->
                    groupingGuard[index] && confidenceAt(index) >= looseThreshold
                }
            }
            val distanceFromRenderCore = nativeDistanceField(renderCore, groupingGuard)
            val renderMargin = (medianLineHeight * FINAL_ALPHA_MARGIN_LINE_RATIO)
                .roundToInt()
                .coerceIn(outlinePasses + 2, 24)
            return BooleanArray(groupingGuard.size) { index ->
                groupingGuard[index] &&
                        (confidenceAt(index) >= looseThreshold ||
                                distanceFromRenderCore[index] <= renderMargin * 3)
            }
        }

        var totalBoundaryLobes = 0
        var snappedBoundaryLobes = 0
        lobeMasks.forEachIndexed { lobeIndex, detectedComponent ->
            totalBoundaryLobes++
            val groupingGuard = lobeGuards.getOrNull(lobeIndex) ?: nativeGuard
            val lobeGuard = createBubbleOnlyGuard(groupingGuard)
            // The tight model fence is allowed to control the visible outer
            // edge, but it may not carve through a recognized text box. Add
            // only this lobe's OCR rectangles back into its private guard.
            lobeRegions[lobeIndex].forEach { regionIndex ->
                val regionBounds = mappedTextBounds[regionIndex]
                for (textY in regionBounds.top until regionBounds.bottom) {
                    for (textX in regionBounds.left until regionBounds.right) {
                        val index = textY * width + textX
                        if (nativeAllowed[index]) lobeGuard[index] = true
                    }
                }
            }
            val fillColor = lobeFillColors.getOrNull(lobeIndex) ?: Color.WHITE
            val renderMarker = lobeMarkers.getOrNull(lobeIndex)
            val retracedComponent = renderMarker?.let { marker ->
                traceFillComponent(
                    pixels = pixels,
                    width = width,
                    height = height,
                    marker = marker,
                    allowed = lobeGuard
                )
            }
            // The broad trace remains useful for grouping only. A model-backed
            // cutout is always rendered from the tighter re-trace. OCR-only
            // captions have no model mask, so their established trace is kept.
            val component = if (probabilityMask != null) {
                retracedComponent ?: BooleanArray(detectedComponent.size)
            } else {
                detectedComponent
            }
            val componentSize = component.count { it }
            val coverage = componentSize.toFloat() / component.size
            var nativeTraceSafe = componentSize > 0 && coverage in 0.0025f..0.78f
            val nativeTraceReasons = mutableListOf<String>()
            if (!nativeTraceSafe) nativeTraceReasons += "coverage=${formatScore(coverage)}"

            for (index in lobeGuard.indices) {
                if (lobeGuard[index]) finalAlphaGuard[index] = true
            }

            var guardBoundaryHits = 0
            for (index in component.indices) {
                if (!component[index]) continue
                val x = index % width
                val y = index / width
                val hitsGuard = x == 0 || y == 0 || x == width - 1 || y == height - 1 ||
                        (x > 0 && !lobeGuard[index - 1]) ||
                        (x + 1 < width && !lobeGuard[index + 1]) ||
                        (y > 0 && !lobeGuard[index - width]) ||
                        (y + 1 < height && !lobeGuard[index + width])
                if (hitsGuard) guardBoundaryHits++
            }
            val maximumBoundaryHits = max(16, (sqrt(componentSize.toFloat()) * 0.60f).roundToInt())
            // A valid fill normally stops at printed ink. A long contact with
            // the artificial guard means the flood escaped and was clipped by
            // the model instead, which would create the background wedges seen
            // in v13. Only this lobe uses the conservative ML fallback.
            if (guardBoundaryHits > maximumBoundaryHits) {
                nativeTraceSafe = false
                nativeTraceReasons += "guard-boundary=$guardBoundaryHits/$maximumBoundaryHits"
            }

            if (probabilityMask != null) {
                var supportedPixels = 0
                var finalFenceSupportedPixels = 0
                val finalFenceSupportThreshold =
                    (FINAL_ALPHA_LOOSE_THRESHOLD * 255f).roundToInt()
                for (index in component.indices) {
                    if (component[index] && confidenceAt(index) >= supportThreshold) {
                        supportedPixels++
                    }
                    if (component[index] && confidenceAt(index) >= finalFenceSupportThreshold) {
                        finalFenceSupportedPixels++
                    }
                }
                // The guard already limits growth; this second check catches a
                // component dominated by similarly colored panel artwork.
                if (supportedPixels < max(24, componentSize / 8)) {
                    nativeTraceSafe = false
                    nativeTraceReasons += "weak-model-support"
                }
                // A real balloon interior should remain substantially supported
                // by the segmentation model. A large low-confidence annex is the
                // signature of native flood-fill escaping into panel artwork.
                val minimumFinalSupport = max(
                    24,
                    (componentSize * 0.42f).roundToInt()
                )
                if (finalFenceSupportedPixels < minimumFinalSupport) {
                    nativeTraceSafe = false
                    nativeTraceReasons += "weak-final-fence"
                }
            }

            if (!nativeTraceSafe && probabilityMask != null) {
                logStage(
                    stage = "NATIVE_TRACE",
                    outcome = "FALLBACK",
                    detail = "lobe=$lobeIndex, reasons=${nativeTraceReasons.joinToString("+")}",
                    text = lobeRegions[lobeIndex].joinToString(" ") { textRegions[it].text }
                )
                val modelRecovered = mergeSolidModelFallback(
                    guard = lobeGuard,
                    regionIndices = lobeRegions[lobeIndex]
                )
                logStage(
                    stage = "MODEL_FALLBACK",
                    outcome = if (modelRecovered) "ACCEPT" else "REJECT",
                    detail = "lobe=$lobeIndex"
                )
                if (!modelRecovered) {
                    val marker = lobeMarkers.getOrNull(lobeIndex)
                    if (marker != null) {
                        val ownershipRecovered = mergeOwnershipPartitionRecovery(
                            marker = marker,
                            regionIndices = lobeRegions[lobeIndex]
                        )
                        if (!ownershipRecovered) {
                            mergeLocalOcrContourRecovery(
                                marker = marker,
                                regionIndices = lobeRegions[lobeIndex]
                            )
                        }
                    }
                }
                return@forEachIndexed
            }
            if (componentSize == 0) {
                // OCR-only captions have no probability mask to fall back to.
                // Skip a failed empty trace without degrading valid siblings.
                return@forEachIndexed
            }
            logStage(
                stage = "NATIVE_TRACE",
                outcome = "ACCEPT",
                detail = "lobe=$lobeIndex, coverage=${formatScore(coverage)}",
                text = lobeRegions[lobeIndex].joinToString(" ") { textRegions[it].text }
            )

            val distanceFromFill = nativeDistanceField(component, lobeGuard)
            val outlined = BooleanArray(component.size) { index ->
                lobeGuard[index] && distanceFromFill[index] <= outlinePasses * 3
            }

            // Rescue ink that is connected to an open tail or a scan defect and
            // therefore is not technically an enclosed hole. The native-fill
            // distance prevents these OCR boxes from changing the outer shape.
            lobeRegions[lobeIndex].forEach { regionIndex ->
                val regionBounds = mappedTextBounds[regionIndex]
                for (textY in regionBounds.top until regionBounds.bottom) {
                    for (textX in regionBounds.left until regionBounds.right) {
                        val index = textY * width + textX
                        if (lobeGuard[index] &&
                            distanceFromFill[index] <= textSafetyRadius * 3
                        ) {
                            outlined[index] = true
                        }
                    }
                }
            }

            val closed = fillEnclosedInterior(outlined, lobeGuard)
            val closedSize = closed.count { it }
            // Filling text holes should add a modest amount of area. If closing
            // would swallow a whole panel compartment, retain the guarded
            // outline plus OCR rescue instead of accepting the bad matte.
            val v15Solid = if (closedSize <= componentSize * 2.75f + 64f) closed else outlined

            /*
             * V18 keeps V17's guarded contour candidate, but both its baseline
             * and candidate now live inside the bubble-only rendering fence. It
             * first builds V15 exactly as before, then tries a wider native-ink
             * candidate.
             * The candidate is accepted only when it is a strict superset of
             * V15, preserves every OCR-covered pixel, stays away from the
             * artificial fence/crop, grows modestly, and has real ink evidence
             * on its new outer edge. Any failed check returns this lobe to the
             * untouched V15 result.
             */
            fun guardedNativeCandidate(): BooleanArray? {
                val maximumContourRadius = (medianLineHeight * 0.30f).roundToInt()
                    .coerceIn(outlinePasses + 1, 26)
                val shellStrong = IntArray(maximumContourRadius + 1)
                val shellTotal = IntArray(maximumContourRadius + 1)
                val fillLuminance = (
                        Color.red(fillColor) * 299 +
                                Color.green(fillColor) * 587 +
                                Color.blue(fillColor) * 114
                        ) / 1000
                val inkThreshold = when {
                    fillLuminance >= 190 -> 82
                    fillLuminance <= 60 -> 68
                    else -> 76
                }

                for (index in component.indices) {
                    if (!lobeGuard[index]) continue
                    val distance = distanceFromFill[index]
                    if (distance > maximumContourRadius * 3) continue
                    val radius = ((distance + 2) / 3)
                        .coerceIn(0, maximumContourRadius)
                    shellTotal[radius]++
                    if (nativeInkContrast(index, fillColor) >= inkThreshold) {
                        shellStrong[radius]++
                    }
                }

                var contourRadius = outlinePasses
                for (radius in outlinePasses + 1..maximumContourRadius) {
                    val total = shellTotal[radius]
                    val required = max(8, (total * 0.18f).roundToInt())
                    if (total >= 16 && shellStrong[radius] >= required) {
                        contourRadius = radius
                    }
                }
                if (contourRadius <= outlinePasses) return null

                val candidateOutline = BooleanArray(component.size) { index ->
                    lobeGuard[index] && distanceFromFill[index] <= contourRadius * 3
                }

                // Recover only native ink that forms a connected continuation
                // from the accepted contour. This can restore a thin tail but
                // cannot pick up an unrelated high-contrast object elsewhere.
                val tailRadius = (medianLineHeight * 0.50f).roundToInt()
                    .coerceIn(contourRadius, 34)
                val tailEligible = BooleanArray(component.size) { index ->
                    lobeGuard[index] &&
                            distanceFromFill[index] <= tailRadius * 3 &&
                            confidenceAt(index) >= supportThreshold &&
                            nativeInkContrast(index, fillColor) >= inkThreshold
                }
                val queue = IntArray(component.size)
                var queueStart = 0
                var queueEnd = 0
                candidateOutline.forEachIndexed { index, present ->
                    if (present) queue[queueEnd++] = index
                }
                fun enqueueTail(index: Int) {
                    if (!candidateOutline[index] && tailEligible[index]) {
                        candidateOutline[index] = true
                        queue[queueEnd++] = index
                    }
                }
                while (queueStart < queueEnd) {
                    val index = queue[queueStart++]
                    val x = index % width
                    val y = index / width
                    if (x > 0) enqueueTail(index - 1)
                    if (x + 1 < width) enqueueTail(index + 1)
                    if (y > 0) enqueueTail(index - width)
                    if (y + 1 < height) enqueueTail(index + width)
                }

                lobeRegions[lobeIndex].forEach { regionIndex ->
                    val regionBounds = mappedTextBounds[regionIndex]
                    for (textY in regionBounds.top until regionBounds.bottom) {
                        for (textX in regionBounds.left until regionBounds.right) {
                            val index = textY * width + textX
                            if (lobeGuard[index] &&
                                distanceFromFill[index] <= textSafetyRadius * 3
                            ) {
                                candidateOutline[index] = true
                            }
                        }
                    }
                }

                val candidateClosed = fillEnclosedInterior(candidateOutline, lobeGuard)
                val candidateClosedSize = candidateClosed.count { it }
                val candidateSolid = if (
                    candidateClosedSize <= componentSize * 2.75f + 64f
                ) {
                    candidateClosed
                } else {
                    candidateOutline
                }

                var baselineSize = 0
                var candidateSize = 0
                var addedPixels = 0
                var addedFenceHits = 0
                var addedCropHits = 0
                var newBoundaryPixels = 0
                var newBoundaryInk = 0
                var newBoundaryEvidence = 0
                var preservesV15 = true
                for (index in candidateSolid.indices) {
                    val baselinePresent = v15Solid[index]
                    val candidatePresent = candidateSolid[index]
                    if (baselinePresent) {
                        baselineSize++
                        if (!candidatePresent) preservesV15 = false
                    }
                    if (!candidatePresent) continue
                    candidateSize++
                    if (baselinePresent) continue
                    addedPixels++
                    val x = index % width
                    val y = index / width
                    if (touchesGuardBoundary(index, lobeGuard)) addedFenceHits++
                    if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                        addedCropHits++
                    }
                    val isOuterBoundary =
                        x == 0 || y == 0 || x == width - 1 || y == height - 1 ||
                                (x > 0 && !candidateSolid[index - 1]) ||
                                (x + 1 < width && !candidateSolid[index + 1]) ||
                                (y > 0 && !candidateSolid[index - width]) ||
                                (y + 1 < height && !candidateSolid[index + width])
                    if (isOuterBoundary) {
                        newBoundaryPixels++
                        val hasNativeInk = nativeInkContrast(index, fillColor) >= inkThreshold
                        val hasModelSupport = confidenceAt(index) >=
                                (FINAL_ALPHA_LOOSE_THRESHOLD * 255f).roundToInt()
                        if (hasNativeInk) newBoundaryInk++
                        if (hasNativeInk || hasModelSupport) newBoundaryEvidence++
                    }
                }

                if (!preservesV15 || addedPixels == 0) return null
                val maximumGrowth = max(64, (baselineSize * 0.20f).roundToInt())
                if (addedPixels > maximumGrowth ||
                    candidateSize <= baselineSize ||
                    addedFenceHits > 0 ||
                    addedCropHits > 0
                ) {
                    return null
                }

                // Explicit OCR preservation check. The superset rule already
                // protects these pixels globally; keeping this local assertion
                // prevents future renderer edits from weakening that contract.
                for (regionIndex in lobeRegions[lobeIndex]) {
                    val regionBounds = mappedTextBounds[regionIndex]
                    for (textY in regionBounds.top until regionBounds.bottom) {
                        for (textX in regionBounds.left until regionBounds.right) {
                            val index = textY * width + textX
                            if (v15Solid[index] && !candidateSolid[index]) return null
                        }
                    }
                }

                val requiredBoundaryInk = max(
                    5,
                    (newBoundaryPixels * 0.28f).roundToInt()
                )
                if (newBoundaryPixels == 0 || newBoundaryInk < requiredBoundaryInk) {
                    return null
                }
                val boundaryEvidenceRatio = newBoundaryEvidence.toFloat() / newBoundaryPixels
                if (boundaryEvidenceRatio < GUARDED_BOUNDARY_MIN_EVIDENCE) return null
                return candidateSolid
            }

            val snappedBoundary = guardedNativeCandidate()
            if (snappedBoundary != null) snappedBoundaryLobes++
            logStage(
                stage = "BOUNDARY_SNAP",
                outcome = if (snappedBoundary != null) "ACCEPT" else "FALLBACK",
                detail = "lobe=$lobeIndex",
                text = lobeRegions[lobeIndex].joinToString(" ") { textRegions[it].text }
            )
            val selectedSolid = snappedBoundary ?: v15Solid
            // Close only small gaps in the accepted outer contour, then flood
            // its complete interior. This is deliberately OCR-independent: an
            // OCR miss can no longer leave a hole through an otherwise valid
            // balloon. Each lobe still completes before group compositing.
            val fullLobeCloseRadius = (medianLineHeight * FULL_LOBE_CLOSE_LINE_RATIO)
                .roundToInt()
                .coerceIn(2, 14)
            val contourClosed = closeSmallContourGaps(
                mask = selectedSolid,
                guard = lobeGuard,
                radius = fullLobeCloseRadius
            )
            val repairedSolid = fillEnclosedInterior(contourClosed, lobeGuard)
            lobeRegions[lobeIndex].forEach { regionIndex ->
                val regionBounds = mappedTextBounds[regionIndex]
                for (textY in regionBounds.top until regionBounds.bottom) {
                    for (textX in regionBounds.left until regionBounds.right) {
                        val index = textY * width + textX
                        if (lobeGuard[index]) repairedSolid[index] = true
                    }
                }
            }
            val finalLobeSolid = fillEnclosedInterior(repairedSolid, lobeGuard)
            val selectedSize = selectedSolid.count { it }
            val repairedSize = finalLobeSolid.count { it }
            val textAreaForLobe = lobeRegions[lobeIndex].sumOf { regionIndex ->
                mappedTextBounds[regionIndex].let { it.width() * it.height() }
            }
            val maximumInteriorRepair = max(
                textAreaForLobe + 96,
                (selectedSize * 0.48f).roundToInt() + 96
            )
            if (repairedSize - selectedSize > maximumInteriorRepair) {
                logStage(
                    stage = "INTERIOR_REPAIR",
                    outcome = "REJECT",
                    detail = "lobe=$lobeIndex, added=${repairedSize - selectedSize}, " +
                            "limit=$maximumInteriorRepair"
                )
                return null
            }
            logStage(
                stage = "INTERIOR_REPAIR",
                outcome = "ACCEPT",
                detail = "lobe=$lobeIndex, sealed=${repairedSize - selectedSize}, " +
                        "radius=$fullLobeCloseRadius"
            )
            val lobeTextIsSolid = lobeRegions[lobeIndex].all { regionIndex ->
                val regionBounds = mappedTextBounds[regionIndex]
                var total = 0
                var solid = 0
                for (textY in regionBounds.top until regionBounds.bottom) {
                    for (textX in regionBounds.left until regionBounds.right) {
                        total++
                        if (finalLobeSolid[textY * width + textX]) solid++
                    }
                }
                solid.toFloat() / total.coerceAtLeast(1) >= OCR_ALPHA_REQUIRED_COVERAGE
            }
            if (!lobeTextIsSolid) {
                logStage(
                    stage = "INTERIOR_REPAIR",
                    outcome = "REJECT",
                    detail = "lobe=$lobeIndex, reason=text-alpha"
                )
                return null
            }
            for (index in finalLobeSolid.indices) {
                if (finalLobeSolid[index] && lobeGuard[index]) mergeAlpha(index, 255)
            }
        }

        val maximumCoverage = if (textRegions.size > 1) 0.92f else 0.84f
        val mergedCoverage = visiblePixels.toFloat() / mergedAlpha.size
        if (visiblePixels == 0 || mergedCoverage !in 0.008f..maximumCoverage) return null

        // Exactly one native-pixel fringe is left translucent. Compose then
        // performs the single required display resample with FilterQuality.High.
        val resultAlpha = IntArray(width * height) { index ->
            if (mergedAlpha[index] > 0) mergedAlpha[index]
            else {
                val currentX = index % width
                val currentY = index / width
                var touchesEdge = false
                for (dy in -1..1) for (dx in -1..1) {
                    val targetX = currentX + dx
                    val targetY = currentY + dy
                    if (targetX in 0 until width && targetY in 0 until height &&
                        finalAlphaGuard[index] && mergedAlpha[targetY * width + targetX] >= 224
                    ) touchesEdge = true
                }
                if (touchesEdge) 112 else 0
            }
        }
        val allTextSolid = mappedTextBounds.all { bounds ->
            var total = 0
            var solid = 0
            for (y in bounds.top until bounds.bottom) {
                for (x in bounds.left until bounds.right) {
                    total++
                    if (resultAlpha[y * width + x] >= FINAL_ALPHA_SOLID_THRESHOLD) solid++
                }
            }
            solid.toFloat() / total.coerceAtLeast(1) >= OCR_ALPHA_REQUIRED_COVERAGE
        }
        if (!allTextSolid) return null
        val allBoundariesSnapped = totalBoundaryLobes > 0 &&
                snappedBoundaryLobes == totalBoundaryLobes
        logStage(
            stage = "BOUNDARY_CONTRACT",
            outcome = if (allBoundariesSnapped) "PASS" else "FAIL",
            detail = "snapped=$snappedBoundaryLobes/$totalBoundaryLobes",
            text = detection.textRegion?.text.orEmpty()
        )
        return RefinedAlphaResult(
            alpha = resultAlpha,
            allBoundariesSnapped = allBoundariesSnapped,
            snappedLobes = snappedBoundaryLobes,
            totalLobes = totalBoundaryLobes
        )
    }

    /**
     * Returns the ownership map for one dialogue item inside a shared ONNX
     * shape. The expensive partition is built only once per shared shape and
     * reused by every balloon saved from it.
     */
    private fun createEdgeOwnershipMask(
        source: Bitmap,
        detection: DetectedBubble,
        cutoutWidth: Int,
        cutoutHeight: Int,
        cutoutBounds: SourceBounds,
        edgePartitionCache: MutableMap<Int, EdgePartition?>
    ): BooleanArray? {
        val groupId = detection.partitionGroupId
        val regionIndex = detection.partitionRegionIndex
        val groupCount = detection.partitionRegionGroups
            .takeIf { it.isNotEmpty() }
            ?.size
            ?: detection.partitionRegions.size
        if (groupId < 0 || groupCount <= 1 || regionIndex !in 0 until groupCount
        ) return null

        val partition = if (edgePartitionCache.containsKey(groupId)) {
            edgePartitionCache[groupId]
        } else {
            runCatching { createEdgePartition(source, detection) }
                .getOrNull()
                .also { edgePartitionCache[groupId] = it }
        }
        return partition?.ownershipMask(
            outputWidth = cutoutWidth,
            outputHeight = cutoutHeight,
            outputBounds = cutoutBounds,
            owner = regionIndex
        )
    }

    /**
     * Marker-controlled watershed over the source image's color gradient.
     * OCR regions provide the markers, while ink outlines and panel gutters
     * are expensive to cross. Unlike the old midpoint split, the resulting
     * frontier bends around the original balloon outline and speech tail.
     */
    private fun createEdgePartition(
        source: Bitmap,
        detection: DetectedBubble
    ): EdgePartition? {
        val regionGroups = detection.partitionRegionGroups.takeIf { it.isNotEmpty() }
            ?: detection.partitionRegions.map { listOf(it) }
        if (regionGroups.size <= 1 || regionGroups.size > 254) return null
        val raster = createShapeRaster(source, detection) ?: return null
        val bounds = raster.bounds
        val width = raster.width
        val height = raster.height
        val pixels = raster.pixels

        val occupiedSeeds = BooleanArray(pixels.size)
        val seeds = mutableListOf<Pair<Int, Int>>()
        regionGroups.forEachIndexed { groupIndex, regions ->
            var groupHasSeed = false
            regions.forEach regionLoop@{ region ->
                val marker = findPartitionMarker(
                    pixels = pixels,
                    width = width,
                    height = height,
                    bounds = bounds,
                    region = region,
                    occupied = occupiedSeeds
                ) ?: return@regionLoop
                occupiedSeeds[marker.index] = true
                seeds += groupIndex to marker.index
                groupHasSeed = true
            }
            if (!groupHasSeed) return null
        }

        // A common gradient field is important here: it makes this a true
        // multi-source shortest-path watershed rather than several unrelated
        // masks that may overlap or leave holes.
        val gradient = IntArray(pixels.size)
        for (index in pixels.indices) {
            val x = index % width
            val y = index / width
            var strongest = 0
            if (x > 0) strongest = max(strongest, colorDistance(pixels[index], pixels[index - 1]))
            if (x + 1 < width) strongest = max(strongest, colorDistance(pixels[index], pixels[index + 1]))
            if (y > 0) strongest = max(strongest, colorDistance(pixels[index], pixels[index - width]))
            if (y + 1 < height) strongest = max(strongest, colorDistance(pixels[index], pixels[index + width]))
            gradient[index] = strongest
        }

        val distances = FloatArray(pixels.size) { Float.POSITIVE_INFINITY }
        val labels = IntArray(pixels.size) { -1 }
        val queue = PriorityQueue<WatershedNode>(compareBy(WatershedNode::cost))
        seeds.forEach { (label, seed) ->
            distances[seed] = 0f
            labels[seed] = label
            queue += WatershedNode(seed, label, 0f)
        }

        while (queue.isNotEmpty()) {
            val node = queue.poll()
            val index = node.index
            if (node.label != labels[index] || node.cost > distances[index] + 0.001f) continue
            val x = index % width
            val y = index / width

            fun relax(next: Int) {
                val ridge = max(gradient[index], gradient[next])
                val transition = colorDistance(pixels[index], pixels[next])
                val stepCost = 1f + ridge * 0.085f + transition * 0.025f
                val candidate = node.cost + stepCost
                if (candidate + 0.001f < distances[next]) {
                    distances[next] = candidate
                    labels[next] = node.label
                    queue += WatershedNode(next, node.label, candidate)
                }
            }

            if (x > 0) relax(index - 1)
            if (x + 1 < width) relax(index + 1)
            if (y > 0) relax(index - width)
            if (y + 1 < height) relax(index + width)
        }

        val packedLabels = ByteArray(labels.size) { index ->
            labels[index].coerceAtLeast(0).toByte()
        }
        return EdgePartition(width, height, bounds, packedLabels)
    }

    /** Chooses a marker on the dominant fill color surrounding an OCR block. */
    private fun findPartitionMarker(
        pixels: IntArray,
        width: Int,
        height: Int,
        bounds: SourceBounds,
        region: DialogueTextRegion,
        occupied: BooleanArray,
        allowed: BooleanArray? = null
    ): PartitionMarker? {
        val scaleX = width / (bounds.right - bounds.left).coerceAtLeast(1f)
        val scaleY = height / (bounds.bottom - bounds.top).coerceAtLeast(1f)
        val regionLeft = ((region.left - bounds.left) * scaleX).roundToInt().coerceIn(0, width - 1)
        val regionTop = ((region.top - bounds.top) * scaleY).roundToInt().coerceIn(0, height - 1)
        val regionRight = ((region.right - bounds.left) * scaleX).roundToInt()
            .coerceIn(regionLeft + 1, width)
        val regionBottom = ((region.bottom - bounds.top) * scaleY).roundToInt()
            .coerceIn(regionTop + 1, height)
        val paddingX = max(2, (regionRight - regionLeft) / 4)
        val paddingY = max(2, (regionBottom - regionTop) / 3)
        val sampleLeft = (regionLeft - paddingX).coerceAtLeast(0)
        val sampleTop = (regionTop - paddingY).coerceAtLeast(0)
        val sampleRight = (regionRight + paddingX).coerceAtMost(width)
        val sampleBottom = (regionBottom + paddingY).coerceAtMost(height)

        val histogram = IntArray(4096)
        for (y in sampleTop until sampleBottom) {
            for (x in sampleLeft until sampleRight) {
                val index = y * width + x
                if (allowed?.get(index) == false) continue
                val color = pixels[index]
                val key = ((Color.red(color) shr 4) shl 8) or
                        ((Color.green(color) shr 4) shl 4) or
                        (Color.blue(color) shr 4)
                histogram[key]++
            }
        }
        val dominantKey = histogram.indices.maxByOrNull(histogram::get) ?: return null
        if (histogram[dominantKey] == 0) return null
        val dominantColor = Color.rgb(
            (((dominantKey shr 8) and 0xF) shl 4) + 8,
            (((dominantKey shr 4) and 0xF) shl 4) + 8,
            ((dominantKey and 0xF) shl 4) + 8
        )
        val centerX = (regionLeft + regionRight) / 2
        val centerY = (regionTop + regionBottom) / 2
        var bestIndex = -1
        var bestScore = Long.MAX_VALUE
        for (y in sampleTop until sampleBottom) {
            for (x in sampleLeft until sampleRight) {
                val index = y * width + x
                if (occupied[index] || allowed?.get(index) == false) continue
                val colorScore = colorDistanceSquared(pixels[index], dominantColor).toLong()
                val dx = x - centerX
                val dy = y - centerY
                val score = colorScore * 4L + dx.toLong() * dx + dy.toLong() * dy
                if (score < bestScore) {
                    bestScore = score
                    bestIndex = index
                }
            }
        }
        return bestIndex.takeIf { it >= 0 }?.let { PartitionMarker(it, dominantColor) }
    }

    private fun createShapeRaster(
        source: Bitmap,
        detection: DetectedBubble
    ): ShapeRaster? = runCatching {
        val requestedBounds = paddedBounds(source, detection)
        val sourceLeft = requestedBounds.left.toInt().coerceIn(0, source.width - 1)
        val sourceTop = requestedBounds.top.toInt().coerceIn(0, source.height - 1)
        val sourceRight = requestedBounds.right.roundToInt().coerceIn(sourceLeft + 1, source.width)
        val sourceBottom = requestedBounds.bottom.roundToInt().coerceIn(sourceTop + 1, source.height)
        val sourceRect = Rect(sourceLeft, sourceTop, sourceRight, sourceBottom)
        val bounds = SourceBounds(
            sourceLeft.toFloat(),
            sourceTop.toFloat(),
            sourceRight.toFloat(),
            sourceBottom.toFloat()
        )
        val scale = min(
            1f,
            PARTITION_MAX_DIMENSION / max(sourceRect.width(), sourceRect.height()).toFloat()
        )
        val width = max(8, (sourceRect.width() * scale).roundToInt())
        val height = max(8, (sourceRect.height() * scale).roundToInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawBitmap(
                source,
                sourceRect,
                Rect(0, 0, width, height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            ShapeRaster(width, height, bounds, pixels)
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    private fun colorDistance(first: Int, second: Int): Int =
        abs(Color.red(first) - Color.red(second)) +
                abs(Color.green(first) - Color.green(second)) +
                abs(Color.blue(first) - Color.blue(second))

    private fun colorDistanceSquared(first: Int, second: Int): Int {
        val red = Color.red(first) - Color.red(second)
        val green = Color.green(first) - Color.green(second)
        val blue = Color.blue(first) - Color.blue(second)
        return red * red + green * green + blue * blue
    }

    private fun paddedBounds(source: Bitmap, detection: DetectedBubble): SourceBounds {
        val horizontalPadding = detection.width * CUTOUT_PADDING_RATIO
        val verticalPadding = detection.height * CUTOUT_PADDING_RATIO
        return SourceBounds(
            left = (detection.left - horizontalPadding).coerceIn(0f, source.width - 1f),
            top = (detection.top - verticalPadding).coerceIn(0f, source.height - 1f),
            right = (detection.right + horizontalPadding).coerceIn(1f, source.width.toFloat()),
            bottom = (detection.bottom + verticalPadding).coerceIn(1f, source.height.toFloat())
        )
    }

    /**
     * Tries to pull the bubble crop from the full-resolution source file.
     * Falls back to cropping the already-decoded (capped) [source] bitmap
     * if region decoding isn't available for this file.
     */
    private fun extractCutout(
        regionDecoder: BitmapRegionDecoder?,
        source: Bitmap,
        detection: DetectedBubble
    ): ExtractedCutout? {
        val bounds = paddedBounds(source, detection)
        if (regionDecoder != null) {
            val upscaleX = regionDecoder.width.toFloat() / source.width
            val upscaleY = regionDecoder.height.toFloat() / source.height
            val fullLeft = (bounds.left * upscaleX).toInt().coerceIn(0, regionDecoder.width - 1)
            val fullTop = (bounds.top * upscaleY).toInt().coerceIn(0, regionDecoder.height - 1)
            val fullRight = (bounds.right * upscaleX).roundToInt()
                .coerceIn(fullLeft + 1, regionDecoder.width)
            val fullBottom = (bounds.bottom * upscaleY).roundToInt()
                .coerceIn(fullTop + 1, regionDecoder.height)
            val region = runCatching {
                regionDecoder.decodeRegion(
                    Rect(fullLeft, fullTop, fullRight, fullBottom),
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                )
            }.getOrNull()
            if (region != null) return ExtractedCutout(region, bounds)
        }

        // Fallback: crop directly out of the capped `source` bitmap.
        val left = bounds.left.toInt().coerceIn(0, source.width - 1)
        val top = bounds.top.toInt().coerceIn(0, source.height - 1)
        val right = bounds.right.roundToInt().coerceIn(left + 1, source.width)
        val bottom = bounds.bottom.roundToInt().coerceIn(top + 1, source.height)
        return runCatching {
            ExtractedCutout(
                bitmap = Bitmap.createBitmap(source, left, top, right - left, bottom - top),
                bounds = SourceBounds(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            )
        }.getOrNull()
    }

    private fun smoothAlpha(probability: Float): Int {
        val featherLow = MASK_THRESHOLD - 0.035f
        val featherHigh = MASK_THRESHOLD + 0.035f
        val t = ((probability - featherLow) / (featherHigh - featherLow)).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t) // smoothstep
        return (eased * 255f).roundToInt()
    }

    private fun createLetterbox(source: Bitmap): Letterbox {
        val scale = min(INPUT_SIZE.toFloat() / source.width, INPUT_SIZE.toFloat() / source.height)
        val scaledWidth = (source.width * scale).roundToInt()
        val scaledHeight = (source.height * scale).roundToInt()
        val padX = (INPUT_SIZE - scaledWidth) / 2f
        val padY = (INPUT_SIZE - scaledHeight) / 2f
        val bitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(
                source,
                null,
                RectF(padX, padY, padX + scaledWidth, padY + scaledHeight),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
        }
        return Letterbox(bitmap, scale, padX, padY)
    }

    private fun bitmapToTensor(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val planeSize = pixels.size
        return FloatArray(planeSize * 3).also { tensor ->
            pixels.forEachIndexed { index, color ->
                tensor[index] = Color.red(color) / 255f
                tensor[planeSize + index] = Color.green(color) / 255f
                tensor[planeSize * 2 + index] = Color.blue(color) / 255f
            }
        }
    }

    private fun decodeSampledBitmap(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_PAGE_DIMENSION) sampleSize *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private data class Letterbox(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private data class SourceBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    private data class WatershedNode(
        val index: Int,
        val label: Int,
        val cost: Float
    )

    private data class PartitionMarker(
        val index: Int,
        val dominantColor: Int
    )

    private data class ComponentMembership(
        val core: Int = -1,
        val bridge: Int = -1
    )

    private data class SourceConnectivity(
        val enclosed: Boolean,
        val touchedSides: Int,
        val edgeContacts: Int,
        val edgeContactRatio: Float
    )

    private enum class CandidateKind {
        SPEECH_BALLOON,
        CAPTION,
        ENVIRONMENT,
        SOUND_EFFECT,
        UNKNOWN
    }

    private data class CandidateDecision(
        val kind: CandidateKind,
        val accepted: Boolean,
        val confidence: Float,
        val reason: String,
        val enclosureBounds: SourceBounds? = null
    )

    private data class FallbackCandidate(
        val region: DialogueTextRegion,
        val decision: CandidateDecision
    )

    private data class NativeEnclosureEvidence(
        val score: Float,
        val bounds: SourceBounds
    )

    private data class ShapeRaster(
        val width: Int,
        val height: Int,
        val bounds: SourceBounds,
        val pixels: IntArray
    )

    private data class EdgePartition(
        val width: Int,
        val height: Int,
        val bounds: SourceBounds,
        val labels: ByteArray
    ) {
        fun ownershipMask(
            outputWidth: Int,
            outputHeight: Int,
            outputBounds: SourceBounds,
            owner: Int
        ): BooleanArray {
            val outputScaleX = outputWidth /
                    (outputBounds.right - outputBounds.left).coerceAtLeast(1f)
            val outputScaleY = outputHeight /
                    (outputBounds.bottom - outputBounds.top).coerceAtLeast(1f)
            val partitionScaleX = width / (bounds.right - bounds.left).coerceAtLeast(1f)
            val partitionScaleY = height / (bounds.bottom - bounds.top).coerceAtLeast(1f)
            return BooleanArray(outputWidth * outputHeight) { index ->
                val outputX = index % outputWidth
                val outputY = index / outputWidth
                val sourceX = outputBounds.left + (outputX + 0.5f) / outputScaleX
                val sourceY = outputBounds.top + (outputY + 0.5f) / outputScaleY
                val partitionX = ((sourceX - bounds.left) * partitionScaleX).toInt()
                    .coerceIn(0, width - 1)
                val partitionY = ((sourceY - bounds.top) * partitionScaleY).toInt()
                    .coerceIn(0, height - 1)
                (labels[partitionY * width + partitionX].toInt() and 0xFF) == owner
            }
        }
    }

    private data class ExtractedCutout(
        val bitmap: Bitmap,
        val bounds: SourceBounds
    )

    private data class SavedCutout(
        val path: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val isPresentationFallback: Boolean,
        val strategy: MaskStrategy
    )

    private enum class MaskStrategy {
        NATIVE_REFINED,
        MODEL_SOLID,
        RAW_MODEL,
        HYBRID_TOPOLOGY,
        POLISHED_PAGE_CONTEXT
    }

    private data class MaskQuality(
        val total: Float,
        val detail: String,
        val hardGatesPassed: Boolean = false,
        val failedGates: List<String> = emptyList()
    )

    private data class AlphaCandidate(
        val strategy: MaskStrategy,
        val alpha: IntArray,
        val score: Float = 0f,
        val boundarySnapped: Boolean = false
    )

    private data class RefinedAlphaResult(
        val alpha: IntArray,
        val allBoundariesSnapped: Boolean,
        val snappedLobes: Int,
        val totalLobes: Int
    )

    private data class DetectedBubble(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val confidence: Float,
        val coefficients: FloatArray? = null,
        val prototypes: FloatArray? = null,
        val prototypeWidth: Int = 0,
        val prototypeHeight: Int = 0,
        val letterbox: Letterbox? = null,
        val textRegion: DialogueTextRegion? = null,
        val textRegions: List<DialogueTextRegion> = emptyList(),
        val partitionGroupId: Int = -1,
        val partitionRegions: List<DialogueTextRegion> = emptyList(),
        val partitionRegionGroups: List<List<DialogueTextRegion>> = emptyList(),
        val partitionRegionIndex: Int = -1,
        val isOcrFallback: Boolean = false,
        val candidateKind: CandidateKind = CandidateKind.SPEECH_BALLOON,
        val modelClass: BubbleModelClass = BubbleModelClass.GENERIC
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val area: Float get() = (right - left) * (bottom - top)
        val anchorLeft: Float get() = textRegion?.left ?: left
        val anchorTop: Float get() = textRegion?.top ?: top
        val anchorBottom: Float get() = textRegion?.bottom ?: bottom
        val anchorHeight: Float get() = anchorBottom - anchorTop
        val anchorCenterY: Float get() = (anchorTop + anchorBottom) / 2f

        fun createProbabilityMask(): FloatArray? {
            val coefficients = coefficients ?: return null
            val prototypes = prototypes ?: return null
            if (prototypeWidth <= 0 || prototypeHeight <= 0) return null
            val planeSize = prototypeWidth * prototypeHeight
            return FloatArray(planeSize) { pixelOffset ->
                var logit = 0f
                for (channel in 0 until MASK_CHANNELS) {
                    logit += coefficients[channel] * prototypes[channel * planeSize + pixelOffset]
                }
                (1.0 / (1.0 + exp(-logit.toDouble()))).toFloat()
            }
        }

        fun maskProbability(mask: FloatArray?, sourceX: Float, sourceY: Float): Float {
            if (mask == null) return 0f
            val letterbox = letterbox ?: return 0f
            val modelX = sourceX * letterbox.scale + letterbox.padX
            val modelY = sourceY * letterbox.scale + letterbox.padY
            val prototypeX = (modelX / INPUT_SIZE * prototypeWidth)
                .coerceIn(0f, prototypeWidth - 1f)
            val prototypeY = (modelY / INPUT_SIZE * prototypeHeight)
                .coerceIn(0f, prototypeHeight - 1f)
            val x0 = floor(prototypeX).toInt()
            val y0 = floor(prototypeY).toInt()
            val x1 = (x0 + 1).coerceAtMost(prototypeWidth - 1)
            val y1 = (y0 + 1).coerceAtMost(prototypeHeight - 1)
            val xWeight = prototypeX - x0
            val yWeight = prototypeY - y0
            val top = mask[y0 * prototypeWidth + x0] * (1f - xWeight) +
                    mask[y0 * prototypeWidth + x1] * xWeight
            val bottom = mask[y1 * prototypeWidth + x0] * (1f - xWeight) +
                    mask[y1 * prototypeWidth + x1] * xWeight
            return top * (1f - yWeight) + bottom * yWeight
        }
    }
}
