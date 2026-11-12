package com.hakunaa.app.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Runs before Application.onCreate and before every other startup
 * component, so the crash handler is installed even if an early
 * startup step crashes. Declared with a high initOrder in the
 * manifest. Does nothing else.
 */
class CrashInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let { HakunaaApp.install(it) }
        return true
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
