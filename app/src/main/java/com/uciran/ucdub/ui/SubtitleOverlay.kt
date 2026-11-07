package com.uciran.ucdub.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Floating subtitles shown over other apps. Requires the "display over other apps" permission. */
class SubtitleOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun show() {
        main.post {
            if (view != null || !canShow()) return@post
            val text = TextView(context).apply {
                setBackgroundColor(0xB0000000.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                setPadding(24, 16, 24, 16)
                gravity = Gravity.CENTER
                maxLines = 4
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
                y = 200
            }
            windowManager.addView(text, params)
            view = text
        }
    }

    fun update(source: String, translated: String) {
        main.post {
            view?.text = listOf(source, translated).filter { it.isNotBlank() }.joinToString("\n")
        }
    }

    fun hide() {
        main.post {
            view?.let { windowManager.removeView(it) }
            view = null
        }
    }
}
