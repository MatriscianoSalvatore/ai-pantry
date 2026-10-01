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

        // Il warm-up all'avvio ha già deciso il backend, quindi qui è affidabile.
        // Su CPU ogni patch in più è prefill che l'utente aspetta: si scende a
        // 256px (~256 patch contro ~1000) accettando meno dettaglio pur di
        // restare in un tempo di scansione tollerabile.
        val maxSide = if (engineHolder.currentBackendIsGpu()) 512 else 256
        val jpeg = bitmap.downscaled(maxSide).toJpegBytes()
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
                Contents.of(Content.Text(DetectionPrompt.build(target)), Content.ImageBytes(jpeg))
            ).text()
        }

        if (!engineHolder.currentBackendIsGpu()) return sendMessage()

        val generation = watchdogScope.async { sendMessage() }
        // Oltre all'hang, la GPU può fallire con eccezione immediata (es.
        // OpenCL assente sull'emulatore): stessa sorte del timeout
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

    // Scope scollegato per il watchdog: i job che vi girano possono restare
    // bloccati su una chiamata GPU nativa senza trascinarsi la coroutine chiamante
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A 768px lato lungo produce fino a ~2400 patch nel vision encoder (quasi
    // il limite 2520) e satura la RAM su device con poco margine, causando un
    // kill silenzioso del processo da parte del low-memory killer di Android
    // a metà inferenza. 512px riduce i patch di ~55% mantenendo abbastanza
    // dettaglio per riconoscere ingredienti in una foto di frigo/dispensa;
    // su backend CPU si scende a 256px (vedi [detect]).
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
