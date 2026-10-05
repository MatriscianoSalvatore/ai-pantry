package com.smatrisciano.aipantry.core.data.ai

import android.content.Context
import android.util.Log
import com.google.android.play.core.aipacks.AiPackManager
import com.google.android.play.core.aipacks.AiPackState
import com.google.android.play.core.aipacks.AiPackStateUpdateListener
import com.google.android.play.core.aipacks.model.AiPackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.File

sealed interface ModelStatus {
    data object NotInstalled : ModelStatus
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelStatus
    data object WaitingForWifi : ModelStatus
    data object RequiresConfirmation : ModelStatus
    data object Assembling : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

/**
 * LLM provisioning without in-app HTTP downloads (the app's own manifest
 * doesn't even declare INTERNET). Two routes, decided by the flavor:
 *  - [ModelSource.AiPacks]: Play for On-device AI, ≤1.5 GB chunks delivered
 *    by Google Play (fast-follow) and reassembled into a single .litertlm;
 *  - [ModelSource.BundledAssets]: model embedded in the APK assets (`beta`
 *    flavor, APK handed out manually) and copied into files on first launch.
 */
class ModelRepository(
    private val context: Context,
    private val aiPackManager: AiPackManager,
    private val appScope: CoroutineScope,
    private val engineHolder: LlmEngineHolder
) {

    private val _statuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    val statuses = _statuses.asStateFlow()

    private val packStates = mutableMapOf<String, AiPackState>()
    private val provisionMutex = Mutex()

    private val listener = AiPackStateUpdateListener { state -> onPackStateUpdate(state) }

    init {
        aiPackManager.registerListener(listener)
        appScope.launch(Dispatchers.IO) { refresh() }
    }

    fun modelFile(model: LlmModel): File {
        // Dev convenience: a model sideloaded via adb takes priority (APK builds
        // of the play flavor don't contain the AI packs)
        val sideloaded = File("/data/local/tmp/llm/${model.fileName}")
        if (sideloaded.exists()) return sideloaded
        return File(modelsDir(), model.fileName)
    }

    fun activeModel(): LlmModel = LlmCatalog.default

    /** The active model, only if ready to use. */
    fun readyActiveModel(): LlmModel? =
        activeModel().takeIf { _statuses.value[it.id] == ModelStatus.Ready }

    private suspend fun refresh() {
        cleanupOrphanedFiles()
        LlmCatalog.all.forEach { model ->
            if (isProvisioned(model)) {
                setStatus(model, ModelStatus.Ready)
                return@forEach
            }
            when (val source = model.source) {
                // Zero-touch: the embedded model prepares itself on first launch
                is ModelSource.BundledAssets -> provisionBundled(model, source)
                is ModelSource.AiPacks -> refreshAiPacks(model, source)
            }
        }
    }

    // region AI packs (play flavor)

    private suspend fun refreshAiPacks(model: LlmModel, source: ModelSource.AiPacks) {
        if (allPacksAvailable(model, source)) {
            assemble(model, source)
            return
        }
        // Zero-touch: if the packs aren't on the device yet, request them from Play
        // right away (fast-follow starts by itself after install, but this also covers
        // on-demand and retries if the first attempt failed)
        setStatus(model, ModelStatus.Downloading(0, model.approxSizeBytes))
        runCatching { aiPackManager.fetch(source.packNames).await() }
            .onSuccess { states ->
                states.packStates().values.forEach { packStates[it.name()] = it }
                recomputeAiPackStatus(model, source)
            }
            .onFailure { error ->
                Log.w(TAG, "AI pack fetch failed", error)
                setStatus(
                    model,
                    ModelStatus.Failed(
                        "Google Play couldn't deliver the model: " +
                            "${error.message ?: error.javaClass.simpleName}"
                    )
                )
            }
    }

    private fun onPackStateUpdate(state: AiPackState) {
        packStates[state.name()] = state
        val model = LlmCatalog.all.firstOrNull {
            (it.source as? ModelSource.AiPacks)?.packNames?.contains(state.name()) == true
        } ?: return
        recomputeAiPackStatus(model, model.source as ModelSource.AiPacks)
    }

    private fun recomputeAiPackStatus(model: LlmModel, source: ModelSource.AiPacks) {
        if (isProvisioned(model)) {
            setStatus(model, ModelStatus.Ready)
            return
        }
        if (allPacksAvailable(model, source)) {
            appScope.launch(Dispatchers.IO) { assemble(model, source) }
            return
        }

        val states = source.packNames.mapNotNull { packStates[it] }
        val statuses = states.map { it.status() }
        val current = when {
            AiPackStatus.FAILED in statuses ->
                ModelStatus.Failed(
                    "Google Play download failed (error ${states.first { it.status() == AiPackStatus.FAILED }.errorCode()})."
                )
            AiPackStatus.REQUIRES_USER_CONFIRMATION in statuses -> ModelStatus.RequiresConfirmation
            AiPackStatus.WAITING_FOR_WIFI in statuses -> ModelStatus.WaitingForWifi
            statuses.any { it == AiPackStatus.DOWNLOADING || it == AiPackStatus.PENDING || it == AiPackStatus.TRANSFERRING } -> {
                val downloaded = states.sumOf { it.bytesDownloaded() }
                val total = states.sumOf { it.totalBytesToDownload() }
                    .takeIf { it > 0 } ?: model.approxSizeBytes
                ModelStatus.Downloading(downloaded, total)
            }
            statuses.isNotEmpty() && statuses.all { it == AiPackStatus.COMPLETED } -> ModelStatus.Assembling
            else -> ModelStatus.NotInstalled
        }
        setStatus(model, current)
    }

    /** Reassembles the pack chunks into a single .litertlm usable by LiteRT-LM. */
    private suspend fun assemble(model: LlmModel, source: ModelSource.AiPacks) =
        provisionMutex.withLock {
            if (isProvisioned(model)) {
                setStatus(model, ModelStatus.Ready)
                return@withLock
            }
            setStatus(model, ModelStatus.Assembling)
            val destination = File(modelsDir(), model.fileName)
            val temp = File(modelsDir(), "${model.fileName}.assembling")
            runCatching {
                temp.outputStream().use { output ->
                    source.packNames.forEach { packName ->
                        val chunk = chunkFile(packName, source)
                            ?: error("Chunk missing in AI pack $packName")
                        chunk.inputStream().use { it.copyTo(output, BUFFER_SIZE) }
                    }
                }
                check(temp.renameTo(destination)) { "Cannot move assembled model into place" }
                // The packs aren't needed anymore: this frees twice the space
                source.packNames.forEach { aiPackManager.removePack(it) }
                setStatus(model, ModelStatus.Ready)
            }.onFailure { error ->
                Log.w(TAG, "Model assembly failed", error)
                temp.delete()
                setStatus(
                    model,
                    ModelStatus.Failed("Model preparation failed: ${error.message ?: error.javaClass.simpleName}")
                )
            }
        }

    private fun allPacksAvailable(model: LlmModel, source: ModelSource.AiPacks): Boolean =
        source.packNames.all { chunkFile(it, source) != null }

    private fun chunkFile(packName: String, source: ModelSource.AiPacks): File? {
        val location = aiPackManager.getPackLocation(packName) ?: return null
        val assetsPath = location.assetsPath() ?: return null
        return File(assetsPath, source.chunkAssetName(packName)).takeIf { it.exists() }
    }

    // endregion

    // region Model embedded in the APK (beta flavor)

    private suspend fun provisionBundled(model: LlmModel, source: ModelSource.BundledAssets) =
        provisionMutex.withLock {
            if (isProvisioned(model)) {
                setStatus(model, ModelStatus.Ready)
                return@withLock
            }
            setStatus(model, ModelStatus.Assembling)
            val destination = File(modelsDir(), model.fileName)
            val temp = File(modelsDir(), "${model.fileName}.copying")
            runCatching {
                temp.outputStream().use { output ->
                    source.assetPaths.forEach { assetPath ->
                        context.assets.open(assetPath).use { it.copyTo(output, BUFFER_SIZE) }
                    }
                }
                check(temp.renameTo(destination)) { "Cannot move model into place" }
                setStatus(model, ModelStatus.Ready)
            }.onFailure { error ->
                Log.w(TAG, "Bundled model provisioning failed", error)
                temp.delete()
                setStatus(
                    model,
                    ModelStatus.Failed(
                        "Model not available in this build: ${error.message ?: error.javaClass.simpleName}"
                    )
                )
            }
        }

    // endregion

    private fun isProvisioned(model: LlmModel): Boolean = modelFile(model).exists()

    /** Removes models from previous app versions (e.g. after a catalog change), with their files. */
    private fun cleanupOrphanedFiles() {
        val known = LlmCatalog.all.map { it.fileName }
        modelsDir().listFiles()?.forEach { file ->
            if (!isNeeded(file.name, known)) {
                Log.i(TAG, "Deleting orphaned model file ${file.name} (${file.length()} bytes)")
                file.delete()
            }
        }
    }

    /** The XNNPack caches LiteRT-LM keeps next to [model]'s file (see [isNeeded]). */
    fun cacheFiles(model: LlmModel): List<File> {
        val file = modelFile(model)
        return file.parentFile
            ?.listFiles { other -> isCache(other.name, file.name) }
            ?.toList()
            .orEmpty()
    }

    /**
     * A file of the models folder still in use: a model of the catalog, the copy in
     * progress of one that isn't there yet or, next to one that is, the XNNPack caches
     * LiteRT-LM writes (the weights already laid out for the CPU, which every launch
     * would otherwise build again).
     */
    private fun isNeeded(name: String, known: List<String>): Boolean = known.any { model ->
        val present = File(modelsDir(), model).exists()
        when (name) {
            model -> true
            "$model.assembling", "$model.copying" -> !present
            else -> present && isCache(name, model)
        }
    }

    /**
     * A cache the LiteRT-LM in use writes next to [model]: "<model>_<hash>_<size>.xnnpack_cache".
     * A cache named another way ("<model>.xnnpack_cache_…") is another runtime's: this
     * one doesn't read it, and it goes (almost 1 GB).
     */
    private fun isCache(name: String, model: String): Boolean = name.startsWith("${model}_")

    private fun setStatus(model: LlmModel, status: ModelStatus) {
        _statuses.update { it + (model.id to status) }
        if (status == ModelStatus.Ready) warmUpEngine(model)
    }

    /**
     * Preloads the LiteRT engine in the background as soon as the model is ready:
     * the first scan/generation doesn't pay the ~30-60 s of loading.
     */
    private val warmedUp = mutableSetOf<String>()

    private fun warmUpEngine(model: LlmModel) {
        if (!warmedUp.add(model.id)) return
        appScope.launch(Dispatchers.Default) {
            runCatching { engineHolder.warmUp(model, modelFile(model)) }
                .onFailure {
                    Log.w(TAG, "Engine warm-up failed", it)
                    warmedUp.remove(model.id)
                }
        }
    }

    private fun modelsDir(): File =
        (context.getExternalFilesDir("models") ?: File(context.filesDir, "models"))
            .apply { mkdirs() }

    private companion object {
        const val TAG = "ModelRepository"
        const val BUFFER_SIZE = 1024 * 1024
    }
}
