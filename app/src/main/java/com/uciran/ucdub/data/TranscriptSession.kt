package com.uciran.ucdub.data

/**
 * Builds sentences from the streamed transcript pieces and saves each finished pair.
 * Pieces may arrive as increments or as the full text so far; both are handled.
 */
class TranscriptSession(private val store: TranscriptStore) {
    private val source = StringBuilder()
    private val translated = StringBuilder()
    private var sourceDone = false
    private var translatedDone = false
    private val recent = ArrayDeque<TranscriptEntry>()

    @Synchronized
    fun reset() {
        source.clear()
        translated.clear()
        sourceDone = false
        translatedDone = false
        recent.clear()
    }

    @Synchronized
    fun onSource(text: String, finished: Boolean) {
        // A new spoken sentence started before the previous translation finished: save what we have.
        if (sourceDone && text.isNotBlank()) commit()
        merge(source, text)
        if (finished) sourceDone = true
        if (sourceDone && translatedDone) commit()
    }

    @Synchronized
    fun onTranslated(text: String, finished: Boolean) {
        if (translatedDone && text.isNotBlank()) commit()
        merge(translated, text)
        if (finished) translatedDone = true
        if (sourceDone && translatedDone) commit()
    }

    /** Saves whatever is still in progress (called when the session stops). */
    @Synchronized
    fun flush() = commit()

    /** Recent saved pairs followed by the sentence currently being spoken. */
    @Synchronized
    fun snapshot(): List<TranscriptEntry> {
        val list = recent.toMutableList()
        pending()?.let { list.add(it) }
        return list
    }

    private fun pending(): TranscriptEntry? {
        val s = source.toString().trim()
        val t = translated.toString().trim()
        return if (s.isEmpty() && t.isEmpty()) null
        else TranscriptEntry(s, t, System.currentTimeMillis())
    }

    private fun commit() {
        pending()?.let { entry ->
            store.append(entry)
            recent.addLast(entry)
            while (recent.size > RECENT) recent.removeFirst()
        }
        source.clear()
        translated.clear()
        sourceDone = false
        translatedDone = false
    }

    private fun merge(buf: StringBuilder, text: String) {
        if (text.isEmpty()) return
        val current = buf.toString()
        if (current.isNotEmpty() && text.startsWith(current)) {
            buf.setLength(0)
            buf.append(text)
        } else {
            buf.append(text)
        }
    }

    companion object {
        private const val RECENT = 30
    }
}
