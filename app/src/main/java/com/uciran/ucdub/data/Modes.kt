package com.uciran.ucdub.data

/**
 * FAST: translation model (lowest latency).
 * TEHRANI: voice model with colloquial Tehrani Persian.
 * PRECISE: Groq Whisper -> Gemini translation -> Gemini TTS (more accurate, more delay).
 */
enum class TranslationMode { FAST, TEHRANI, PRECISE }

/** What the user hears and reads. */
enum class OutputMode { BOTH, DUB, SUBS }
