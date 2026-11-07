package com.uciran.ucdub.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsStore(context: Context) {
    private val plain: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val secure: SharedPreferences = openSecurePrefs(context)

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

    companion object {
        private const val TAG = "UCDub"
        private const val SECURE_FILE = "secure_settings"
        private const val FALLBACK_FILE = "secure_settings_fallback"

        /**
         * Opens the encrypted store without ever letting an exception escape.
         * The Keystore key can be lost or incompatible on some devices, which used to crash the app on launch.
         * If the encrypted file cannot be read, it is reset once. If that still fails, app-private storage is used.
         */
        private fun openSecurePrefs(context: Context): SharedPreferences {
            return try {
                createEncrypted(context)
            } catch (first: Throwable) {
                Log.w(TAG, "Encrypted settings unreadable, resetting", first)
                runCatching { context.deleteSharedPreferences(SECURE_FILE) }
                try {
                    createEncrypted(context)
                } catch (second: Throwable) {
                    Log.e(TAG, "Encrypted settings unavailable, using private storage", second)
                    context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
                }
            }
        }

        private fun createEncrypted(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                SECURE_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
