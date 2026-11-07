package com.uciran.ucdub.audio

import java.io.ByteArrayOutputStream

/**
 * Converts 48 kHz stereo PCM16 (little-endian) to 16 kHz mono PCM16, as expected by Gemini Live.
 * Uses a 3:1 average, which also acts as a simple low-pass filter.
 * Keeps leftover bytes between calls so frames split across buffers are not lost.
 */
class Downsampler {
    private var carry = ByteArray(0)
    private var sum = 0
    private var count = 0

    fun process(src: ByteArray, len: Int): ByteArray {
        val input = carry + src.copyOf(len)
        val usable = input.size - input.size % 4
        carry = input.copyOfRange(usable, input.size)

        val out = ByteArrayOutputStream(usable / 6 + 2)
        var i = 0
        while (i < usable) {
            val left = (input[i + 1].toInt() shl 8) or (input[i].toInt() and 0xff)
            val right = (input[i + 3].toInt() shl 8) or (input[i + 2].toInt() and 0xff)
            sum += (left + right) / 2
            count++
            if (count == 3) {
                val value = sum / 3
                out.write(value and 0xff)
                out.write((value shr 8) and 0xff)
                sum = 0
                count = 0
            }
            i += 4
        }
        return out.toByteArray()
    }
}
