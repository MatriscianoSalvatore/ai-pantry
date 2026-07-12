package com.smatrisciano.aipantry.capture.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.smatrisciano.aipantry.capture.domain.DetectedIngredient
import com.smatrisciano.aipantry.capture.domain.IngredientDetector
import com.smatrisciano.aipantry.capture.domain.ScanTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Riconoscimento ingredienti on-device (MediaPipe/LiteRT), due modelli bundlati:
 *  - EfficientDet-Lite2 (COCO): object detection multi-oggetto sulla scena
 *    intera (banana, apple, orange, broccoli, carrot, bottle, bowl…);
 *  - EfficientNet-Lite2 (ImageNet): classificazione del soggetto dominante
 *    (funghi, peperoni, limoni, zucchine… classi assenti da COCO).
 * I risultati grezzi di entrambi finiscono in logcat (tag "IngredientDetector")
 * per diagnosticare cosa vede davvero il modello prima del mapping.
 */
class MediaPipeIngredientDetector(
    private val context: Context
) : IngredientDetector {

    override val engineName = "EfficientDet + EfficientNet · LiteRT"

    private val objectDetector: ObjectDetector by lazy {
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(DETECTOR_ASSET).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(16)
                .setScoreThreshold(SCORE_THRESHOLD)
                .build()
        )
    }

    private val classifier: ImageClassifier by lazy {
        ImageClassifier.createFromOptions(
            context,
            ImageClassifier.ImageClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(CLASSIFIER_ASSET).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(8)
                .setScoreThreshold(SCORE_THRESHOLD)
                .build()
        )
    }

    override suspend fun detect(
        bitmap: Bitmap,
        target: ScanTarget
    ): List<DetectedIngredient> = withContext(Dispatchers.Default) {
        val image = BitmapImageBuilder(bitmap).build()

        // Object detection: più oggetti nella stessa scena, con bounding box
        val detected = objectDetector.detect(image)
            .detections()
            .mapNotNull { detection ->
                val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                Log.i(TAG, "objectDetector: '${category.categoryName()}' score=${"%.2f".format(category.score())}")
                IngredientLabels.fromModelLabel(category.categoryName())
                    ?.let { it to category.score() }
            }

        // Classificazione: soggetto dominante (classi ImageNet fuori da COCO)
        val classified = classifier.classify(image)
            .classificationResult()
            .classifications()
            .flatMap { it.categories() }
            .mapNotNull { category ->
                Log.i(TAG, "classifier: '${category.categoryName()}' score=${"%.2f".format(category.score())}")
                IngredientLabels.fromModelLabel(category.categoryName())
                    ?.let { it to category.score() }
            }

        (detected + classified)
            .groupBy({ it.first }, { it.second })
            .map { (name, scores) ->
                DetectedIngredient(
                    name = name,
                    quantity = IngredientLabels.defaultQuantity(name),
                    confidence = scores.max()
                )
            }
            .sortedByDescending { it.confidence }
            .also { Log.i(TAG, "mapped ingredients: ${it.map(DetectedIngredient::name)}") }
    }

    private companion object {
        const val TAG = "IngredientDetector"
        const val DETECTOR_ASSET = "models/detector.tflite"
        const val CLASSIFIER_ASSET = "models/ingredients.tflite"
        // Sotto 0.25 COCO produce falsi positivi sistematici
        // (pomodori→"orange", oggetti allungati→"carrot")
        const val SCORE_THRESHOLD = 0.25f
    }
}

/**
 * Mappa le label COCO (object detector) e ImageNet (classificatore) su
 * ingredienti "di dispensa" e assegna una quantità approssimativa di default.
 */
object IngredientLabels {

    private val labelMap = mapOf(
        // Modello fine-tuned su Open Images (31 classi alimentari) + COCO
        "banana" to "Bananas",
        "apple" to "Apples",
        "orange" to "Oranges",
        "broccoli" to "Broccoli",
        "carrot" to "Carrots",
        "hot dog" to "Sausages",
        "pizza" to "Pizza",
        "cake" to "Cake",
        "grapefruit" to "Grapefruit",
        "grape" to "Grapes",
        "cheese" to "Cheese",
        "milk" to "Milk",
        "egg (food)" to "Eggs",
        "egg" to "Eggs",
        "juice" to "Juice",
        "bread" to "Bread",
        "croissant" to "Croissants",
        "cabbage" to "Cabbage",
        "pear" to "Pears",
        "peach" to "Peaches",
        "watermelon" to "Watermelon",
        "potato" to "Potatoes",
        "radish" to "Radishes",
        "cantaloupe" to "Cantaloupe",
        "salad" to "Salad greens",
        // ImageNet (EfficientNet)
        "lemon" to "Lemons",
        "fig" to "Figs",
        "pineapple" to "Pineapple",
        "strawberry" to "Strawberries",
        "pomegranate" to "Pomegranate",
        "granny smith" to "Apples",
        "custard apple" to "Apples",
        "bell pepper" to "Bell peppers",
        "cucumber" to "Cucumbers",
        "cauliflower" to "Cauliflower",
        "zucchini" to "Zucchini",
        "mushroom" to "Mushrooms",
        "artichoke" to "Artichokes",
        "head cabbage" to "Cabbage",
        "butternut squash" to "Squash",
        "acorn squash" to "Squash",
        "spaghetti squash" to "Squash",
        "corn" to "Corn",
        "french loaf" to "Bread",
        "bagel" to "Bagels",
        "pretzel" to "Pretzels",
        "milk can" to "Milk",
        "eggnog" to "Milk",
        "carbonara" to "Pasta",
        "guacamole" to "Avocado",
        // Modelli food-specific custom
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
        "Apples" to "2 pcs",
        "Oranges" to "3 pcs",
        "Lemons" to "2 pcs",
        "Carrots" to "3 pcs",
        "Bell peppers" to "2 pcs",
        "Cucumbers" to "1 pc",
        "Mushrooms" to "1 pack",
        "Cheese" to "1 pack",
        "Eggs" to "6 pcs",
        "Juice" to "1 bottle",
        "Grapes" to "1 bunch",
        "Strawberries" to "1 pack",
        "Potatoes" to "4 pcs",
        "Pears" to "2 pcs",
        "Peaches" to "2 pcs",
        "Croissants" to "2 pcs"
    )

    fun fromModelLabel(label: String): String? {
        val normalized = label.lowercase().replace('_', ' ').trim()
        // Match esatto prima del substring: "grape" è contenuto in "grapefruit"
        labelMap[normalized]?.let { return it }
        return labelMap.entries.firstOrNull { normalized.contains(it.key) }?.value
    }

    fun defaultQuantity(name: String): String = quantities[name] ?: "1 pc"
}
