package ph.bettergov.kuyaj.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import ph.bettergov.kuyaj.kb.KbRecord
import ph.bettergov.kuyaj.kb.KnowledgeStore
import ph.bettergov.kuyaj.kb.SearchIndex
import ph.bettergov.kuyaj.llm.LlmEngine
import ph.bettergov.kuyaj.safety.Safety

data class Reply(
    val text: String,
    val kind: Kind,
    val sources: List<KbRecord> = emptyList(),
    /** Why a record answered instead of the model, shown under the answer. */
    val note: String? = null,
    /** What the model read from an attached photo. */
    val photoReading: String? = null,
) {
    enum class Kind { RECORD, MODEL, SYSTEM, REFUSAL }
}

/**
 * Airgap's request path, offline: code-owned controls first, then local
 * search, then the model phrases the records, then the grounding check.
 * The model never supplies a fact the records do not contain.
 */
class Orchestrator(
    private val store: KnowledgeStore,
    private val llm: () -> LlmEngine?,
    private val stalenessNote: () -> String? = { null },
    /** The phone's date and time as a sentence, e.g. "Thursday, 26 November 2026, 3:10 PM". */
    private val clock: () -> String = { "" },
    private val answerDeadlineMs: Long = 60_000,
) {
    // The model runs outside the caller's coroutine so a native call that never returns can't hold the chat.
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class Turn(val user: String, val bot: String)

    private val history = ArrayDeque<Turn>()
    private var lastRecordAnswer: Reply? = null

    fun clear() {
        history.clear()
        lastRecordAnswer = null
    }

    suspend fun reply(input: String, photo: ByteArray? = null, onText: (String) -> Unit = {}): Reply {
        val reply = route(input.trim(), photo, onText)
        history.addLast(Turn(input.trim(), reply.text))
        while (history.size > 3) history.removeFirst()
        lastRecordAnswer = reply.takeIf { it.sources.isNotEmpty() && it.kind != Reply.Kind.REFUSAL }
        return reply
    }

    private suspend fun route(text: String, photo: ByteArray?, onText: (String) -> Unit): Reply {
        if (text.isEmpty() && photo == null) return system("Ask a question about a Philippine government service.")
        if (text.length > 2000) return system("Please shorten the question to 2,000 characters.")

        Safety.checkInput(text)?.let { return Reply(Safety.REFUSALS.getValue(it), Reply.Kind.REFUSAL) }

        val plain = text.lowercase().replace(Regex("[!.,?]"), "").trim()
        if (plain in GREETINGS) return system(GREETING_REPLY)
        if (plain in FAREWELLS) return system("Walang anuman. Ask again any time.")
        if (IDENTITY.matches(plain)) return system(IDENTITY_REPLY)
        if (CLOCK.matches(text)) {
            val clock = clock().ifEmpty { return system("I can't read this phone's clock.") }
            return system("It is $clock on this phone's clock. I have no clock of my own; each record shows the date it was checked.")
        }
        // A doubt check replays the record. The model is not asked again, so the answer cannot move.
        if (DOUBT.containsMatchIn(text)) {
            val last = lastRecordAnswer ?: return system("Ask a sourced question first so I can check its record again.")
            return last.copy(text = "I checked the record again. It has not changed:\n\n${last.sources.first().content}", kind = Reply.Kind.RECORD, note = null)
        }

        val engine = llm()?.takeIf { it.isReady }
        var photoReading: String? = null
        if (photo != null) {
            if (engine == null) return system("Reading photos needs the AI model. Download it in Settings, then try again.")
            photoReading = engine.generate(PHOTO_SYSTEM, PHOTO_PROMPT, photo).take(1500)
        }

        // The photo's text counts like the question: the person supplied it.
        val question = listOfNotNull(text.takeIf { it.isNotEmpty() }, photoReading).joinToString("\n")

        // Tool mode, for a model trained to search: it picks the keywords, the app runs its own search.
        // Follow-ups and photos stay on the path below, as in training.
        if (engine != null && photo == null && history.isEmpty()) toolAnswer(engine, question, onText)?.let { return it }
        var hits = search(question)
        if (hits.isEmpty() && history.isNotEmpty() && SearchTerms.count(text) <= 4) {
            hits = search("${history.last().user} $text") // short follow-up
        }
        if (hits.isEmpty()) {
            val fallback = if (photo == null && SMALL_TALK.containsMatchIn(plain)) SMALL_TALK_REPLY else FALLBACK
            return Reply(fallback, Reply.Kind.SYSTEM, photoReading = photoReading)
        }

        val records = hits.map { it.record }
        val top = records.first()
        val recordReply = Reply(withStaleness(top.content), Reply.Kind.RECORD, records, photoReading = photoReading)
        if (engine == null || top.verbatim) return recordReply

        val sources = Safety.sources(records, question, NAMES)
        val gate = Safety.TokenGate(sources, onText)
        return checked(recordReply, records, sources, { gate.halted }) { live ->
            engine.generate(SYSTEM_PROMPT, userMessage(question, records)) { if (live.on) gate.accept(it) }
        }
    }

    /**
     * The model calls search_kb with English keywords; the app searches (the raw question too if the keywords miss)
     * and checks the answer against what it returned. Null when the model can't search, didn't, or found nothing:
     * the caller then searches the question itself.
     */
    private suspend fun toolAnswer(engine: LlmEngine, question: String, onText: (String) -> Unit): Reply? {
        var found = emptyList<KbRecord>()
        var gate: Safety.TokenGate? = null // made once records arrive; text before any search is never shown
        val live = Live()
        val generated = runModel(live) {
            engine.generateWithSearch(TOOL_SYSTEM_PROMPT, question, search = { query ->
                val hits = search(query).ifEmpty { search(question) }.map { it.record }
                // ponytail: a second call widens the sources and restarts the gate; the model makes one call in practice.
                found = (found + hits).distinctBy { it.id }
                gate = Safety.TokenGate(Safety.sources(found, question, NAMES), onText)
                if (hits.isEmpty()) "No records found." else recordsBlock(hits)
            }) { if (live.on) gate?.accept(it) }
        }
        if (generated is ModelRun.Done && generated.text == null) return null // not a searching model
        if (found.isEmpty()) return null
        val top = found.first()
        val recordReply = Reply(withStaleness(top.content), Reply.Kind.RECORD, found)
        if (top.verbatim) return recordReply
        return verdict(generated, recordReply, found, Safety.sources(found, question, NAMES), gate?.halted == true)
    }

    private sealed interface ModelRun {
        data class Done(val text: String?) : ModelRun
        data object TimedOut : ModelRun
        data object Failed : ModelRun
    }

    /** Runs the model outside the caller's coroutine, under the answer deadline; after it, late words are dropped. */
    private suspend fun runModel(live: Live, block: suspend () -> String?): ModelRun = try {
        val job = modelScope.async { block() }
        withTimeoutOrNull(answerDeadlineMs) { ModelRun.Done(job.await()) } ?: run { live.on = false; ModelRun.TimedOut }
    } catch (e: Exception) {
        ModelRun.Failed
    }

    private suspend fun checked(
        recordReply: Reply, records: List<KbRecord>, sources: Safety.Sources, halted: () -> Boolean,
        block: suspend (Live) -> String,
    ): Reply {
        val live = Live()
        return verdict(runModel(live) { block(live) }, recordReply, records, sources, halted())
    }

    /** The model's answer if every amount, date and name in it is in the records; otherwise the record as written. */
    private fun verdict(run: ModelRun, recordReply: Reply, records: List<KbRecord>, sources: Safety.Sources, halted: Boolean): Reply {
        val generated = when (run) {
            ModelRun.TimedOut -> return recordReply.copy(note = "The AI model took too long, so the record is shown as written.")
            ModelRun.Failed -> return recordReply.copy(note = "The AI model failed, so the record is shown as written.")
            is ModelRun.Done -> run.text.orEmpty()
        }
        val issues = Safety.ungrounded(generated, sources)
        if (halted || issues.isNotEmpty() || generated.length < 2) {
            return recordReply.copy(note = "The AI answer was withheld: ${issues.firstOrNull() ?: "it stated something the records do not"}. The record is shown as written.")
        }
        return Reply(withStaleness(generated), Reply.Kind.MODEL, records, photoReading = recordReply.photoReading)
    }

    /**
     * Prefix and typo matching find some record for almost any text ("pila" -> "pilipinas"),
     * so a weak top match counts as no match. One or two words ("pasko", "sss hotline") get a lower bar.
     * ponytail: thresholds measured on the 27 seed records (real questions >= 23, off-topic <= 18);
     * BM25 scores move with corpus size, so re-measure when bundles grow.
     */
    private fun search(query: String): List<SearchIndex.Hit> {
        val hits = store.search(query, TOP_K)
        val min = if (SearchIndex.tokenize(query).size <= 2) 5.0 else 20.0
        return if ((hits.firstOrNull()?.score ?: 0.0) >= min) hits else emptyList()
    }

    private fun userMessage(question: String, records: List<KbRecord>): String = buildString {
        if (history.isNotEmpty()) {
            appendLine("RECENT CONVERSATION:")
            history.forEach { appendLine("User: ${it.user}\n$BOT_NAME: ${it.bot.take(300)}") }
            appendLine()
        }
        appendLine("CONTEXT:")
        appendLine(recordsBlock(records))
        appendLine()
        append("QUESTION: ").append(question)
    }

    /** Numbered records, as the model sees them in CONTEXT and in a search_kb result. */
    private fun recordsBlock(records: List<KbRecord>) =
        records.mapIndexed { i, r -> "[${i + 1}] ${r.title}\n${r.content.take(RECORD_CHARS)}" }.joinToString("\n")

    private fun withStaleness(text: String) = stalenessNote()?.let { "$it\n\n$text" } ?: text

    private fun system(text: String) = Reply(text, Reply.Kind.SYSTEM)

    private object SearchTerms {
        fun count(text: String) = text.split(Regex("\\s+")).count { it.isNotBlank() }
    }

    companion object {
        const val BOT_NAME = "Kuya J"
        const val TOP_K = 3
        const val RECORD_CHARS = 1200
        private val NAMES = listOf(BOT_NAME, "BetterGov")

        val SYSTEM_PROMPT = """
            You are $BOT_NAME, a guide to Philippine government services from BetterGov.ph.
            Answer only from the CONTEXT. Reply in the same language as the question, including regional Philippine languages such as Cebuano, Ilocano, Hiligaynon, Waray, Kapampangan, Bikol, Pangasinan and Tausug.
            Copy names, dates, fees and source links exactly as written. Never guess an office holder, date or fee.
            If the CONTEXT does not answer the question, say in one sentence that you have no record for it.
            Never give political opinions, legal advice or medical advice.
            Begin with the fact. Answer in three sentences or fewer, then end with the source line of the record you used.
        """.trimIndent()

        // Byte-identical to TOOL_SYSTEM_PROMPT in kuya-j-evals distill/restructure_v3.py, which the model was trained with.
        val TOOL_SYSTEM_PROMPT = """
            You are $BOT_NAME, a guide to Philippine government services from BetterGov.ph.
            Before answering a question about a government service, fee, requirement, hotline, holiday or official, call search_kb with English keywords. Do not call it for greetings or small talk.
            Answer only from the search_kb results. Reply in the same language as the question, including regional Philippine languages such as Cebuano, Ilocano, Hiligaynon, Waray, Kapampangan, Bikol, Pangasinan and Tausug.
            Copy names, dates, fees and source links exactly as written. Never guess an office holder, date or fee.
            If the results do not answer the question, say in one sentence that you have no record for it.
            Never give political opinions, legal advice or medical advice.
            Begin with the fact. Answer in three sentences or fewer, then end with the source line of the record you used.
        """.trimIndent()

        const val PHOTO_SYSTEM = "You read photos of documents, forms, signs and IDs for a government-services assistant."
        const val PHOTO_PROMPT =
            "Transcribe the important text in this photo, then name what kind of document or place it shows in one short line. Do not add facts that are not visible."

        const val GREETING_REPLY =
            "Kumusta! Ako si Kuya J. Ask me about government services, fees, requirements, hotlines and holidays. I work offline and every answer shows its source and the date it was checked."
        const val IDENTITY_REPLY =
            "Ako si Kuya J, BetterGov.ph's civic assistant. This version runs fully on your phone and answers from saved, dated records. No government agency operates it."
        const val FALLBACK =
            "I don't have a saved record for that. Try naming the service and the detail you need, for example \"NBI clearance fee\" or \"SSS hotline\". For anything else, call 8888 or check the agency's official website."

        const val SMALL_TALK_REPLY =
            "Hehe, AI lang ako, kaya hindi ako kumakain, tumatanda o umaalis ng bahay. 😄 Ako si Kuya J. Tanungin mo ako tungkol sa serbisyo ng gobyerno, gaya ng NBI clearance, passport, SSS o mga holiday."

        // Personal questions to the bot (eating, age, company, whereabouts, love life) in Tagalog,
        // Visayan, Ilocano and Kapampangan; checked only once no record matched. ponytail: word list, grow it from real logs.
        private val SMALL_TALK = Regex(
            "\\b(nagkaon|kaon|mikaon|kumain|kain|nangan|mangan|sud-an|ulam|tuig|edad|idad|taon|upod|kauban|kasama|" +
                "nagkadto|nagadto|nag-adto|pumunta|napunta|asawa|jowa|uyab|crush|girlfriend|boyfriend|how old|have you eaten)\\b",
        )
        private val GREETINGS = setOf("hi", "hello", "hey", "good morning", "good afternoon", "good evening", "kumusta", "kamusta", "musta", "magandang umaga", "magandang hapon", "magandang gabi")
        private val FAREWELLS = setOf("thanks", "thank you", "salamat", "salamat po", "bye", "goodbye", "paalam")
        private val IDENTITY = Regex("^(sino ka|who are you|what is your name|ano pangalan mo)$")
        private val CLOCK = Regex(
            "^(?:(?:what|which) (?:time|date|day)(?: and time)?(?: is it)?(?: (?:now|today|right now))?|(?:anong|ano ang|ano'ng) (?:oras|petsa|araw)(?: na)?(?: ngayon)?)[?.!]*$",
            RegexOption.IGNORE_CASE,
        )
        private val DOUBT = Regex("^(are you sure|you sure|really|talaga|sigurado (ka|ba)|sure ba|is that (right|correct))\\b", RegexOption.IGNORE_CASE)
    }
}

/** Cleared when a model run times out, so text the model still streams afterwards is dropped. */
private class Live {
    @kotlin.concurrent.Volatile
    var on = true
}
