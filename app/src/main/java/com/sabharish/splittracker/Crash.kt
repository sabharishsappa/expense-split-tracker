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
                CrashLog.append(this, "${Date()}\nthread: ${thread.name}\n${Log.getStackTraceString(error)}")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

object CrashLog {
    const val FILE = "last_crash.txt"
    /** Keeps the last few problems, newest first, not just the latest one. */
    fun append(ctx: Context, entry: String) {
        try {
            val f = File(ctx.filesDir, FILE)
            val old = if (f.exists()) f.readText().split(SEP).filter { it.isNotBlank() } else emptyList()
            f.writeText((listOf(entry.take(2500)) + old).take(5).joinToString(SEP))
        } catch (_: Throwable) {}
    }
    const val SEP = "\n=====\n"
    fun read(ctx: Context): String = try { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }
    /** Keeps the error of a problem we recovered from, so it can still be read in Settings. */
    fun record(ctx: Context, error: Throwable) {
        append(ctx, "${Date()}\n(handled)\n${Log.getStackTraceString(error)}")
    }
    fun clear(ctx: Context) { try { File(ctx.filesDir, FILE).delete() } catch (_: Exception) {} }
}
