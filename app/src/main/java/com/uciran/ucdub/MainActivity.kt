package com.uciran.ucdub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.uciran.ucdub.data.OutputMode
import com.uciran.ucdub.data.SettingsStore
import com.uciran.ucdub.data.TranslationMode
import com.uciran.ucdub.ui.AppState

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var apiKey: EditText
    private lateinit var groqKey: EditText
    private lateinit var language: Spinner
    private lateinit var voice: Spinner
    private lateinit var modeGroup: RadioGroup
    private lateinit var outputGroup: RadioGroup
    private lateinit var autoDuck: CheckBox
    private lateinit var consent: CheckBox
    private lateinit var status: TextView
    private lateinit var level: ProgressBar
    private lateinit var details: TextView

    private val codes = Languages.CODES
    private val voices = listOf("Charon", "Puck", "Kore", "Zephyr")
    private val voiceLabels = listOf(
        "مرد ۱ (Charon)",
        "مرد ۲ (Puck)",
        "زن ۱ (Kore)",
        "زن ۲ (Zephyr)"
    )

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            renderDiagnostics()
            ui.postDelayed(this, 500)
        }
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, result.data)
                ContextCompat.startForegroundService(this, intent)
                status.text = getString(R.string.status_running)
            } else {
                status.text = getString(R.string.status_denied)
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true) {
                val manager = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(manager.createScreenCaptureIntent())
            } else {
                status.text = getString(R.string.status_no_mic)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsStore(this)
        apiKey = findViewById(R.id.api_key)
        groqKey = findViewById(R.id.groq_key)
        language = findViewById(R.id.language)
        voice = findViewById(R.id.voice)
        modeGroup = findViewById(R.id.mode_group)
        outputGroup = findViewById(R.id.output_group)
        autoDuck = findViewById(R.id.auto_duck)
        consent = findViewById(R.id.consent)
        status = findViewById(R.id.status)
        level = findViewById(R.id.level)
        details = findViewById(R.id.details)

        apiKey.setText(settings.apiKey)
        groqKey.setText(settings.groqKey)
        language.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            codes.map { Languages.displayName(it) }
        )
        language.setSelection(codes.indexOf(settings.targetLanguage).coerceAtLeast(0))
        voice.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            voiceLabels
        )
        voice.setSelection(voices.indexOf(settings.voice).coerceAtLeast(0))

        modeGroup.check(
            when (settings.mode) {
                TranslationMode.FAST -> R.id.mode_fast
                TranslationMode.TEHRANI -> R.id.mode_tehrani
                TranslationMode.PRECISE -> R.id.mode_precise
            }
        )
        outputGroup.check(
            when (settings.output) {
                OutputMode.DUB -> R.id.output_dub
                OutputMode.SUBS -> R.id.output_subs
                OutputMode.BOTH -> R.id.output_both
            }
        )
        autoDuck.isChecked = settings.autoDuck
        consent.isChecked = settings.consentGiven
        status.text = getString(R.string.status_idle)

        modeGroup.setOnCheckedChangeListener { _, _ -> refreshEnabled() }
        refreshEnabled()

        findViewById<Button>(R.id.overlay_permission).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.start).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.stop).setOnClickListener {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            status.text = getString(R.string.status_idle)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    /** Language is fixed to Persian in Tehrani mode; voice is used in Tehrani and precise modes; Groq key only in precise mode. */
    private fun refreshEnabled() {
        val mode = selectedMode()
        language.isEnabled = mode != TranslationMode.TEHRANI
        voice.isEnabled = mode != TranslationMode.FAST
        groqKey.isEnabled = mode == TranslationMode.PRECISE
    }

    private fun renderDiagnostics() {
        level.progress = AppState.peak
        val percent = AppState.peak * 100 / 32767
        val hint = when {
            AppState.connection == "ready" && AppState.peak == 0 ->
                "صدایی از برنامه دیگر گرفته نمی‌شود. ممکن است آن برنامه ضبط را بسته باشد."
            AppState.connection == "ready" && AppState.sentChunks > 0 && AppState.dubChunks == 0L ->
                "صدا ارسال می‌شود ولی هنوز ترجمه‌ای برنگشته. چند ثانیه صبر کن."
            AppState.connection.startsWith("closed") ->
                "اتصال قطع شد. کلید API و اینترنت را بررسی کن."
            else -> ""
        }
        details.text = buildString {
            appendLine("وضعیت: ${AppState.connection}")
            appendLine("سطح صدای ورودی: $percent٪")
            appendLine("بسته‌های صوتی ارسالی: ${AppState.sentChunks}")
            appendLine("بسته‌های دوبله دریافتی: ${AppState.dubChunks}")
            if (AppState.lastSource.isNotBlank()) appendLine("متن اصلی: ${AppState.lastSource}")
            if (AppState.lastTranslated.isNotBlank()) appendLine("ترجمه: ${AppState.lastTranslated}")
            if (hint.isNotEmpty()) append("\n$hint")
        }
    }

    private fun selectedMode(): TranslationMode = when (modeGroup.checkedRadioButtonId) {
        R.id.mode_tehrani -> TranslationMode.TEHRANI
        R.id.mode_precise -> TranslationMode.PRECISE
        else -> TranslationMode.FAST
    }

    private fun selectedOutput(): OutputMode = when (outputGroup.checkedRadioButtonId) {
        R.id.output_dub -> OutputMode.DUB
        R.id.output_subs -> OutputMode.SUBS
        else -> OutputMode.BOTH
    }

    private fun onStartClicked() {
        val key = apiKey.text.toString().trim()
        val mode = selectedMode()
        if (key.isEmpty()) {
            status.text = getString(R.string.status_no_key)
            return
        }
        if (mode == TranslationMode.PRECISE && groqKey.text.toString().trim().isEmpty()) {
            status.text = getString(R.string.status_no_groq)
            return
        }
        if (!consent.isChecked) {
            status.text = getString(R.string.status_no_consent)
            return
        }
        settings.apiKey = key
        settings.groqKey = groqKey.text.toString()
        settings.consentGiven = true
        settings.targetLanguage = codes[language.selectedItemPosition]
        settings.voice = voices[voice.selectedItemPosition]
        settings.mode = mode
        settings.output = selectedOutput()
        settings.autoDuck = autoDuck.isChecked

        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(permissions.toTypedArray())
    }
}
