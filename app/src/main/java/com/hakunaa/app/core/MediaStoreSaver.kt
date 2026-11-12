package com.hakunaa.app.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Publishes a finished file into shared storage the Android way.
 *
 * Android 10+ (scoped storage): apps cannot write a plain File into
 * Movies/Pictures/Documents. The file must go through MediaStore,
 * which also makes it visible in the Gallery/Files apps at once.
 * Android 7–9: direct File writes still work, so fall back to them.
 *
 * Every tool writes its result to a temp file in the app cache first,
 * then calls [save] — the work itself never touches shared storage.
 */
object MediaStoreSaver {
    enum class Kind(val folder: String, val mime: String) {
        VIDEO("Movies/Hakunaa", "video/mp4"),
        IMAGE("Pictures/Hakunaa", "image/jpeg"),
        DOCUMENT("Documents/Hakunaa", "application/pdf"),
        // Crash reports: plain text in Downloads, the most visible
        // place in the Files app. Never use the PDF mime for a .txt
        // file — MediaStore can reject the mismatch and the file
        // silently never appears.
        LOG("Download/Hakunaa", "text/plain")
    }

    /** Save [file] as [displayName] in the shared [Kind] folder. Null on failure. */
    fun save(context: Context, file: File, kind: Kind, displayName: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveViaMediaStore(context, file, kind, displayName)
            else saveViaFile(file, kind, displayName)
        } catch (_: Throwable) {
            null
        }
    }

    private fun saveViaMediaStore(context: Context, file: File, kind: Kind, displayName: String): Uri? {
        val resolver = context.contentResolver
        val collection = when (kind) {
            Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Kind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Kind.LOG -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Kind.DOCUMENT -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, kind.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, kind.folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                .also { resolver.update(uri, it, null, null) }
        } catch (_: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return uri
    }

    private fun saveViaFile(file: File, kind: Kind, displayName: String): Uri? {
        val base = when (kind) {
            Kind.VIDEO -> Environment.DIRECTORY_MOVIES
            Kind.IMAGE -> Environment.DIRECTORY_PICTURES
            Kind.LOG -> Environment.DIRECTORY_DOWNLOADS
            Kind.DOCUMENT -> Environment.DIRECTORY_DOCUMENTS
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(base), "Hakunaa"
        ).apply { mkdirs() }
        val dest = File(dir, displayName)
        return try {
            file.copyTo(dest, overwrite = true)
            Uri.fromFile(dest)
        } catch (_: Throwable) {
            null
        }
    }
}
