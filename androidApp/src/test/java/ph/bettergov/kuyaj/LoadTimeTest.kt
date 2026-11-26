package ph.bettergov.kuyaj

import org.junit.Assert.assertTrue
import org.junit.Test
import ph.bettergov.kuyaj.kb.KbRecord
import ph.bettergov.kuyaj.kb.SearchIndex
import java.io.File

/** Startup cost of the shipped records, and of one search, so growth that would freeze phones shows up here. */
class LoadTimeTest {
    @Test
    fun recordsLoadAndSearchQuickly() {
        val files = File("src/main/assets/kb").listFiles()!!.filter { it.name.endsWith(".json") }
        repeat(2) { // the second run is warm, closer to a phone's steady state
            val t0 = System.nanoTime()
            val records = files.flatMap { KbRecord.parseArray(it.readText()) }
            val t1 = System.nanoTime()
            val index = SearchIndex(records)
            val t2 = System.nanoTime()
            repeat(20) { index.search("Kailan ipinanganak si Rizal at magkano ang NBI clearance") }
            val t3 = System.nanoTime()
            println("records=${records.size} parse=${(t1 - t0) / 1_000_000}ms index=${(t2 - t1) / 1_000_000}ms search=${(t3 - t2) / 20_000_000}ms")
            assertTrue("index took ${(t2 - t1) / 1_000_000} ms", (t2 - t1) / 1_000_000 < 5_000)
        }
    }
}
