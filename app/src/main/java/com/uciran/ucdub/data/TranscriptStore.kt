package com.uciran.ucdub.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** One sentence pair: the spoken text and its translation. */
data class TranscriptEntry(val source: String, val translated: String, val time: Long)

/**
 * Saves the transcript in the app's private storage so it survives closing the app.
 * - [transcripts.jsonl] holds finished sentences.
 * - [current.json] holds the sentence being spoken right now, rewritten as it changes,
 *   so the history screen shows text while it is still being translated.
 * The whole history is cleared each time a new session starts.
 */
class TranscriptStore(context: Context) {
    private val file = File(context.filesDir, "transcripts.jsonl")
    private val currentFile = File(context.filesDir, "current.json")

    @Synchronized
    fun clear() {
        runCatching { file.writeText("") }
        runCatching { currentFile.delete() }
    }

    @Synchronized
    fun append(entry: TranscriptEntry) {
        runCatching { file.appendText(toJson(entry).toString() + "\n") }
    }

    /** Replaces the in-progress sentence. Pass null when it has been saved as finished. */
    @Synchronized
    fun saveCurrent(entry: TranscriptEntry?) {
        if (entry == null) {
            runCatching { currentFile.delete() }
        } else {
            runCatching { currentFile.writeText(toJson(entry).toString()) }
        }
    }

    /** All finished sentences, followed by the one in progress (if any). */
    @Synchronized
    fun readAll(): List<TranscriptEntry> {
        val list = mutableListOf<TranscriptEntry>()
        if (file.exists()) {
            file.readLines().forEach { line ->
                runCatching { fromJson(JSONObject(line)) }.getOrNull()?.let { list.add(it) }
            }
        }
        if (currentFile.exists()) {
            runCatching { fromJson(JSONObject(currentFile.readText())) }.getOrNull()?.let { list.add(it) }
        }
        return list
    }

    private fun toJson(e: TranscriptEntry): JSONObject =
        JSONObject().put("s", e.source).put("t", e.translated).put("ts", e.time)

    private fun fromJson(o: JSONObject): TranscriptEntry =
        TranscriptEntry(o.optString("s"), o.optString("t"), o.optLong("ts"))
}
