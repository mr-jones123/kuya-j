package ph.bettergov.kuyaj.chat

import ph.bettergov.kuyaj.kb.KbRecord
import ph.bettergov.kuyaj.kb.SearchIndex

/**
 * For "Kailan ipinanganak si Rizal?", code picks the NHCP marker that records the birth (or death) of the person
 * named, so the model answers from the right record: search alone ranks dozens of Rizal markers by word overlap.
 * Person markers carry metadata.person ("Jose Rizal"), lifeYears and personMarkers (from tools/records/harvest_nhcp.py).
 */
object People {
    private val BIRTH_Q = Regex("""\b(born|birth|birthday|ipinanganak|isinilang|sinilang|kapanganakan|kaarawan|natawo|gipanganak|nayanak|kaadlawan)\b""")
    private val DEATH_Q = Regex("""\b(died|death|die|namatay|kamatayan|pumanaw|binaril|pinatay|nasawi|binitay|executed|namatyan|natay)\b""")
    private val BIRTH = Regex("""ISINILANG|IPINANGANAK|SINILANG|BORN|NATAWO|KAPANGANAKAN""", RegexOption.IGNORE_CASE)
    private val DEATH = Regex("""NAMATAY|PUMANAW|BINARIL|PINATAY|NASAWI|BINITAY|EXECUTED|DIED|KAMATAYAN|MARTYRED""", RegexOption.IGNORE_CASE)
    private val SMALL = setOf("y", "de", "del", "dela", "delos", "los", "ma", "jr", "sr", "ii", "iii", "dr", "gat", "heneral")

    private fun words(s: String) = SearchIndex.tokenize(s).filter { it !in SMALL }.toSet()

    // Words that may stand before a surname in a question without being a first name: "si Rizal", "when was Rizal born".
    private val NOT_A_NAME = setOf("si", "ni", "kay", "kailan", "kelan", "when", "was", "were", "did", "ipinanganak", "isinilang",
        "sinilang", "namatay", "pumanaw", "born", "died", "kaarawan", "kapanganakan", "kamatayan", "birthday", "death", "birth",
        "kanus", "kanusa", "anus", "ano", "petsa", "date", "of", "ang")

    /** True if every name-like word just before a matched surname in the question belongs to this person's name. */
    private fun firstNamesFit(question: String, name: Set<String>): Boolean {
        val q = SearchIndex.tokenize(question).filter { it !in SMALL }
        return q.indices.filter { q[it] in name }.all { i -> i == 0 || q[i - 1] in name || q[i - 1] in NOT_A_NAME }
    }

    fun isLifeQuestion(question: String) = question.lowercase().let { BIRTH_Q.containsMatchIn(it) || DEATH_Q.containsMatchIn(it) }

    /**
     * For a birth or death question code couldn't answer: true if this title names someone the question names. Without
     * it, "Kailan ipinanganak si Juan Dela Cruz?" matched the "Dela Fuente-Villaroman Ancestral House" marker on "dela".
     * A prefix counts, so "Lapu-Lapu" names "Lapulapu".
     */
    fun names(question: String, title: String): Boolean {
        val asked = words(question).filter { it !in NOT_A_NAME && it.length >= 3 && !BIRTH_Q.matches(it) && !DEATH_Q.matches(it) }
        val t = words(title)
        return asked.any { a -> t.any { it.startsWith(a) } }
    }

    fun record(question: String, records: List<KbRecord>): KbRecord? {
        val q = question.lowercase()
        val birth = BIRTH_Q.containsMatchIn(q)
        if (!birth && !DEATH_Q.containsMatchIn(q)) return null
        val asked = words(question)
        // People whose surname (any name word after the first) the question names; most words matched, then most markers.
        val people = records.filter { it.metadata["person"] is String }.groupBy { words(it.metadata["person"] as String).joinToString(" ") }
        val best = people.entries.mapNotNull { (_, recs) ->
            val name = words(recs.first().metadata["person"] as String).toList()
            val hit = name.count { it in asked }
            if (name.drop(1).none { it in asked } || hit == 0 || !firstNamesFit(question, name.toSet())) null
            else Triple(recs, hit, recs.sumOf { (it.metadata["personMarkers"] as? Number)?.toInt() ?: 1 })
        }.maxWithOrNull(compareBy({ it.second }, { it.third })) ?: return null
        val want = if (birth) BIRTH else DEATH
        return best.first.firstOrNull { r ->
            val marker = r.content.substringAfter("Marker text:\n").substringBeforeLast("\nSource:")
            want.containsMatchIn(marker)
        }
    }
}
