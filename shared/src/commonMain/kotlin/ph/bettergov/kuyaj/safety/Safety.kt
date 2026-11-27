package ph.bettergov.kuyaj.safety

import ph.bettergov.kuyaj.kb.KbRecord

/**
 * Ported from Airgap's safetyLayer.ts and publicService.ts. Offline, there is
 * no Jev, so input screening is pattern-based and every model answer must
 * only state amounts, dates and names that the retrieved records contain.
 */
object Safety {

    // ---- Input policy -------------------------------------------------------

    private val PROMPT_PROBES = listOf(
        "\\b(system|hidden|secret|initial|developer)\\s+(prompt|instructions?|message)\\b",
        "\\b(your|the bot'?s|the assistant'?s)\\s+(prompt|instructions|rules|guidelines)\\b",
        "\\b(ignore|disregard|forget|override|bypass)\\b.{0,40}\\b(instructions?|rules|prompt|guidelines)\\b",
        "\\b(repeat|print|output|write|paste)\\b.{0,30}\\b(words|text|lines|everything|all)\\b.{0,20}\\babove\\b",
        "\\b(developer|debug|god|admin)\\s+mode\\b",
        "\\b(your|the bot'?s|the assistant'?s)\\s+(config(uration)?|settings|source code|code|model file|tools?)\\b",
        "\\bjailbreak\\b",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** `reason:phrase`. Matches whole words. From Airgap's government template. */
    val TOPIC_BLOCKLIST = listOf(
        "blocked_topic:who should I vote for",
        "blocked_topic:sino ang dapat iboto",
        "blocked_topic:dapat kong iboto",
        "blocked_topic:sino ang iboboto",
        "blocked_topic:who should I vote",
        "blocked_topic:kinsa akong botohan",
        "blocked_topic:your opinion on",
        "blocked_topic:political opinion",
        "blocked_topic:is the president good",
        "not_legal_advice:sue",
        "not_legal_advice:legal advice",
        "not_legal_advice:can I be arrested",
        "not_medical_advice:diagnose",
        "not_medical_advice:prescribe",
        "not_financial_advice:should I invest",
    )

    private val CONDUCT = Regex(
        "\\b(allegations?|alleged(?:ly)?|accus(?:ed|ation|ations)|scandals?|anomal(?:y|ies|ous)|controvers(?:y|ies|ial)|corrupt(?:ion)?|kurakot|guilty|indict(?:ed|ment)|impeach(?:ed|ment))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val DEPARTURE = Regex(
        "\\b(resign(?:ed|s|ation|ations)?|nagbitiw|magbibitiw|pagbibitiw|nag-?resign|fired|sacked|ousted|quit|step(?:ped|s)? down|stepping down|tinanggal|sinibak)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val MOTIVE = Regex(
        "\\b(why|bakit|reasons?|dahilan|motives?|how come|what (?:led|caused|prompted)|behind)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Returns a refusal reason, or null when the question may go on. */
    fun checkInput(query: String): String? {
        if (PROMPT_PROBES.any { it.containsMatchIn(query) }) return "prompt_probe"
        for (entry in TOPIC_BLOCKLIST) {
            val reason = entry.substringBefore(':')
            val phrase = entry.substringAfter(':').trim()
            if (Regex("(^|\\W)${Regex.escape(phrase)}(\\W|$)", RegexOption.IGNORE_CASE).containsMatchIn(query)) return reason
        }
        if (CONDUCT.containsMatchIn(query) || (MOTIVE.containsMatchIn(query) && DEPARTURE.containsMatchIn(query))) {
            return "no_commentary"
        }
        return null
    }

    val REFUSALS = mapOf(
        "prompt_probe" to "I can't share my internal instructions or settings. I answer from official records about Philippine government services. Ask me about a service, a fee, a hotline or an office holder.",
        "blocked_topic" to "I don't give political opinions or voting advice. I can explain service steps, requirements, fees, and official records. (Hindi ako nagbibigay ng opinyong pampolitika.)",
        "not_legal_advice" to "I can't give legal advice. For legal help, contact the Public Attorney's Office or a licensed lawyer. I can still explain service steps and requirements.",
        "not_medical_advice" to "I can't give medical advice. For medical concerns, please consult a licensed healthcare professional.",
        "not_financial_advice" to "I can't give investment or financial advice. For financial planning, please consult a licensed advisor.",
        "no_commentary" to "I don't explain reasons, motives, or allegations about officials. I only show dated official records.",
    )

    // ---- Grounding ----------------------------------------------------------

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    // Filipino month names, as the model writes them in Tagalog, Cebuano, Ilocano and others ("30 Nobyembre 2026").
    private val FILIPINO_MONTHS = mapOf(
        "enero" to 1, "pebrero" to 2, "febrero" to 2, "marso" to 3, "abril" to 4, "mayo" to 5, "hunyo" to 6, "hulyo" to 7,
        "agosto" to 8, "setyembre" to 9, "septiyembre" to 9, "septyembre" to 9, "oktubre" to 10, "nobyembre" to 11,
        "disyembre" to 12,
    )
    private val MONTH_NAME =
        "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?|" +
            FILIPINO_MONTHS.keys.joinToString("|") + ")\\.?"
    private val DATE = Regex(
        "\\b(?:(\\d{4})-(\\d{2})-(\\d{2})" +
            "|(\\d{1,2})/(\\d{1,2})/(\\d{2,4})" +
            "|($MONTH_NAME)\\s+(\\d{1,2})(?:,?\\s+(\\d{4}))?" +
            "|(\\d{1,2})\\s+($MONTH_NAME)(?:,?\\s+(\\d{4}))?)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val NUMBER = Regex("\\d+(?:\\.\\d+)?")
    private val CURRENCY = Regex("(?:php|\\$|₱|peso[s]?|usd|eur|gbp)\\s*\\d+(?:[.,]\\d+)*", RegexOption.IGNORE_CASE)
    private val CALENDAR_WORDS = MONTHS + listOf(
        "january", "february", "march", "april", "june", "july", "august", "sept", "september", "october",
        "november", "december", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "lunes", "martes", "miyerkules", "miyerkoles", "huwebes", "biyernes", "sabado", "linggo",
    ) + FILIPINO_MONTHS.keys

    internal data class DateParts(val month: Int, val day: Int, val year: Int?)

    private fun monthIndex(name: String) =
        FILIPINO_MONTHS[name.lowercase().trimEnd('.')] ?: (MONTHS.indexOf(name.take(3).lowercase()) + 1)

    private fun parseDates(text: String): List<Pair<String, List<DateParts>>> = DATE.findAll(text).map { m ->
        val g = m.groupValues
        val readings = when {
            g[1].isNotEmpty() -> listOf(DateParts(g[2].toInt(), g[3].toInt(), g[1].toInt()))
            g[4].isNotEmpty() -> {
                val year = g[6].takeIf { it.length == 4 }?.toInt()
                listOf(DateParts(g[4].toInt(), g[5].toInt(), year), DateParts(g[5].toInt(), g[4].toInt(), year))
            }
            g[7].isNotEmpty() -> listOf(DateParts(monthIndex(g[7]), g[8].toInt(), g[9].toIntOrNull()))
            else -> listOf(DateParts(monthIndex(g[11]), g[10].toInt(), g[12].toIntOrNull()))
        }
        m.value to readings
    }.toList()

    private fun sameDate(a: DateParts, b: DateParts) =
        a.month == b.month && a.day == b.day && (a.year == null || b.year == null || a.year == b.year)

    private fun wordKey(w: String) = w.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
    private fun prefixKey(k: String) = if (k.length >= 5) k.take(5) else k

    /** What the retrieved records support. Built once per answer, not per token. */
    class Sources internal constructor(
        internal val numbers: Set<Double>,
        internal val dates: List<DateParts>,
        internal val known: Set<String>,
        internal val checkNames: Boolean = true,
    )

    /**
     * [checkNames] off skips the unknown-name check: it exists to stop an invented office holder, and elsewhere it
     * mostly caught Filipino words for what the English record says ("Pamantasan" for "University"). Amounts and dates
     * are always checked.
     */
    fun sources(records: List<KbRecord>, question: String, extraNames: List<String> = emptyList(), checkNames: Boolean = true): Sources {
        val corpus = records.joinToString("\n") { "${it.title}\n${it.content}" }.lowercase()
        val text = (records.map { "${it.title} ${it.content} ${it.keywords.joinToString(" ")}" } +
            question + extraNames + CALENDAR_WORDS).joinToString(" ")
        val known = HashSet<String>()
        for (w in text.split(Regex("[^\\p{L}\\p{N}'’.-]+"))) {
            val k = wordKey(w)
            if (k.isEmpty()) continue
            known += k
            known += prefixKey(k)
        }
        return Sources(
            numbers = NUMBER.findAll(corpus.replace(",", "")).map { it.value.toDouble() }.toSet(),
            dates = parseDates(corpus).flatMap { it.second },
            known = known,
            checkNames = checkNames,
        )
    }

    /** Lists every amount, date or name in [answer] that the sources do not contain. */
    fun ungrounded(answer: String, s: Sources): List<String> {
        val issues = mutableListOf<String>()
        for (m in CURRENCY.findAll(answer)) {
            val n = NUMBER.find(m.value.replace(",", ""))?.value?.toDouble() ?: continue
            if (n !in s.numbers) issues += "Amount \"${m.value}\" is not in the records"
        }
        for ((raw, readings) in parseDates(answer)) {
            if (readings.none { r -> s.dates.any { sameDate(r, it) } }) issues += "Date \"$raw\" is not in the records"
        }
        if (s.checkNames) for (name in unsourcedNames(answer, s.known)) issues += "Name \"$name\" is not in the records"
        return issues
    }

    // A capitalized word mid-sentence that no record, the question or the brand contains.
    private val NAME_CANDIDATE = Regex("(^|[^\\p{L}\\p{N}])([A-Z][\\p{L}\\p{N}'’.-]*)")
    private val SENTENCE_START = Regex("(^|\\n)\\s*$")
    private val AFTER_PUNCT = Regex("[.!?:(\"'“‘\\-–]\\s*$")

    private fun unsourcedNames(answer: String, known: Set<String>): List<String> =
        NAME_CANDIDATE.findAll(answer).mapNotNull { m ->
            val word = m.groupValues[2].trimEnd('.', '\'', '’', '-')
            val before = answer.substring(0, m.range.first + m.groupValues[1].length)
            when {
                SENTENCE_START.containsMatchIn(before) || AFTER_PUNCT.containsMatchIn(before) -> null
                word.none { it.isLowerCase() } -> null // acronym such as NBI or PHP
                wordKey(word).length < 2 -> null
                wordKey(word) in known || prefixKey(wordKey(word)) in known -> null
                else -> word
            }
        }.toList()

    /**
     * Forwards streamed text only up to the last whole word, and only while
     * that prefix passes the grounding check. The first unsourced value stops
     * the stream, so the screen never shows something the final check rejects.
     */
    class TokenGate(private val sources: Sources, private val onText: (String) -> Unit) {
        private val received = StringBuilder()
        private var forwarded = 0
        var halted = false
            private set

        fun accept(token: String) {
            if (halted) return
            received.append(token)
            val settledEnd = received.indexOfLast { it.isWhitespace() } + 1
            if (settledEnd <= forwarded) return
            val settled = received.substring(0, settledEnd)
            if (ungrounded(settled, sources).isNotEmpty()) {
                halted = true
                return
            }
            onText(settled.substring(forwarded))
            forwarded = settledEnd
        }
    }
}
