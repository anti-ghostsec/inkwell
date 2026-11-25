package io.github.inkwell.viewer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands pictures to other apps (Share, Open with, Set as wallpaper) as
 * copies in Inkwell's cache: each app can read only the copy it was
 * given, and only for as long as Android lets it. The copies are cleared
 * at the next start.
 */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val dir = folder(ctx).canonicalFile
        val f = File(dir, name).canonicalFile
        // Only files in the share folder, whatever the address says.
        if (f.parentFile != dir || !f.isFile) throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = typeOf(uri.lastPathSegment ?: "")

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = file(uri)
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else f.name })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        private const val AUTHORITY = "io.github.inkwell.viewer.share"

        private fun folder(ctx: Context) = File(ctx.cacheDir, "share")

        fun typeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heif"
            "avif" -> "image/avif"
            else -> "application/octet-stream"
        }

        /** [bytes] as a file other apps can be given; null if it couldn't be written. */
        fun put(ctx: Context, name: String, bytes: ByteArray): Uri? = try {
            val dir = folder(ctx)
            dir.mkdirs()
            // A plain name: no folders, nothing hidden.
            val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ ()-]"), "_").trimStart('.').ifEmpty { "picture" }
            File(dir, safe).writeBytes(bytes)
            Uri.parse("content://$AUTHORITY/" + Uri.encode(safe))
        } catch (e: Exception) {
            null
        }

        /** Copies handed out last time aren't kept. */
        fun clear(ctx: Context) {
            folder(ctx).deleteRecursively()
        }
    }
}
