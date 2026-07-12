package com.smatrisciano.aipantry.core.data.ai

data class LlmModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long?,
    val requiresHfToken: Boolean,
    val supportsVision: Boolean,
    val license: String
)

object LlmCatalog {

    val gemma3nE4B = LlmModel(
        id = "gemma-3n-e4b",
        displayName = "Gemma 3n E4B",
        fileName = "gemma-3n-E4B-it-int4.task",
        url = "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int4.task",
        sizeBytes = 4_405_655_031,
        requiresHfToken = true,
        supportsVision = true,
        license = "Gemma Terms of Use"
    )

    val gemma3nE2B = LlmModel(
        id = "gemma-3n-e2b",
        displayName = "Gemma 3n E2B",
        fileName = "gemma-3n-E2B-it-int4.task",
        url = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task",
        sizeBytes = 3_136_226_711,
        requiresHfToken = true,
        supportsVision = true,
        license = "Gemma Terms of Use"
    )

    val qwen25 = LlmModel(
        id = "qwen2.5-1.5b",
        displayName = "Qwen2.5 1.5B",
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
        url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
        sizeBytes = 1_598_556_720,
        requiresHfToken = false,
        supportsVision = false,
        license = "Apache 2.0"
    )

    val all = listOf(gemma3nE4B, gemma3nE2B, qwen25)
    val default = gemma3nE4B

    fun byId(id: String?): LlmModel = all.firstOrNull { it.id == id } ?: default
}
