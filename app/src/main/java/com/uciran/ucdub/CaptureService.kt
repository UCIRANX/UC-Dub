package com.uciran.ucdub

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.uciran.ucdub.audio.Downsampler
import com.uciran.ucdub.audio.DubPlayer
import com.uciran.ucdub.data.OutputMode
import com.uciran.ucdub.data.SettingsStore
import com.uciran.ucdub.data.TranslationMode
import com.uciran.ucdub.live.GeminiLiveClient
import com.uciran.ucdub.live.PrecisePipeline
import com.uciran.ucdub.ui.AppState
import com.uciran.ucdub.ui.SubtitleOverlay

/**
 * Captures device audio (AudioPlaybackCapture) and feeds it to the selected engine:
 * Gemini Live (fast / Tehrani) or the precise pipeline. Plays the dub and shows subtitles.
 */
class CaptureService : Service(), GeminiLiveClient.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val downsampler = Downsampler()
    private val reconnectTask = Runnable { connectLive() }

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var dub: DubPlayer? = null
    private var overlay: SubtitleOverlay? = null
    private var settings: SettingsStore? = null

    @Volatile
    private var client: GeminiLiveClient? = null

    @Volatile
    private var precise: PrecisePipeline? = null

    @Volatile
    private var running = false

    private var apiKey = ""
    private var groqKey = ""
    private var voice = "Kore"
    private var targetLanguage = "fa"
    private var mode = TranslationMode.FAST
    private var output = OutputMode.BOTH
    private var autoDuck = true
    private var retryDelayMs = 1000L
    private var lastSource = ""
    private var lastTranslated = ""

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        AppState.reset()
        AppState.connection = "connecting"
        createChannel()
        // Must run promptly after the service starts (Android 14+ rule), even if we exit early.
        startForeground(
            NOTIF_ID,
            buildNotification(connected = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = readDataExtra(intent)
        if (resultCode != Activity.RESULT_OK || data == null) {
            fail("screen capture denied")
            return START_NOT_STICKY
        }

        val prefs = settings ?: SettingsStore(this).also { settings = it }
        apiKey = prefs.apiKey
        groqKey = prefs.groqKey
        voice = prefs.voice
        targetLanguage = prefs.targetLanguage
        mode = prefs.mode
        output = prefs.output
        autoDuck = prefs.autoDuck

        if (apiKey.isBlank()) {
            fail("api key missing")
            return START_NOT_STICKY
        }
        if (mode == TranslationMode.PRECISE && groqKey.isBlank()) {
            fail("groq key missing")
            return START_NOT_STICKY
        }

        val proj = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopAll()
            }
        }, null)

        running = true
        // Fast mode drops old speech to stay real-time; the other modes keep it so sentences are not cut.
        val queueLimit = if (mode == TranslationMode.FAST) 50 else 400
        dub = DubPlayer(this, autoDuck, queueLimit)
        if (output != OutputMode.DUB && Settings.canDrawOverlays(this)) {
            overlay = SubtitleOverlay(this).also { it.show() }
        }
        connectLive()
        startCapture(proj)
        return START_NOT_STICKY
    }

    // region Engines

    private fun connectLive() {
        if (!running) return
        main.removeCallbacks(reconnectTask)
        client?.stop()
        client = null

        if (mode == TranslationMode.PRECISE) {
            precise?.stop()
            precise = PrecisePipeline(apiKey, groqKey, targetLanguage, voice, this)
            onReady()
            return
        }

        AppState.connection = "connecting"
        client = GeminiLiveClient(apiKey, mode, targetLanguage, voice, this).also { it.connect() }
    }

    override fun onReady() {
        retryDelayMs = 1000L
        AppState.connection = "ready"
        updateNotification(connected = true)
    }

    override fun onDubAudio(pcm24k: ByteArray) {
        AppState.dubChunks++
        if (output != OutputMode.SUBS) dub?.play(pcm24k)
    }

    override fun onInterrupted() {
        // Tehrani and precise modes must finish the sentence; only fast mode cuts the old dub.
        if (mode == TranslationMode.FAST) dub?.clear()
    }

    override fun onTranscript(source: Boolean, text: String, finished: Boolean) {
        if (text.isBlank()) return
        if (source) lastSource = text else lastTranslated = text
        val s = lastSource
        val t = lastTranslated
        AppState.lastSource = s
        AppState.lastTranslated = t
        main.post { overlay?.update(s, t) }
    }

    override fun onClosed(reason: String, recoverable: Boolean) {
        Log.w(TAG, "Session closed: $reason (recoverable=$recoverable)")
        if (!running) return
        if (recoverable) {
            AppState.connection = "closed: $reason"
            main.postDelayed(reconnectTask, retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(15_000L)
        } else {
            main.post { stopAll("closed: $reason") }
        }
    }

    // endregion

    // region Capture

    private fun startCapture(proj: MediaProjection) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("microphone permission missing")
            return
        }
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(CAPTURE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                CAPTURE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .build()

            audioRecord = record
            record.startRecording()
            captureThread = Thread({ captureLoop(record, minBuf) }, "ucdub-capture").apply { start() }
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed to start", e)
            fail("capture failed: ${e.message}")
        }
    }

    private fun captureLoop(record: AudioRecord, bufSize: Int) {
        val buffer = ByteArray(bufSize)
        try {
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                AppState.peak = peakOf(buffer, read)
                val chunk = downsampler.process(buffer, read)
                if (chunk.isNotEmpty()) {
                    val p = precise
                    if (p != null) p.push(chunk) else client?.sendAudio(chunk)
                    AppState.sentChunks++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Capture loop stopped", e)
        }
    }

    // endregion

    private fun fail(reason: String) {
        Log.w(TAG, "Failed: $reason")
        stopAll(reason)
    }

    private fun stopAll(finalState: String = "stopped") {
        running = false
        main.removeCallbacks(reconnectTask)
        captureThread?.interrupt()
        captureThread = null
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }
        audioRecord?.release()
        audioRecord = null
        client?.stop()
        client = null
        precise?.stop()
        precise = null
        dub?.release()
        dub = null
        overlay?.hide()
        overlay = null
        projection?.stop()
        projection = null
        AppState.connection = finalState
        AppState.peak = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun readDataExtra(intent: Intent?): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(connected: Boolean): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                getString(if (connected) R.string.notif_connected else R.string.notif_connecting)
            )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop), stopIntent)
            .build()
    }

    private fun updateNotification(connected: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(connected))
    }

    companion object {
        const val ACTION_STOP = "com.uciran.ucdub.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 1
        private const val CAPTURE_RATE = 48000
        private const val TAG = "UCDub"

        /** Loudest absolute sample value in a PCM16 buffer. */
        fun peakOf(buf: ByteArray, len: Int): Int {
            var peak = 0
            var i = 0
            while (i + 1 < len) {
                val sample = (buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)
                val a = if (sample < 0) -sample else sample
                if (a > peak) peak = a
                i += 2
            }
            return peak
        }
    }
}
