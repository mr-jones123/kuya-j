package ph.bettergov.kuyaj

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Debug builds only. Runs every line of files/bench.txt through Orchestrator.reply(), each as a new chat, and logs one
 * JSON line per question under KuyaJ.Bench; the engine's own lines (KuyaJ.Llm) carry first-token time and tokens/s.
 *
 *     adb push questions.txt /sdcard/Android/data/ph.bettergov.kuyaj/files/bench.txt
 *     adb shell am broadcast -n ph.bettergov.kuyaj/.BenchReceiver
 *
 * Keep the app open while it runs. See docs/development.md.
 */
class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as KuyaJApp).container
        val conversations = File(context.getExternalFilesDir(null), "conversations.jsonl")
        if (conversations.exists()) return run { c.scope.launch { converse(context, c, conversations) } }
        c.scope.launch {
            val questions = File(context.getExternalFilesDir(null), "bench.txt").readLines().filter { it.isNotBlank() }
            repeat(300) { if (!c.engine.isReady) delay(1000) } // the model may still be loading
            Log.i(TAG, JSONObject().put("event", "begin").put("model", c.models.variant.id).put("backend", c.engine.activeBackend)
                .put("ready", c.engine.isReady).put("questions", questions.size).toString())
            questions.forEachIndexed { i, q ->
                c.orchestrator.clear()
                val t0 = System.currentTimeMillis()
                var first = -1L
                Log.i(TAG, JSONObject().put("event", "start").put("i", i).toString())
                val r = runCatching { c.orchestrator.reply(q) { if (first < 0) first = System.currentTimeMillis() - t0 } }
                Log.i(TAG, JSONObject().put("event", "end").put("i", i).put("q", q).put("ms", System.currentTimeMillis() - t0)
                    .put("shownFirstMs", first).put("kind", r.getOrNull()?.kind?.name ?: "ERROR")
                    .put("note", r.getOrNull()?.note ?: r.exceptionOrNull()?.message)
                    .put("answer", r.getOrNull()?.text.orEmpty().take(600)).toString())
            }
            Log.i(TAG, JSONObject().put("event", "finish").toString())
        }
    }

    /**
     * Multi-turn mode: files/conversations.jsonl (the format of src/test/resources/conversations.jsonl) is run
     * conversation by conversation, turns in order in one chat. Replies go to files/conversations.out.jsonl, which
     * kuya-j-evals/evals/conversations_device.py scores with the same checks as ConversationEval.
     */
    private suspend fun converse(context: Context, c: AppContainer, file: File) {
        val out = File(context.getExternalFilesDir(null), "conversations.out.jsonl").apply { delete() }
        repeat(300) { if (!c.engine.isReady) delay(1000) }
        Log.i(TAG, JSONObject().put("event", "begin").put("backend", c.engine.activeBackend).put("ready", c.engine.isReady).toString())
        for (line in file.readLines().filter { it.isNotBlank() }) {
            val conv = JSONObject(line)
            val turns = conv.getJSONArray("turns")
            c.orchestrator.clear()
            for (i in 0 until turns.length()) {
                val user = turns.getJSONObject(i).getString("user")
                val t0 = System.currentTimeMillis()
                var first = -1L
                val r = runCatching { c.orchestrator.reply(user) { if (first < 0) first = System.currentTimeMillis() - t0 } }
                out.appendText(JSONObject().put("id", conv.getString("id")).put("turn", i).put("user", user)
                    .put("reply", r.getOrNull()?.text ?: "ERROR: ${r.exceptionOrNull()?.message}")
                    .put("kind", r.getOrNull()?.kind?.name ?: "SYSTEM").put("note", r.getOrNull()?.note ?: JSONObject.NULL)
                    .put("sources", org.json.JSONArray(r.getOrNull()?.sources?.map { it.id }.orEmpty()))
                    .put("suggestions", org.json.JSONArray(r.getOrNull()?.suggestions.orEmpty()))
                    .put("ms", System.currentTimeMillis() - t0).put("firstMs", first).toString() + "\n")
                Log.i(TAG, JSONObject().put("event", "turn").put("id", conv.getString("id")).put("turn", i).toString())
            }
        }
        Log.i(TAG, JSONObject().put("event", "finish").toString())
    }

    private companion object { const val TAG = "KuyaJ.Bench" }
}
