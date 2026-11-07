package com.uciran.ucdub

import java.util.Locale

/** Target languages supported by Gemini Live translation (same list as the Chrome extension). */
object Languages {
    val CODES: List<String> = listOf(
        "fa", "en", "ar", "zh-Hans", "zh-Hant", "de", "fr", "it", "es", "ru", "ja", "ko", "tr",
        "pt-BR", "pt-PT", "nl", "pl", "uk", "hi", "ur", "he", "id", "ms", "vi", "th",
        "af", "ak", "sq", "am", "hy", "az", "eu", "be", "bn", "bg", "my", "ca", "hr", "cs", "da",
        "et", "fil", "fi", "gl", "ka", "el", "gu", "ha", "hu", "is", "jv", "kn", "kk", "km", "rw",
        "lo", "lv", "lt", "mk", "ml", "mr", "mn", "ne", "no", "nb", "pa", "ro", "sr", "sd", "si",
        "sk", "sl", "su", "sw", "sv", "ta", "te", "uz", "zu"
    )

    fun displayName(code: String): String {
        val locale = Locale.forLanguageTag(code)
        val name = locale.getDisplayName(locale)
        return if (name.isBlank()) code else "$name ($code)"
    }
}
