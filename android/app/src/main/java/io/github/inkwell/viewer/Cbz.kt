package io.github.inkwell.viewer

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater

/** Bytes that can be read from anywhere in them. */
interface Source : Closeable {
    val size: Long

    /** Fills `len` bytes of [buf] from [pos]; throws if the file ends first. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int)
}

fun Source.read(pos: Long, buf: ByteArray) = read(pos, buf, 0, buf.size)

/** A file read where it lies: only the parts asked for are ever read. */
class ChannelSource(private val channel: FileChannel, private val owner: Closeable) : Source {
    override val size: Long = channel.size()

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
        val bb = ByteBuffer.wrap(buf, off, len)
        var at = pos
        while (bb.hasRemaining()) {
            val n = channel.read(bb, at)
            if (n < 0) throw IOException("the file ends early")
            at += n
        }
    }

    override fun close() = owner.close()
}

/** A file read into memory first (one that can only be read straight through). */
class BytesSource(private val bytes: ByteArray) : Source {
    override val size: Long = bytes.size.toLong()

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
        if (pos < 0 || pos + len > bytes.size) throw IOException("the file ends early")
        System.arraycopy(bytes, pos.toInt(), buf, off, len)
    }

    override fun close() {}
}

/**
 * A comic book (.cbz: a zip of pictures), read in place. It's never unpacked
 * to storage: each page is unpacked in memory when it's shown. The pages
 * are its pictures in reading order; names are only for sorting and showing.
 */
class Cbz private constructor(private val src: Source, val pages: List<Page>) : Closeable {
    class Page(
        val name: String,
        internal val method: Int,
        internal val compressed: Long,
        internal val size: Long,
        internal val header: Long,
    ) {
        /** The name without the folders inside the comic. */
        val title: String get() = name.substringAfterLast('/')
    }

    /** One page unpacked in memory: null if it's damaged, or would unpack past [MAX_PAGE_BYTES]. */
    fun read(page: Page): ByteArray? = try {
        unpack(page)
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    override fun close() = src.close()

    private fun unpack(p: Page): ByteArray? {
        if (p.size > MAX_PAGE_BYTES) return null
        val lh = ByteArray(30)
        src.read(p.header, lh)
        val h = le(lh)
        if (h.getInt(0) != LOCAL) return null
        val data = p.header + 30 + h.u16(26) + h.u16(28)
        if (p.compressed < 0 || data + p.compressed > src.size) return null
        return when (p.method) {
            STORED -> if (p.compressed > MAX_PAGE_BYTES) null else ByteArray(p.compressed.toInt()).also { src.read(data, it) }
            DEFLATED -> inflate(data, p.compressed, p.size)
            else -> null
        }
    }

    /**
     * The size a page claims can be a lie (a "zip bomb": a few KB that
     * unpack to gigabytes): unpacking stops past the cap, whatever it said.
     */
    private fun inflate(at: Long, compressed: Long, claimed: Long): ByteArray? {
        val inflater = Inflater(true)
        try {
            val out = ByteArrayOutputStream(claimed.coerceIn(1024, 64L shl 20).toInt())
            val input = ByteArray(64 * 1024)
            val output = ByteArray(64 * 1024)
            var pos = at
            val end = at + compressed
            var padded = false
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    val n = minOf(input.size.toLong(), end - pos).toInt()
                    if (n > 0) {
                        src.read(pos, input, 0, n)
                        pos += n
                        inflater.setInput(input, 0, n)
                    } else if (!padded) {
                        // Raw deflate can want one byte past the end to finish.
                        padded = true
                        inflater.setInput(ByteArray(1))
                    } else {
                        return null
                    }
                }
                if (inflater.needsDictionary()) return null
                val m = inflater.inflate(output)
                out.write(output, 0, m)
                if (out.size() > MAX_PAGE_BYTES) return null
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    companion object {
        /** The most one page may unpack to. */
        const val MAX_PAGE_BYTES = 256L shl 20

        private const val LOCAL = 0x04034b50
        private const val CENTRAL = 0x02014b50
        private const val END = 0x06054b50
        private const val ZIP64_LOCATOR = 0x07064b50
        private const val ZIP64_END = 0x06064b50
        private const val STORED = 0
        private const val DEFLATED = 8

        private val PICTURES = setOf("png", "jpg", "jpeg", "bmp", "webp", "gif", "avif", "heic", "heif")

        fun isPicture(name: String) = name.substringAfterLast('.', "").lowercase() in PICTURES

        /** The comic in [src], or null if it isn't a zip with pictures in it. */
        fun open(src: Source): Cbz? = try {
            parse(src)
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }

        private fun parse(src: Source): Cbz? {
            // The end record is in the last 22 bytes, or before a comment of up to 64 KB.
            val tailLen = minOf(src.size, 65557L).toInt()
            if (tailLen < 22) return null
            val tail = ByteArray(tailLen)
            src.read(src.size - tailLen, tail)
            val t = le(tail)
            var e = tailLen - 22
            while (e >= 0 && t.getInt(e) != END) e--
            if (e < 0) return null
            var cdSize = t.u32(e + 12)
            var cdAt = t.u32(e + 16)
            if (cdSize == 0xFFFFFFFFL || cdAt == 0xFFFFFFFFL) {
                // ZIP64 (a comic over 4 GB): the real numbers are in a record the locator points to.
                val loc = e - 20
                if (loc < 0 || t.getInt(loc) != ZIP64_LOCATOR) return null
                val rec = ByteArray(56)
                src.read(t.getLong(loc + 8), rec)
                val r = le(rec)
                if (r.getInt(0) != ZIP64_END) return null
                cdSize = r.getLong(40)
                cdAt = r.getLong(48)
            }
            if (cdAt < 0 || cdSize < 0 || cdSize > (64L shl 20) || cdAt + cdSize > src.size) return null
            val cd = ByteArray(cdSize.toInt())
            src.read(cdAt, cd)
            val c = le(cd)
            val pages = ArrayList<Page>()
            var p = 0
            while (p + 46 <= cd.size && c.getInt(p) == CENTRAL) {
                val flags = c.u16(p + 8)
                val method = c.u16(p + 10)
                var compressed = c.u32(p + 20)
                var size = c.u32(p + 24)
                val nameLen = c.u16(p + 28)
                val extraLen = c.u16(p + 30)
                val commentLen = c.u16(p + 32)
                var header = c.u32(p + 42)
                if (p + 46 + nameLen + extraLen > cd.size) break
                val utf8 = flags and 0x800 != 0
                val name = String(cd, p + 46, nameLen, if (utf8) Charsets.UTF_8 else Charsets.ISO_8859_1)
                // ZIP64's own sizes and place, for those too big for the plain fields.
                var x = p + 46 + nameLen
                val xEnd = x + extraLen
                while (x + 4 <= xEnd) {
                    val id = c.u16(x)
                    val len = c.u16(x + 2)
                    val fieldEnd = minOf(x + 4 + len, xEnd)
                    if (id == 1) {
                        var f = x + 4
                        if (size == 0xFFFFFFFFL && f + 8 <= fieldEnd) { size = c.getLong(f); f += 8 }
                        if (compressed == 0xFFFFFFFFL && f + 8 <= fieldEnd) { compressed = c.getLong(f); f += 8 }
                        if (header == 0xFFFFFFFFL && f + 8 <= fieldEnd) { header = c.getLong(f) }
                    }
                    x += 4 + len
                }
                p += 46 + nameLen + extraLen + commentLen
                val base = name.substringAfterLast('/')
                val locked = flags and 1 != 0
                // Not folders, nor the hidden "._page.jpg" twins Macs add, nor locked pages.
                if (!name.endsWith("/") && !base.startsWith(".") && !name.startsWith("__MACOSX/") && !locked && isPicture(base)) {
                    pages += Page(name, method, compressed, size, header)
                }
            }
            if (pages.isEmpty()) return null
            pages.sortWith { a, b -> Natural.compare(a.name, b.name) }
            return Cbz(src, pages)
        }

        private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        private fun ByteBuffer.u16(i: Int) = getShort(i).toInt() and 0xFFFF
        private fun ByteBuffer.u32(i: Int) = getInt(i).toLong() and 0xFFFFFFFFL
    }
}
