package com.hakunaa.app.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object FileUtils {
    fun copyUriToCache(context: Context, uri: Uri, name: String): File {
        val out = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    fun fileName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: "file"
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    fun fileSize(resolver: ContentResolver, uri: Uri): Long {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0) return cursor.getLong(index)
            }
        }
        return -1L
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "unknown size"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    fun savedPercent(before: Long, after: Long): String {
        if (before <= 0 || after < 0) return ""
        val saved = (1.0 - after.toDouble() / before.toDouble()) * 100.0
        return "%.0f%% smaller".format(saved)
    }

    /** Decode a JPEG file with its EXIF orientation applied
     *  (v0.1.66). BitmapFactory.decodeFile ignores EXIF, so a
     *  photo the camera saved as landscape pixels with a 90°
     *  EXIF tag displays as a small wide band instead of the
     *  full portrait image the user saw in the preview. This
     *  rotates the bitmap to its true upright orientation.
     *  Returns null when the file cannot be decoded. */
    fun decodeBitmapWithExif(path: String): android.graphics.Bitmap? {
        val bmp = android.graphics.BitmapFactory.decodeFile(path) ?: return null
        val degrees = try {
            val exif = androidx.exifinterface.media.ExifInterface(path)
            when (exif.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }
        if (degrees == 0f) return bmp
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees)
        val rotated = android.graphics.Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height, matrix, true
        )
        bmp.recycle()
        return rotated
    }
}
