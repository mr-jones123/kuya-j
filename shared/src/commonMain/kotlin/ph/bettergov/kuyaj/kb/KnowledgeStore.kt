package ph.bettergov.kuyaj.kb

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The records the app answers from: the built-in set, or the last verified
 * bundle. Swapping is one volatile write, so a search never sees half a bundle.
 */
class KnowledgeStore(private var builtIn: List<KbRecord> = emptyList()) {
    data class State(
        val records: List<KbRecord>,
        val index: SearchIndex,
        val source: Source,
        val version: String?,
    )

    enum class Source { BUILT_IN, BUNDLE }

    @Volatile
    var state: State = State(builtIn, SearchIndex(builtIn), Source.BUILT_IN, null)
        private set

    // Ready at once when records are given up front (tests); the app loads them in the background and calls markReady().
    private val ready = CompletableDeferred<Unit>().apply { if (builtIn.isNotEmpty()) complete(Unit) }

    /** Replaces the built-in records: the app parses them off the main thread at startup (thousands of records). */
    fun loadBuiltIn(records: List<KbRecord>) {
        builtIn = records
        state = State(records, SearchIndex(records), Source.BUILT_IN, null)
    }

    fun markReady() {
        ready.complete(Unit)
    }

    /** Waits until the records are loaded, so a question asked in the first second isn't answered from nothing. */
    suspend fun awaitReady() = ready.await()

    fun search(query: String, limit: Int = 3): List<SearchIndex.Hit> = state.index.search(query, limit)

    /** Throws on a malformed bundle and keeps the current records. */
    fun replaceFromBundle(bundleText: String, version: String) {
        val records = parseBundle(bundleText)
        state = State(records, SearchIndex(records), Source.BUNDLE, version)
    }

    fun revertToBuiltIn() {
        state = State(builtIn, SearchIndex(builtIn), Source.BUILT_IN, null)
    }

    companion object {
        /** `{ files: { "x.json": "<JSON array of records>" }, generatedAt }` */
        fun parseBundle(text: String): List<KbRecord> {
            val files = Json.parseToJsonElement(text).jsonObject["files"] as? JsonObject
                ?: throw IllegalArgumentException("bundle has no files")
            val records = files.flatMap { (name, payload) ->
                require(name.endsWith(".json")) { "bundle file $name is not .json" }
                val text = (payload as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("bundle file $name is not a string")
                KbRecord.parseArray(text)
            }
            require(records.isNotEmpty()) { "bundle contained zero records" }
            return records
        }
    }
}
