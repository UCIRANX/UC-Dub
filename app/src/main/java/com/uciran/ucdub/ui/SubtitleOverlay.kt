package com.uciran.ucdub.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import com.uciran.ucdub.data.TranscriptEntry

/**
 * Floating subtitles shown over other apps. Several recent lines stay visible in a box
 * at the bottom of the screen, and the box always scrolls to the newest line.
 * Requires the "display over other apps" permission.
 */
class SubtitleOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var scroll: ScrollView? = null
    private var text: TextView? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun show() {
        main.post {
            if (scroll != null || !canShow()) return@post
            val tv = TextView(context).apply {
                textSize = 16f
                setLineSpacing(0f, 1.15f)
                setPadding(24, 16, 24, 16)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val sv = ScrollView(context).apply {
                setBackgroundColor(0xB0000000.toInt())
                isVerticalScrollBarEnabled = false
                addView(tv)
            }
            val height = (context.resources.displayMetrics.heightPixels * OVERLAY_HEIGHT_RATIO).toInt()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                y = 200
            }
            windowManager.addView(sv, params)
            scroll = sv
            text = tv
        }
    }

    fun update(items: List<TranscriptEntry>) {
        main.post {
            val tv = text ?: return@post
            tv.text = render(items)
            scroll?.let { s -> s.post { s.fullScroll(View.FOCUS_DOWN) } }
        }
    }

    private fun render(items: List<TranscriptEntry>): CharSequence {
        val sb = SpannableStringBuilder()
        var first = true
        for (e in items) {
            if (e.source.isBlank() && e.translated.isBlank()) continue
            if (!first) sb.append("\n\n")
            first = false
            if (e.source.isNotBlank()) appendColored(sb, e.source, 0xFFFFFFFF.toInt())
            if (e.source.isNotBlank() && e.translated.isNotBlank()) sb.append("\n")
            if (e.translated.isNotBlank()) appendColored(sb, e.translated, 0xFFFFE082.toInt())
        }
        return sb
    }

    private fun appendColored(sb: SpannableStringBuilder, s: String, color: Int) {
        val start = sb.length
        sb.append(s)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun hide() {
        main.post {
            scroll?.let { windowManager.removeView(it) }
            scroll = null
            text = null
        }
    }

    companion object {
        private const val OVERLAY_HEIGHT_RATIO = 0.35f
    }
}
