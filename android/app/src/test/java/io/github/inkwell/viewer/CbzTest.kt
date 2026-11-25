package io.github.inkwell.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CbzTest {
    private fun zip(vararg entries: Pair<String, ByteArray>, stored: Set<String> = emptySet()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (name in stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun pagesAreInTheOrderPeopleCount() {
        val names = listOf("page10.jpg", "Page2.jpg", "page1.jpg", "page002b.jpg", "cover.png")
        assertEquals(listOf("cover.png", "page1.jpg", "Page2.jpg", "page002b.jpg", "page10.jpg"), names.sortedWith(Natural))
    }

    @Test
    fun onlyPicturesArePages() {
        val bytes = zip(
            "ComicInfo.xml" to "<x/>".toByteArray(),
            "art/" to ByteArray(0),
            "art/2.png" to ByteArray(10) { 2 },
            "art/10.png" to ByteArray(10) { 10 },
            "art/._2.png" to ByteArray(3),
            "__MACOSX/art/2.png" to ByteArray(3),
            "notes.txt" to "hi".toByteArray(),
        )
        val cbz = Cbz.open(BytesSource(bytes))!!
        assertEquals(listOf("art/2.png", "art/10.png"), cbz.pages.map { it.name })
        assertEquals("10.png", cbz.pages[1].title)
    }

    @Test
    fun pagesUnpackPackedOrNot() {
        val big = ByteArray(300_000) { (it % 7).toByte() }
        val small = "stored as it is".toByteArray()
        val cbz = Cbz.open(BytesSource(zip("1.jpg" to big, "2.jpg" to small, stored = setOf("2.jpg"))))!!
        assertArrayEquals(big, cbz.read(cbz.pages[0]))
        assertArrayEquals(small, cbz.read(cbz.pages[1]))
    }

    @Test
    fun notAComic() {
        assertNull(Cbz.open(BytesSource("just some text, not a zip".toByteArray())))
        assertNull(Cbz.open(BytesSource(zip("readme.txt" to "no pictures".toByteArray()))))
        assertNull(Cbz.open(BytesSource(ByteArray(0))))
    }

    @Test
    fun aDamagedPageIsJustThatPage() {
        val bytes = zip("1.jpg" to ByteArray(5000) { 1 }, "2.jpg" to ByteArray(5000) { 2 })
        val cbz = Cbz.open(BytesSource(bytes))!!
        // Break the first page's local header.
        val first = cbz.pages[0]
        val broken = bytes.copyOf()
        broken[0] = 0
        val damaged = Cbz.open(BytesSource(broken))!!
        assertNull(damaged.read(damaged.pages[0]))
        assertArrayEquals(ByteArray(5000) { 2 }, damaged.read(damaged.pages[1]))
        assertEquals("1.jpg", first.name)
    }
}
