package com.uciran.ucdub.ui

/** Live diagnostics shared between the capture service and the screen. */
object AppState {
    @Volatile var connection: String = "idle"
    @Volatile var peak: Int = 0
    @Volatile var sentChunks: Long = 0
    @Volatile var dubChunks: Long = 0

    fun reset() {
        connection = "idle"
        peak = 0
        sentChunks = 0
        dubChunks = 0
    }
}
