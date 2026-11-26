package ph.bettergov.kuyaj

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ph.bettergov.kuyaj.chat.Orchestrator
import ph.bettergov.kuyaj.chat.People
import ph.bettergov.kuyaj.chat.Reply
import ph.bettergov.kuyaj.kb.KbRecord
import ph.bettergov.kuyaj.kb.KnowledgeStore
import java.io.File

/** For births and deaths, code picks the NHCP marker that records them; the model answers from it. */
class PeopleTest {
    private val records = File("src/main/assets/kb").listFiles()!!.filter { it.name.endsWith(".json") }
        .flatMap { KbRecord.parseArray(it.readText()) }

    /** The marker picked: "Jose Rizal (1861–1896) | <marker text>". */
    private fun ask(q: String) = People.record(q, records)?.let { "${it.metadata["person"]} (${it.metadata["lifeYears"]}) | ${it.content}" }

    @Test
    fun rizalsBirthInFilipinoAndEnglish() {
        for (q in listOf("Kailan ipinanganak si Rizal?", "When was Jose Rizal born?", "kaarawan ni Rizal")) {
            val a = ask(q)!!
            assertTrue("$q -> $a", a.startsWith("Jose Rizal (1861–1896)") && Regex("19 (HUNYO|JUNE) 1861").containsMatchIn(a))
        }
    }

    @Test
    fun otherHeroes() {
        assertTrue(ask("Kailan ipinanganak si Andres Bonifacio?")!!.startsWith("Andres Bonifacio"))
        assertNull(ask("When was Apolinario Mabini born?")) // his marker has no birth sentence: search handles it
        assertTrue(ask("Kailan namatay si Rizal?")!!.contains("1896"))
    }

    @Test
    fun theSurnameWithTheMostMarkersWins() {
        // Paciano and Trinidad Rizal have markers too; "si Rizal" means José.
        assertTrue(ask("Kailan ipinanganak si Rizal?")!!.startsWith("Jose Rizal"))
        assertTrue(ask("Kailan ipinanganak si Paciano Rizal?")!!.startsWith("Paciano Rizal"))
    }

    @Test
    fun notABirthOrDeathQuestion() {
        assertNull(ask("Kailan ang Rizal Day?"))
        assertNull(ask("Magkano ang NBI clearance?"))
        assertNull(ask("When was Juan Dela Cruz born?")) // no marker
    }

    @Test
    fun personalSmallTalkIsAnsweredBeforeSearch() = runBlocking {
        val o = Orchestrator(KnowledgeStore(records), llm = { null })
        for (q in listOf("Kumusta ang araw mo?", "Mahal mo ba ako?")) {
            o.clear()
            assertEquals(q, Orchestrator.SMALL_TALK_REPLY, o.reply(q).text)
        }
        o.clear()
        assertEquals(Reply.Kind.RECORD, o.reply("Kailan ipinanganak si Rizal?").kind)
    }

    @Test
    fun someoneWithNoMarkerGetsNoRecordNotAHouse() = runBlocking {
        val o = Orchestrator(KnowledgeStore(records), llm = { null })
        // Search alone found the "Dela Fuente-Villaroman Ancestral House" marker on "dela".
        assertEquals(Reply.Kind.SYSTEM, o.reply("Kailan ipinanganak si Juan Dela Cruz?").kind)
        o.clear() // a marker that names the person still shows; so do non-history birth and death questions
        assertTrue(o.reply("Kailan ipinanganak si Mabini?").sources.first().title.contains("Mabini"))
        o.clear()
        assertTrue(o.reply("Anong taon ipinanganak si Lapu-Lapu?").sources.first().title.contains("Lapulapu"))
        o.clear()
        assertTrue(o.reply("Paano kumuha ng birth certificate?").sources.first().category != "history")
    }
}
