package com.uciran.ucdub.data

/**
 * Hands out Gemini API keys in order. A key that hits its quota (or is rejected) is put on
 * cooldown and the next key is used, so the switch happens without the user noticing.
 */
class ApiKeyPool(private val store: SettingsStore) {

    private val cooldownUntil = HashMap<String, Long>()
    private var index = 0

    /** The key to use right now, or null when every key is cooling down or there are none. */
    @Synchronized
    fun current(): String? {
        val keys = store.geminiKeys
        if (keys.isEmpty()) return null
        val now = System.currentTimeMillis()
        for (offset in keys.indices) {
            val i = (index + offset) % keys.size
            if ((cooldownUntil[keys[i]] ?: 0L) <= now) {
                index = i
                return keys[i]
            }
        }
        return null
    }

    /** Milliseconds until the first key becomes usable again. */
    @Synchronized
    fun waitMs(): Long {
        val keys = store.geminiKeys
        if (keys.isEmpty()) return 0L
        val now = System.currentTimeMillis()
        val earliest = keys.minOf { cooldownUntil[it] ?: 0L }
        return (earliest - now).coerceAtLeast(0L)
    }

    @Synchronized
    fun markExhausted(key: String, durationMs: Long) {
        cooldownUntil[key] = System.currentTimeMillis() + durationMs
        index += 1
    }
}
