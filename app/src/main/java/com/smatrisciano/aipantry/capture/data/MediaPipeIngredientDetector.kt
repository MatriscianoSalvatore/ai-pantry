package com.smatrisciano.aipantry.capture.data

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Riconoscimento ingredienti on-device via MediaPipe ImageClassifier (LiteRT).
 *
 * Il modello va copiato sul device in [modelFile] (vedi README). Se assente,
 * lo [SmartIngredientDetector] usa il fallback demo.
 */
class MediaPipeIngredientDetector(
    private val context: Context
) : IngredientDetector {

    override val engineName = "MediaPipe · LiteRT"

    private val classifier: ImageClassifier by lazy {
        val options = ImageClassifier.ImageClassifierOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(modelFile(context).absolutePath)
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(12)
            .setScoreThreshold(0.25f)
            .build()
        ImageClassifier.createFromOptions(context, options)
    }

    override suspend fun detect(
        bitmap: Bitmap,
        target: ScanTarget
    ): List<DetectedIngredient> = withContext(Dispatchers.Default) {
        val result = classifier.classify(BitmapImageBuilder(bitmap).build())
        result.classificationResult()
            .classifications()
            .flatMap { it.categories() }
            .mapNotNull { category ->
                IngredientLabels.fromModelLabel(category.categoryName())?.let { name ->
                    DetectedIngredient(
                        name = name,
                        quantity = IngredientLabels.defaultQuantity(name),
                        confidence = category.score()
                    )
                }
            }
            .distinctBy { it.name }
    }

    companion object {
        fun modelFile(context: Context): File =
            File(context.filesDir, "models/ingredients.tflite")

        fun isModelAvailable(context: Context): Boolean = modelFile(context).exists()
    }
}

/**
 * Mappa le label del classificatore (ImageNet/Food101-style) su ingredienti
 * "di dispensa" e assegna una quantità approssimativa di default.
 */
object IngredientLabels {

    private val labelMap = mapOf(
        "mozzarella" to "Mozzarella",
        "cheese" to "Mozzarella",
        "milk" to "Milk",
        "milk can" to "Milk",
        "carton" to "Milk",
        "tomato" to "Tomatoes",
        "cherry tomato" to "Tomatoes",
        "basil" to "Basil",
        "herb" to "Basil",
        "pasta" to "Pasta",
        "spaghetti" to "Pasta",
        "carbonara" to "Pasta",
        "tuna" to "Canned tuna",
        "can" to "Canned tuna",
        "onion" to "Onions",
        "parmesan" to "Parmigiano",
        "grated cheese" to "Parmigiano"
    )

    private val quantities = mapOf(
        "Mozzarella" to "1 pack",
        "Milk" to "1 carton",
        "Tomatoes" to "2 pcs",
        "Basil" to "1 bunch",
        "Pasta" to "500 g",
        "Canned tuna" to "2 cans",
        "Onions" to "2 pcs",
        "Parmigiano" to "1 wedge"
    )

    fun fromModelLabel(label: String): String? {
        val normalized = label.lowercase().replace('_', ' ').trim()
        return labelMap.entries.firstOrNull { normalized.contains(it.key) }?.value
    }

    fun defaultQuantity(name: String): String = quantities[name] ?: "1 pc"
}
