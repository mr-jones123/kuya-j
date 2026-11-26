package ph.bettergov.kuyaj.kb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** One knowledge record. Same shape as an Airgap bundle document. */
data class KbRecord(
    val id: String,
    val category: String,
    val title: String,
    val content: String,
    val keywords: List<String>,
    val tags: List<String> = emptyList(),
    val metadata: Map<String, Any?> = emptyMap(),
) {
    val source: String? get() = metadata["source"] as? String
    val publisher: String? get() = metadata["publisher"] as? String
    val asOf: String? get() = metadata["asOf"] as? String
    val verbatim: Boolean get() = metadata["verbatim"] == true

    companion object {
        /** Throws on any record that misses a required field, like Airgap's verifier. */
        fun parseArray(json: String): List<KbRecord> = Json.parseToJsonElement(json).jsonArray.map { parse(it.jsonObject) }

        private fun parse(o: JsonObject): KbRecord = KbRecord(
            id = o.requireString("id"),
            category = o.requireString("category"),
            title = o.requireString("title"),
            content = o.requireString("content"),
            keywords = (o["keywords"] as? JsonArray ?: throw IllegalArgumentException("keywords is not a list")).map {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("keyword is not a string")
            },
            tags = (o["tags"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: "" } ?: emptyList(),
            metadata = (o["metadata"] as? JsonObject)?.mapValues { (_, v) -> plain(v) } ?: emptyMap(),
        )

        /** JSON values as Kotlin values: strings, booleans and numbers; anything nested stays a JsonElement. */
        private fun plain(v: JsonElement): Any? = when {
            v is JsonNull -> null
            v is JsonPrimitive && v.isString -> v.content
            v is JsonPrimitive -> v.booleanOrNull ?: v.longOrNull ?: v.doubleOrNull
            else -> v
        }

        private fun JsonObject.requireString(key: String): String =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$key is not a string")
    }
}
