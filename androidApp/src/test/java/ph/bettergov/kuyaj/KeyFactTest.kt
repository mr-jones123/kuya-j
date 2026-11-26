package ph.bettergov.kuyaj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ph.bettergov.kuyaj.kb.KbRecord
import ph.bettergov.kuyaj.ui.KeyFact
import ph.bettergov.kuyaj.ui.keyFact
import java.io.File

/** The large value on an answer card must come from the record: check it on every built-in record. */
class KeyFactTest {
    private val records = File("src/main/assets/kb").listFiles()!!.filter { it.name.endsWith(".json") }
        .flatMap { KbRecord.parseArray(it.readText()) }.associateBy { it.id }

    private fun fact(id: String) = keyFact(records.getValue(id))

    @Test
    fun feesShowTheAmount() {
        assertEquals(KeyFact("PHP 130", KeyFact.Kind.MONEY), fact("fee-002"))
        assertEquals(KeyFact("PHP 950", KeyFact.Kind.MONEY), fact("fee-011"))
        assertNull(fact("fee-005")) // the SS number page states no fee
    }

    @Test
    fun hotlinesShowTheNumberAndNeverAYear() {
        assertEquals("1455", fact("hot-005")?.value)
        assertEquals("911", fact("hot-002")?.value)
        assertEquals("8888", fact("hot-001")?.value)
        assertEquals("(02) 8523-8231", fact("hot-010")?.value)
        assertEquals("0285238231", fact("hot-010")?.dialable)
    }

    @Test
    fun holidaysShowASingleDateOnly() {
        assertEquals("20 March 2026", fact("hol-004")?.value)
        assertNull(fact("hol-001")) // a list of dates has no single headline
    }

    @Test
    fun everyFactAppearsInItsRecord() {
        for (r in records.values) {
            val f = keyFact(r) ?: continue
            if (f.kind == KeyFact.Kind.DATE) continue // reformatted from the ISO date in the title
            val text = r.title + " " + r.content.replace("Php", "PHP")
            assert(f.value in text) { "${r.id}: ${f.value} is not in the record" }
        }
    }
}
