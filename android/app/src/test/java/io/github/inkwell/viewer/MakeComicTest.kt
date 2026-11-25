package io.github.inkwell.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class MakeComicTest {
    @Test
    fun aComicReadsBackInOrderStoredAndUndated() {
        val pages = listOf("1.jpg" to ByteArray(500) { 1 }, "2.png" to ByteArray(70_000) { (it % 5).toByte() }, "10.jpg" to ByteArray(3) { 9 })
        val out = ByteArrayOutputStream()
        var done = 0
        MakeComic.write(out, pages.map { (n, b) -> MakeComic.Page(n) { b } }) { done = it }
        assertEquals(3, done)
        val bytes = out.toByteArray()
        val cbz = Cbz.open(BytesSource(bytes))!!
        assertEquals(listOf("1.jpg", "2.png", "10.jpg"), cbz.pages.map { it.name })
        pages.forEachIndexed { i, (_, b) -> assertArrayEquals(b, cbz.read(cbz.pages[i])) }
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                assertEquals(ZipEntry.STORED, e.method)
                assertTrue(e.time < java.util.GregorianCalendar(1980, 0, 3).timeInMillis)
            }
        }
    }

    @Test
    fun namesAreNeverTakenOrUnsafe() {
        assertEquals("Comic.cbz", MakeComic.freeName(setOf("a.jpg"), "Comic"))
        assertEquals("Comic (2).cbz", MakeComic.freeName(setOf("comic.CBZ"), "Comic"))
        assertEquals("Comic (3).cbz", MakeComic.freeName(setOf("Comic.cbz", "Comic (2).cbz"), "Comic"))
        assertEquals("a_b_c", MakeComic.safeStem("a/b:c"))
        assertEquals("Comic", MakeComic.safeStem("..."))
    }

    @Test
    fun aComicIsKnownByWhatsInItNotItsName() {
        fun comic(vararg pages: Pair<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            MakeComic.write(out, pages.map { (n, b) -> MakeComic.Page(n) { b } })
            return out.toByteArray()
        }
        val a = comic("1.jpg" to ByteArray(10) { 1 }, "2.jpg" to ByteArray(10) { 2 })
        val same = Cbz.open(BytesSource(a.copyOf()))!!.id
        assertArrayEquals(Cbz.open(BytesSource(a))!!.id, same)
        val other = Cbz.open(BytesSource(comic("1.jpg" to ByteArray(10) { 1 }, "2.jpg" to ByteArray(10) { 3 })))!!.id
        assertTrue(!same.contentEquals(other))
        assertEquals(32, same.size)
    }

    @Test(expected = MakeComic.Cancelled::class)
    fun cancellingStops() {
        MakeComic.write(ByteArrayOutputStream(), listOf(MakeComic.Page("1.jpg") { ByteArray(1) }), cancelled = { true })
    }
}
