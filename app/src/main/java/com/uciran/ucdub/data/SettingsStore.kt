package com.uciran.ucdub.data

import android.content.Context
import android.content.SharedPreferences

/**
 * App settings. Keys are stored in the app's private storage (not readable by other apps,
 * not included in backups since allowBackup is false).
 */
class SettingsStore(context: Context) {
    private val plain: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val secure: SharedPreferences =
        context.getSharedPreferences("keys", Context.MODE_PRIVATE)

    var apiKey: String
        get() = secure.getString("api_key", "") ?: ""
        set(value) = secure.edit().putString("api_key", value.trim()).apply()

    var targetLanguage: String
        get() = plain.getString("target_lang", "fa") ?: "fa"
        set(value) = plain.edit().putString("target_lang", value).apply()

    var consentGiven: Boolean
        get() = plain.getBoolean("consent", false)
        set(value) = plain.edit().putBoolean("consent", value).apply()

    var subtitlesEnabled: Boolean
        get() = plain.getBoolean("subtitles", true)
        set(value) = plain.edit().putBoolean("subtitles", value).apply()

    var mode: TranslationMode
        get() = runCatching {
            TranslationMode.valueOf(plain.getString("mode", TranslationMode.FAST.name) ?: TranslationMode.FAST.name)
        }.getOrDefault(TranslationMode.FAST)
        set(value) = plain.edit().putString("mode", value.name).apply()

    var output: OutputMode
        get() = runCatching {
            OutputMode.valueOf(plain.getString("output", OutputMode.BOTH.name) ?: OutputMode.BOTH.name)
        }.getOrDefault(OutputMode.BOTH)
        set(value) = plain.edit().putString("output", value.name).apply()

    var groqKey: String
        get() = secure.getString("groq_key", "") ?: ""
        set(value) = secure.edit().putString("groq_key", value.trim()).apply()

    var voice: String
        get() = plain.getString("voice", "Kore") ?: "Kore"
        set(value) = plain.edit().putString("voice", value).apply()

    var autoDuck: Boolean
        get() = plain.getBoolean("auto_duck", true)
        set(value) = plain.edit().putBoolean("auto_duck", value).apply()
}
