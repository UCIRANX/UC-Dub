package com.uciran.ucdub.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * App settings. API keys live in the app's private storage (not readable by other apps,
 * and not included in backups because allowBackup is false).
 */
class SettingsStore(context: Context) {
    private val plain: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val secure: SharedPreferences =
        context.getSharedPreferences("keys", Context.MODE_PRIVATE)

    /** Gemini API keys, used in order. A key that hits its limit is skipped automatically. */
    var geminiKeys: List<String>
        get() {
            val raw = secure.getString("gemini_keys", null)
            if (raw != null) {
                return runCatching {
                    val arr = JSONArray(raw)
                    (0 until arr.length()).map { arr.getString(it) }
                }.getOrDefault(emptyList())
            }
            // Older versions stored a single key.
            val legacy = secure.getString("api_key", "").orEmpty()
            return if (legacy.isNotBlank()) listOf(legacy) else emptyList()
        }
        set(value) {
            val clean = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            secure.edit()
                .putString("gemini_keys", JSONArray(clean).toString())
                .remove("api_key")
                .apply()
        }

    var targetLanguage: String
        get() = plain.getString("target_lang", "fa") ?: "fa"
        set(value) = plain.edit().putString("target_lang", value).apply()

    /** Languages whose speech stays as the original: not dubbed and not subtitled. Persian is on by default. */
    var keepLanguages: Set<String>
        get() = plain.getStringSet("keep_langs", setOf("fa"))?.toSet() ?: setOf("fa")
        set(value) = plain.edit().putStringSet("keep_langs", value.toSet()).apply()

    var output: OutputMode
        get() = runCatching {
            OutputMode.valueOf(plain.getString("output", OutputMode.BOTH.name) ?: OutputMode.BOTH.name)
        }.getOrDefault(OutputMode.BOTH)
        set(value) = plain.edit().putString("output", value.name).apply()

}
