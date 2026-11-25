package io.github.inkwell.viewer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pictures into a comic book (.cbz), as FerroOS's Files does: a folder's
 * pictures into "Folder.cbz" beside it, or the ones you pick into a comic in
 * their folder. The pages go in the order people count ("p2" before "p10"),
 * stored as they are (pictures are squeezed already), each under its name
 * alone, with no dates or other details from the phone. Nothing is
 * overwritten, and the pictures stay where they were.
 */
object MakeComic {
    /** What a picture's bytes are fetched with. */
    class Page(val name: String, val read: () -> ByteArray)

    class Cancelled : IOException("cancelled")

    /** Of [docs], the pictures, in page order. */
    fun pages(docs: List<Doc>): List<Doc> =
        docs.filter { !it.isFolder && !it.isComic && Cbz.isPicture(it.name) && !it.name.startsWith(".") }
            .sortedWith { a, b -> Natural.compare(a.name, b.name) }

    /** "Name.cbz", or "Name (2).cbz" and on if that's [taken]. */
    fun freeName(taken: Set<String>, stem: String): String {
        val lower = taken.map { it.lowercase() }.toSet()
        var n = 1
        while (true) {
            val name = if (n == 1) "$stem.cbz" else "$stem ($n).cbz"
            if (name.lowercase() !in lower) return name
            n++
        }
    }

    /** A name a file can have: no slashes or other characters folders refuse. */
    fun safeStem(name: String): String =
        name.map { if (it in "/\\:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("")
            .trim().trimStart('.').take(120).ifEmpty { "Comic" }

    /**
     * Writes [pages] into [out] as a comic: stored, in the order given,
     * every page dated the same (1 January 1980, zip's first day) so no
     * dates from the phone go with it. [progress] hears each page done.
     */
    fun write(out: OutputStream, pages: List<Page>, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}) {
        val used = HashSet<String>()
        ZipOutputStream(out).use { z ->
            z.setMethod(ZipOutputStream.STORED)
            pages.forEachIndexed { i, page ->
                if (cancelled()) throw Cancelled()
                val data = page.read()
                // Two pages can't share a name in one comic.
                var name = page.name.substringAfterLast('/').ifEmpty { "page" }
                if (!used.add(name.lowercase())) {
                    val stem = name.substringBeforeLast('.')
                    val ext = name.substringAfterLast('.', "")
                    var n = 2
                    while (!used.add("$stem ($n).$ext".lowercase())) n++
                    name = "$stem ($n).$ext"
                }
                val e = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = data.size.toLong()
                    compressedSize = data.size.toLong()
                    crc = CRC32().apply { update(data) }.value
                    time = FIRST_DAY
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
                progress(i + 1)
            }
        }
    }

    /** 1 January 1980, local time: the earliest date a zip can hold. */
    private val FIRST_DAY = java.util.GregorianCalendar(1980, 0, 1).timeInMillis

    /** Every name in a folder you added (hidden ones too), so a new one taken is never reused. */
    private fun names(context: Context, tree: Uri, folderId: String): Set<String> {
        val out = HashSet<String>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { out += it }
        }
        return out
    }

    /**
     * [pages] made into a comic [stem].cbz in folder [intoId] of [tree].
     * Made under a hidden name first and then renamed, so a half-made comic
     * is never seen (where Android can't rename, it's made under its own
     * name, and deleted if it can't be finished). The name it was given.
     */
    fun make(
        context: Context,
        tree: Uri,
        intoId: String,
        stem: String,
        pages: List<Doc>,
        cancelled: () -> Boolean,
        progress: (Int) -> Unit,
    ): String {
        if (pages.isEmpty()) throw IOException("There are no pictures to add.")
        val resolver = context.contentResolver
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, intoId)
        val wanted = freeName(names(context, tree, intoId), safeStem(stem))
        val work = ".inkwell-${System.currentTimeMillis()}.cbz"
        // "octet-stream": Android adds no extension of its own to the name.
        var made = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", work)
            ?: throw IOException("Android didn't let Inkwell make a file in this folder")
        try {
            val out = resolver.openOutputStream(made, "wt") ?: throw IOException("can't write the comic")
            out.use { o -> write(o, pages.map { d -> Page(d.name) { Media.bytes(context, d.uri) } }, cancelled, progress) }
            val renamed = try {
                DocumentsContract.renameDocument(resolver, made, wanted)
            } catch (e: Exception) {
                null
            }
            if (renamed != null) {
                made = renamed
            } else {
                // No renaming here: written again, straight under its name.
                val direct = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", wanted)
                    ?: throw IOException("Android didn't let Inkwell name the comic")
                try {
                    val out2 = resolver.openOutputStream(direct, "wt") ?: throw IOException("can't write the comic")
                    out2.use { o -> write(o, pages.map { d -> Page(d.name) { Media.bytes(context, d.uri) } }, cancelled, progress) }
                } catch (e: Exception) {
                    runCatching { DocumentsContract.deleteDocument(resolver, direct) }
                    throw e
                }
                runCatching { DocumentsContract.deleteDocument(resolver, made) }
                made = direct
            }
            return Files.name(context, made)
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, made) }
            throw e
        }
    }
}
