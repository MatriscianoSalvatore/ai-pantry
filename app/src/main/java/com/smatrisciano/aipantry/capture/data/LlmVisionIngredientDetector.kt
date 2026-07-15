package com.smatrisciano.aipantry.capture.data

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import com.smatrisciano.aipantry.core.data.ai.LlmEngineHolder
import com.smatrisciano.aipantry.core.data.ai.LlmModel
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import com.smatrisciano.aipantry.core.data.ai.text
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
 * Riconoscimento ingredienti dalla foto con il modello multimodale attivo
 * (Gemma 4 E2B con vision modality, runtime LiteRT-LM) — completamente
 * on-device. Approccio "solo Gemma": nessun detector ausiliario, lo stesso
 * modello riconosce gli ingredienti e genera le ricette.
 */
class LlmVisionIngredientDetector(
    private val modelRepository: ModelRepository,
    private val engineHolder: LlmEngineHolder
) : IngredientDetector {

    override val engineName: String
        get() = "${modelRepository.activeModel().displayName} vision · LiteRT"

    override suspend fun detect(
        bitmap: Bitmap,
        target: ScanTarget
    ): List<DetectedIngredient> = withContext(Dispatchers.Default) {
        val model = requireNotNull(modelRepository.readyActiveModel()) { "No LLM model available" }
        require(model.supportsVision) { "Active model has no vision support" }

        val jpeg = bitmap.downscaled().toJpegBytes()
        val rawOutput = detectChecked(model, target, jpeg)
        DetectionJsonParser.parse(rawOutput)
    }

    /**
     * Stesso watchdog GPU di [com.smatrisciano.aipantry.recipes.data.LlmRecipeGenerator]:
     * su driver degradato `sendMessage` può bloccarsi per sempre (chiamata
     * nativa non interrompibile), quindi la vision inference gira in un job
     * scollegato e allo scadere del timeout si forza la CPU.
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
                Contents.of(Content.Text(buildPrompt(target)), Content.ImageBytes(jpeg))
            ).text()
        }

        if (!engineHolder.currentBackendIsGpu()) return sendMessage()

        val generation = watchdogScope.async { sendMessage() }
        return withTimeoutOrNull(DETECTION_TIMEOUT_MS) { generation.await() } ?: run {
            generation.cancel()
            Log.w(TAG, "GPU detection timed out after ${DETECTION_TIMEOUT_MS}ms, switching to CPU")
            engineHolder.reportGpuUnusable(model)
            sendMessage()
        }
    }

    // Scope scollegato per il watchdog: i job che vi girano possono restare
    // bloccati su una chiamata GPU nativa senza trascinarsi la coroutine chiamante
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun buildPrompt(target: ScanTarget): String {
        val place = if (target == ScanTarget.FRIDGE) "fridge" else "pantry"
        return """
            This is a photo of the inside of a $place.
            Identify every food ingredient you can see.
            Respond with ONLY a JSON array (no markdown, no extra text):
            [{"name": "short ingredient name", "quantity": "approximate quantity like '2 pcs' or '1 carton'", "confidence": 0.0-1.0}]
            Only include items you actually see. Use common English ingredient names.
        """.trimIndent()
    }

    // A 768px lato lungo produce fino a ~2400 patch nel vision encoder (quasi
    // il limite 2520) e satura la RAM su device con poco margine, causando un
    // kill silenzioso del processo da parte del low-memory killer di Android
    // a metà inferenza. 512px riduce i patch di ~55% mantenendo abbastanza
    // dettaglio per riconoscere ingredienti in una foto di frigo/dispensa.
    private fun Bitmap.downscaled(maxSide: Int = 512): Bitmap {
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

        // Vision inference è più pesante della sola generazione testo (token
        // immagine extra): timeout più alto dei 75s usati per le ricette
        const val DETECTION_TIMEOUT_MS = 120_000L
    }
}

/** Estrae e deserializza le detection dall'output dell'LLM. */
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
