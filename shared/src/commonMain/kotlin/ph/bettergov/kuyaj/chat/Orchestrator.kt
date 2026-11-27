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
    /** Record titles offered when nothing answered the question exactly; tapping one asks it. */
    val suggestions: List<String> = emptyList(),
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
    /** The phone's date as YYYY-MM-DD, for "the next" holiday. Empty if unknown. */
    private val today: () -> String = { "" },
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
        store.awaitReady()
        val reply = route(input.trim(), photo, onText)
        // A "no record" reply is left out of what the model sees next: with two of them in view it copied the
        // phrase for a question the records do answer ("magkano philhealth" after "magkano sss").
        history.addLast(Turn(input.trim(), if (isNoAnswer(reply)) "" else reply.text))
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
        // Personal questions to Kuya J ("kumusta ang araw mo?", "mahal mo ba ako?") before search: their everyday words
        // ("araw", "mahal") would otherwise match holiday names or marker text.
        if (PERSONAL.containsMatchIn(plain)) return system(SMALL_TALK_REPLY)
        // A tapped choice is a record's own title: answer from that record alone. With other records and earlier turns
        // in view, the model told "SSS pension claim: fees" (which says "Filing is free") that no fee was stated,
        // a line from the SS number record.
        if (photo == null) exactRecord(text, onText)?.let { return it }
        // Holiday dates are fixed facts: answered from the record by code.
        if (photo == null) Holidays.answer(text, store.state.records, today())?.let {
            return Reply(withStaleness(it.text), Reply.Kind.RECORD, listOf(it.record))
        }
        // "Kailan ipinanganak si Rizal?": code picks the marker that records it; the model words the answer from it.
        val pinned = if (photo == null) People.record(text, store.state.records) else null
        // Someone with no marker: a history match must still name them, or it's another person's (or a house's) marker.
        // Other categories pass: "birth certificate" and "SSS death benefit" are birth and death words too.
        if (photo == null && pinned == null && People.isLifeQuestion(text)) search(text).firstOrNull()?.record?.let {
            if (it.category == "history" && !People.names(text, it.title)) return Reply(FALLBACK, Reply.Kind.SYSTEM)
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

        val grounded = grounded(engine, text, question, plain, photo, photoReading, pinned, onText)
        // Nothing usable from the records and no fact asked for ("tell me a joke", "turuan mo ako ng Bisaya"): just talk.
        val weak = isNoAnswer(grounded) || (grounded.note != null && (searchQuestion(text).firstOrNull()?.words ?: 0) < 2)
        if (engine != null && photo == null && weak && SearchIndex.intents(text).none { it in FACT_INTENTS } && !People.isLifeQuestion(text)) {
            chat(engine, text, onText)?.let { return it }
        }
        // Still nothing: instead of a bare "no record", say what the records do have on the topic.
        if (isNoAnswer(grounded)) offer(text)?.let { return it }
        return grounded
    }

    /**
     * "Magkano sss" has three possible answers in the records (SS number, pension claim, MySSS card) and the model
     * may search for a fourth. The titles of the records the question itself finds, as choices to tap. Null when
     * no record shares a real word with the question.
     */
    private fun offer(text: String): Reply? {
        if (searchQuestion(text).isEmpty()) return null
        val query = if (SearchTerms.continues(text) && history.isNotEmpty()) "${history.last().user} $text" else text
        val asked = SearchIndex.tokenize(query).toSet()
        val wanted = SearchIndex.intents(query) // "magkano" puts the fee records first
        val titles = store.search(query, 12).map { it.record }
            .filter { r -> SearchIndex.tokenize("${r.title} ${r.keywords.joinToString(" ")}").any { it in asked } }
            .sortedByDescending { it.category in wanted } // stable: search order within each group
            .map { it.title.substringBefore(" (part ") }.distinct().take(4)
        return if (titles.isEmpty()) null else Reply(OFFER, Reply.Kind.SYSTEM, suggestions = titles)
    }

    private suspend fun exactRecord(text: String, onText: (String) -> Unit): Reply? {
        val records = store.state.records.filter { it.title.substringBefore(" (part ").equals(text, ignoreCase = true) }.take(TOP_K)
        if (records.isEmpty()) return null
        val recordReply = Reply(withStaleness(records.first().content), Reply.Kind.RECORD, records)
        val engine = llm()?.takeIf { it.isReady }
        if (engine == null || records.first().verbatim) return recordReply
        val sources = Safety.sources(records, text, NAMES, checkNames = records.any { it.category == "officials" })
        val gate = Safety.TokenGate(sources, onText)
        return checked(recordReply, records, sources, { gate.halted }) { live ->
            engine.generate(SYSTEM_PROMPT, "CONTEXT:\n${recordsBlock(records)}\n\nQUESTION: $text") { if (live.on) gate.accept(it) }
        }
    }

    private fun isNoAnswer(r: Reply) = r.kind == Reply.Kind.SYSTEM && (r.text == FALLBACK || NO_RECORD.containsMatchIn(r.text))

    /** The question's own records: a fragment ("ng pilipinas", "how about the fee?") is searched with the question before it. */
    private fun searchQuestion(text: String, question: String = text): List<SearchIndex.Hit> {
        var hits = search(question)
        if (history.isNotEmpty() && (hits.isEmpty() && SearchTerms.count(text) <= 4 || SearchTerms.continues(text))) {
            search("${history.last().user} $text").takeIf { it.isNotEmpty() }?.let { hits = it }
        }
        return hits
    }

    /** An answer from the records: the model searches (tool mode) or the app searches for it (context mode). */
    private suspend fun grounded(
        engine: LlmEngine?, text: String, question: String, plain: String, photo: ByteArray?, photoReading: String?,
        pinned: KbRecord?, onText: (String) -> Unit,
    ): Reply {
        // Tool mode, for a model trained to search: it sees the conversation, picks the keywords, and the app runs its
        // own search. Every text turn, so "how about the requirements?" is searched as what it means.
        // If it then says "no record" although the question's own search found something, the app shows it those
        // records directly (context mode) before giving up: "sino president" got "wala akong record" this way.
        val hits = searchQuestion(text, question)
        if (engine != null && photo == null) toolAnswer(engine, text, question, pinned, onText)?.let {
            if (!isNoAnswer(it) || hits.isEmpty()) return it
        }
        if (hits.isEmpty() && pinned == null) {
            // With a model, the chat path answers small talk; the canned reply is for phones without one
            // ("ilan jowa ni Rizal" is not about Kuya J, and the word list can't tell).
            val fallback = if (photo == null && engine == null && SMALL_TALK.containsMatchIn(plain)) SMALL_TALK_REPLY else FALLBACK
            return Reply(fallback, Reply.Kind.SYSTEM, photoReading = photoReading)
        }

        val records = (listOfNotNull(pinned) + hits.map { it.record }).distinctBy { it.id }.take(TOP_K)
        val top = records.first()
        val recordReply = Reply(withStaleness(top.content), Reply.Kind.RECORD, records, photoReading = photoReading)
        if (engine == null || top.verbatim) return recordReply

        val sources = grounding(records, question)
        val gate = Safety.TokenGate(sources, onText)
        return checked(recordReply, records, sources, { gate.halted }) { live ->
            engine.generate(SYSTEM_PROMPT, userMessage(question, records)) { if (live.on) gate.accept(it) }
        }
    }

    /**
     * Free conversation, for what the records have nothing to say about. The model talks from what it knows, but a
     * reply that states money or cites a source is dropped: facts come from records only.
     */
    private suspend fun chat(engine: LlmEngine, text: String, onText: (String) -> Unit): Reply? {
        val live = Live()
        val shown = StringBuilder()
        val run = runModel(live) { engine.generate(CHAT_SYSTEM_PROMPT, withHistory(text)) { if (live.on) shown.append(it) } }
        val said = (run as? ModelRun.Done)?.text?.trim().orEmpty()
        if (said.length < 2 || CHAT_FACTS.containsMatchIn(said)) return null
        onText(said)
        return Reply(said, Reply.Kind.SYSTEM)
    }

    /**
     * What an answer may state: the records it was given plus those behind the previous answer, since a follow-up
     * repeats figures from it ("+₱75 if expired" after the fee was already quoted). Names are checked only when an
     * office holder is in play.
     */
    private fun grounding(records: List<KbRecord>, question: String): Safety.Sources {
        val all = (records + lastRecordAnswer?.sources.orEmpty()).distinctBy { it.id }
        val names = all.any { it.category == "officials" }
        return Safety.sources(all, withHistory(question), NAMES, checkNames = names)
    }

    /** The question with the last turns before it, so the model knows what "it", "siya" or "how about..." refers to. */
    private fun withHistory(text: String): String = if (history.isEmpty()) text else buildString {
        appendLine("RECENT CONVERSATION:")
        history.forEach { appendLine("User: ${it.user}" + if (it.bot.isBlank()) "" else "\n$BOT_NAME: ${it.bot.take(300)}") }
        appendLine()
        append("QUESTION: ").append(text)
    }

    /**
     * The model calls search_kb with English keywords; the app searches (the raw question too if the keywords miss)
     * and checks the answer against what it returned. Null when the model can't search, didn't, or found nothing:
     * the caller then searches the question itself.
     */
    private suspend fun toolAnswer(engine: LlmEngine, text: String, question: String, pinned: KbRecord?, onText: (String) -> Unit): Reply? {
        var found = emptyList<KbRecord>()
        var pin = pinned
        var gate: Safety.TokenGate? = null // made once records arrive; text before any search is never shown
        val live = Live()
        val generated = runModel(live) {
            engine.generateWithSearch(TOOL_SYSTEM_PROMPT, withHistory(question), search = { query ->
                // The question's own top record always gets a place: a misread word ("tagpila" taken for an office)
                // makes the model's keywords miss what the person asked.
                val byKeywords = search(query)
                val byQuestion = searchQuestion(text, question)
                // "kailan siya namatay?" names nobody; the model's keywords ("Jose Rizal death") do.
                pin = pin ?: People.record(query, store.state.records)
                val hits = (listOfNotNull(pin) + (byKeywords.take(TOP_K - 1) + byQuestion.take(1) + byKeywords.drop(TOP_K - 1)
                    + byQuestion.drop(1)).map { it.record }).distinctBy { it.id }.take(TOP_K)
                // ponytail: a second call widens the sources and restarts the gate; the model makes one call in practice.
                found = (found + hits).distinctBy { it.id }
                gate = Safety.TokenGate(grounding(found, question), onText)
                if (hits.isEmpty()) "No records found." else recordsBlock(hits)
            }) { if (live.on) gate?.accept(it) }
        }
        if (generated is ModelRun.Done && generated.text == null) return null // not a searching model
        if (found.isEmpty()) return null
        // If the model's answer is withheld, show the record closest to the question ("Bonifacio Day 2026", not 2025).
        found = (listOfNotNull(pin) + bySupport(found, question)).distinctBy { it.id }
        val top = found.first()
        val recordReply = Reply(withStaleness(top.content), Reply.Kind.RECORD, found)
        if (top.verbatim) return recordReply
        return verdict(generated, recordReply, found, grounding(found, question), gate?.halted == true)
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
        // "Wala akong record..." cites nothing: shown as plain text, without sources or a "checked" mark.
        if (NO_RECORD.containsMatchIn(generated) && generated.length < 200 && CURRENCY_MARK !in generated) {
            // Models sometimes add "Source: No record found." to it; there is no source to show.
            val plain = generated.lines().filterNot { it.trim().startsWith("Source:", ignoreCase = true) }.joinToString("\n").trim()
            return Reply(plain.ifEmpty { generated }, Reply.Kind.SYSTEM, photoReading = recordReply.photoReading)
        }
        return Reply(withStaleness(generated), Reply.Kind.MODEL, bySupport(records, generated), photoReading = recordReply.photoReading)
    }

    /**
     * Records ordered by how many of the text's numbers and words they contain, title matches counting double, so the
     * card shows the record the answer used ("Bonifacio Day: 2026-11-30", not the 2027 record that cites a 2026 law).
     */
    private fun bySupport(records: List<KbRecord>, text: String): List<KbRecord> {
        val terms = SearchIndex.tokenize(text).filter { it.length > 3 || it.any(Char::isDigit) }.toSet()
        fun hits(s: String) = SearchIndex.tokenize(s).toSet().count { it in terms }
        return records.sortedByDescending { r -> 2 * hits(r.title) + hits(r.content) }
    }

    /**
     * Prefix and typo matching find some record for almost any text ("pila" -> "pilipinas"),
     * so a weak top match counts as no match. One word ("pasko") needs 10 and an exact or prefix match, two ("sss hotline")
     * 15; longer questions need
     * 25, or 34 if the top record shares only one of their words ("won the game last night" vs "Last Day of the Year").
     * Words like "the" or "ng" don't count (SearchIndex.STOPWORDS). Measured on the 462 records shipped with the app
     * (11 from Airgap + 451 from BetterGov.ph): 227 of 228 real questions pass; off-topic questions that still match share a
     * real word with a record ("rice" and PhilRice, "weather" and PAGASA).
     * ponytail: BM25 scores move with corpus size; re-measure (docs/records-pipeline.md) when it changes a lot.
     */
    private fun search(query: String): List<SearchIndex.Hit> {
        val hits = store.search(query, TOP_K)
        val top = hits.firstOrNull() ?: return emptyList()
        val ok = when (SearchIndex.tokenize(query).size) {
            0, 1 -> top.score >= 10.0 && top.words >= 1 // a typo match alone ("sud" ~ "Bud Dajo") doesn't count
            2 -> top.score >= 15.0
            else -> top.score >= 25.0 && (top.words >= 2 || top.score >= 34.0)
        }
        return if (ok) hits else emptyList()
    }

    private fun userMessage(question: String, records: List<KbRecord>): String = buildString {
        if (history.isNotEmpty()) {
            appendLine("RECENT CONVERSATION:")
            history.forEach { appendLine("User: ${it.user}" + if (it.bot.isBlank()) "" else "\n$BOT_NAME: ${it.bot.take(300)}") }
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

        private val ASKS = setOf("sino", "ano", "anong", "saan", "kailan", "kelan", "magkano", "paano", "ilan", "bakit", "alin",
            "who", "what", "when", "where", "how", "which", "why", "pila", "tagpila", "unsa", "kinsa", "asa", "unsaon", "ania", "mano")
        private val LINKS = Regex("""^(how about|what about|paano naman|e+h?\b|at\b|and\b|ng\b|sa\b|ni\b|kay\b|pati\b|of\b|for\b|in\b)""")

        /**
         * True for a fragment that only makes sense after the last question: it opens with a linking word, names no
         * topic at all ("magkano?"), or names one without asking anything ("requirements?"). "Sino ang mga senador?"
         * names one topic but asks its own question, so it stands alone.
         */
        fun continues(text: String): Boolean {
            val raw = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            val topics = SearchIndex.tokenize(text).size
            return count(text) <= 4 && (LINKS.containsMatchIn(text.trim().lowercase()) || topics == 0 || (topics < 2 && raw.none { it in ASKS }))
        }
    }

    companion object {
        const val BOT_NAME = "Kuya J"
        const val TOP_K = 3

        // ponytail: phrase list for the languages the model answers in; a no-record answer worded another way keeps its sources.
        // Questions asking for one of these are never answered from the model's own knowledge.
        private val FACT_INTENTS = setOf("fees", "hotlines", "officials", "history")
        private val NO_RECORD = Regex(
            """\b(no (saved )?record|don.?t have (a |any )?(saved )?(record|information)|wala\w*( po)?( akong| ako| kong)?( [\w-]+){0,2} (record|rekord|tala|naitala\w*|nakitang|impormasyon)|waray (ako )?(record|rekord)|walay (ako )?(record|rekord)|awan (ti )?(record|rekord))""",
            RegexOption.IGNORE_CASE,
        )
        const val RECORD_CHARS = 1200
        private const val CURRENCY_MARK = "₱"
        // Names the model may say without a record containing them: itself, BetterGov, and the country, which a Filipino
        // answer names even when the English record doesn't ("Pilipinas" got a correct answer withheld).
        // Also the Filipino words for offices and titles: a Filipino answer says "Opisina ng Pangulo" for a record that
        // says "Office of the President". The holder's name, dates and amounts are still checked.
        private val NAMES = listOf(BOT_NAME, "BetterGov", "Pilipinas", "Philippines", "Filipinas", "Filipino", "Pilipino",
            "Opisina", "Tanggapan", "Kagawaran", "Kawanihan", "Komisyon", "Pangulo", "Presidente", "Pangalawang", "Bise", "Kalihim",
            "Senado", "Kongreso", "Kapulungan", "Kinatawan", "Korte", "Suprema", "Mahistrado", "Republika", "Pamahalaan", "Gobyerno")

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

        // Not a trained prompt: free conversation for what isn't in the records. The reply is dropped if it states money
        // or cites a source (CHAT_FACTS), and questions that ask for a fee, hotline, date or office holder never get here.
        val CHAT_SYSTEM_PROMPT = """
            You are $BOT_NAME, a friendly Filipino companion from BetterGov.ph. Chat naturally and helpfully: small talk, jokes, encouragement, language help, simple explanations and everyday questions.
            Reply in the language the person uses, in three sentences or fewer.
            You have no saved record for this message. If it asks about a government service, document, fee, date, phone number or who holds an office, say you have no saved record for it; never state those from memory.
            Never give political opinions, legal advice or medical advice.
        """.trimIndent()
        private val CHAT_FACTS = Regex("""₱|\bPHP\b|\bpesos?\b|Source:|https?://""", RegexOption.IGNORE_CASE)

        const val PHOTO_SYSTEM = "You read photos of documents, forms, signs and IDs for a government-services assistant."
        const val PHOTO_PROMPT =
            "Transcribe the important text in this photo, then name what kind of document or place it shows in one short line. Do not add facts that are not visible."

        const val GREETING_REPLY =
            "Kumusta! Ako si Kuya J. Ask me about government services, fees, requirements, hotlines and holidays. I work offline and every answer shows its source and the date it was checked."
        const val IDENTITY_REPLY =
            "Ako si Kuya J, BetterGov.ph's civic assistant. This version runs fully on your phone and answers from saved, dated records. No government agency operates it."
        const val FALLBACK =
            "I don't have a saved record for that. Try naming the service and the detail you need, for example \"NBI clearance fee\" or \"SSS hotline\". For anything else, search Google or the agency's official website when you have internet, or call 8888."

        const val OFFER =
            "Wala akong record niyan. Pero ito ang mayroon ako sa record, pumili ng isa. Kung wala rito ang kailangan mo, " +
                "i-search mo sa Google o tingnan ang opisyal na website ng ahensya kapag may internet ka. " +
                "(I have no record of that. Here is what I do have; if it's not here, search Google or the agency's official website.)"

        const val SMALL_TALK_REPLY =
            "Hehe, AI lang ako, kaya hindi ako kumakain, tumatanda o umaalis ng bahay. 😄 Ako si Kuya J. Tanungin mo ako tungkol sa serbisyo ng gobyerno, gaya ng NBI clearance, passport, SSS o mga holiday."

        // Personal questions to the bot (eating, age, company, whereabouts, love life) in Tagalog,
        // Visayan, Ilocano and Kapampangan; checked only once no record matched. ponytail: word list, grow it from real logs.
        private val SMALL_TALK = Regex(
            "\\b(nagkaon|kaon|mikaon|kumain|kain|nangan|mangan|sud-an|ulam|tuig|edad|idad|taon|upod|kauban|kasama|" +
                "nagkadto|nagadto|nag-adto|pumunta|napunta|asawa|jowa|uyab|crush|girlfriend|boyfriend|how old|have you eaten)\\b",
        )
        private val PERSONAL = Regex(
            "\\b(kumusta ang araw mo|kamusta ang araw mo|kumusta ka|kamusta ka|how are you|mahal mo ba ako|do you love me|" +
                "nakakain ka|kumain ka na|ilang taon ka|how old are you|saan ka nakatira|asa ka nagpuyo|may jowa ka|crush mo|" +
                "paborito mong|your favorite|anong pangalan mo|what is your name)\\b",
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
