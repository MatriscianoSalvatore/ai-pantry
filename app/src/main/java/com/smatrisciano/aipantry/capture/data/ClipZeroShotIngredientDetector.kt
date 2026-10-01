package com.smatrisciano.aipantry.capture.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import com.smatrisciano.aipantry.core.domain.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Zero-shot ingredient recognition: the MobileCLIP-S2 image encoder (LiteRT)
 * embeds crops of the photo and compares them by cosine similarity with the
 * text embeddings of ~860 ingredients, precomputed offline
 * (scripts/prepare_clip_assets.py) and bundled as an asset.
 *
 * Discriminative, not generative: hundreds of ms instead of minutes, and it runs
 * everywhere (emulator included). It is the fallback when Gemini Nano/AICore is
 * missing. Inherent limits: closed vocabulary and no quantity estimate.
 */
class ClipZeroShotIngredientDetector(private val context: Context) : IngredientDetector {

    override val engineName: String = "MobileCLIP-S2 zero-shot · LiteRT"

    @Serializable
    private data class LabelSpace(
        val dim: Int,
        val labels: List<String>,
        val embeddings: List<List<Float>>,
        // Distractors ("empty shelf", "plastic container", excluded snacks…) compete
        // in the softmax and absorb crops, but never show up in the results
        val distractors: List<Boolean> = emptyList(),
        // Name shown in the UI: several specific labels ("lactose-free milk",
        // "whole milk carton") can merge into the same name ("milk")
        val display: List<String> = emptyList(),
        // The same names in Italian (scripts/ingredient_names_it.txt): matching
        // stays on the English labels, only the reported name changes
        @SerialName("display_it") val displayIt: List<String> = emptyList()
    ) {
        fun displayName(i: Int, language: AppLanguage): String = when (language) {
            AppLanguage.IT -> displayIt.getOrNull(i) ?: display.getOrElse(i) { labels[i] }
            AppLanguage.EN -> display.getOrElse(i) { labels[i] }
        }
    }

    private val labelSpace: LabelSpace by lazy {
        Json { ignoreUnknownKeys = true }.decodeFromString<LabelSpace>(
            context.assets.open(LABELS_ASSET).bufferedReader().readText()
        )
    }

    // Already L2-normalised at export; flat matrix for the dot product
    private val labelMatrix: Array<FloatArray> by lazy {
        labelSpace.embeddings.map { it.toFloatArray() }.toTypedArray()
    }

    // The mapped file is shared: the weights sit in memory only once, even
    // with several interpreters
    private val modelBuffer by lazy {
        context.assets.openFd(MODEL_ASSET).use { fd ->
            fd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    // An Interpreter isn't thread-safe: a pool of instances (weights shared via
    // mmap) embeds the crops in parallel instead of one after another
    private val interpreterPool: List<Interpreter> by lazy {
        List(POOL_SIZE) {
            Interpreter(modelBuffer, Interpreter.Options().apply { numThreads = THREADS_PER_INTERPRETER })
        }
    }

    // The input can be NHWC (1,256,256,3) or NCHW (1,3,256,256) depending on
    // the export: detected once from the tensor shape
    private val inputIsNchw: Boolean by lazy {
        interpreterPool.first().getInputTensor(0).shape()[1] == 3
    }

    override suspend fun detect(bitmap: Bitmap, target: ScanTarget): List<DetectedIngredient> =
        withContext(Dispatchers.Default) {
            val start = System.currentTimeMillis()
            val bestScore = FloatArray(labelSpace.labels.size)
            val crops = generateCrops(bitmap)
            // Round-robin of the crops over the pool's interpreters, one worker per
            // interpreter: real parallelism without contending for the same instance
            val perCropTops = interpreterPool.mapIndexed { worker, interpreter ->
                async {
                    crops.filterIndexed { i, _ -> i % interpreterPool.size == worker }
                        .map { crop -> softmaxOverLabels(embed(interpreter, crop)) }
                }
            }.awaitAll().flatten()
            for (probs in perCropTops) {
                // Only the crop's best matches: in a crowded crop the softmax spreads
                // the probability and absolute scores drop, but the top 3 stay a
                // reliable signal
                val top = probs.indices.sortedByDescending { probs[it] }.take(TOP_PER_CROP)
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "crop top: " + top.joinToString {
                        "${labelSpace.labels[it]}=${"%.2f".format(probs[it])}"
                    })
                }
                for (i in top) {
                    if (probs[i] > bestScore[i]) bestScore[i] = probs[i]
                }
            }
            Log.i(TAG, "${crops.size} crops in ${System.currentTimeMillis() - start}ms")
            val distractors = labelSpace.distractors
            val language = AppLanguage.current()
            bestScore.indices
                .filter { distractors.getOrElse(it) { false }.not() && bestScore[it] >= MIN_PROB }
                // Different labels with the same display name (e.g. the milk variants)
                // collapse into a single result, with the best score
                .groupBy { labelSpace.displayName(it, language) }
                .map { (name, indices) -> name to indices.maxOf { bestScore[it] } }
                .sortedByDescending { (_, score) -> score }
                .take(MAX_RESULTS)
                .map { (name, score) ->
                    DetectedIngredient(
                        name = name.replaceFirstChar(Char::uppercase),
                        // CLIP classifies, it doesn't count: placeholder quantity
                        quantity = "1 pc",
                        confidence = score
                    )
                }
        }

    /**
     * Multi-scale square crops with 25% overlap: the full frame squeezes small
     * objects below the encoder's useful resolution (256 px), so the photo is
     * scanned at two finer scales. The last tile of each row and column is
     * anchored to the edge so the photo's margins aren't lost.
     */
    private fun generateCrops(bitmap: Bitmap): List<Bitmap> {
        val minSide = minOf(bitmap.width, bitmap.height)
        val crops = mutableListOf<Bitmap>()
        for (scale in CROP_SCALES) {
            val side = (minSide * scale).toInt()
            val step = side * 3 / 4
            for (y in edgeAnchoredSteps(bitmap.height, side, step)) {
                for (x in edgeAnchoredSteps(bitmap.width, side, step)) {
                    crops += Bitmap.createBitmap(bitmap, x, y, side, side)
                }
            }
        }
        return crops
    }

    /** Fixed-step offsets, with the last one anchored to the edge. */
    private fun edgeAnchoredSteps(extent: Int, side: Int, step: Int): List<Int> {
        if (side >= extent) return listOf(0)
        val offsets = (0..(extent - side) step step).toMutableList()
        if (offsets.last() != extent - side) offsets += extent - side
        return offsets
    }

    private fun centerSquare(bitmap: Bitmap): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        return Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
    }

    /** Training preprocessing: 256x256 bilinear, RGB in [0,1], no normalisation. */
    private fun embed(interpreter: Interpreter, crop: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(crop, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val input = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        if (inputIsNchw) {
            for (c in 0..2) {
                for (p in pixels) {
                    val channel = (p shr (16 - 8 * c)) and 0xFF
                    input.putFloat(channel / 255f)
                }
            }
        } else {
            for (p in pixels) {
                input.putFloat(((p shr 16) and 0xFF) / 255f)
                input.putFloat(((p shr 8) and 0xFF) / 255f)
                input.putFloat((p and 0xFF) / 255f)
            }
        }
        input.rewind()

        val output = Array(1) { FloatArray(labelSpace.dim) }
        interpreter.run(input, output)
        return l2Normalize(output[0])
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    /** Softmax over the similarities scaled by the standard CLIP logit scale (100). */
    private fun softmaxOverLabels(embedding: FloatArray): FloatArray {
        val logits = FloatArray(labelMatrix.size)
        for (i in labelMatrix.indices) {
            var dot = 0f
            val label = labelMatrix[i]
            for (d in embedding.indices) dot += embedding[d] * label[d]
            logits[i] = dot * LOGIT_SCALE
        }
        val max = logits.max()
        var sum = 0f
        for (i in logits.indices) {
            logits[i] = exp(logits[i] - max)
            sum += logits[i]
        }
        for (i in logits.indices) logits[i] /= sum
        return logits
    }

    private companion object {
        const val TAG = "ClipDetector"
        const val MODEL_ASSET = "clip/mobileclip_s2_image.tflite"
        const val LABELS_ASSET = "clip/label_embeddings.json"
        const val INPUT_SIZE = 256
        const val LOGIT_SCALE = 100f

        // Below this probability (softmax over ~880 labels) a match is noise.
        // Explicit requirement: discard everything below 22%
        const val MIN_PROB = 0.22f
        const val MAX_RESULTS = 15
        const val TOP_PER_CROP = 3

        // Fractions of the photo's short side, sized for objects (not for the
        // scene): with 25% overlap ≈ 36 crops on a 4:3 photo, processed in parallel by
        // the pool
        val CROP_SCALES = floatArrayOf(0.5f, 0.33f)

        val POOL_SIZE = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        const val THREADS_PER_INTERPRETER = 2
    }
}
