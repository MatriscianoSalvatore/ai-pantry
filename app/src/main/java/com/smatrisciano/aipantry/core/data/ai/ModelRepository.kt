package com.smatrisciano.aipantry.core.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

sealed interface ModelStatus {
    data class NotDownloaded(val resumableBytes: Long = 0L) : ModelStatus
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : ModelStatus
    data class Failed(val message: String, val resumableBytes: Long = 0L) : ModelStatus
    data object Ready : ModelStatus
}

/**
 * Provisioning dei modelli LLM: download in-app con resume, nessun setup manuale
 * richiesto all'utente. I modelli finiscono nella sandbox dell'app
 * (external files dir, esente da permessi storage).
 */
class ModelRepository(
    private val context: Context,
    private val settings: AiSettings,
    private val appScope: CoroutineScope
) {

    private val _statuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    val statuses = _statuses.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    init {
        refreshStatuses()
    }

    fun modelFile(model: LlmModel): File {
        // Convenienza dev: modello sideloadato via adb ha priorità
        val sideloaded = File("/data/local/tmp/llm/${model.fileName}")
        if (sideloaded.exists()) return sideloaded
        return File(modelsDir(), model.fileName)
    }

    fun activeModel(): LlmModel = LlmCatalog.byId(settings.activeModelId.value)

    /** Il modello attivo, solo se pronto all'uso. */
    fun readyActiveModel(): LlmModel? =
        activeModel().takeIf { _statuses.value[it.id] == ModelStatus.Ready }

    fun setActiveModel(model: LlmModel) = settings.setActiveModel(model.id)

    fun startDownload(model: LlmModel) {
        if (jobs[model.id]?.isActive == true) return
        if (model.requiresHfToken && settings.hfToken.value.isBlank()) {
            setStatus(
                model,
                ModelStatus.Failed(
                    "A Hugging Face token is required: accept the Gemma license on huggingface.co, then paste a read token above.",
                    resumableBytes = partFile(model).length()
                )
            )
            return
        }
        jobs[model.id] = appScope.launch(Dispatchers.IO) { download(model) }
    }

    fun cancelDownload(model: LlmModel) {
        jobs.remove(model.id)?.cancel()
        setStatus(model, ModelStatus.NotDownloaded(resumableBytes = partFile(model).length()))
    }

    fun deleteModel(model: LlmModel) {
        jobs.remove(model.id)?.cancel()
        File(modelsDir(), model.fileName).delete()
        partFile(model).delete()
        refreshStatuses()
    }

    private fun refreshStatuses() {
        _statuses.value = LlmCatalog.all.associate { model ->
            model.id to when {
                isDownloadedAndComplete(model) -> ModelStatus.Ready
                else -> ModelStatus.NotDownloaded(resumableBytes = partFile(model).length())
            }
        }
    }

    private fun isDownloadedAndComplete(model: LlmModel): Boolean {
        val file = modelFile(model)
        if (!file.exists()) return false
        val expected = model.sizeBytes ?: return true
        return file.length() == expected
    }

    private suspend fun download(model: LlmModel) {
        val part = partFile(model)
        try {
            part.parentFile?.mkdirs()
            var offset = part.length()
            var connection = openFollowingRedirects(model, offset)

            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    // Il server non supporta il resume: si riparte da zero
                    offset = 0
                    RandomAccessFile(part, "rw").use { it.setLength(0) }
                }
                HttpURLConnection.HTTP_PARTIAL -> Unit
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> {
                    connection.disconnect()
                    setStatus(
                        model,
                        ModelStatus.Failed(
                            "Access denied (${connection.responseCode}): check your Hugging Face token and make sure you accepted the model license on huggingface.co.",
                            resumableBytes = part.length()
                        )
                    )
                    return
                }
                else -> {
                    val code = connection.responseCode
                    connection.disconnect()
                    setStatus(
                        model,
                        ModelStatus.Failed("Download failed (HTTP $code)", resumableBytes = part.length())
                    )
                    return
                }
            }

            val total = model.sizeBytes
                ?: connection.contentLengthLong.takeIf { it > 0 }?.plus(offset)
            var downloaded = offset
            setStatus(model, ModelStatus.Downloading(downloaded, total))

            connection.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { output ->
                    output.seek(offset)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastUpdate = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 300) {
                            lastUpdate = now
                            setStatus(model, ModelStatus.Downloading(downloaded, total))
                        }
                    }
                }
            }
            connection.disconnect()

            if (model.sizeBytes != null && downloaded != model.sizeBytes) {
                setStatus(
                    model,
                    ModelStatus.Failed(
                        "Incomplete download (${downloaded / MB} of ${model.sizeBytes / MB} MB) — tap Resume to continue.",
                        resumableBytes = downloaded
                    )
                )
                return
            }

            val destination = File(modelsDir(), model.fileName)
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
                part.delete()
            }
            setStatus(model, ModelStatus.Ready)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for ${model.id}", e)
            setStatus(
                model,
                ModelStatus.Failed(
                    "Download error: ${e.message ?: e.javaClass.simpleName} — tap Resume to retry.",
                    resumableBytes = part.length()
                )
            )
        }
    }

    /**
     * Segue i redirect manualmente: l'header Authorization va inviato solo a
     * huggingface.co, mai al CDN firmato (che altrimenti rifiuta la richiesta).
     */
    private fun openFollowingRedirects(model: LlmModel, offset: Long): HttpURLConnection {
        var url = URL(model.url)
        val token = settings.hfToken.value
        repeat(MAX_REDIRECTS) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                if (model.requiresHfToken && token.isNotBlank() && url.host.endsWith("huggingface.co")) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirect without Location header")
                connection.disconnect()
                url = URL(url, location)
            } else {
                return connection
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun setStatus(model: LlmModel, status: ModelStatus) {
        _statuses.update { it + (model.id to status) }
    }

    private fun modelsDir(): File =
        (context.getExternalFilesDir("models") ?: File(context.filesDir, "models"))
            .apply { mkdirs() }

    private fun partFile(model: LlmModel): File = File(modelsDir(), "${model.fileName}.part")

    private companion object {
        const val TAG = "ModelRepository"
        const val BUFFER_SIZE = 256 * 1024
        const val MAX_REDIRECTS = 6
        const val MB = 1024L * 1024L
    }
}
