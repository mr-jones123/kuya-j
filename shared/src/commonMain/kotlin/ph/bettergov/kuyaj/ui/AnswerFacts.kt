package ph.bettergov.kuyaj.ui

import ph.bettergov.kuyaj.kb.KbRecord

/** The one value an answer card shows large: a fee, a hotline number or a date. */
data class KeyFact(val value: String, val kind: Kind) {
    enum class Kind { MONEY, PHONE, DATE }

    /** Digits only, for a tel: link. */
    val dialable: String get() = value.filter { it.isDigit() || it == '+' }
}

private val MONEY = Regex("""(?:PHP|Php|₱)\s?\d[\d,]*(?:\.\d{2})?""")
// Short codes (911, 1455, 8888, 1-6565) and landlines/mobiles like (02) 8523-8231 or 0939 150 2880.
private val PHONE = Regex("""\(0\d{1,2}\)\s?\d{3,4}[- ]?\d{4}|\b09\d{2}[- ]?\d{3}[- ]?\d{4}\b|\b1-\d{4}\b|\b\d{3,4}\b""")
private val ISO_DATE = Regex("""\b(20\d{2})-(\d{2})-(\d{2})\b""")
private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/**
 * Picks the headline value for an answer from its record, never from the model's text alone:
 * the card must not show a number the record doesn't contain. Returns null when there's no single clear value.
 */
fun keyFact(record: KbRecord): KeyFact? = when (record.category) {
    // Only when the first sentence states one amount as the fee: "₱160 total." or "...the fee is PHP 130."
    // Other amounts are often minimums, caps or one of several prices (SEC's "minimum of ₱2,000"), not the fee.
    "fees" -> firstSentence(record.content).let { s ->
        MONEY.findAll(s).toList().singleOrNull()
            ?.takeIf { it.range.first == 0 || s.substring(0, it.range.first).trimEnd().endsWith("fee is", ignoreCase = true) }
            ?.let { KeyFact(it.value.replace("Php", "PHP"), KeyFact.Kind.MONEY) }
    }
    // The title names the main number ("SSS hotline 1455"); fall back to the first full number in the text.
    "hotlines" -> (PHONE.find(record.title.replace(Regex("""\b20\d{2}\b"""), ""))
        ?: PHONE.find(record.content.replace(ISO_DATE, "")).takeIf { it != null && it.value.length >= 7 })
        ?.let { KeyFact(it.value, KeyFact.Kind.PHONE) }
    "holidays" -> ISO_DATE.find(record.title)?.let { KeyFact(longDate(it), KeyFact.Kind.DATE) }
    else -> null
}

private fun firstSentence(text: String): String =
    text.removePrefix("What it costs:").trim().split(Regex("""(?<=[.!?])\s+(?=[A-Z])""")).first()

/** 2026-03-20 -> "20 March 2026", the order Philippine agencies write dates in. */
private fun longDate(m: MatchResult): String {
    val (y, mo, d) = m.destructured
    return "${d.toInt()} ${MONTHS[mo.toInt() - 1]} $y"
}
