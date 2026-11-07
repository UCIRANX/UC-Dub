package com.uciran.ucdub

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.uciran.ucdub.data.OutputMode
import com.uciran.ucdub.data.SettingsStore
import com.uciran.ucdub.ui.AppState
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var keysList: LinearLayout
    private lateinit var keysEmpty: TextView
    private lateinit var keysCount: TextView
    private lateinit var keyInput: TextInputEditText
    private lateinit var languageDropdown: MaterialAutoCompleteTextView
    private lateinit var outputGroup: ChipGroup
    private lateinit var autoDuck: SwitchMaterial
    private lateinit var status: TextView
    private lateinit var level: LinearProgressIndicator
    private lateinit var levelPercent: TextView
    private lateinit var details: TextView

    private val codes = Languages.CODES

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
        showLastCrashIfAny()
        setContentView(R.layout.activity_main)

        settings = SettingsStore(this)
        bindViews()
        setupLanguage()
        setupOutput()

        autoDuck.isChecked = settings.autoDuck
        autoDuck.setOnCheckedChangeListener { _, checked -> settings.autoDuck = checked }

        renderKeys()
        status.text = getString(R.string.status_idle)

        findViewById<MaterialButton>(R.id.add_key).setOnClickListener { addKey() }
        findViewById<MaterialButton>(R.id.telegram).setOnClickListener { openTelegram() }
        findViewById<MaterialButton>(R.id.overlay_permission).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<MaterialButton>(R.id.start).setOnClickListener { onStartClicked() }
        findViewById<MaterialButton>(R.id.stop).setOnClickListener {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            status.text = getString(R.string.status_idle)
        }
    }

    private fun bindViews() {
        keysList = findViewById(R.id.keys_list)
        keysEmpty = findViewById(R.id.keys_empty)
        keysCount = findViewById(R.id.key_count)
        keyInput = findViewById(R.id.key_input)
        languageDropdown = findViewById(R.id.language_dropdown)
        outputGroup = findViewById(R.id.output_group)
        autoDuck = findViewById(R.id.auto_duck)
        status = findViewById(R.id.status)
        level = findViewById(R.id.level)
        levelPercent = findViewById(R.id.level_percent)
        details = findViewById(R.id.details)
    }

    // region Language and output

    private fun setupLanguage() {
        val names = codes.map { Languages.displayName(it) }
        languageDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        )
        val initial = codes.indexOf(settings.targetLanguage).coerceAtLeast(0)
        languageDropdown.setText(names[initial], false)
        languageDropdown.setOnItemClickListener { _, _, position, _ ->
            settings.targetLanguage = codes[position]
        }
    }

    private fun setupOutput() {
        outputGroup.check(
            when (settings.output) {
                OutputMode.BOTH -> R.id.chip_both
                OutputMode.DUB -> R.id.chip_dub
                OutputMode.SUBS -> R.id.chip_subs
            }
        )
        outputGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            settings.output = when (checkedIds.firstOrNull()) {
                R.id.chip_dub -> OutputMode.DUB
                R.id.chip_subs -> OutputMode.SUBS
                else -> OutputMode.BOTH
            }
        }
    }

    // endregion

    // region Gemini keys

    private fun renderKeys() {
        val keys = settings.geminiKeys
        keysCount.text = getString(R.string.keys_count, keys.size)
        keysEmpty.visibility = if (keys.isEmpty()) View.VISIBLE else View.GONE
        keysList.removeAllViews()
        keys.forEachIndexed { index, key -> keysList.addView(keyRow(index, key)) }
    }

    private fun keyRow(index: Int, key: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = "${index + 1}.  ${maskKey(key)}"
            typeface = Typeface.MONOSPACE
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_delete)
            contentDescription = getString(R.string.delete_key)
            setBackgroundResource(selectableBorderless())
            setOnClickListener { confirmDelete(key) }
        })
        return row
    }

    private fun addKey() {
        val key = keyInput.text?.toString()?.trim().orEmpty()
        val keys = settings.geminiKeys
        when {
            key.isEmpty() -> keyInput.error = getString(R.string.key_empty)
            key in keys -> keyInput.error = getString(R.string.key_duplicate)
            else -> {
                settings.geminiKeys = keys + key
                keyInput.setText("")
                keyInput.error = null
                renderKeys()
            }
        }
    }

    private fun confirmDelete(key: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_key_title)
            .setMessage(R.string.delete_key_message)
            .setPositiveButton(R.string.delete_key) { _, _ ->
                settings.geminiKeys = settings.geminiKeys.filter { it != key }
                renderKeys()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun maskKey(key: String): String =
        if (key.length <= 8) "••••" else "${key.take(4)}••••••••${key.takeLast(4)}"

    // endregion

    // region Start, stop, live status

    private fun onStartClicked() {
        if (settings.geminiKeys.isEmpty()) {
            status.text = getString(R.string.status_no_key)
            return
        }
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun renderDiagnostics() {
        val percent = (AppState.peak * 100 / 32767).coerceIn(0, 100)
        level.setProgressCompat(percent, true)
        levelPercent.text = "$percent٪"

        details.text = buildString {
            appendLine("${getString(R.string.details_connection)}: ${connectionLabel()}")
            appendLine("${getString(R.string.details_sent)}: ${AppState.sentChunks}")
            appendLine("${getString(R.string.details_dub)}: ${AppState.dubChunks}")
            if (AppState.lastSource.isNotBlank()) {
                appendLine("${getString(R.string.details_source)}: ${AppState.lastSource}")
            }
            if (AppState.lastTranslated.isNotBlank()) {
                appendLine("${getString(R.string.details_translated)}: ${AppState.lastTranslated}")
            }
            val hint = hintText()
            if (hint.isNotEmpty()) append("\n$hint")
        }
    }

    private fun connectionLabel(): String = when (AppState.connection) {
        "ready" -> getString(R.string.conn_ready)
        "connecting" -> getString(R.string.conn_connecting)
        "reconnecting" -> getString(R.string.conn_reconnecting)
        "waiting_keys" -> getString(R.string.conn_waiting)
        "stopped" -> getString(R.string.conn_stopped)
        "idle" -> getString(R.string.conn_idle)
        else -> if (AppState.connection.startsWith("closed")) {
            getString(R.string.conn_error)
        } else {
            AppState.connection
        }
    }

    private fun hintText(): String = when {
        AppState.connection == "ready" && AppState.peak == 0 -> getString(R.string.hint_no_sound)
        AppState.connection == "ready" && AppState.sentChunks > 0 && AppState.dubChunks == 0L ->
            getString(R.string.hint_waiting_translation)
        AppState.connection == "waiting_keys" -> getString(R.string.hint_waiting_keys)
        else -> ""
    }

    private fun openTelegram() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.telegram_url))))
        } catch (_: ActivityNotFoundException) {
        }
    }

    // endregion

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun selectableBorderless(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        return tv.resourceId
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    /** Shows the stack trace of the previous crash (saved by UcDubApp), then deletes it. */
    private fun showLastCrashIfAny() {
        val file = File(filesDir, UcDubApp.CRASH_FILE)
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrDefault("")
        file.delete()
        if (text.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle("کرش قبلی برنامه")
            .setMessage(text.take(4000))
            .setPositiveButton("باشه", null)
            .show()
    }
}
