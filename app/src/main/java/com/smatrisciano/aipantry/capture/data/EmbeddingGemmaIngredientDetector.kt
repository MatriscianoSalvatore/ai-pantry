package com.smatrisciano.aipantry.capture.data

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanProgress
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Zero-shot ingredient recognition with EmbeddingGemma 2 (text and vision, 440M, LiteRT-LM):
 * the scan MobileCLIP makes ([ZeroShotScan]), with the crops and the ingredient names in
 * EmbeddingGemma's space. The names are embedded offline
 * (scripts/prepare_embeddinggemma_assets.py): on the phone only the crops are.
 *
 * Only when picked on the hidden page, to try it out: the app never chooses it by itself,
 * and doesn't deliver the model either, which is copied in with adb ([installPath]).
 */
class EmbeddingGemmaIngredientDetector(private val context: Context) : IngredientDetector {

    override val engineName: String = "EmbeddingGemma 2 zero-shot · LiteRT-LM"

    private val scan = ZeroShotScan(context, LABELS_ASSET, TAG)

    // One engine, embedding one crop at a time; the lock keeps it from being closed under a scan
    @Volatile
    private var engine: EmbeddingEngine? = null
    private val engineLock = Mutex()

    @Volatile
    private var engineIsGpu = false

    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Where it runs, once it is loaded: for the verbose display. */
    val backendName: String
        get() = when {
            engine == null -> "LiteRT-LM"
            engineIsGpu -> "GPU"
            else -> "CPU"
        }

    /** The model's file: one pushed into /data/local/tmp/llm with adb comes first, as for Gemma. */
    fun modelFile(): File = File(SIDELOAD_DIR, FILE_NAME).takeIf { it.exists() } ?: File(folder(), FILE_NAME)

    /** Where the file goes when it is copied in with adb: the folder its caches are written in. */
    fun installPath(): String = File(folder(), FILE_NAME).absolutePath

    fun isPresent(): Boolean = modelFile().exists()

    /** What the model weighs; null when it isn't on the phone. */
    fun modelSizeBytes(): Long? = modelFile().takeIf { it.exists() }?.length()

    /** The labels and the engine, ready before the shot (the camera has just opened). */
    override suspend fun warmUp() {
        withContext(Dispatchers.Default) {
            val start = SystemClock.elapsedRealtime()
            val labels = scan.prepare()
            engineLock.withLock { engine() }
            Log.i(TAG, "$labels labels and the engine ($backendName) ready in ${SystemClock.elapsedRealtime() - start}ms")
        }
    }

    override suspend fun detect(
        bitmap: Bitmap,
        target: ScanTarget,
        onProgress: (ScanProgress) -> Unit
    ): List<DetectedIngredient> = withContext(Dispatchers.Default) {
        engineLock.withLock {
            val engine = engine()
            scan.detect(bitmap, onProgress, listOf { crop: Bitmap -> embed(engine, crop) })
        }
    }

    /**
     * Takes the model out of memory (some 400-700 MB): the camera is closed, or another detector
     * took over, and Gemma will want the room. A scan under way finishes first.
     */
    override fun release() {
        releaseScope.launch {
            engineLock.withLock {
                engine?.let {
                    it.close()
                    Log.i(TAG, "Out of memory until the next scan")
                }
                engine = null
                engineIsGpu = false
            }
        }
    }

    /**
     * The engine, loaded on first use: on the GPU if it starts there and gives sound embeddings,
     * otherwise on the CPU. On a Pixel 7 the GPU takes some 1.5 s a crop, the CPU 2.5 (the
     * vision encoder is most of it). Called with [engineLock] held.
     */
    private fun engine(): EmbeddingEngine {
        engine?.let { return it }
        val file = modelFile()
        check(file.exists()) { "EmbeddingGemma 2 is not on the device: copy it to ${installPath()}" }
        val created = create(file, gpu = true)?.also { engineIsGpu = true }
            ?: create(file, gpu = false)?.also { engineIsGpu = false }
            ?: error("Cannot initialize EmbeddingGemma 2")
        engine = created
        return created
    }

    private fun create(file: File, gpu: Boolean): EmbeddingEngine? = runCatching {
        val backend = if (gpu) Backend.GPU() else Backend.CPU(threadCount = CPU_THREADS)
        val engine = EmbeddingEngine(
            EmbeddingEngineConfig(
                modelPath = file.absolutePath,
                backend = backend,
                visionBackend = backend,
                // Where the app can write, wherever the model is: the GPU doesn't start
                // without writing its weights first
                cacheDir = folder().absolutePath,
                maxInputLength = MAX_INPUT_TOKENS,
                visionTokensPerImage = VISION_TOKENS
            )
        ).apply { initialize() }
        // A first embedding pays the one-off costs, and tells a GPU that gives garbage
        val probe = embed(engine, Bitmap.createBitmap(PROBE_SIDE, PROBE_SIDE, Bitmap.Config.ARGB_8888))
        if (probe.any { !it.isFinite() } || probe.all { it == 0f }) {
            engine.close()
            error("unusable embedding")
        }
        engine
    }.onFailure {
        Log.w(TAG, "EmbeddingGemma 2 init failed (gpu=$gpu)", it)
    }.getOrNull()

    /**
     * The crop as LiteRT-LM takes an image, encoded; it scales it to the vision encoder's
     * input itself. A big crop (a camera photo is some 4000 px) is scaled down first: it
     * would only cost more to encode and decode.
     */
    private fun embed(engine: EmbeddingEngine, crop: Bitmap): FloatArray {
        val image = if (crop.width > MAX_CROP_SIDE) {
            Bitmap.createScaledBitmap(crop, MAX_CROP_SIDE, crop.height * MAX_CROP_SIDE / crop.width, true)
        } else {
            crop
        }
        val jpeg = ByteArrayOutputStream().use { out ->
            image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        return engine.computeEmbedding(
            listOf(InputData.Image(jpeg)),
            EmbeddingOptions(normalize = true, visionTokensPerImage = VISION_TOKENS)
        ).embedding
    }

    private fun folder(): File =
        (context.getExternalFilesDir("embedding") ?: File(context.filesDir, "embedding")).apply { mkdirs() }

    private companion object {
        const val TAG = "EmbeddingGemmaDetector"
        const val FILE_NAME = "embeddinggemma-2-text-vision-440m.litertlm"
        const val SIDELOAD_DIR = "/data/local/tmp/llm"
        const val LABELS_ASSET = "embeddinggemma/label_embeddings.json"

        // The vision encoder takes 70 or 140 soft tokens per image: on the demo photos 70
        // finds as much as 140, in less than half the time
        const val VISION_TOKENS = 70

        // An image is its soft tokens and a few special ones. Without a limit the engine
        // readies every text length up to 8192 tokens too: on the Pixel 7's GPU that is
        // GBs, and Android closes the app
        const val MAX_INPUT_TOKENS = 128

        const val MAX_CROP_SIDE = 512
        const val JPEG_QUALITY = 90
        const val PROBE_SIDE = 64

        // As for Gemma on the CPU: all the cores heat and throttle the phone
        val CPU_THREADS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    }
}
