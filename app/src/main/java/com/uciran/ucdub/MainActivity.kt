package com.uciran.ucdub

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.uciran.ucdub.data.OutputMode
import com.uciran.ucdub.data.SettingsStore
import com.uciran.ucdub.data.TranscriptEntry
import com.uciran.ucdub.data.TranscriptStore
import com.uciran.ucdub.ui.AppState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var keysScroll: ScrollView
    private lateinit var keysList: LinearLayout
    private lateinit var keysEmpty: TextView
    private lateinit var keysCount: TextView
    private lateinit var keyInput: TextInputEditText
    private lateinit var languageButton: MaterialButton
    private lateinit var outputSelect: MaterialButton
    private lateinit var connection: TextView
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var hint: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton

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
        renderLanguage()
        renderOutput()
        renderKeys()
        status.text = getString(R.string.status_idle)

        languageButton.setOnClickListener { chooseLanguage() }
        // Let the inner keys list scroll even when the outer page is also scrollable (portrait and landscape).
        keysScroll.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        findViewById<MaterialButton>(R.id.get_key).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.get_key_url))))
            } catch (_: ActivityNotFoundException) {
            }
        }
        outputSelect.setOnClickListener { chooseOutput() }
        findViewById<MaterialButton>(R.id.add_key).setOnClickListener { addKey() }
        findViewById<MaterialButton>(R.id.telegram).setOnClickListener { openTelegram() }
        findViewById<MaterialButton>(R.id.show_transcripts).setOnClickListener { showTranscripts() }
        findViewById<MaterialButton>(R.id.overlay_permission).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        startButton.setOnClickListener { onStartClicked() }
        stopButton.setOnClickListener {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            status.text = getString(R.string.status_idle)
        }
    }

    private fun bindViews() {
        keysScroll = findViewById(R.id.keys_scroll)
        keysList = findViewById(R.id.keys_list)
        keysEmpty = findViewById(R.id.keys_empty)
        keysCount = findViewById(R.id.key_count)
        keyInput = findViewById(R.id.key_input)
        languageButton = findViewById(R.id.language_button)
        outputSelect = findViewById(R.id.output_select)
        connection = findViewById(R.id.connection)
        status = findViewById(R.id.status)
        stats = findViewById(R.id.stats)
        hint = findViewById(R.id.hint)
        startButton = findViewById(R.id.start)
        stopButton = findViewById(R.id.stop)
    }

    // region Language and output

    private fun renderLanguage() {
        languageButton.text = getString(R.string.language_button, Languages.displayName(settings.targetLanguage))
    }

    /** Language picker: a bottom sheet with one rounded row per language, the current one highlighted. */
    private fun chooseLanguage() {
        val sheet = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(20))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.language_label)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(8), dp(4), dp(12))
        })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        codes.forEach { code ->
            val selected = code == settings.targetLanguage
            list.addView(TextView(this).apply {
                text = (if (selected) "✓   " else "") + Languages.displayName(code)
                textSize = 16f
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (selected) R.color.brand_on_gradient else R.color.toggle_off_text
                    )
                )
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(
                        ContextCompat.getColor(
                            this@MainActivity,
                            if (selected) R.color.brand_primary else R.color.toggle_off
                        )
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    settings.targetLanguage = code
                    renderLanguage()
                    sheet.dismiss()
                }
            })
        }

        val scroll = ScrollView(this)
        scroll.addView(list)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.displayMetrics.heightPixels * 6 / 10
            )
        )
        sheet.setContentView(root)
        sheet.show()
    }

    private fun outputLabel(mode: OutputMode): String = getString(
        when (mode) {
            OutputMode.BOTH -> R.string.output_both
            OutputMode.DUB -> R.string.output_dub
            OutputMode.SUBS -> R.string.output_subs
        }
    )

    private fun renderOutput() {
        outputSelect.text = getString(R.string.output_button, outputLabel(settings.output))
    }

    private fun chooseOutput() {
        val modes = OutputMode.values()
        val labels = modes.map { outputLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.output_dialog_title)
            .setSingleChoiceItems(labels, modes.indexOf(settings.output)) { dialog, which ->
                settings.output = modes[which]
                renderOutput()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // endregion

    // region Gemini keys

    private fun renderKeys() {
        val keys = settings.geminiKeys
        keysCount.text = getString(R.string.keys_count, keys.size)
        keysEmpty.visibility = if (keys.isEmpty()) View.VISIBLE else View.GONE
        keysList.removeAllViews()
        keys.forEachIndexed { index, key -> keysList.addView(keyRow(index, key)) }

        // Show at most two keys; the rest scroll inside the box.
        val params = keysScroll.layoutParams
        params.height = if (keys.size > VISIBLE_KEYS) dp(KEY_ROW_DP * VISIBLE_KEYS) else LinearLayout.LayoutParams.WRAP_CONTENT
        keysScroll.layoutParams = params
    }

    private fun keyRow(index: Int, key: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(KEY_ROW_DP)
            )
        }
        row.addView(TextView(this).apply {
            text = "${index + 1}.  ${maskKey(key)}"
            typeface = Typeface.MONOSPACE
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_delete)
            contentDescription = getString(R.string.delete_key)
            setBackgroundResource(selectableBorderless())
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
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

    private fun isActive(): Boolean = AppState.connection in ACTIVE_STATES

    private fun renderDiagnostics() {
        val active = isActive()
        // While the service is running, Start is disabled so the session can't be started twice.
        startButton.isEnabled = !active
        stopButton.isEnabled = active

        renderConnection()

        stats.text = getString(R.string.stats_format, AppState.sentChunks, AppState.dubChunks)

        val h = hintText()
        hint.text = h
        hint.visibility = if (h.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Green = connected, amber = trying to connect, red = disconnected. */
    private fun renderConnection() {
        val (label, color) = when (AppState.connection) {
            "ready" -> getString(R.string.conn_ready) to R.color.status_ok
            "connecting" -> getString(R.string.conn_connecting) to R.color.status_warn
            "reconnecting" -> getString(R.string.conn_reconnecting) to R.color.status_warn
            "waiting_keys" -> getString(R.string.conn_waiting) to R.color.status_warn
            "idle" -> getString(R.string.conn_idle) to R.color.status_error
            "stopped" -> getString(R.string.conn_stopped) to R.color.status_error
            else -> getString(R.string.conn_error) to R.color.status_error
        }
        val c = ContextCompat.getColor(this, color)
        connection.text = label
        connection.setTextColor(c)
        connection.compoundDrawablePadding = dp(6)
        connection.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(c), null, null, null)
    }

    private fun dot(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setSize(dp(8), dp(8))
        setColor(color)
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

    // region Transcripts

    /** Saved sentence pairs. The list refreshes while the window is open, so new text appears live. */
    private fun showTranscripts() {
        val store = TranscriptStore(this)
        val sheet = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.transcripts_title)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(MaterialButton(this).apply {
            text = getString(R.string.transcripts_copy_all)
            textSize = 13f
            setAllCaps(false)
            setOnClickListener {
                val entries = store.readAll()
                if (entries.isEmpty()) return@setOnClickListener
                val all = entries.joinToString("\n\n") { "${it.source}\n${it.translated}" }
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("UC Dub transcripts", all))
                Toast.makeText(this@MainActivity, R.string.transcripts_copied, Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(header)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.displayMetrics.heightPixels * 7 / 10
            )
        )

        val handler = Handler(Looper.getMainLooper())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        var shown = ""
        val refresh = object : Runnable {
            override fun run() {
                val entries = store.readAll()
                val signature = entries.joinToString("\u0001") { "${it.time}|${it.source}|${it.translated}" }
                if (signature != shown) {
                    val atBottom = scroll.getChildAt(0)?.let { scroll.scrollY + scroll.height >= it.height - dp(40) } ?: true
                    shown = signature
                    list.removeAllViews()
                    if (entries.isEmpty()) {
                        list.addView(TextView(this@MainActivity).apply {
                            text = getString(R.string.transcripts_empty)
                            textSize = 14f
                            setPadding(dp(4), dp(16), dp(4), dp(16))
                        })
                    }
                    entries.forEach { list.addView(transcriptRow(it, timeFormat.format(Date(it.time)))) }
                    if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
                handler.postDelayed(this, 700)
            }
        }
        sheet.setOnDismissListener { handler.removeCallbacks(refresh) }
        sheet.setContentView(root)
        sheet.show()
        handler.post(refresh)
    }

    private fun transcriptRow(entry: TranscriptEntry, time: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(ContextCompat.getColor(this@MainActivity, R.color.toggle_off))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        row.addView(TextView(this).apply {
            text = time
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.toggle_off_text))
        })
        if (entry.source.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = entry.source
                textSize = 15f
                setTextIsSelectable(true)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.toggle_off_text))
                setPadding(0, dp(4), 0, 0)
            })
        }
        if (entry.translated.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = entry.translated
                textSize = 15f
                setTextIsSelectable(true)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.brand_primary))
                setPadding(0, dp(6), 0, 0)
            })
        }
        return row
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

    companion object {
        private val ACTIVE_STATES = setOf("connecting", "ready", "reconnecting", "waiting_keys")
        private const val VISIBLE_KEYS = 3
        private const val KEY_ROW_DP = 40
    }
}
