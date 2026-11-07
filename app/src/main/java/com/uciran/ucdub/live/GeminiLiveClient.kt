package com.uciran.ucdub.live

import android.util.Base64
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

/** Why a session ended, so the service knows whether to retry, switch key, or just reconnect. */
enum class FailureKind {
    /** Connection problem: retry the same key with backoff. */
    NETWORK,

    /** The key hit its quota or rate limit: switch to another key. */
    QUOTA,

    /** The key was rejected: switch to another key and keep this one out of use for longer. */
    AUTH,

    /** The server asked us to reconnect (for example goAway): reconnect right away. */
    RECONNECT
}

/** Client for the Gemini Live translation WebSocket (BidiGenerateContent). */
class GeminiLiveClient(
    private val apiKey: String,
    private val targetLanguage: String,
    private val listener: Listener
) {
    interface Listener {
        fun onReady()
        fun onDubAudio(pcm24k: ByteArray)

        /** source = true for the spoken (original) text, false for the translated text. */
        fun onTranscript(source: Boolean, text: String, finished: Boolean)
        fun onInterrupted()
        fun onClosed(reason: String, kind: FailureKind)
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
                    val message = t.message ?: "connection_failed"
                    finish(message, classify(message, response?.code))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val text = reason.ifBlank { "closed_$code" }
                    finish(text, classify(text))
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
            finish(message, classify(message))
            s?.close(1000, "error")
            return
        }

        if (msg.has("goAway")) {
            val s = socket
            finish("goAway", FailureKind.RECONNECT)
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

    private fun finish(reason: String, kind: FailureKind) {
        if (closing) return
        closing = true
        ready = false
        socket = null
        synchronized(lock) { pending.clear() }
        listener.onClosed(reason, kind)
    }

    private fun setupJson(): JSONObject {
        val setup = JSONObject()
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
        return JSONObject().put("setup", setup)
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
        const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MAX_PENDING = 60

        /** Maps an error text (and optional HTTP status) to what the service should do next. */
        fun classify(text: String, httpCode: Int? = null): FailureKind {
            if (httpCode == 401 || httpCode == 403) return FailureKind.AUTH
            if (httpCode == 429) return FailureKind.QUOTA
            val t = text.lowercase()
            return when {
                listOf("quota", "resource_exhausted", "rate limit", "rate_limit", "429", "billing")
                    .any { t.contains(it) } -> FailureKind.QUOTA
                listOf("api key", "api_key", "not valid", "permission", "unauthenticated", "401", "403")
                    .any { t.contains(it) } -> FailureKind.AUTH
                else -> FailureKind.NETWORK
            }
        }
    }
}
