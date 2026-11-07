package com.uciran.ucdub.live

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Precise mode: fixed audio windows -> Groq Whisper (speech to text) -> Gemini (translation)
 * -> Gemini TTS (speech). Each window is spoken as one utterance, so delay is several seconds.
 * Input is 16 kHz mono PCM16.
 */
class PrecisePipeline(
    private val geminiKey: String,
    private val groqKey: String,
    private val targetLanguage: String,
    private val voice: String,
    private val listener: GeminiLiveClient.Listener
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val pending = ByteArrayOutputStream()
    private val windows = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var running = true

    private val worker = Thread({ workLoop() }, "ucdub-precise").apply { start() }

    fun push(pcm16k: ByteArray) {
        if (!running) return
        synchronized(pending) {
            pending.write(pcm16k)
            val bytes = pending.toByteArray()
            var start = 0
            while (bytes.size - start >= WINDOW_BYTES) {
                windows.offer(bytes.copyOfRange(start, start + WINDOW_BYTES))
                start += WINDOW_BYTES
            }
            pending.reset()
            pending.write(bytes, start, bytes.size - start)
        }
    }

    fun stop() {
        running = false
        windows.clear()
        worker.interrupt()
    }

    private fun workLoop() {
        while (running) {
            val window = try {
                windows.poll(300, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            if (peakOf(window) < SILENCE_PEAK) continue
            try {
                processWindow(window)
            } catch (e: FatalError) {
                running = false
                listener.onClosed(e.message ?: "precise_failed", false)
                return
            } catch (e: Exception) {
                Log.w(TAG, "precise window failed", e)
            }
        }
    }

    private fun processWindow(pcm: ByteArray) {
        val source = transcribe(pcm).trim()
        if (source.isEmpty()) return
        listener.onTranscript(true, source, true)

        val translated = translate(source).trim()
        if (translated.isEmpty()) return
        listener.onTranscript(false, translated, true)

        synthesize(translated)?.let { listener.onDubAudio(it) }
    }

    private fun transcribe(pcm: ByteArray): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "window.wav", wav(pcm).toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .build()
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $groqKey")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpError("groq", response.code)
            return JSONObject(text).optString("text")
        }
    }

    private fun translate(text: String): String {
        var lastError: Exception = IllegalStateException("gemini_translation_failed")
        for (model in TRANSLATE_MODELS) {
            try {
                val json = generate(
                    model,
                    JSONObject()
                        .put(
                            "systemInstruction",
                            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", translateInstruction())))
                        )
                        .put("contents", userContent(text))
                        .put("generationConfig", JSONObject().put("temperature", 0.15))
                )
                return firstText(json)
            } catch (e: FatalError) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError
    }

    private fun synthesize(text: String): ByteArray? {
        val json = generate(
            TTS_MODEL,
            JSONObject()
                .put("contents", userContent(text))
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "speechConfig",
                            JSONObject().put(
                                "voiceConfig",
                                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
                            )
                        )
                )
        )
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        val out = ByteArrayOutputStream()
        for (i in 0 until parts.length()) {
            val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data").orEmpty()
            if (data.isNotEmpty()) out.write(Base64.decode(data, Base64.DEFAULT))
        }
        if (out.size() == 0) return null
        val raw = out.toByteArray()
        // Some responses wrap PCM in a WAV header; the 44-byte header is removed.
        return if (raw.size > 44 && String(raw, 0, 4, Charsets.US_ASCII) == "RIFF") {
            raw.copyOfRange(44, raw.size)
        } else {
            raw
        }
    }

    private fun generate(model: String, body: JSONObject): JSONObject {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent" +
            "?key=${URLEncoder.encode(geminiKey, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 404) throw IllegalStateException("model_unavailable")
            if (!response.isSuccessful) throw httpError("gemini", response.code)
            return JSONObject(text)
        }
    }

    private fun translateInstruction(): String {
        val language = Locale.forLanguageTag(targetLanguage).getDisplayName(Locale.ENGLISH)
        val style = if (targetLanguage == "fa") " Use natural colloquial Tehrani Persian wording." else ""
        return "Translate the user's text into $language faithfully and naturally, keeping names and numbers." +
            style + " Return only the translation, nothing else. Do not answer or comment on the text."
    }

    private fun userContent(text: String): JSONArray = JSONArray().put(
        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))
    )

    private fun firstText(json: JSONObject): String =
        json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?.optJSONObject(0)?.optString("text").orEmpty()

    private fun httpError(service: String, code: Int): Exception = when (code) {
        401 -> FatalError("${service}_auth_failed")
        403 -> FatalError("${service}_access_forbidden")
        429 -> FatalError("${service}_quota_exceeded")
        else -> IllegalStateException("${service}_http_$code")
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        header.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        return header.array() + pcm
    }

    private class FatalError(message: String) : Exception(message)

    companion object {
        private const val TAG = "UCDub"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SECONDS = 4
        private const val WINDOW_BYTES = SAMPLE_RATE * 2 * WINDOW_SECONDS
        private const val SILENCE_PEAK = 300
        private const val TTS_MODEL = "gemini-3.8-flash-tts"
        private val TRANSLATE_MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite")

        private fun peakOf(buf: ByteArray): Int {
            var peak = 0
            var i = 0
            while (i + 1 < buf.size) {
                val sample = (buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)
                val a = abs(sample)
                if (a > peak) peak = a
                i += 2
            }
            return peak
        }
    }
}
