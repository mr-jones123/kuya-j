package ph.bettergov.kuyaj.llm

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Gemma 4 E2B through LiteRT-LM. Tries the accelerators the model file was
 * built for, in order (NPU, then GPU, then CPU), and keeps the first that loads.
 */
class LiteRtLmEngine(private val context: Context) : LlmEngine {
    private var engine: Engine? = null
    private val lock = Mutex()

    var activeBackend: String? = null
        private set
    private var searches = false

    /**
     * Gemma 4's template from the .litertlm, patched: LiteRT-LM hands tool results to the template as
     * tool_response parts, which the stock template drops, so the model saw an empty search result.
     */
    private val toolTemplate by lazy { context.assets.open("gemma4_tool_template.jinja").bufferedReader().readText() }

    override val isReady: Boolean get() = engine != null

    /** Loads [model] with the first backend that works. Throws if none does. */
    suspend fun load(model: File, variant: ModelVariant) = withContext(Dispatchers.Default) {
        lock.withLock {
            close()
            var lastError: Throwable? = null
            // An emulator's "GPU" is emulated graphics: far slower than its CPU. Real phones keep NPU -> GPU -> CPU.
            val order = if (isEmulator()) listOf("CPU") else variant.backends
            for (name in order) {
                val backend = when (name) {
                    "NPU" -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
                    "GPU" -> Backend.GPU()
                    else -> Backend.CPU()
                }
                try {
                    val e = Engine(
                        EngineConfig(
                            modelPath = model.absolutePath,
                            backend = backend,
                            visionBackend = if (name == "CPU") Backend.CPU() else Backend.GPU(),
                            maxNumTokens = 4096,
                            maxNumImages = 1,
                            cacheDir = context.cacheDir.absolutePath,
                        ),
                    )
                    e.initialize()
                    engine = e
                    activeBackend = name
                    searches = variant.toolSearch
                    return@withContext
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            throw IllegalStateException("No backend could load the model", lastError)
        }
    }

    private fun isEmulator() = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" ||
        Build.FINGERPRINT.startsWith("generic") || Build.PRODUCT.startsWith("sdk")

    fun close() {
        engine?.close()
        engine = null
        activeBackend = null
    }

    override suspend fun generate(system: String, user: String, image: ByteArray?, onText: (String) -> Unit): String =
        run(system, user, image, emptyList(), onText)

    override suspend fun generateWithSearch(system: String, user: String, search: (String) -> String, onText: (String) -> Unit): String? {
        if (!searches) return null
        val searchKb = object : OpenApiTool {
            override fun getToolDescriptionJsonString() = SEARCH_KB
            override fun execute(paramsJsonString: String): String {
                val query = runCatching { JSONObject(paramsJsonString).optString("query") }.getOrDefault("")
                return search(query).also { Log.i(TAG, "search_kb(\"$query\"): ${it.length} chars") }
            }
        }
        return run(system, user, null, listOf(tool(searchKb)), onText, toolTemplate)
    }

    private suspend fun run(
        system: String, user: String, image: ByteArray?, tools: List<ToolProvider>, onText: (String) -> Unit, template: String? = null,
    ): String =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val e = engine ?: throw IllegalStateException("model not loaded")
                val t0 = System.currentTimeMillis()
                Log.i(TAG, "generate: ${user.length} chars on $activeBackend")
                coroutineScope {
                    e.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(system),
                            tools = tools,
                            automaticToolCalling = true, // the library runs search_kb and feeds the records back
                            chatTemplate = template,
                            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2, seed = 0),
                        ),
                    ).use { conversation ->
                        Log.i(TAG, "conversation ready +${System.currentTimeMillis() - t0}ms")
                        val parts = buildList {
                            if (image != null) add(Content.ImageBytes(image))
                            add(Content.Text(user))
                        }
                        val reply = StringBuilder()
                        var chunks = 0 // the stream delivers about one token per chunk
                        var firstAt = 0L
                        // withTimeout can't interrupt a blocking native call; cancelProcess() from another thread can.
                        var timedOut = false
                        val watchdog = launch {
                            delay(GENERATE_TIMEOUT_MS)
                            timedOut = true
                            Log.w(TAG, "no reply after ${GENERATE_TIMEOUT_MS}ms, cancelling")
                            conversation.cancelProcess()
                        }
                        try {
                        conversation.sendMessageAsync(Contents.of(parts)).collect { message ->
                            val chunk = message.text()
                            // Handle both delta and cumulative streams.
                            val delta = if (chunk.startsWith(reply)) chunk.substring(reply.length) else chunk
                            if (delta.isNotEmpty()) {
                                if (reply.isEmpty()) {
                                    firstAt = System.currentTimeMillis()
                                    Log.i(TAG, "first token +${firstAt - t0}ms")
                                }
                                chunks++
                                reply.append(delta)
                                onText(delta)
                            }
                        }
                        } finally {
                            watchdog.cancel()
                        }
                        if (timedOut) throw IllegalStateException("generation timed out")
                        val now = System.currentTimeMillis()
                        val rate = if (chunks > 1 && now > firstAt) (chunks - 1) * 1000.0 / (now - firstAt) else 0.0
                        Log.i(TAG, "done +${now - t0}ms, ${reply.length} chars, $chunks chunks, ${"%.1f".format(rate)} tok/s")
                        reply.toString().trim()
                    }
                }
            }
        }

    private companion object {
        const val TAG = "KuyaJ.Llm"
        const val GENERATE_TIMEOUT_MS = 55_000L // a little under Orchestrator's deadline

        // Same declaration the model was trained with (kuya-j-evals distill/restructure_v3.py TOOLS).
        val SEARCH_KB = JSONObject()
            .put("name", "search_kb")
            .put("description", "Search Kuya J's saved government-service records (written in English).")
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("query", JSONObject()
                    .put("type", "string").put("description", "English search keywords, e.g. 'NBI clearance fee'")))
                .put("required", org.json.JSONArray().put("query")))
            .toString()
    }

    private fun Message.text(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
}
