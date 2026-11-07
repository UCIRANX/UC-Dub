package com.uciran.ucdub

import android.app.Application
import android.util.Log
import java.io.File

/**
 * Saves the stack trace of any uncaught crash to a file, so it can be shown on the next launch
 * without needing adb or logcat.
 */
class UcDubApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, CRASH_FILE).writeText(
                    "Thread: ${thread.name}\n${Log.getStackTraceString(error)}"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
