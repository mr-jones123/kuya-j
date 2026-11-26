package ph.bettergov.kuyaj

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ph.bettergov.kuyaj.app.Freshness
import ph.bettergov.kuyaj.app.KbStatus
import ph.bettergov.kuyaj.app.KuyaServices
import ph.bettergov.kuyaj.app.ModelInfo
import ph.bettergov.kuyaj.app.ModelState
import ph.bettergov.kuyaj.chat.Orchestrator
import ph.bettergov.kuyaj.kb.KbRecord
import ph.bettergov.kuyaj.kb.KnowledgeStore
import ph.bettergov.kuyaj.llm.LiteRtLmEngine
import ph.bettergov.kuyaj.llm.ModelManager
import ph.bettergov.kuyaj.sync.BundleSync
import ph.bettergov.kuyaj.sync.SyncWorker
import java.text.DateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class KuyaJApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncWorker.schedule(this)
    }
}

/** Manual wiring, one instance per process. Implements the platform half of the shared UI. */
class AppContainer(private val context: Context) : KuyaServices {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Records load in the background: parsing a few MB of JSON and indexing thousands of records on the main thread
    // froze the app at startup (Android killed it as not responding). Questions wait for them (KnowledgeStore.awaitReady).
    val store = KnowledgeStore()
    val bundleSync = BundleSync(context, store, builtInVersion(context))
    val models = ModelManager(context)
    val engine = LiteRtLmEngine(context)

    private val _kbStatus = MutableStateFlow(currentStatus())
    override val kbStatus: StateFlow<KbStatus> = _kbStatus

    override val modelState: StateFlow<ModelState> get() = models.state

    private val _engineError = MutableStateFlow<String?>(null)
    override val engineError: StateFlow<String?> = _engineError

    override val modelInfo: ModelInfo
        get() = ModelInfo(models.variant.label, models.variant.sizeBytes, engine.activeBackend, engine.isReady)

    override val orchestrator = Orchestrator(
        store,
        llm = { engine },
        stalenessNote = ::stalenessNote,
        clock = { ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH)) },
        today = { java.time.LocalDate.now().toString() },
    )

    init {
        scope.launch {
            val t0 = System.currentTimeMillis()
            val records = loadBuiltIn(context)
            val t1 = System.currentTimeMillis()
            store.loadBuiltIn(records)
            val t2 = System.currentTimeMillis()
            bundleSync.loadInstalled()
            store.markReady()
            refreshSyncStatus()
            android.util.Log.i("KuyaJ.Records", "${records.size} records: parse ${t1 - t0} ms, index ${t2 - t1} ms, bundle ${System.currentTimeMillis() - t2} ms")
        }
        scope.launch {
            models.refresh()
            loadModelIfReady()
        }
    }

    override fun startModelDownload() {
        models.startDownload()
        prepareModel()
    }

    override fun deleteModel() {
        engine.close()
        models.delete()
    }

    override fun syncNow() = SyncWorker.syncNow(context)

    private var preparing: Job? = null

    @Synchronized
    override fun prepareModel() {
        if (preparing?.isActive == true) return
        preparing = scope.launch {
            if (models.state.value is ModelState.Downloading) models.awaitDownload()
            loadModelIfReady()
        }
    }

    suspend fun loadModelIfReady() {
        if (models.state.value != ModelState.Ready || engine.isReady) return
        runCatching { engine.load(models.file, models.variant) }
            .onSuccess { _engineError.value = null }
            .onFailure { _engineError.value = it.message }
    }

    fun refreshSyncStatus() {
        _kbStatus.value = currentStatus()
    }

    private fun currentStatus(): KbStatus {
        val s = store.state
        val last = bundleSync.lastSyncAt
        val age = last?.let { System.currentTimeMillis() - it }
        return KbStatus(
            fromBundle = s.source == KnowledgeStore.Source.BUNDLE,
            version = s.version,
            recordCount = s.records.size,
            lastSync = last?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
            freshness = when {
                age == null -> Freshness.NEVER
                age < TimeUnit.HOURS.toMillis(24) -> Freshness.FRESH
                age < TimeUnit.DAYS.toMillis(7) -> Freshness.STALE
                else -> Freshness.VERY_STALE
            },
            syncConfigured = AppConfig.syncBaseUrl != null && AppConfig.pinnedKeys.isNotEmpty(),
        )
    }

    // Only a sync server makes freshness meaningful; built-in records carry their own "as of" dates.
    private fun stalenessNote(): String? {
        val status = currentStatus()
        if (!status.syncConfigured) return null
        return when (status.freshness) {
            Freshness.FRESH -> null
            Freshness.STALE -> "Note: my records were last updated more than a day ago."
            Freshness.VERY_STALE -> "Note: my records are more than a week old. Connect to Wi-Fi to update them."
            Freshness.NEVER -> "Note: I haven't downloaded updated records yet. These are the records built into the app."
        }
    }

    /** The records' publish version, written by tools/records/publish.py next to them. */
    private fun builtInVersion(context: Context): String? =
        runCatching { context.assets.open("kb/VERSION").bufferedReader().use { it.readText().trim() } }.getOrNull()?.ifBlank { null }

    private fun loadBuiltIn(context: Context): List<KbRecord> =
        context.assets.list("kb").orEmpty().filter { it.endsWith(".json") }.sorted().flatMap { name ->
            KbRecord.parseArray(context.assets.open("kb/$name").bufferedReader().use { it.readText() })
        }
}
