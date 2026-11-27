package ph.bettergov.kuyaj

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import ph.bettergov.kuyaj.chat.Orchestrator
import ph.bettergov.kuyaj.kb.KbRecord
import ph.bettergov.kuyaj.kb.KnowledgeStore
import java.io.File

/** A short follow-up is searched together with the question before it, on the shipped records. */
class FollowUpTest {
    private val records = File("src/main/assets/kb").listFiles()!!.filter { it.name.endsWith(".json") }
        .flatMap { KbRecord.parseArray(it.readText()) }

    private fun top(vararg turns: String): String = runBlocking {
        val o = Orchestrator(KnowledgeStore(records), llm = { null })
        turns.map { o.reply(it) }.last().sources.firstOrNull()?.title.orEmpty()
    }

    @Test
    fun aFragmentContinuesTheQuestionBeforeIt() {
        // Alone, "ng pilipinas" found any record with "Pilipinas" in it.
        assertTrue(top("sino president", "ng pilipinas").startsWith("President of the Philippines"))
        assertTrue(top("Magkano ang passport?", "how about the requirements?").contains("passport", ignoreCase = true))
        assertTrue(top("Paano kumuha ng NBI clearance?", "magkano?").contains("NBI"))
    }

    @Test
    fun aFullQuestionStartsItsOwnTopic() {
        assertTrue(top("Magkano ang NBI clearance?", "SSS hotline?").contains("SSS"))
        // One topic word, but it asks its own question: not a continuation of the DILG one.
        assertTrue(top("sino ang dilg secretary", "sino ang MGA senador").contains("senators"))
    }

    @Test
    fun noRecordAnswerOffersWhatTheRecordsHave() = runBlocking {
        // A model that searches for something the records lack, as v3 did with "SSS contribution rate".
        val model = object : ph.bettergov.kuyaj.llm.LlmEngine {
            override val isReady = true
            override suspend fun generate(system: String, user: String, image: ByteArray?, onText: (String) -> Unit) = "Wala akong record tungkol diyan."
            override suspend fun generateWithSearch(system: String, user: String, search: (String) -> String, onText: (String) -> Unit): String {
                search("SSS contribution rate"); return "Wala akong na-save na record tungkol sa SSS contribution rate."
            }
        }
        val r = Orchestrator(KnowledgeStore(records), llm = { model }).reply("magkano sss")
        assertTrue(r.text, r.text == Orchestrator.OFFER)
        // A "magkano" question leads with the fee records.
        assertTrue(r.suggestions.toString(), r.suggestions.first().contains("fee", ignoreCase = true) && r.suggestions.all { "SS" in it })
        println("OFFER " + r.suggestions)
    }

    @Test
    fun aTappedChoiceIsAnsweredFromThatRecordAlone() = runBlocking {
        var seen = ""
        val model = object : ph.bettergov.kuyaj.llm.LlmEngine {
            override val isReady = true
            override suspend fun generate(system: String, user: String, image: ByteArray?, onText: (String) -> Unit): String { seen = user; return "Filing is free." }
            override suspend fun generateWithSearch(system: String, user: String, search: (String) -> String, onText: (String) -> Unit) = "Wala akong record."
        }
        val o = Orchestrator(KnowledgeStore(records), llm = { model })
        o.reply("Fees and validity for an SS number")
        val r = o.reply("SSS pension claim: fees")
        assertTrue(r.sources.map { it.id }.toString(), r.sources.map { it.id } == listOf("bg-guide-sss-claim-what-it-costs"))
        // No other record and no earlier turn in what the model saw.
        assertTrue(seen, "SS number" !in seen && "RECENT CONVERSATION" !in seen && "Filing is free" in seen)
    }

    @Test
    fun thePresidentIsThePhilippinesNotAnOfficeOrAMarker() {
        assertTrue(top("Sino ang Pangulo ng Pilipinas?").startsWith("President of the Philippines"))
    }
}
