package com.hakunaa.app.core

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hakunaa.app.MainActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Safety net: the app must never crash without leaving a trace.
 * [install] is called twice on purpose — once from [CrashInitProvider]
 * (which runs before Application.onCreate, so even a crash in another
 * startup component is caught) and once from [onCreate] as a backup.
 * On an unexpected error it:
 *  1. writes the full crash report to Download/Hakunaa/ so the user
 *     can open it in the Files app and tell us the exact error, and
 *  2. reopens the home screen once. A second crash within 10 seconds
 *     means the home screen itself is broken, so it lets the process
 *     die normally instead of looping.
 */
class HakunaaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        install(this)
    }

    companion object {
        @Volatile
        private var installed = false

        fun install(appContext: Context) {
            if (installed) return
            installed = true
            val app = appContext.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    writeCrashLog(app, thread, error)
                } catch (_: Throwable) {
                    // Logging must never make a crash worse.
                }
                try {
                    val prefs = app.getSharedPreferences("hakunaa_crash", Context.MODE_PRIVATE)
                    val lastRestart = prefs.getLong("last_restart_ms", 0L)
                    val now = System.currentTimeMillis()
                    if (now - lastRestart > 10_000L) {
                        prefs.edit().putLong("last_restart_ms", now).apply()
                        val restart = Intent(app, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                        app.startActivity(restart)
                        android.os.Process.killProcess(android.os.Process.myPid())
                        return@setDefaultUncaughtExceptionHandler
                    }
                } catch (_: Throwable) {
                    // Fall through to the default handler below.
                }
                previous?.uncaughtException(thread, error)
            }
        }

        private fun writeCrashLog(app: Context, thread: Thread, error: Throwable) {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            val report = buildString {
                appendLine("Hakunaa crash report — $stamp")
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("App 0.1.1, thread: ${thread.name}")
                appendLine()
                append(sw.toString())
            }
            val file = File(app.cacheDir, "crash-log-$stamp.txt").apply {
                writeText(report)
            }
            // Visible in the Files app: Download/Hakunaa/crash-log-*.txt
            // (text/plain — the earlier build saved the .txt as
            // application/pdf and MediaStore silently dropped it).
            // Fall back to Documents if Downloads fails.
            val saved = MediaStoreSaver.save(app, file, MediaStoreSaver.Kind.LOG, file.name)
            if (saved == null) {
                MediaStoreSaver.save(app, file, MediaStoreSaver.Kind.DOCUMENT, file.name)
            }
        }
    }
}
