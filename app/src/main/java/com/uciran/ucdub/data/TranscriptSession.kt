package com.uciran.ucdub.data

import android.os.Handler
import android.os.Looper

/**
 * Builds sentence pairs from the streamed transcript pieces.
 * Saves the history: a sentence pair is written to the store once it is complete
 * (both sides finished, too long, or a short pause). The subtitle does not use this.
 * Pieces may arrive as increments or as the full text so far; both are handled.
 */
class TranscriptSession(private val store: TranscriptStore) {
    private val source = StringBuilder()
    private val translated = StringBuilder()
    private var sourceDone = false
    private var translatedDone = false
    private var startedAt = 0L
    private val main = Handler(Looper.getMainLooper())
    private val pauseCommit = Runnable { flushPause() }

    @Synchronized
    fun reset() {
        main.removeCallbacks(pauseCommit)
        source.clear()
        translated.clear()
        sourceDone = false
        translatedDone = false
        startedAt = 0L
    }

    @Synchronized
    fun onSource(text: String, finished: Boolean) {
        if (text.isNotBlank()) {
            if (sourceDone) commit()
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
            if (translatedDone) commit()
            merge(translated, text)
            touch()
            if (translated.length > MAX_CHARS) commit()
        }
        if (finished) translatedDone = true
        afterUpdate()
    }

    /** Saves whatever is still in progress (called when the session stops). */
    @Synchronized
    fun flush() {
        main.removeCallbacks(pauseCommit)
        commit()
    }

    /** Pieces currently being spoken or translated, plus the saved history is kept in the store. */
    @Synchronized
    fun pendingEntry(): TranscriptEntry? = pending()

    private fun flushPause() {
        synchronized(this) { commit() }
    }

    private fun touch() {
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
    }

    private fun afterUpdate() {
        if (sourceDone && translatedDone) {
            commit()
        } else {
            store.saveCurrent(pending())
            main.removeCallbacks(pauseCommit)
            if (pending() != null) main.postDelayed(pauseCommit, IDLE_MS)
        }
    }

    private fun pending(): TranscriptEntry? {
        val s = source.toString().trim()
        val t = translated.toString().trim()
        if (s.isEmpty() && t.isEmpty()) return null
        return TranscriptEntry(s, t, if (startedAt == 0L) System.currentTimeMillis() else startedAt)
    }

    private fun commit() {
        main.removeCallbacks(pauseCommit)
        pending()?.let { entry ->
            store.append(entry)
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
        private const val IDLE_MS = 900L
        /** Longest chunk kept together; a longer piece is saved and the next one starts after it. */
        private const val MAX_CHARS = 110
    }
}
