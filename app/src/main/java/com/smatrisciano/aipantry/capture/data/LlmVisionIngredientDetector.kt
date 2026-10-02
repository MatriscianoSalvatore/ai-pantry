package com.smatrisciano.aipantry.capture.data

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanProgress
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import com.smatrisciano.aipantry.core.data.ai.LlmEngineHolder
import com.smatrisciano.aipantry.core.data.ai.LlmModel
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import com.smatrisciano.aipantry.core.data.ai.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

/**
 * Ingredient recognition from the photo with the active multimodal model
 * (Gemma 4 E2B with vision modality, LiteRT-LM runtime), fully on-device.
 * "Gemma only" approach: no auxiliary detector, the same model recognises the
 * ingredients and generates the recipes. Kept as an alternative route, off the
 * default path (see CaptureModule).
 */
class LlmVisionIngredientDetector(
    private val modelRepository: ModelRepository,
    private val engineHolder: LlmEngineHolder
) : IngredientDetector {

    override val engineName: String
        get() = "${modelRepository.activeModel().displayName} vision · LiteRT"

    // One inference on the whole photo: there is no progress to report along the way
    override suspend fun detect(
        bitmap: Bitmap,
        target: ScanTarget,
        onProgress: (ScanProgress) -> Unit
    ): List<DetectedIngredient> = withContext(Dispatchers.Default) {
        val model = requireNotNull(modelRepository.readyActiveModel()) { "No LLM model available" }
        require(model.supportsVision) { "Active model has no vision support" }

        // The warm-up at startup has already chosen the backend, so this is reliable.
        // On CPU every extra patch is prefill the user waits for: drop to 256 px
        // (~256 patches instead of ~1000), accepting less detail to keep the scan
        // time tolerable.
        val maxSide = if (engineHolder.currentBackendIsGpu()) 512 else 256
        val jpeg = bitmap.downscaled(maxSide).toJpegBytes()
        val rawOutput = detectChecked(model, target, jpeg)
        DetectionJsonParser.parse(rawOutput)
    }

    /**
     * Same GPU watchdog as [com.smatrisciano.aipantry.recipes.data.LlmRecipeGenerator]:
     * on a degraded driver `sendMessage` can block forever (non-interruptible
     * native call), so vision inference runs in a detached job and the CPU is
     * forced when the timeout expires.
     */
    private suspend fun detectChecked(model: LlmModel, target: ScanTarget, jpeg: ByteArray): String {
        val file = modelRepository.modelFile(model)

        fun sendMessage() = engineHolder.createConversation(
            model,
            file,
            temperature = 0.2,
            topK = 40
        ).use { conversation ->
            conversation.sendMessage(
                Contents.of(Content.Text(DetectionPrompt.build(target)), Content.ImageBytes(jpeg))
            ).text()
        }

        if (!engineHolder.currentBackendIsGpu()) return sendMessage()

        val generation = watchdogScope.async { sendMessage() }
        // Besides hanging, the GPU can fail with an immediate exception (e.g.
        // OpenCL missing on the emulator): same treatment as the timeout
        val result = try {
            withTimeoutOrNull(DETECTION_TIMEOUT_MS) { generation.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "GPU detection failed", e)
            null
        }
        return result ?: run {
            generation.cancel()
            Log.w(TAG, "GPU detection failed or timed out, switching to CPU")
            engineHolder.reportGpuUnusable(model)
            sendMessage()
        }
    }

    // Detached scope for the watchdog: its jobs can stay stuck on a native GPU
    // call without dragging the calling coroutine along
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // At 768 px on the long side it produces up to ~2400 patches in the vision
    // encoder (almost the 2520 limit) and saturates RAM on devices with little
    // headroom, causing a silent process kill by Android's low-memory killer
    // mid-inference. 512 px cuts the patches by ~55% while keeping enough detail
    // to recognise ingredients in a fridge/pantry photo; on the CPU backend it
    // drops to 256 px (see [detect]).
    private fun Bitmap.downscaled(maxSide: Int): Bitmap {
        val largest = maxOf(width, height)
        if (largest <= maxSide) return this
        val scale = maxSide.toFloat() / largest
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt(),
            (height * scale).toInt(),
            true
        )
    }

    private fun Bitmap.toJpegBytes(quality: Int = 85): ByteArray =
        ByteArrayOutputStream().use { stream ->
            compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }

    private companion object {
        const val TAG = "LlmVisionDetector"

        // Vision inference is heavier than text-only generation (extra image
        // tokens): a higher timeout than the 75 s used for recipes
        const val DETECTION_TIMEOUT_MS = 120_000L
    }
}

/** Extracts and deserialises the detections from the LLM output. */
object DetectionJsonParser {

    @Serializable
    private data class DetectionDto(
        val name: String,
        val quantity: String = "1 pc",
        val confidence: Float = 0.8f
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(rawOutput: String): List<DetectedIngredient> {
        val start = rawOutput.indexOf('[')
        val end = rawOutput.lastIndexOf(']')
        require(start in 0 until end) { "No JSON array found in LLM output" }
        return json.decodeFromString<List<DetectionDto>>(rawOutput.substring(start, end + 1))
            .map {
                DetectedIngredient(
                    name = it.name.replaceFirstChar(Char::uppercase),
                    quantity = it.quantity,
                    confidence = it.confidence.coerceIn(0f, 1f)
                )
            }
            .distinctBy { it.name.lowercase() }
    }
}
