package com.smatrisciano.aipantry.core.data.ai

import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
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
 * Provisioning del modello LLM senza download HTTP in-app (l'app non ha
 * nemmeno il permesso INTERNET). Due strade, decise dal flavor:
 *  - [ModelSource.AiPacks]: Play for On-device AI — chunk ≤1.5GB consegnati
 *    da Google Play (fast-follow) e ricomposti in un singolo .task;
 *  - [ModelSource.BundledAsset]: modello embeddato negli assets dell'APK
 *    (Firebase App Distribution) e copiato in files al primo avvio.
 */
class ModelRepository(
    private val context: Context,
    private val aiPackManager: AiPackManager,
    private val appScope: CoroutineScope
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
        // Convenienza dev: modello sideloadato via adb ha priorità (le build via
        // APK del flavor play non contengono gli AI pack)
        val sideloaded = File("/data/local/tmp/llm/${model.fileName}")
        if (sideloaded.exists()) return sideloaded
        return File(modelsDir(), model.fileName)
    }

    fun activeModel(): LlmModel = LlmCatalog.default

    /** Il modello attivo, solo se pronto all'uso. */
    fun readyActiveModel(): LlmModel? =
        activeModel().takeIf { _statuses.value[it.id] == ModelStatus.Ready }

    /** Avvia (o ritenta) il provisioning del modello. */
    fun startDownload(model: LlmModel) {
        when (val source = model.source) {
            is ModelSource.AiPacks -> {
                setStatus(model, ModelStatus.Downloading(0, model.approxSizeBytes))
                appScope.launch(Dispatchers.IO) {
                    runCatching { aiPackManager.fetch(source.packNames).await() }
                        .onFailure { error ->
                            Log.w(TAG, "AI pack fetch failed", error)
                            setStatus(
                                model,
                                ModelStatus.Failed(
                                    "Google Play couldn't start the download: " +
                                        "${error.message ?: error.javaClass.simpleName}. " +
                                        "Make sure the app is installed from Play (or bundletool)."
                                )
                            )
                        }
                }
            }
            is ModelSource.BundledAssets ->
                appScope.launch(Dispatchers.IO) { provisionBundled(model, source) }
        }
    }

    fun cancelDownload(model: LlmModel) {
        (model.source as? ModelSource.AiPacks)?.let { aiPackManager.cancel(it.packNames) }
        setStatus(model, ModelStatus.NotInstalled)
    }

    fun deleteModel(model: LlmModel) {
        File(modelsDir(), model.fileName).delete()
        (model.source as? ModelSource.AiPacks)?.packNames?.forEach { aiPackManager.removePack(it) }
        setStatus(model, ModelStatus.NotInstalled)
    }

    /** Dialog di Play per confermare download su rete mobile / senza Wi-Fi. */
    fun showConfirmationDialog(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        aiPackManager.showConfirmationDialog(launcher)
    }

    private suspend fun refresh() {
        LlmCatalog.all.forEach { model ->
            if (isProvisioned(model)) {
                setStatus(model, ModelStatus.Ready)
                return@forEach
            }
            when (val source = model.source) {
                // Zero-touch: il modello embeddato si prepara da solo al primo avvio
                is ModelSource.BundledAssets -> provisionBundled(model, source)
                is ModelSource.AiPacks -> refreshAiPacks(model, source)
            }
        }
    }

    // region AI pack (flavor play)

    private suspend fun refreshAiPacks(model: LlmModel, source: ModelSource.AiPacks) {
        if (allPacksAvailable(model, source)) {
            assemble(model, source)
            return
        }
        runCatching { aiPackManager.getPackStates(source.packNames).await() }
            .onSuccess { states ->
                states.packStates().values.forEach { packStates[it.name()] = it }
                recomputeAiPackStatus(model, source)
            }
            .onFailure {
                Log.w(TAG, "getPackStates failed", it)
                setStatus(model, ModelStatus.NotInstalled)
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

    /** Ricompone i chunk dei pack in un unico .task utilizzabile da LiteRT. */
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
                // I pack non servono più: si libera il doppio dello spazio
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

    // region Modello embeddato nell'APK (flavor firebase)

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

    private fun setStatus(model: LlmModel, status: ModelStatus) {
        _statuses.update { it + (model.id to status) }
    }

    private fun modelsDir(): File =
        (context.getExternalFilesDir("models") ?: File(context.filesDir, "models"))
            .apply { mkdirs() }

    private companion object {
        const val TAG = "ModelRepository"
        const val BUFFER_SIZE = 1024 * 1024
    }
}
