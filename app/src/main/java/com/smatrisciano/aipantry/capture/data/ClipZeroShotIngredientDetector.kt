package com.smatrisciano.aipantry.capture.data

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanProgress
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Zero-shot ingredient recognition: the MobileCLIP-S2 image encoder (LiteRT)
 * embeds crops of the photo and compares them by cosine similarity with the
 * text embeddings of ~860 ingredients, precomputed offline
 * (scripts/prepare_clip_assets.py) and bundled as an asset (see [ZeroShotScan]).
 *
 * Discriminative, not generative: hundreds of ms instead of minutes, and it runs
 * everywhere (emulator included). It is the fallback when Gemini Nano/AICore is
 * missing. Inherent limits: closed vocabulary and no quantity estimate.
 */
class ClipZeroShotIngredientDetector(private val context: Context) : IngredientDetector {

    override val engineName: String = "MobileCLIP-S2 zero-shot · LiteRT"

    private val scan = ZeroShotScan(context, LABELS_ASSET, TAG)

    /** What the bundled encoder weighs; null when the build doesn't have it (see scripts/prepare_clip_assets.py). */
    fun modelSizeBytes(): Long? =
        runCatching { context.assets.openFd(MODEL_ASSET).use { it.length } }.getOrNull()

    // The mapped file is shared: the weights sit in memory only once, even
    // with several interpreters
    private val modelBuffer by lazy {
        context.assets.openFd(MODEL_ASSET).use { fd ->
            fd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    // An Interpreter isn't thread-safe: a pool of instances (weights shared via
    // mmap) embeds the crops in parallel instead of one after another
    private var pool: List<Interpreter>? = null
    private val poolLock = Mutex()

    // The input can be NHWC (1,256,256,3) or NCHW (1,3,256,256) depending on
    // the export: detected once from the tensor shape
    @Volatile
    private var inputIsNchw = false

    /**
     * Labels and interpreters, ready before the shot (the camera has just opened):
     * otherwise the first photo would pay for them.
     */
    override suspend fun warmUp() {
        withContext(Dispatchers.Default) {
            val start = SystemClock.elapsedRealtime()
            val labels = scan.prepare()
            pool()
            Log.i(TAG, "$labels labels and $POOL_SIZE interpreters ready in ${SystemClock.elapsedRealtime() - start}ms")
        }
    }

    override suspend fun detect(
        bitmap: Bitmap,
        target: ScanTarget,
        onProgress: (ScanProgress) -> Unit
    ): List<DetectedIngredient> = withContext(Dispatchers.Default) {
        val interpreters = pool()
        scan.detect(bitmap, onProgress, interpreters.map { interpreter -> { crop: Bitmap -> embed(interpreter, crop) } })
    }

    /**
     * The interpreters, created on first use. Each embeds a blank image straight
     * away: the first inference pays one-off costs that would otherwise land on
     * the first photo.
     */
    private suspend fun pool(): List<Interpreter> = poolLock.withLock {
        pool ?: coroutineScope {
            val interpreters = List(POOL_SIZE) {
                async { Interpreter(modelBuffer, Interpreter.Options().apply { numThreads = THREADS_PER_INTERPRETER }) }
            }.awaitAll()
            inputIsNchw = interpreters.first().getInputTensor(0).shape()[1] == 3
            val blank = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            interpreters.map { async { embed(it, blank) } }.awaitAll()
            interpreters
        }.also { pool = it }
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

        val output = Array(1) { FloatArray(scan.dim) }
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

    private companion object {
        const val TAG = "ClipDetector"
        const val MODEL_ASSET = "clip/mobileclip_s2_image.tflite"
        const val LABELS_ASSET = "clip/label_embeddings.json"
        const val INPUT_SIZE = 256

        // ≈ 36 crops on a 4:3 photo (see ZeroShotScan), processed in parallel by the pool
        val POOL_SIZE = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        const val THREADS_PER_INTERPRETER = 2
    }
}
