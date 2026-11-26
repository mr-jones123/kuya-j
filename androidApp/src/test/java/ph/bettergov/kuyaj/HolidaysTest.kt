package ph.bettergov.kuyaj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ph.bettergov.kuyaj.chat.Holidays
import ph.bettergov.kuyaj.kb.KbRecord
import ph.bettergov.kuyaj.ui.KeyFact
import ph.bettergov.kuyaj.ui.statedIn
import java.io.File

/** Holiday dates are answered by code from the records: check names, years, "next" and what isn't a holiday question. */
class HolidaysTest {
    private val records = File("src/main/assets/kb").listFiles()!!.filter { it.name.endsWith(".json") }
        .flatMap { KbRecord.parseArray(it.readText()) }

    private fun date(q: String, today: String = "2026-11-26") = Holidays.answer(q, records, today)?.record?.title?.substringAfter(": ")?.take(10)

    @Test
    fun filipinoAndEnglishNamesFindTheNextOne() {
        assertEquals("2027-06-12", date("kailan ang araw ng kalayaan"))   // June 2026 has passed
        assertEquals("2026-06-12", date("kailan ang araw ng kalayaan", today = "2026-03-01"))
        assertEquals("2026-12-25", date("Pasko"))
        assertEquals("2026-12-24", date("kailan ang Bisperas ng Pasko?"))  // the longer name wins over "pasko"
        assertEquals("2027-11-01", date("when is Undas"))
        assertEquals("2026-11-30", date("Kanus-a ang Bonifacio Day?"))
    }

    @Test
    fun theYearAskedWins() {
        assertEquals("2025-12-25", date("Pasko 2025"))
        assertNull(date("Pasko 2030")) // no proclamation on record: the normal path says so
    }

    @Test
    fun answerGivesTheFilipinoDateAndTheRecord() {
        val a = Holidays.answer("araw ng kalayaan 2026", records, "2026-11-26")!!
        assertTrue(a.text, a.text.startsWith("Independence Day (Araw ng Kalayaan): Biyernes, 12 Hunyo 2026 (regular holiday)."))
        assertTrue(a.text.contains("Source: "))
    }

    @Test
    fun otherQuestionsThatNameAHolidayGoToSearch() {
        assertNull(date("christmas bonus requirements for employees"))
        assertNull(date("magkano ang NBI clearance?"))
    }

    @Test
    fun aHeadlineOnlyWhenTheAnswerStatesIt() {
        val day = KeyFact("9 April 2027", KeyFact.Kind.DATE)
        assertTrue(day.statedIn("Ang Araw ng Kagitingan ay sa 9 Abril 2027."))
        assertTrue(!day.statedIn("Walang naitalang record tungkol sa petsa ng Araw ng Kalayaan."))
        assertTrue(KeyFact("₱160", KeyFact.Kind.MONEY).statedIn("Ang NBI clearance ay ₱160 lahat."))
    }
}
