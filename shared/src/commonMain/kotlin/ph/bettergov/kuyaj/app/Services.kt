package ph.bettergov.kuyaj.app

import kotlinx.coroutines.flow.StateFlow
import ph.bettergov.kuyaj.chat.Orchestrator

/** What the settings screen shows about the knowledge base. */
data class KbStatus(
    val fromBundle: Boolean,
    val version: String?,
    val recordCount: Int,
    /** Already formatted for this phone's locale, or null if never synced. */
    val lastSync: String?,
    val freshness: Freshness,
    val syncConfigured: Boolean,
)

enum class Freshness { FRESH, STALE, VERY_STALE, NEVER }

/** The on-device model's download state. */
sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val fraction: Float) : ModelState
    data object Verifying : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

data class ModelInfo(val label: String, val sizeBytes: Long, val backend: String?, val running: Boolean)

/**
 * Everything the shared UI needs from the platform. Android implements it in AppContainer
 * (LiteRT-LM model, DownloadManager, WorkManager sync); an iOS app would implement it the same way.
 */
interface KuyaServices {
    val orchestrator: Orchestrator
    val kbStatus: StateFlow<KbStatus>
    val modelState: StateFlow<ModelState>
    val engineError: StateFlow<String?>
    val modelInfo: ModelInfo

    fun startModelDownload()
    fun deleteModel()
    fun syncNow()

    /**
     * In the background, waits for a running download and its checksum, then starts the model. Returns at once:
     * the work belongs to the app, not to the screen that asked, so closing Settings doesn't cancel it.
     */
    fun prepareModel()
}
