package com.uciran.ucdub.live

import com.google.mlkit.nl.languageid.LanguageIdentification

/** On-device language check of the spoken text. Used to skip dubbing for the languages the user keeps. */
class SpeechLanguage {
    private val identifier = LanguageIdentification.getClient()

    /** Calls back with a language code such as "fa" or "en", or "und" when it is not sure. */
    fun identify(text: String, onResult: (String) -> Unit) {
        identifier.identifyLanguage(text)
            .addOnSuccessListener { onResult(it) }
            .addOnFailureListener { onResult("und") }
    }

    fun close() {
        identifier.close()
    }
}
