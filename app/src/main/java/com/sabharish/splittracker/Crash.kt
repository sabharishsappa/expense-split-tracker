package com.sabharish.splittracker

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.util.Date

/** Saves the stack trace of any crash so it can be read in Settings and sent to the developer. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(filesDir, CrashLog.FILE).writeText("${Date()}\nthread: ${thread.name}\n${Log.getStackTraceString(error)}")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

object CrashLog {
    const val FILE = "last_crash.txt"
    fun read(ctx: Context): String = try { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }
    fun clear(ctx: Context) { try { File(ctx.filesDir, FILE).delete() } catch (_: Exception) {} }
}
