package ph.bettergov.kuyaj.kb

import kotlin.concurrent.Volatile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The records the app answers from: the built-in set, or the last verified
 * bundle. Swapping is one volatile write, so a search never sees half a bundle.
 */
class KnowledgeStore(private val builtIn: List<KbRecord>) {
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
