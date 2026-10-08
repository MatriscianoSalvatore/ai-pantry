package com.smatrisciano.aipantry.capture.data

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.ScanCell
import com.smatrisciano.aipantry.capture.domain.ScanGrid
import com.smatrisciano.aipantry.capture.domain.ScanProgress
import com.smatrisciano.aipantry.core.domain.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp

/**
 * Zero-shot recognition with a model that puts photos and words in the same space: crops
 * of the photo are embedded and compared by cosine similarity with the embeddings of the
 * ingredient names, precomputed offline and bundled as [labelsAsset]; across the crops
 * each ingredient keeps its best score. The detectors that use it only differ in the model
 * that embeds the crops (MobileCLIP-S2, EmbeddingGemma 2).
 */
internal class ZeroShotScan(
    private val context: Context,
    private val labelsAsset: String,
    private val tag: String
) {

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
            context.assets.open(labelsAsset).bufferedReader().readText()
        )
    }

    // Already L2-normalised at export; flat matrix for the dot product
    private val labelMatrix: Array<FloatArray> by lazy {
        labelSpace.embeddings.map { it.toFloatArray() }.toTypedArray()
    }

    /** The size of the embeddings the crops are compared with. */
    val dim: Int get() = labelSpace.dim

    /** Reads the labels ahead of the first photo, which would otherwise pay for them: how many there are. */
    fun prepare(): Int = labelMatrix.size

    /** A square crop of the photo: where it is in pixels, and the cell it stands for on screen. */
    private class Crop(val x: Int, val y: Int, val side: Int, val cell: ScanCell)

    /** The crops in the order they are looked at, and the grid of each pass they make. */
    private class CropLayout(val crops: List<Crop>, val grids: List<ScanGrid>)

    /**
     * The ingredients in [bitmap], best first. Each of [embedders] takes the next crop as
     * soon as it is free (one per interpreter, where the model can run several at once) and
     * returns its embedding, L2-normalised. [onProgress] follows the crops as they go.
     */
    suspend fun detect(
        bitmap: Bitmap,
        onProgress: (ScanProgress) -> Unit,
        embedders: List<(Bitmap) -> FloatArray>
    ): List<DetectedIngredient> = withContext(Dispatchers.Default) {
        val start = SystemClock.elapsedRealtime()
        val layout = cropsOf(bitmap)
        val crops = layout.crops
        val language = AppLanguage.current()
        val bestScore = FloatArray(labelSpace.labels.size)
        // Guards the scores and the progress: every worker reports as it goes
        val lock = Any()
        val active = linkedSetOf<Int>()
        val done = mutableSetOf<ScanCell>()
        fun report() = onProgress(
            ScanProgress(
                grids = layout.grids,
                totalRegions = crops.size,
                doneRegions = done.size,
                done = done.toSet(),
                active = active.map { crops[it].cell },
                found = results(bestScore, language)
            )
        )

        synchronized(lock) { report() }
        val next = AtomicInteger()
        // The workers take the crops in order, each the next one as soon as it is free:
        // the coarse crops, which cover the whole photo, are all done first
        embedders.map { embed ->
            async {
                while (true) {
                    val index = next.getAndIncrement()
                    if (index >= crops.size) break
                    synchronized(lock) {
                        active += index
                        report()
                    }
                    val crop = crops[index]
                    val pixels = Bitmap.createBitmap(bitmap, crop.x, crop.y, crop.side, crop.side)
                    val probs = softmaxOverLabels(embed(pixels))
                    synchronized(lock) {
                        keepBestMatches(probs, bestScore)
                        active -= index
                        done += crop.cell
                        report()
                    }
                }
            }
        }.awaitAll()
        Log.i(tag, "${crops.size} crops in ${SystemClock.elapsedRealtime() - start}ms")
        results(bestScore, language)
    }

    /**
     * Only the crop's best matches count: in a crowded crop the softmax spreads the
     * probability and absolute scores drop, but the top 3 stay a reliable signal.
     */
    private fun keepBestMatches(probs: FloatArray, bestScore: FloatArray) {
        val top = probs.indices.sortedByDescending { probs[it] }.take(TOP_PER_CROP)
        if (Log.isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, "crop top: " + top.joinToString {
                "${labelSpace.labels[it]}=${"%.2f".format(probs[it])}"
            })
        }
        for (i in top) {
            if (probs[i] > bestScore[i]) bestScore[i] = probs[i]
        }
    }

    /** The ingredients above the threshold so far, best first. */
    private fun results(bestScore: FloatArray, language: AppLanguage): List<DetectedIngredient> {
        val distractors = labelSpace.distractors
        return bestScore.indices
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
                    // Zero-shot classifies, it doesn't count: placeholder quantity
                    quantity = "1 pc",
                    confidence = score
                )
            }
    }

    /**
     * Multi-scale square crops with 25% overlap: the full frame squeezes small
     * objects below the encoder's useful resolution, so the photo is scanned at
     * two finer scales. The last tile of each row and column is anchored to the
     * edge so the photo's margins aren't lost. The coarser scale comes first: it
     * covers the whole photo in a third of the work, so the first ingredients turn
     * up early. Each scale is a pass, its crops row by row: on screen they are the
     * cells of a grid.
     */
    private fun cropsOf(bitmap: Bitmap): CropLayout {
        val minSide = minOf(bitmap.width, bitmap.height)
        val crops = mutableListOf<Crop>()
        val grids = mutableListOf<ScanGrid>()
        CROP_SCALES.forEachIndexed { pass, scale ->
            val side = (minSide * scale).toInt()
            val step = side * 3 / 4
            val rows = edgeAnchoredSteps(bitmap.height, side, step)
            val columns = edgeAnchoredSteps(bitmap.width, side, step)
            grids += ScanGrid(rows = rows.size, columns = columns.size)
            rows.forEachIndexed { row, y ->
                columns.forEachIndexed { column, x ->
                    crops += Crop(x, y, side, ScanCell(pass, row, column))
                }
            }
        }
        return CropLayout(crops, grids)
    }

    /** Fixed-step offsets, with the last one anchored to the edge. */
    private fun edgeAnchoredSteps(extent: Int, side: Int, step: Int): List<Int> {
        if (side >= extent) return listOf(0)
        val offsets = (0..(extent - side) step step).toMutableList()
        if (offsets.last() != extent - side) offsets += extent - side
        return offsets
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
        // EmbeddingGemma 2's similarities sit higher and closer together than CLIP's, yet
        // on the demo photos the same scale and threshold give it sound lists too: one
        // calibration for both
        const val LOGIT_SCALE = 100f

        // Below this probability (softmax over ~880 labels) a match is noise.
        // Explicit requirement: discard everything below 22%
        const val MIN_PROB = 0.22f
        const val MAX_RESULTS = 15
        const val TOP_PER_CROP = 3

        // Fractions of the photo's short side, sized for objects (not for the
        // scene): with 25% overlap ≈ 36 crops on a 4:3 photo
        val CROP_SCALES = floatArrayOf(0.5f, 0.33f)
    }
}
