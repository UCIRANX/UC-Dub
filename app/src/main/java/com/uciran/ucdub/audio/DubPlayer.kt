package com.uciran.ucdub.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays translated speech (24 kHz mono PCM16).
 * Uses USAGE_ASSISTANCE_ACCESSIBILITY, which the capture side does not match, so the dub is never captured again.
 * When autoDuck is on, it asks Android for transient audio focus while speech is playing,
 * so media apps lower their own volume during the dub.
 */
class DubPlayer(context: Context, private val autoDuck: Boolean, private val maxQueue: Int) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()
    private val releaseFocus = Runnable { releaseFocusNow() }
    private var focusHeld = false

    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var running = true

    private val track: AudioTrack
    private val writer: Thread

    init {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        writer = Thread({ writeLoop() }, "ucdub-dub").apply { start() }
    }

    fun play(pcm: ByteArray) {
        queue.offer(pcm)
        while (queue.size > maxQueue) queue.poll()
        if (autoDuck) main.post { holdFocus() }
    }

    /** Only used in fast mode: drops pending speech so the dub stays close to real time. */
    fun clear() {
        queue.clear()
        try {
            track.pause()
            track.flush()
            track.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "clear failed", e)
        }
    }

    fun release() {
        running = false
        queue.clear()
        main.removeCallbacks(releaseFocus)
        releaseFocusNow()
        try {
            track.stop()
        } catch (_: IllegalStateException) {
        }
        track.release()
    }

    private fun holdFocus() {
        if (!running) return
        main.removeCallbacks(releaseFocus)
        if (!focusHeld) {
            audioManager.requestAudioFocus(focusRequest)
            focusHeld = true
        }
        main.postDelayed(releaseFocus, FOCUS_IDLE_MS)
    }

    private fun releaseFocusNow() {
        if (focusHeld) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            focusHeld = false
        }
    }

    private fun writeLoop() {
        try {
            while (running) {
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                track.write(chunk, 0, chunk.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "dub writer stopped", e)
        }
    }

    companion object {
        private const val SAMPLE_RATE = 24000
        private const val FOCUS_IDLE_MS = 1500L
        private const val TAG = "UCDub"
    }
}
