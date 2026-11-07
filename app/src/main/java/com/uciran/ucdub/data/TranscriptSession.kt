package com.uciran.ucdub.data

/**
 * Builds sentence pairs from the streamed transcript pieces.
 * Every change is written to the store immediately. A pair is finished when both sides
 * report it finished, when the next sentence starts, or after a short pause.
 * Pieces may arrive as increments or as the full text so far; both are handled.
 */
class TranscriptSession(private val store: TranscriptStore) {
    private val source = StringBuilder()
    private val translated = StringBuilder()
    private var sourceDone = false
    private var translatedDone = false
    private var startedAt = 0L
    private var lastUpdate = 0L
    private val recent = ArrayDeque<TranscriptEntry>()

    @Synchronized
    fun reset() {
        source.clear()
        translated.clear()
        sourceDone = false
        translatedDone = false
        startedAt = 0L
        lastUpdate = 0L
        recent.clear()
    }

    @Synchronized
    fun onSource(text: String, finished: Boolean) {
        if (text.isNotBlank()) {
            if (sourceDone || isIdle()) commit()
            merge(source, text)
            touch()
            if (source.length > MAX_CHARS) commit()
        }
        if (finished) sourceDone = true
        afterUpdate()
    }

    @Synchronized
    fun onTranslated(text: String, finished: Boolean) {
        if (text.isNotBlank()) {
            if (translatedDone || isIdle()) commit()
            merge(translated, text)
            touch()
            if (translated.length > MAX_CHARS) commit()
        }
        if (finished) translatedDone = true
        afterUpdate()
    }

    /** Saves whatever is still in progress (called when the session stops). */
    @Synchronized
    fun flush() = commit()

    /** Recent finished pairs followed by the sentence currently being spoken. */
    @Synchronized
    fun snapshot(): List<TranscriptEntry> {
        val list = recent.toMutableList()
        pending()?.let { list.add(it) }
        return list
    }

    private fun isIdle(): Boolean =
        pending() != null && System.currentTimeMillis() - lastUpdate > IDLE_MS

    private fun touch() {
        val now = System.currentTimeMillis()
        if (startedAt == 0L) startedAt = now
        lastUpdate = now
    }

    private fun afterUpdate() {
        if (sourceDone && translatedDone) commit() else store.saveCurrent(pending())
    }

    private fun pending(): TranscriptEntry? {
        val s = source.toString().trim()
        val t = translated.toString().trim()
        if (s.isEmpty() && t.isEmpty()) return null
        return TranscriptEntry(s, t, if (startedAt == 0L) System.currentTimeMillis() else startedAt)
    }

    private fun commit() {
        pending()?.let { entry ->
            store.append(entry)
            recent.addLast(entry)
            while (recent.size > RECENT) recent.removeFirst()
        }
        store.saveCurrent(null)
        source.clear()
        translated.clear()
        sourceDone = false
        translatedDone = false
        startedAt = 0L
    }

    private fun merge(buf: StringBuilder, text: String) {
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
        private const val IDLE_MS = 2500L
        /** Longest chunk shown at once; a longer piece is saved and the next chunk replaces it. */
        private const val MAX_CHARS = 60
    }
}
