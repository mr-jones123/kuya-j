package ph.bettergov.kuyaj.llm

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import ph.bettergov.kuyaj.BuildConfig
import ph.bettergov.kuyaj.app.ModelState
import java.io.File
import java.security.MessageDigest

/** One downloadable build of the model. NPU builds are compiled for a single chip. */
data class ModelVariant(
    val id: String,
    val label: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val backends: List<String>,
    /** Build.SOC_MODEL values this build runs on. Empty means any chip. */
    val socModels: List<String> = emptyList(),
    /** Trained to call search_kb itself (Kuya J v3 and later); stock Gemma lets the app search instead. */
    val toolSearch: Boolean = false,
)

object ModelCatalog {
    private const val BASE = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/"

    // Sizes and hashes from the Hugging Face file listing, 2026-11-16.
    val GENERIC = ModelVariant(
        id = "gemma4-e2b",
        label = "Gemma 4 E2B (GPU/CPU)",
        fileName = "gemma-4-E2B-it.litertlm",
        url = BASE + "gemma-4-E2B-it.litertlm",
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        sizeBytes = 2_588_147_712,
        backends = listOf("GPU", "CPU"),
    )

    // ponytail: SOC_MODEL strings for Tensor chips are unverified; confirm with `adb shell getprop ro.soc.model` on a Pixel and add Qualcomm/MediaTek builds as Google publishes them.
    private val NPU_BUILDS = listOf(
        ModelVariant(
            id = "gemma4-e2b-tensor-g5",
            label = "Gemma 4 E2B (Tensor G5 NPU)",
            fileName = "gemma-4-E2B-it_Google_Tensor_G5.litertlm",
            url = BASE + "gemma-4-E2B-it_Google_Tensor_G5.litertlm",
            sha256 = "72d056066f34362adcecf6b76995de67ad899f6f33dfe9079593c1acd2984191",
            sizeBytes = 3_206_299_658,
            backends = listOf("NPU"),
            socModels = listOf("Tensor G5"),
        ),
        ModelVariant(
            id = "gemma4-e2b-tensor-g6",
            label = "Gemma 4 E2B (Tensor G6 NPU)",
            fileName = "gemma-4-E2B-it_Google_Tensor_G6.litertlm",
            url = BASE + "gemma-4-E2B-it_Google_Tensor_G6.litertlm",
            sha256 = "d6cbe8d625f4965a8c32565f65ae774d19598d0d967a4c2101a7e04988daaf58",
            sizeBytes = 3_453_960_202,
            backends = listOf("NPU"),
            socModels = listOf("Tensor G6"),
        ),
    )

    /** Debug builds only: a model pushed to models/custom.litertlm loads without a checksum, for testing fine-tunes. */
    val CUSTOM = ModelVariant(
        id = "custom", label = "Custom model (debug)", fileName = "custom.litertlm",
        url = "", sha256 = "", sizeBytes = -1, backends = listOf("GPU", "CPU"), toolSearch = true,
    )

    /** The pushed custom model on a debug build, else the NPU build for this phone's chip, else the generic GPU/CPU build. */
    fun forThisDevice(context: Context): ModelVariant =
        if (BuildConfig.DEBUG && File(context.getExternalFilesDir(null), "models/${CUSTOM.fileName}").exists()) CUSTOM
        else forThisDevice()

    fun forThisDevice(): ModelVariant {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
        return NPU_BUILDS.firstOrNull { v -> v.socModels.any { it.equals(soc, ignoreCase = true) } } ?: GENERIC
    }
}

/**
 * Downloads the model over Wi-Fi with the system DownloadManager, then checks
 * its SHA-256 before anything loads it. A file pushed with adb is checked the same way.
 */
class ModelManager(private val context: Context, val variant: ModelVariant = ModelCatalog.forThisDevice(context)) {
    private val _state = MutableStateFlow<ModelState>(ModelState.Missing)
    val state: StateFlow<ModelState> = _state

    private val prefs = context.getSharedPreferences("model", Context.MODE_PRIVATE)
    private val dm = context.getSystemService(DownloadManager::class.java)

    val file: File = File(context.getExternalFilesDir(null), "models/${variant.fileName}")

    /** Re-checks a file already on disk. Hashing 2.5 GB takes a while, so a verified file is remembered by size and time. */
    suspend fun refresh() {
        if (prefs.getLong("downloadId", -1) >= 0) return awaitDownload()
        if (!file.exists()) return run { _state.value = ModelState.Missing }
        val stamp = "${file.length()}:${file.lastModified()}"
        if (prefs.getString("verified:${variant.id}", null) == stamp) return run { _state.value = ModelState.Ready }
        verify()
    }

    fun startDownload() {
        file.parentFile?.mkdirs()
        file.delete()
        val request = DownloadManager.Request(Uri.parse(variant.url))
            .setTitle("Kuya J AI model")
            .setDescription(variant.label)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file))
        prefs.edit().putLong("downloadId", dm.enqueue(request)).apply()
        _state.value = ModelState.Downloading(0f)
    }

    suspend fun awaitDownload() {
        val id = prefs.getLong("downloadId", -1)
        while (id >= 0) {
            val (status, done, total) = dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) Triple(DownloadManager.STATUS_FAILED, 0L, 0L)
                else Triple(
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                )
            }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().remove("downloadId").apply()
                    return verify()
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove("downloadId").apply()
                    _state.value = ModelState.Failed("Download failed. Connect to Wi-Fi and try again.")
                    return
                }
                else -> _state.value = ModelState.Downloading(if (total > 0) done.toFloat() / total else 0f)
            }
            delay(1000)
        }
    }

    fun delete() {
        prefs.getLong("downloadId", -1).takeIf { it >= 0 }?.let { dm.remove(it) }
        prefs.edit().remove("downloadId").remove("verified:${variant.id}").apply()
        file.delete()
        _state.value = ModelState.Missing
    }

    private suspend fun verify() {
        _state.value = ModelState.Verifying
        val ok = withContext(Dispatchers.IO) {
            (BuildConfig.DEBUG && variant == ModelCatalog.CUSTOM) ||
                (file.length() == variant.sizeBytes && sha256(file) == variant.sha256)
        }
        if (ok) {
            prefs.edit().putString("verified:${variant.id}", "${file.length()}:${file.lastModified()}").apply()
            _state.value = ModelState.Ready
        } else {
            file.delete()
            _state.value = ModelState.Failed("The model file did not match its checksum and was deleted.")
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
