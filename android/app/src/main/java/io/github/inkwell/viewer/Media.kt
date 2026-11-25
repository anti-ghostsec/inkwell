package io.github.inkwell.viewer

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * A picture's place among the phone's photos (Android's MediaStore): which
 * folder it's in, so the rest of that folder can be swiped through, and
 * where an edit of it is saved.
 */
class MediaRow(
    val uri: Uri,
    val name: String,
    val bucket: String?,
    /** Its folder, as Android names it: "Pictures/Screenshots/". Android 10 on. */
    val folder: String?,
    val size: Long,
    val modified: Long,
)

object Media {
    private val IMAGES: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** What to ask for, to see the phone's pictures. */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Whether Inkwell may see the phone's pictures (all, or some on Android 14's "Select photos"). */
    fun canRead(ctx: Context) = permissions().any { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun columns(): Array<String> {
        val cols = mutableListOf(
            MediaStore.Images.ImageColumns._ID,
            MediaStore.Images.ImageColumns.DISPLAY_NAME,
            MediaStore.Images.ImageColumns.BUCKET_ID,
            MediaStore.Images.ImageColumns.SIZE,
            MediaStore.Images.ImageColumns.DATE_MODIFIED,
        )
        if (Build.VERSION.SDK_INT >= 29) cols += MediaStore.Images.ImageColumns.RELATIVE_PATH
        return cols.toTypedArray()
    }

    private fun rows(ctx: Context, from: Uri, where: String?, args: Array<String>?, one: Boolean): List<MediaRow> {
        val out = ArrayList<MediaRow>()
        val cursor = try {
            ctx.contentResolver.query(from, columns(), where, args, null)
        } catch (e: Exception) {
            null
        } ?: return out
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += MediaRow(
                    uri = ContentUris.withAppendedId(IMAGES, id),
                    name = c.getString(1) ?: continue,
                    bucket = c.getString(2),
                    folder = if (c.columnCount > 5) c.getString(5) else null,
                    size = c.getLong(3),
                    modified = c.getLong(4) * 1000,
                )
                if (one) break
            }
        }
        return out
    }

    /**
     * The phone's photo a file is, from wherever it was opened: itself if
     * it came from the photos, else the picture with its name and size.
     */
    fun find(ctx: Context, uri: Uri, name: String): MediaRow? {
        if (!canRead(ctx)) return null
        if (uri.authority == MediaStore.AUTHORITY) {
            return rows(ctx, uri, null, null, true).firstOrNull()
        }
        val size = sizeOf(ctx, uri) ?: return null
        return rows(
            ctx, IMAGES,
            "${MediaStore.Images.ImageColumns.DISPLAY_NAME} = ? AND ${MediaStore.Images.ImageColumns.SIZE} = ?",
            arrayOf(name, size.toString()), true,
        ).firstOrNull()
    }

    /** The pictures in a folder, in name order. */
    fun folder(ctx: Context, bucket: String): List<MediaRow> =
        rows(ctx, IMAGES, "${MediaStore.Images.ImageColumns.BUCKET_ID} = ?", arrayOf(bucket), false)
            .sortedWith { a, b -> Natural.compare(a.name, b.name) }

    fun sizeOf(ctx: Context, uri: Uri): Long? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** A file's bytes, as they are (up to [cap]). */
    fun bytes(ctx: Context, uri: Uri, cap: Long = 256L shl 20): ByteArray {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("can't open")
        input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > cap) throw IOException("too big")
            }
            return out.toByteArray()
        }
    }

    /**
     * Into the phone's Recycle Bin (Android 11 on; it asks you first), or,
     * on older Androids, the question Android asks before deleting it.
     * Null if it was deleted with nothing asked, or can't be.
     */
    fun trashRequest(ctx: Context, row: MediaRow): IntentSender? {
        if (Build.VERSION.SDK_INT >= 30) {
            return MediaStore.createTrashRequest(ctx.contentResolver, listOf(row.uri), true).intentSender
        }
        try {
            ctx.contentResolver.delete(row.uri, null, null)
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) return e.userAction.actionIntent.intentSender
            throw e
        }
        return null
    }

    /**
     * Saves [bytes] as [name] in the same folder as [next] (Android 10 on):
     * the new file's address, or null if it can't be put there.
     */
    fun saveBeside(ctx: Context, next: MediaRow, name: String, mime: String, bytes: ByteArray): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val folder = next.folder ?: return null
        val values = ContentValues().apply {
            put(MediaStore.Images.ImageColumns.DISPLAY_NAME, name)
            put(MediaStore.Images.ImageColumns.MIME_TYPE, mime)
            put(MediaStore.Images.ImageColumns.RELATIVE_PATH, folder)
            put(MediaStore.Images.ImageColumns.IS_PENDING, 1)
        }
        val uri = try {
            ctx.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("can't write")
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.ImageColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            try { ctx.contentResolver.delete(uri, null, null) } catch (ignored: Exception) {}
            null
        }
    }

    /** Saves [bytes] as [name] in a folder you added: the new file, or null. */
    fun saveInFolder(ctx: Context, tree: Uri, folderId: String, name: String, mime: String, bytes: ByteArray): Uri? = try {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
        val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, mime, name)
        doc?.also { d -> ctx.contentResolver.openOutputStream(d)?.use { it.write(bytes) } ?: throw IOException("can't write") }
    } catch (e: Exception) {
        null
    }
}
