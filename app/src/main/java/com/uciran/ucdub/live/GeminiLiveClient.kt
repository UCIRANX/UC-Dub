package com.uciran.ucdub.live

import android.util.Base64
import com.uciran.ucdub.data.TranslationMode
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Client for the Gemini Live BidiGenerateContent WebSocket.
 * FAST uses the translation model; TEHRANI uses the voice model with a Tehrani-style instruction.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val mode: TranslationMode,
    private val voice: String,
    private val targetLanguage: String,
    private val listener: Listener
) {
    interface Listener {
        fun onReady()
        fun onDubAudio(pcm24k: ByteArray)
        /** source = true for the spoken (original) text, false for the translated text. */
        fun onTranscript(source: Boolean, text: String, finished: Boolean)
        fun onInterrupted()
        fun onClosed(reason: String, recoverable: Boolean)
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val lock = Any()
    private val pending = ArrayDeque<ByteArray>()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var ready = false

    @Volatile
    private var closing = false

    fun connect() {
        val url = "$ENDPOINT?key=${URLEncoder.encode(apiKey, "UTF-8")}"
        socket = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(setupJson().toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handle(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handle(bytes.utf8())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    finish(t.message ?: "connection_failed", true)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finish(reason.ifBlank { "closed_$code" }, true)
                }
            }
        )
    }

    fun sendAudio(pcm16k: ByteArray) {
        if (closing) return
        if (!ready) {
            synchronized(lock) {
                if (pending.size >= MAX_PENDING) pending.removeFirst()
                pending.addLast(pcm16k.copyOf())
            }
            return
        }
        socket?.send(audioJson(pcm16k).toString())
    }

    /** Ends the session on purpose. No callbacks are sent for an intentional stop. */
    fun stop() {
        closing = true
        ready = false
        val s = socket
        socket = null
        try {
            s?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
        } catch (_: Exception) {
        }
        s?.close(1000, "stop")
    }

    private fun handle(text: String) {
        val msg = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }

        msg.optJSONObject("error")?.let { err ->
            val message = err.optString("message", "gemini_error")
            val s = socket
            finish(message, !message.contains("API key", ignoreCase = true))
            s?.close(1000, "error")
            return
        }

        if (msg.has("goAway")) {
            val s = socket
            finish("goAway", true)
            s?.close(1000, "renew")
            return
        }

        if (msg.has("setupComplete")) {
            ready = true
            flushPending()
            listener.onReady()
            return
        }

        val content = msg.optJSONObject("serverContent") ?: return

        if (content.optBoolean("interrupted")) listener.onInterrupted()

        content.optJSONObject("inputTranscription")?.let {
            listener.onTranscript(true, it.optString("text"), it.optBoolean("finished"))
        }
        content.optJSONObject("outputTranscription")?.let {
            listener.onTranscript(false, it.optString("text"), it.optBoolean("finished"))
        }

        val parts: JSONArray = content.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val data = inline.optString("data")
            if (data.isNotEmpty()) listener.onDubAudio(Base64.decode(data, Base64.DEFAULT))
        }
    }

    private fun flushPending() {
        val s = socket ?: return
        val chunks = synchronized(lock) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        for (chunk in chunks) s.send(audioJson(chunk).toString())
    }

    private fun finish(reason: String, recoverable: Boolean) {
        if (closing) return
        closing = true
        ready = false
        socket = null
        synchronized(lock) { pending.clear() }
        listener.onClosed(reason, recoverable)
    }

    private fun setupJson(): JSONObject {
        val body = if (mode == TranslationMode.TEHRANI) {
            JSONObject()
                .put("model", "models/$VOICE_MODEL")
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "speechConfig",
                            JSONObject().put(
                                "voiceConfig",
                                JSONObject().put(
                                    "prebuiltVoiceConfig",
                                    JSONObject().put("voiceName", voice)
                                )
                            )
                        )
                )
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", TEHRANI_INSTRUCTION))
                    )
                )
        } else {
            JSONObject()
                .put("model", "models/$MODEL")
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "translationConfig",
                            JSONObject()
                                .put("targetLanguageCode", targetLanguage)
                                .put("echoTargetLanguage", false)
                        )
                )
        }
        return JSONObject().put("setup", body)
    }

    private fun audioJson(pcm: ByteArray): JSONObject = JSONObject().put(
        "realtimeInput",
        JSONObject().put(
            "audio",
            JSONObject()
                .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                .put("mimeType", "audio/pcm;rate=16000")
        )
    )

    companion object {
        const val MODEL = "gemini-3.5-live-translate-preview"
        const val VOICE_MODEL = "gemini-3.8-live"
        const val VOICE_NAME = "Kore"
        const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MAX_PENDING = 60

        private const val TEHRANI_INSTRUCTION =
            "You are a live interpreter. Listen to whatever is being said, in any language, and speak only " +
                "its translation into Persian as it is spoken colloquially in Tehran. Use everyday Tehrani " +
                "Persian wording and rhythm (for example: الان، اینه، داره، می‌خوام، نمی‌شه، خیلی)، " +
                "natural short sentences, and no formal literary style. Keep names, brands and numbers as they are. " +
                "Never answer, explain or comment on the speech; only interpret it. Speak nothing else."
    }
}
