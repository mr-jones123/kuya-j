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
        assertNull(fact("bg-guide-passport-what-it-costs")) // two prices (regular and expedited): no single headline
        assertEquals(KeyFact("₱160", KeyFact.Kind.MONEY), fact("bg-guide-nbi-what-it-costs"))
        assertEquals(KeyFact("₱585", KeyFact.Kind.MONEY), fact("bg-guide-drivers-what-it-costs"))
        assertNull(fact("bg-guide-sec-what-it-costs")) // "a minimum of ₱2,000" is not the fee
        assertNull(fact("bg-guide-pagibig-what-it-costs")) // registration is free; "₱1,500 or less" is a threshold
        assertNull(fact("fee-005")) // the SS number page states no fee
    }

    @Test
    fun hotlinesShowTheNumberAndNeverAYear() {
        assertEquals("1455", fact("hot-005")?.value)
        assertEquals("911", fact("bg-hotline-national-emergency-hotline")?.value)
        assertEquals("8888", fact("hot-001")?.value)
        assertEquals("(02) 8523-8231", fact("hot-010")?.value)
        assertEquals("117", fact("bg-hotline-pnp")?.value)
        assertEquals("0285238231", fact("hot-010")?.dialable)
    }

    @Test
    fun holidaysShowASingleDateOnly() {
        assertEquals("20 March 2026", fact("bg-holiday-2026-03-20-eid-l-fitr-feast-of-ramadhan")?.value)
        assertEquals("9 April 2026", fact("bg-holiday-2026-04-09-araw-ng-kagitingan")?.value)
        assertNull(fact("bg-holidays-2026-regular"))
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
