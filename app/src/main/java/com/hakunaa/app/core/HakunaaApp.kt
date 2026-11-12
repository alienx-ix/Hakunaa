package com.hakunaa.app.core

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hakunaa.app.CrashReportActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Safety net: the app must never crash without showing the exact error.
 * [install] is called twice on purpose — once from [CrashInitProvider]
 * (which runs before Application.onCreate, so even a crash in another
 * startup component is caught) and once from [onCreate] as a backup.
 * On an unexpected error it:
 *  1. writes the full crash report to Download/Hakunaa/, and
 *  2. opens [CrashReportActivity] (a plain screen, no Material) showing
 *     the exact error, so the user can screenshot it and tell us.
 * It deliberately does NOT restart the home screen — the restart loop
 * is what turned earlier crashes into a black-screen freeze.
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
                val detail = try {
                    val sw = StringWriter()
                    error.printStackTrace(PrintWriter(sw))
                    sw.toString()
                } catch (_: Throwable) {
                    error.toString()
                }
                try {
                    writeCrashLog(app, thread, error, detail)
                } catch (_: Throwable) {
                    // Logging must never make a crash worse.
                }
                try {
                    // Show the error on screen instead of restarting.
                    // A plain Activity (no Material) so it opens even
                    // if the home screen's UI is what crashed.
                    val show = Intent(app, CrashReportActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(CrashReportActivity.EXTRA_DETAIL, detail.take(4000))
                    }
                    app.startActivity(show)
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@setDefaultUncaughtExceptionHandler
                } catch (_: Throwable) {
                    // Fall through to the default handler below.
                }
                previous?.uncaughtException(thread, error)
            }
        }

        private fun writeCrashLog(app: Context, thread: Thread, error: Throwable, detail: String) {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val report = buildString {
                appendLine("Hakunaa crash report — $stamp")
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("App 0.1.5, thread: ${thread.name}")
                appendLine()
                append(detail)
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
