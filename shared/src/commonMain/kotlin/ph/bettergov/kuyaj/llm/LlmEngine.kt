package ph.bettergov.kuyaj.llm

/** The answer model. The orchestrator only knows this interface, so tests use a fake. */
interface LlmEngine {
    val isReady: Boolean

    /** Streams text through [onText] and returns the full reply. [image] is JPEG or PNG bytes. */
    suspend fun generate(system: String, user: String, image: ByteArray? = null, onText: (String) -> Unit = {}): String

    /**
     * Tool mode: the model may call search_kb; [search] runs each call (English keywords in, records text out).
     * Returns null when this model wasn't trained to search, so the caller searches for it instead.
     */
    suspend fun generateWithSearch(system: String, user: String, search: (String) -> String, onText: (String) -> Unit = {}): String? = null
}
