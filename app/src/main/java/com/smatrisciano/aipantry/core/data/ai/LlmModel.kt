package com.smatrisciano.aipantry.core.data.ai

import com.smatrisciano.aipantry.BuildConfig

/** How the model reaches the device: never with an in-app HTTP download. */
sealed interface ModelSource {

    /**
     * Play for On-device AI packs (`play` flavor): ≤1.5 GB chunks delivered
     * by Google Play and reassembled on first launch.
     */
    data class AiPacks(val packNames: List<String>) : ModelSource {
        /**
         * Chunk name in the pack assets: it must be unique across packs
         * (bundletool rejects same-name entries with different content).
         */
        fun chunkAssetName(packName: String): String = "model.part${packNames.indexOf(packName)}"
    }

    /**
     * Model embedded in the APK assets (`beta` flavor, APK handed out
     * manually): zero setup, reassembled into files on first launch. Chunked
     * because AGP doesn't package single assets >2 GB.
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
     * Same model for every flavor: only the delivery channel changes, decided
     * by the `distribution` flavor via BuildConfig.
     * Gemma 4 E2B, multimodal: it generates the recipes and can also recognise
     * ingredients (vision modality), although detection uses Gemini Nano or
     * MobileCLIP by default (see CaptureModule).
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
