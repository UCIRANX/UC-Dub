package com.uciran.ucdub.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** One finished pair: the spoken text and its translation. */
data class TranscriptEntry(val source: String, val translated: String, val time: Long)

/**
 * Saves every finished pair in the app's private storage, so it survives closing the app.
 * The file is cleared each time a new session starts.
 */
class TranscriptStore(context: Context) {
    private val file = File(context.filesDir, "transcripts.jsonl")

    @Synchronized
    fun clear() {
        runCatching { file.writeText("") }
    }

    @Synchronized
    fun append(entry: TranscriptEntry) {
        val line = JSONObject()
            .put("s", entry.source)
            .put("t", entry.translated)
            .put("ts", entry.time)
            .toString()
        runCatching { file.appendText(line + "\n") }
    }

    @Synchronized
    fun readAll(): List<TranscriptEntry> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                TranscriptEntry(o.optString("s"), o.optString("t"), o.optLong("ts"))
            }.getOrNull()
        }
    }
}
