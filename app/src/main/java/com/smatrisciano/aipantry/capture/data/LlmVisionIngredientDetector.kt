package com.smatrisciano.aipantry.capture.data

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import com.smatrisciano.aipantry.core.data.ai.LlmEngineHolder
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Riconoscimento ingredienti dalla foto con il modello multimodale attivo
 * (Gemma 3n con vision modality) — completamente on-device.
 */
class LlmVisionIngredientDetector(
    private val modelRepository: ModelRepository,
    private val engineHolder: LlmEngineHolder
) : IngredientDetector {

    override val engineName: String
        get() = "${modelRepository.activeModel().displayName} vision · LiteRT"

    fun isAvailable(): Boolean =
        modelRepository.readyActiveModel()?.supportsVision == true

    override suspend fun detect(
        bitmap: Bitmap,
        target: ScanTarget
    ): List<DetectedIngredient> = withContext(Dispatchers.Default) {
        val model = requireNotNull(modelRepository.readyActiveModel()) { "No LLM model available" }
        require(model.supportsVision) { "Active model has no vision support" }

        val engine = engineHolder.acquire(model, modelRepository.modelFile(model))
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(0.2f)
            .setTopK(40)
            .setGraphOptions(
                GraphOptions.builder()
                    .setEnableVisionModality(true)
                    .build()
            )
            .build()

        val rawOutput = LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
            session.addQueryChunk(buildPrompt(target))
            session.addImage(BitmapImageBuilder(bitmap.downscaled()).build())
            session.generateResponse()
        }
        DetectionJsonParser.parse(rawOutput)
    }

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

    private fun Bitmap.downscaled(maxSide: Int = 768): Bitmap {
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
