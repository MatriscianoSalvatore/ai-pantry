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
 * Riconoscimento ingredienti zero-shot: l'image encoder MobileCLIP-S2 (LiteRT)
 * embedda crop della foto e li confronta via cosine similarity con gli
 * embedding testuali di ~200 ingredienti precomputati offline
 * (scripts/prepare_clip_assets.py) e bundlati come asset.
 *
 * Discriminativo, non generativo: centinaia di ms invece di minuti, e gira
 * ovunque (emulatore incluso) — è il fallback quando Gemini Nano/AICore non
 * c'è. Limite intrinseco: vocabolario chiuso e niente stima delle quantità.
 */
class ClipZeroShotIngredientDetector(private val context: Context) : IngredientDetector {

    override val engineName: String = "MobileCLIP-S2 zero-shot · LiteRT"

    @Serializable
    private data class LabelSpace(
        val dim: Int,
        val labels: List<String>,
        val embeddings: List<List<Float>>,
        // I distrattori ("empty shelf", "plastic container"…) competono nel
        // softmax assorbendo i crop senza cibo, ma non vanno mai nei risultati
        val distractors: List<Boolean> = emptyList(),
        // Nome mostrato in UI: più label specifiche ("lactose-free milk",
        // "whole milk carton") possono confluire nello stesso nome ("milk")
        val display: List<String> = emptyList(),
        // Gli stessi nomi in italiano (scripts/ingredient_names_it.txt): il
        // matching resta sulle label inglesi, cambia solo il nome riportato
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

    // L2-normalizzati già in export; matrice piatta per il prodotto scalare
    private val labelMatrix: Array<FloatArray> by lazy {
        labelSpace.embeddings.map { it.toFloatArray() }.toTypedArray()
    }

    // Il file mappato è condiviso: i pesi stanno in memoria una volta sola
    // anche con più interpreter
    private val modelBuffer by lazy {
        context.assets.openFd(MODEL_ASSET).use { fd ->
            fd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    // Un Interpreter non è thread-safe: pool di istanze (pesi condivisi via
    // mmap) per embeddare i crop in parallelo invece che in sequenza
    private val interpreterPool: List<Interpreter> by lazy {
        List(POOL_SIZE) {
            Interpreter(modelBuffer, Interpreter.Options().apply { numThreads = THREADS_PER_INTERPRETER })
        }
    }

    // L'input può essere NHWC (1,256,256,3) o NCHW (1,3,256,256) a seconda
    // dell'export: si rileva una volta dalla shape del tensore
    private val inputIsNchw: Boolean by lazy {
        interpreterPool.first().getInputTensor(0).shape()[1] == 3
    }

    override suspend fun detect(bitmap: Bitmap, target: ScanTarget): List<DetectedIngredient> =
        withContext(Dispatchers.Default) {
            val start = System.currentTimeMillis()
            val bestScore = FloatArray(labelSpace.labels.size)
            val crops = generateCrops(bitmap)
            // Round-robin dei crop sugli interpreter del pool, un worker per
            // interpreter: parallelismo reale senza contendersi la stessa istanza
            val perCropTops = interpreterPool.mapIndexed { worker, interpreter ->
                async {
                    crops.filterIndexed { i, _ -> i % interpreterPool.size == worker }
                        .map { crop -> softmaxOverLabels(embed(interpreter, crop)) }
                }
            }.awaitAll().flatten()
            for (probs in perCropTops) {
                // Solo i migliori match del crop: in un crop affollato il
                // softmax spalma la probabilità e i punteggi assoluti calano,
                // ma i primi 3 restano segnale affidabile
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
                // Label diverse con lo stesso display name (es. le varianti di
                // latte) collassano in un risultato solo, col punteggio migliore
                .groupBy { labelSpace.displayName(it, language) }
                .map { (name, indices) -> name to indices.maxOf { bestScore[it] } }
                .sortedByDescending { (_, score) -> score }
                .take(MAX_RESULTS)
                .map { (name, score) ->
                    DetectedIngredient(
                        name = name.replaceFirstChar(Char::uppercase),
                        // CLIP classifica, non conta: quantità di cortesia
                        quantity = "1 pc",
                        confidence = score
                    )
                }
        }

    /**
     * Crop quadrati multi-scala con ~50% di overlap: il frame intero schiaccia
     * gli oggetti piccoli sotto la risoluzione utile dell'encoder (256px),
     * quindi si scandaglia a due scale più fitte. L'ultima tile di ogni riga e
     * colonna è agganciata al bordo per non perdere i margini della foto.
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

    /** Offset a passo fisso, con l'ultimo agganciato al bordo. */
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

    /** Preprocessing del training: 256x256 bilineare, RGB in [0,1], nessuna normalizzazione. */
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

    /** Softmax sulle similarity scalate con la logit scale standard CLIP (100). */
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

        // Sotto questa probabilità (softmax su ~550 label) il match è rumore.
        // Richiesta esplicita: scartare tutto ciò che sta sotto il 22%
        const val MIN_PROB = 0.22f
        const val MAX_RESULTS = 15
        const val TOP_PER_CROP = 3

        // Frazioni del lato corto della foto, a misura di oggetto (non di
        // scena): con overlap 25% ≈ 30 crop, smaltiti in parallelo dal pool
        val CROP_SCALES = floatArrayOf(0.5f, 0.33f)

        val POOL_SIZE = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        const val THREADS_PER_INTERPRETER = 2
    }
}
