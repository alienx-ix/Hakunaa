package com.hakunaa.app.core

import android.app.Application
import android.content.Intent
import com.hakunaa.app.MainActivity

/**
 * Safety net: the app must never show Android's "keeps stopping"
 * dialog. If an unexpected error escapes every local try/catch, this
 * handler reopens the home screen once. A second crash within 10
 * seconds means the home screen itself is broken, so it lets the
 * process die normally instead of looping.
 */
class HakunaaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val prefs = getSharedPreferences("hakunaa_crash", MODE_PRIVATE)
                val lastRestart = prefs.getLong("last_restart_ms", 0L)
                val now = System.currentTimeMillis()
                if (now - lastRestart > 10_000L) {
                    prefs.edit().putLong("last_restart_ms", now).apply()
                    val restart = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    startActivity(restart)
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@setDefaultUncaughtExceptionHandler
                }
            } catch (_: Throwable) {
                // Fall through to the default handler below.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
