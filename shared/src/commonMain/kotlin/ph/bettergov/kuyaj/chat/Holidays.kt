package ph.bettergov.kuyaj.chat

import ph.bettergov.kuyaj.kb.KbRecord

/**
 * Holiday dates are fixed facts, so code answers them like a greeting: no model, no search ranking to get wrong.
 * "kailan ang araw ng kalayaan", "Pasko 2026", "when is Undas" -> that holiday's record for the year asked, or the
 * next one coming. Names come from the record titles: "Independence Day (Araw ng Kalayaan): 2026-06-12 (regular holiday)".
 */
object Holidays {
    data class Answer(val text: String, val record: KbRecord)

    private val TITLE = Regex("""^(.+?): (\d{4})-(\d{2})-(\d{2}) \(([^)]+)\)$""")
    private val YEAR = Regex("""\b(20\d{2})\b""")
    // Asking when, in Tagalog, Cebuano, Ilocano, Hiligaynon, Waray, Bikol, Kapampangan and English.
    private val WHEN = Regex(
        """\b(kailan|kelan|kanus-?a|anus-?a|kaano|san-?o|sano|nu kaanu|kapilan|when|date|petsa|anong araw|unsang adlaw|what day|holiday|walang pasok)\b""",
    )
    private val FILLER = setOf("ang", "ng", "sa", "ba", "po", "ay", "the", "is", "a", "an", "of", "in", "si", "ni", "kay", "this", "year", "ngayon", "ngayong", "next", "sunod", "sunod nga")
    private val WEEKDAYS = listOf("Lunes", "Martes", "Miyerkules", "Huwebes", "Biyernes", "Sabado", "Linggo")
    private val MONTHS = listOf("Enero", "Pebrero", "Marso", "Abril", "Mayo", "Hunyo", "Hulyo", "Agosto", "Setyembre", "Oktubre", "Nobyembre", "Disyembre")

    private fun norm(s: String) = s.lowercase().replace(Regex("""['’]"""), "").replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** Every name in a title: "Independence Day (Araw ng Kalayaan)" -> independence day, araw ng kalayaan. */
    private fun names(label: String): List<String> =
        (listOf(label.substringBefore(" (")) + Regex("""\(([^)]+)\)""").findAll(label).flatMap { it.groupValues[1].split(", ") })
            .map(::norm).filter { n -> n.length >= 3 && n.any(Char::isLetter) && !Regex("""^\d+(st|nd|rd|th)$""").matches(n) }

    /** [today] is the phone's date as YYYY-MM-DD, or empty if unknown. */
    fun answer(question: String, records: List<KbRecord>, today: String): Answer? {
        val q = norm(question)
        val dated = records.filter { it.category == "holidays" }.mapNotNull { r ->
            TITLE.find(r.title)?.let { m -> Triple(r, names(m.groupValues[1]), "${m.groupValues[2]}-${m.groupValues[3]}-${m.groupValues[4]}") }
        }
        // The longest name in the question wins: "bisperas ng pasko" over "pasko".
        val name = dated.flatMap { it.second }.filter { Regex("""(^| )${Regex.escape(it)}( |$)""").containsMatchIn(q) }
            .maxByOrNull { it.length } ?: return null
        val leftover = q.replace(name, " ").replace(YEAR, " ").split(" ").filter { it.isNotBlank() && it !in FILLER }
        if (!WHEN.containsMatchIn(q) && leftover.size > 1) return null // names a holiday but asks something else
        val year = YEAR.find(q)?.value
        val matches = dated.filter { name in it.second }.sortedBy { it.third }
        val pick = if (year != null) matches.firstOrNull { it.third.startsWith(year) } ?: return null
        else matches.firstOrNull { today.isNotEmpty() && it.third >= today } ?: matches.lastOrNull() ?: return null
        val (record, _, date) = pick
        val (y, m, d) = date.split("-").map { it.toInt() }
        val type = TITLE.find(record.title)!!.groupValues[5]
        val label = TITLE.find(record.title)!!.groupValues[1]
        val line = "$label: ${weekday(y, m, d)}, $d ${MONTHS[m - 1]} $y ($type)."
        return Answer("$line\n\n${record.content}", record)
    }

    /** Day of the week for a date (Sakamoto's method), in Filipino. */
    private fun weekday(y: Int, m: Int, d: Int): String {
        val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        val yy = if (m < 3) y - 1 else y
        val sundayFirst = (yy + yy / 4 - yy / 100 + yy / 400 + t[m - 1] + d) % 7 // 0 = Sunday
        return WEEKDAYS[(sundayFirst + 6) % 7]
    }
}
