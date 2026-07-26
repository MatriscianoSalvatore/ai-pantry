package com.smatrisciano.aipantry.core.data.ai

import com.smatrisciano.aipantry.BuildConfig

/** Come arriva il modello sul device — mai con download HTTP in-app. */
sealed interface ModelSource {

    /**
     * AI pack di Play for On-device AI (flavor `play`): chunk ≤1.5GB
     * consegnati da Google Play e ricomposti al primo avvio.
     */
    data class AiPacks(val packNames: List<String>) : ModelSource {
        /**
         * Nome del chunk negli assets del pack: deve essere unico tra i pack
         * (bundletool rifiuta entry omonime con contenuto diverso).
         */
        fun chunkAssetName(packName: String): String = "model.part${packNames.indexOf(packName)}"
    }

    /**
     * Modello embeddato negli assets dell'APK (flavor `beta`, APK distribuito
     * a mano): zero setup, ricomposto in files al primo avvio. In chunk perché
     * AGP non impacchetta asset singoli >2GB.
     */
    data class BundledAssets(val assetPaths: List<String>) : ModelSource
}

data class LlmModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val source: ModelSource,
    val approxSizeBytes: Long,
    val supportsVision: Boolean,
    val license: String
)

object LlmCatalog {

    /**
     * Stesso modello per tutti i flavor — cambia solo il canale di consegna,
     * deciso dal flavor `distribution` via BuildConfig.
     * Gemma 4 E2B int4, multimodale: un solo modello per **sia** detection
     * ingredienti (vision modality) **sia** generazione ricette — approccio
     * "solo Gemma", senza detector ausiliari.
     */
    val gemma4E2B = LlmModel(
        id = "gemma-4-e2b",
        displayName = "Gemma 4 E2B",
        fileName = "gemma4-e2b-it.litertlm",
        source = when (BuildConfig.MODEL_SOURCE) {
            "BUNDLED" -> ModelSource.BundledAssets(
                listOf("llm/model.part0.litertlm", "llm/model.part1.litertlm", "llm/model.part2.litertlm")
            )
            else -> ModelSource.AiPacks(
                listOf("llm_pack_0", "llm_pack_1", "llm_pack_2")
            )
        },
        approxSizeBytes = 2_600_000_000,
        supportsVision = true,
        license = "Gemma Terms of Use"
    )

    val all: List<LlmModel> = listOf(gemma4E2B)
    val default: LlmModel = gemma4E2B

    fun byId(id: String?): LlmModel = all.firstOrNull { it.id == id } ?: default
}
