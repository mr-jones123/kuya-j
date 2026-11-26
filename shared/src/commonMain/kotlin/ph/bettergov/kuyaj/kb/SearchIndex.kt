package ph.bettergov.kuyaj.kb

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * BM25 over title, keywords and content, with the same boosts, prefix and
 * fuzzy rules Airgap gives MiniSearch (title 2, keywords 3, content 1,
 * fuzzy 0.2, prefix on).
 */
class SearchIndex(
    private val records: List<KbRecord>,
    private val boosts: Map<Field, Double> = mapOf(Field.TITLE to 2.0, Field.KEYWORDS to 3.0, Field.CONTENT to 1.0),
    private val fuzzy: Double = 0.2,
) {
    enum class Field { TITLE, KEYWORDS, CONTENT }

    data class Hit(val record: KbRecord, val score: Double)

    // field -> term -> (record index -> term frequency)
    private val postings = Field.entries.associateWith { HashMap<String, HashMap<Int, Int>>() }
    private val lengths = Field.entries.associateWith { IntArray(records.size) }
    private val avgLength: Map<Field, Double>
    private val vocabulary: Set<String>

    init {
        records.forEachIndexed { i, r ->
            for (field in Field.entries) {
                val tokens = tokenize(textOf(r, field))
                lengths.getValue(field)[i] = tokens.size
                val fieldPostings = postings.getValue(field)
                for (t in tokens) fieldPostings.getOrPut(t) { HashMap() }.let { it[i] = (it[i] ?: 0) + 1 }
            }
        }
        avgLength = Field.entries.associateWith { f ->
            lengths.getValue(f).average().takeIf { it > 0 } ?: 1.0
        }
        vocabulary = postings.values.flatMap { it.keys }.toSet()
    }

    fun search(query: String, limit: Int = 3): List<Hit> {
        val scores = HashMap<Int, Double>()
        for (q in tokenize(query).distinct()) {
            for ((term, weight) in expand(q)) {
                for (field in Field.entries) {
                    val docs = postings.getValue(field)[term] ?: continue
                    val idf = ln(1 + (records.size - docs.size + 0.5) / (docs.size + 0.5))
                    for ((doc, tf) in docs) {
                        val norm = 1.2 * (1 - 0.7 + 0.7 * lengths.getValue(field)[doc] / avgLength.getValue(field))
                        val bm25 = idf * (tf * 2.2) / (tf + norm)
                        scores[doc] = (scores[doc] ?: 0.0) + bm25 * weight * boosts.getValue(field)
                    }
                }
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(limit).map { Hit(records[it.key], it.value) }
    }

    // Exact match counts fully; prefix and typo matches count less, as in MiniSearch.
    // ponytail: scans the whole vocabulary per query term, fine for a few thousand records; add a trie if bundles grow past that.
    private fun expand(q: String): Map<String, Double> {
        val out = HashMap<String, Double>()
        if (q in vocabulary) out[q] = 1.0
        val maxEdits = (q.length * fuzzy).roundToInt()
        for (term in vocabulary) {
            if (term == q) continue
            if (term.startsWith(q)) out.keepMax(term, 0.375 * q.length / term.length)
            if (maxEdits > 0 && kotlin.math.abs(term.length - q.length) <= maxEdits) {
                val d = editDistance(q, term, maxEdits)
                if (d <= maxEdits) out.keepMax(term, 0.45 * q.length / (q.length + d))
            }
        }
        return out
    }

    companion object {
        private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

        fun tokenize(text: String): List<String> =
            text.lowercase().split(SPLIT).filter { it.isNotEmpty() }

        private fun textOf(r: KbRecord, field: Field) = when (field) {
            Field.TITLE -> r.title
            Field.KEYWORDS -> r.keywords.joinToString(" ")
            Field.CONTENT -> r.content
        }

        /** Levenshtein distance, stopping early once it passes [max]. */
        fun editDistance(a: String, b: String, max: Int): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                var rowMin = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                    rowMin = minOf(rowMin, cur[j])
                }
                if (rowMin > max) return max + 1
                prev = cur
            }
            return prev[b.length]
        }
    }
}

private fun HashMap<String, Double>.keepMax(key: String, value: Double) {
    this[key] = maxOf(this[key] ?: value, value)
}
