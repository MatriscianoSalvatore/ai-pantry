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
import kotlinx.coroutines.withContext
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
    private val engineHolder: LlmEngineHolder,
    private val nano: GeminiNanoWriter,
    private val choices: ModelPreferences
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

    /**
     * The Gemma version chosen on the hidden page, if it is ready; otherwise the first one that
     * is, so a version that was removed doesn't leave Gemma out when another is there.
     */
    fun activeModel(): LlmModel {
        fun ready(model: LlmModel) = _statuses.value[model.id] == ModelStatus.Ready
        return LlmCatalog.byId(choices.activeModelId.value).takeIf(::ready)
            ?: LlmCatalog.all.firstOrNull(::ready)
            ?: LlmCatalog.byId(choices.activeModelId.value)
    }

    /**
     * The models copied in by hand may arrive (or go) while the app runs: looks again at the
     * folder. Cheap enough to call every few seconds.
     */
    fun rescanManual() {
        // Not warmed up here: it would swap the engine under an answer being written; it loads when asked
        val before = settling
        settling = true
        try {
            LlmCatalog.all.filter { it.source == ModelSource.Manual }.forEach { model ->
                val there = isProvisioned(model)
                val ready = _statuses.value[model.id] == ModelStatus.Ready
                if (there && !ready) setStatus(model, ModelStatus.Ready)
                if (!there && ready) setStatus(model, ModelStatus.NotInstalled)
            }
        } finally {
            settling = before
        }
    }

    /**
     * Loads the version in use again, for the backend picked by hand to take effect: it is taken out of
     * memory once nothing is being written with it, and warmed up with the new backend.
     */
    suspend fun reloadActive() {
        val model = activeModel()
        engineHolder.clearSessionFailure(model)
        engineHolder.unloadWhenIdle()
        warmedUp.remove(model.id)
        if (_statuses.value[model.id] == ModelStatus.Ready) warmUpEngine(model)
    }

    /** Gemma runs the version [id] from now on: it is loaded when it is next asked. */
    fun selectActive(id: String) {
        choices.setActiveModelId(id)
        syncGemmaReady()
        val model = activeModel()
        if (_statuses.value[model.id] == ModelStatus.Ready) warmUpEngine(model)
    }

    /** Where [model] goes when it is copied in with adb: the folder the app can write caches in. */
    fun installPath(model: LlmModel): String = File(modelsDir(), model.fileName).absolutePath

    private fun syncGemmaReady() {
        choices.gemmaReady = _statuses.value[activeModel().id] == ModelStatus.Ready
    }

    /** The active model, only if ready to use. */
    fun readyActiveModel(): LlmModel? =
        activeModel().takeIf { _statuses.value[it.id] == ModelStatus.Ready }

    // While the models' states are being worked out, nothing is warmed up: which one is in use
    // isn't known until they all are, and two warm-ups at once have each close the other's engine
    @Volatile
    private var settling = false

    private suspend fun refresh() {
        settling = true
        try {
            refreshAll()
        } finally {
            settling = false
        }
        readyActive()?.let { warmUpEngine(it) }
    }

    private fun readyActive(): LlmModel? = activeModel().takeIf { _statuses.value[it.id] == ModelStatus.Ready }

    private suspend fun refreshAll() {
        cleanupOrphanedFiles()
        LlmCatalog.all.forEach { model ->
            // Taken off by hand: it stays off until it is restored
            if (choices.isRemoved(model.id) && !isProvisioned(model)) {
                setStatus(model, ModelStatus.NotInstalled)
                return@forEach
            }
            if (isProvisioned(model)) {
                setStatus(model, ModelStatus.Ready)
                return@forEach
            }
            when (val source = model.source) {
                // Zero-touch: the embedded model prepares itself on first launch
                is ModelSource.BundledAssets -> provisionBundled(model, source)
                is ModelSource.AiPacks -> refreshAiPacks(model, source)
                // Copied in by hand: it is there or it isn't
                ModelSource.Manual -> setStatus(model, ModelStatus.NotInstalled)
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

    /**
     * Takes [model] off the device: out of memory, its file and the caches written next to it.
     * It isn't provisioned again (from the Play packs or the APK) until [restore]. False if a
     * file couldn't be removed (a copy pushed with adb into a folder the app can't write to).
     */
    suspend fun remove(model: LlmModel): Boolean = withContext(Dispatchers.IO) {
        provisionMutex.withLock {
            engineHolder.unload()
            warmedUp.remove(model.id)
            val removed = (listOf(modelFile(model)) + cacheFiles(model)).all { !it.exists() || it.delete() }
            if (removed) choices.setRemoved(model.id, true)
            setStatus(model, if (isProvisioned(model)) ModelStatus.Ready else ModelStatus.NotInstalled)
            removed
        }
    }

    /** Provisions [model] again, after [remove]. */
    suspend fun restore(model: LlmModel) = withContext(Dispatchers.IO) {
        choices.setRemoved(model.id, false)
        refresh()
    }

    private fun setStatus(model: LlmModel, status: ModelStatus) {
        _statuses.update { it + (model.id to status) }
        syncGemmaReady()
        // Only the version in use: the engine holds one model, and warming up two at once has
        // each close the other's engine under it
        if (!settling && status == ModelStatus.Ready && model.id == activeModel().id) warmUpEngine(model)
    }

    /**
     * Preloads the LiteRT engine in the background as soon as the model is ready:
     * the first scan/generation doesn't pay the ~30-60 s of loading. Not where Gemini
     * Nano 4 writes the recipes: Gemma would only take memory, and loads if Nano fails.
     */
    private val warmedUp = mutableSetOf<String>()

    private fun warmUpEngine(model: LlmModel) {
        if (!warmedUp.add(model.id)) return
        appScope.launch(Dispatchers.Default) {
            if (nano.isUsable()) {
                engineHolder.skipWarmUp()
                return@launch
            }
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
