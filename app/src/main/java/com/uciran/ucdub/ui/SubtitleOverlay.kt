package com.uciran.ucdub.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Layout
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.uciran.ucdub.data.TranscriptEntry

/**
 * Floating subtitles over other apps: one row for the original speech and one for the
 * translation. The band is only as tall as its two rows and shows the newest text.
 * Requires the "display over other apps" permission.
 */
class SubtitleOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var band: LinearLayout? = null
    private var sourceRow: Row? = null
    private var translatedRow: Row? = null

    private class Row(val scroll: HorizontalScrollView, val text: TextView)

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun show() {
        main.post {
            if (band != null || !canShow()) return@post
            val src = makeRow(0xFFFFFFFF.toInt())
            val tr = makeRow(0xFFFFE082.toInt())
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xB0000000.toInt())
                setPadding(dp(16), dp(6), dp(16), dp(6))
                addView(src.scroll)
                addView(tr.scroll)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                y = dp(60)
            }
            windowManager.addView(root, params)
            band = root
            sourceRow = src
            translatedRow = tr
        }
    }

    /** Shows the newest original and translated text. */
    fun update(items: List<TranscriptEntry>) {
        main.post {
            val src = sourceRow ?: return@post
            val tr = translatedRow ?: return@post
            setRow(src, items.lastOrNull { it.source.isNotBlank() }?.source.orEmpty())
            setRow(tr, items.lastOrNull { it.translated.isNotBlank() }?.translated.orEmpty())
        }
    }

    private fun makeRow(color: Int): Row {
        val tv = TextView(context).apply {
            textSize = 16f
            setTextColor(color)
            setSingleLine(true)
            ellipsize = null
        }
        val hsv = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        return Row(hsv, tv)
    }

    private fun setRow(row: Row, value: String) {
        if (row.text.text.toString() == value) return
        row.text.text = value
        // Keep the newest words visible: right-to-left text ends on the left, other text on the right.
        row.scroll.post {
            val rtl = row.text.layout?.let { it.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT } ?: false
            if (rtl) row.scroll.scrollTo(0, 0) else row.scroll.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun hide() {
        main.post {
            band?.let { windowManager.removeView(it) }
            band = null
            sourceRow = null
            translatedRow = null
        }
    }
}
