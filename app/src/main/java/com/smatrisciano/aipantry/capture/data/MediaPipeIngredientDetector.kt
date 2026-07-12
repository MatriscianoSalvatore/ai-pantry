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

/**
 * Classificatore immagini on-device (EfficientNet-Lite2 via MediaPipe/LiteRT),
 * bundlato negli assets dell'APK: funziona out-of-the-box senza download.
 * Riconosce frutta/verdura/cibi delle classi ImageNet, mappate su ingredienti.
 */
class MediaPipeIngredientDetector(
    private val context: Context
) : IngredientDetector {

    override val engineName = "EfficientNet-Lite2 · LiteRT"

    private val classifier: ImageClassifier by lazy {
        val options = ImageClassifier.ImageClassifierOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(ASSET_MODEL_PATH)
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(12)
            .setScoreThreshold(0.20f)
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

    private companion object {
        const val ASSET_MODEL_PATH = "models/ingredients.tflite"
    }
}

/**
 * Mappa le label ImageNet del classificatore su ingredienti "di dispensa"
 * e assegna una quantità approssimativa di default.
 */
object IngredientLabels {

    private val labelMap = mapOf(
        // Frutta e verdura presenti in ImageNet
        "banana" to "Bananas",
        "orange" to "Oranges",
        "lemon" to "Lemons",
        "fig" to "Figs",
        "pineapple" to "Pineapple",
        "strawberry" to "Strawberries",
        "pomegranate" to "Pomegranate",
        "granny smith" to "Apples",
        "custard apple" to "Apples",
        "bell pepper" to "Bell peppers",
        "cucumber" to "Cucumbers",
        "broccoli" to "Broccoli",
        "cauliflower" to "Cauliflower",
        "zucchini" to "Zucchini",
        "mushroom" to "Mushrooms",
        "artichoke" to "Artichokes",
        "head cabbage" to "Cabbage",
        "butternut squash" to "Squash",
        "acorn squash" to "Squash",
        "spaghetti squash" to "Squash",
        "corn" to "Corn",
        // Altri cibi/contenitori riconoscibili
        "french loaf" to "Bread",
        "bagel" to "Bagels",
        "pretzel" to "Pretzels",
        "milk can" to "Milk",
        "eggnog" to "Milk",
        "pizza" to "Pizza",
        "carbonara" to "Pasta",
        "guacamole" to "Avocado",
        // Set demo Droidcon (per modelli custom food-specific)
        "mozzarella" to "Mozzarella",
        "tomato" to "Tomatoes",
        "basil" to "Basil",
        "pasta" to "Pasta",
        "spaghetti" to "Pasta",
        "tuna" to "Canned tuna",
        "onion" to "Onions",
        "parmesan" to "Parmigiano"
    )

    private val quantities = mapOf(
        "Mozzarella" to "1 pack",
        "Milk" to "1 carton",
        "Tomatoes" to "2 pcs",
        "Basil" to "1 bunch",
        "Pasta" to "500 g",
        "Canned tuna" to "2 cans",
        "Onions" to "2 pcs",
        "Parmigiano" to "1 wedge",
        "Bread" to "1 loaf",
        "Bananas" to "3 pcs",
        "Oranges" to "3 pcs",
        "Lemons" to "2 pcs",
        "Bell peppers" to "2 pcs",
        "Cucumbers" to "1 pc",
        "Mushrooms" to "1 pack"
    )

    fun fromModelLabel(label: String): String? {
        val normalized = label.lowercase().replace('_', ' ').trim()
        return labelMap.entries.firstOrNull { normalized.contains(it.key) }?.value
    }

    fun defaultQuantity(name: String): String = quantities[name] ?: "1 pc"
}
