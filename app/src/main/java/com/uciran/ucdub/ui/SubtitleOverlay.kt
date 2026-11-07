package com.uciran.ucdub.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.uciran.ucdub.data.TranscriptEntry

/**
 * Floating subtitles over other apps. Each finished sentence is shown in one piece:
 * a line for the original speech and a line for the translation. Only the text has a
 * translucent background. The subtitle is kept a little away from the left and right edges.
 * Requires the "display over other apps" permission.
 */
class SubtitleOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var sourceView: TextView? = null
    private var translatedView: TextView? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun show() {
        main.post {
            if (root != null || !canShow()) return@post
            val src = makeText(0xFFFFFFFF.toInt())
            val tr = makeText(0xFFFFE082.toInt())
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(src)
                addView(tr)
            }
            val params = WindowManager.LayoutParams(
                (context.resources.displayMetrics.widthPixels * SIDE_WIDTH_RATIO).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(60)
            }
            windowManager.addView(container, params)
            root = container
            sourceView = src
            translatedView = tr
        }
    }

    /** Shows one finished sentence, replacing the previous one. */
    fun update(entry: TranscriptEntry) {
        main.post {
            val src = sourceView ?: return@post
            val tr = translatedView ?: return@post
            setText(src, entry.source)
            setText(tr, entry.translated)
        }
    }

    private fun makeText(color: Int): TextView = TextView(context).apply {
        textSize = 16f
        setTextColor(color)
        gravity = Gravity.CENTER
        setBackgroundColor(0xB0000000.toInt())
        setPadding(dp(10), dp(4), dp(10), dp(4))
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(2) }
    }

    private fun setText(view: TextView, value: String) {
        if (value.isBlank()) {
            view.visibility = View.GONE
            return
        }
        view.text = value
        view.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun hide() {
        main.post {
            root?.let { windowManager.removeView(it) }
            root = null
            sourceView = null
            translatedView = null
        }
    }

    companion object {
        /** Width of the subtitle area as a share of the screen: keeps a margin on both sides. */
        private const val SIDE_WIDTH_RATIO = 0.84f
    }
}
