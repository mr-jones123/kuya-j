package ph.bettergov.kuyaj

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import ph.bettergov.kuyaj.chat.Orchestrator
import ph.bettergov.kuyaj.chat.Reply
import ph.bettergov.kuyaj.kb.KbRecord
import ph.bettergov.kuyaj.kb.KnowledgeStore
import ph.bettergov.kuyaj.llm.LlmEngine
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.Normalizer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Scores scripted multi-turn conversations against the app's real answering code and a real model.
 * Skipped unless KUYAJ_EVAL_URL points at an OpenAI-compatible server running the model (see docs/development.md):
 *
 *     llama-server -m kuyaj-v3-Q4_K_M.gguf --port 8091 --jinja -c 8192 -np 4
 *     KUYAJ_EVAL_URL=http://127.0.0.1:8091/v1 ./gradlew :androidApp:testDebugUnitTest --tests '*ConversationEval*'
 *
 * Conversations: src/test/resources/conversations.jsonl, or the file in KUYAJ_EVAL_SET. One JSON object per line:
 * {"id", "tags": [..], "turns": [{"user", checks...}]}. A turn passes when every check it lists passes:
 *   any / all / none   substrings of the reply (lowercase, accents and commas removed)
 *   kind               allowed reply kinds (MODEL, RECORD, SYSTEM, REFUSAL)
 *   source             a substring of the id of one of the reply's sources
 *   no_digits          the reply states no number at all (nothing invented where there is no record)
 *   chat               a real conversational reply: not the fallback, not "no record", no amount, no source line
 * Results go to build/eval/<set name>.json and .txt (the score is passed turns / all turns).
 */
class ConversationEval {
    private val records = File("src/main/assets/kb").listFiles()!!.filter { it.name.endsWith(".json") }
        .flatMap { KbRecord.parseArray(it.readText()) }

    /** The model behind an OpenAI-style server, driven the way LiteRtLmEngine drives it on the phone. */
    private class ServerModel(private val base: String) : LlmEngine {
        private val http = HttpClient.newHttpClient()
        override val isReady = true

        private fun call(messages: JSONArray, tools: Boolean): JSONObject {
            val body = JSONObject().put("model", "kuyaj").put("messages", messages).put("temperature", 0.2).put("seed", 0)
                .put("top_k", 40).put("top_p", 0.95).put("max_tokens", 400)
                .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            if (tools) body.put("tools", JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject(SEARCH_KB))))
            val req = HttpRequest.newBuilder(URI("$base/chat/completions")).header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(120)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            check(res.statusCode() == 200) { "model server: ${res.statusCode()} ${res.body().take(300)}" }
            return JSONObject(res.body()).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        }

        private fun msg(role: String, content: String) = JSONObject().put("role", role).put("content", content)

        // Word by word, as the phone streams, so the grounding gate sees the same thing.
        private fun stream(text: String, onText: (String) -> Unit): String {
            Regex("""\S+\s*""").findAll(text).forEach { onText(it.value) }
            return text.trim()
        }

        override suspend fun generate(system: String, user: String, image: ByteArray?, onText: (String) -> Unit): String =
            stream(call(JSONArray().put(msg("system", system)).put(msg("user", user)), false).optString("content"), onText)

        override suspend fun generateWithSearch(system: String, user: String, search: (String) -> String, onText: (String) -> Unit): String {
            val messages = JSONArray().put(msg("system", system)).put(msg("user", user))
            repeat(2) {
                val m = call(messages, true)
                val tc = m.optJSONArray("tool_calls")?.optJSONObject(0) ?: return stream(m.optString("content"), onText)
                val query = runCatching { JSONObject(tc.getJSONObject("function").getString("arguments")).optString("query") }.getOrDefault("")
                messages.put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(tc)))
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", tc.optString("id", "0")).put("content", search(query)))
            }
            return stream(call(messages, false).optString("content"), onText)
        }

        companion object {
            // Same declaration as LiteRtLmEngine.SEARCH_KB.
            const val SEARCH_KB = """{"name":"search_kb","description":"Search Kuya J's saved government-service records (written in English).",
                "parameters":{"type":"object","properties":{"query":{"type":"string","description":"English search keywords, e.g. 'NBI clearance fee'"}},"required":["query"]}}"""
        }
    }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .replace(Regex("[,*]"), "").replace(Regex("\\s+"), " ")

    private fun strings(o: JSONObject, key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { norm(a.getString(it)) } }.orEmpty()

    private val noRecord = Regex("no (saved )?record|don.?t have (a |any )?(saved )?(record|information)|wala(ng| akong| po akong| ako)? ?(na ?)?(record|rekord|tala|nakitang|impormasyon)")

    /** The checks of [turn] that [reply] fails, empty when it passes. */
    private fun failures(turn: JSONObject, reply: Reply): List<String> = buildList {
        val text = norm(reply.text)
        strings(turn, "any").takeIf { it.isNotEmpty() }?.let { if (it.none(text::contains)) add("any $it") }
        strings(turn, "all").filterNot(text::contains).forEach { add("all '$it'") }
        strings(turn, "none").filter(text::contains).forEach { add("none '$it'") }
        turn.optJSONArray("kind")?.let { k -> if ((0 until k.length()).none { k.getString(it) == reply.kind.name }) add("kind ${reply.kind}") }
        turn.optString("source").takeIf { it.isNotEmpty() }?.let { s -> if (reply.sources.none { s in it.id }) add("source $s (got ${reply.sources.map { it.id }})") }
        // The app's standard "no saved record" text mentions the 8888 hotline; that is not an invented number.
        if (turn.optBoolean("no_digits") && reply.text != Orchestrator.FALLBACK && text.any(Char::isDigit)) add("no_digits")
        if (turn.optBoolean("chat")) {
            if (reply.text == Orchestrator.FALLBACK) add("chat: fallback")
            if (noRecord.containsMatchIn(text)) add("chat: said no record")
            if (Regex("₱|\\bphp\\b|source:").containsMatchIn(text)) add("chat: amount or source line")
            if (reply.text.trim().length < 12) add("chat: too short")
        }
    }

    @Test fun run() {
        val url = System.getenv("KUYAJ_EVAL_URL") ?: return
        val set = File(System.getenv("KUYAJ_EVAL_SET") ?: "src/test/resources/conversations.jsonl")
        val conversations = set.readLines().filter { it.isNotBlank() && !it.startsWith("//") }.map(::JSONObject)
        val model = ServerModel(url)
        val pool = Executors.newFixedThreadPool(4)
        val results = conversations.map { c ->
            pool.submit<JSONObject> {
                val o = Orchestrator(KnowledgeStore(records), llm = { model }, clock = { "Friday, 27 November 2026, 3:10 PM" }, today = { "2026-11-27" })
                val turns = c.getJSONArray("turns")
                val out = JSONArray()
                for (i in 0 until turns.length()) {
                    val t = turns.getJSONObject(i)
                    val reply = runCatching { runBlocking { o.reply(t.getString("user")) } }
                        .getOrElse { Reply("ERROR: ${it.message}", Reply.Kind.SYSTEM) }
                    val failed = failures(t, reply)
                    out.put(JSONObject().put("user", t.getString("user")).put("reply", reply.text).put("kind", reply.kind.name)
                        .put("note", reply.note ?: JSONObject.NULL).put("sources", JSONArray(reply.sources.map { it.id }))
                        .put("pass", failed.isEmpty()).put("failed", JSONArray(failed)))
                }
                JSONObject().put("id", c.getString("id")).put("tags", c.optJSONArray("tags") ?: JSONArray()).put("turns", out)
            }
        }.map { it.get() }
        pool.shutdown(); pool.awaitTermination(1, TimeUnit.MINUTES)

        val byTag = sortedMapOf<String, IntArray>()
        var pass = 0; var total = 0
        val report = StringBuilder()
        for (r in results) {
            val turns = r.getJSONArray("turns")
            val tags = r.getJSONArray("tags").let { a -> (0 until a.length()).map(a::getString) }
            for (i in 0 until turns.length()) {
                val t = turns.getJSONObject(i)
                val ok = t.getBoolean("pass")
                total++; if (ok) pass++
                tags.forEach { byTag.getOrPut(it) { IntArray(2) }.let { n -> n[1]++; if (ok) n[0]++ } }
                report.appendLine("${if (ok) "PASS" else "FAIL"} ${r.getString("id")}#${i + 1} [${t.getString("kind")}] ${t.getString("user")}")
                report.appendLine("     -> ${t.getString("reply").replace("\n", " ").take(220)}")
                if (!ok) report.appendLine("     !! ${t.getJSONArray("failed").join("; ")}${t.opt("note").takeIf { it != JSONObject.NULL }?.let { "  note: $it" } ?: ""}")
            }
        }
        val summary = "SCORE $pass/$total  " + byTag.entries.joinToString("  ") { "${it.key} ${it.value[0]}/${it.value[1]}" }
        val dir = File("build/eval").apply { mkdirs() }
        File(dir, "${set.nameWithoutExtension}.json").writeText(JSONArray(results).toString(1))
        File(dir, "${set.nameWithoutExtension}.txt").writeText("$summary\n\n$report")
        println(summary)
    }
}
