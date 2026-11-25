package io.github.glint.viewer

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException

/** A file or folder in a folder you added. */
class Doc(val uri: Uri, val id: String, val name: String, val mime: String) {
    val isFolder get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    val isComic get() = Files.isComic(name, mime)
    val isPicture get() = !isComic && Files.isPicture(name, mime)
}

object Files {
    /** The most a comic is read into memory, when it can't be read in place. */
    private const val MAX_IN_MEMORY = 512L shl 20

    private val COMIC_TYPES = setOf("application/vnd.comicbook+zip", "application/x-cbz")

    fun isComic(name: String, mime: String?) =
        name.lowercase().endsWith(".cbz") || mime in COMIC_TYPES

    fun isPicture(name: String, mime: String?) =
        mime?.startsWith("image/") == true || Cbz.isPicture(name)

    /** What a file is called, as its app tells it. */
    fun name(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (e: Exception) {
            // Not every app answers; the address's last part will do.
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Picture"
    }

    /** A file to read where it lies, or (if it can only be read straight through) in memory. */
    fun source(context: Context, uri: Uri): Source {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("can't open")
        val stream = FileInputStream(pfd.fileDescriptor)
        val owner = Closeable {
            stream.close()
            pfd.close()
        }
        try {
            val channel = stream.channel
            if (channel.size() > 0) {
                // A pipe has no size and can't be read from the middle.
                val probe = java.nio.ByteBuffer.allocate(1)
                if (channel.read(probe, 0) >= 0) return ChannelSource(channel, owner)
            }
        } catch (e: IOException) {
            // Read it straight through below.
        }
        try {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_IN_MEMORY) throw IOException("too big to read into memory")
            }
            return BytesSource(out.toByteArray())
        } finally {
            owner.close()
        }
    }

    /** What's in a folder you added: folders first, then comics and pictures, in name order. */
    fun list(context: Context, tree: Uri, folderId: String): List<Doc> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = ArrayList<Doc>()
        val cursor: Cursor = context.contentResolver.query(children, cols, null, null, null) ?: return out
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val doc = Doc(DocumentsContract.buildDocumentUriUsingTree(tree, id), id, name, c.getString(2) ?: "")
                if (doc.isFolder || doc.isComic || doc.isPicture) out += doc
            }
        }
        out.sortWith(compareBy<Doc> { !it.isFolder }.then { a, b -> Natural.compare(a.name, b.name) })
        return out
    }
}
