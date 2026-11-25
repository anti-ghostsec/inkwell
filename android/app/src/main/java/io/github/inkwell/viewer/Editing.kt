package io.github.inkwell.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Edit mode, as on FerroOS: a copy of the picture being changed (the file
 * itself is never touched; saving writes a new one), the steps back, and
 * the box drawn on it.
 */
class Editing(image: Bitmap, val stem: String, val jpeg: Boolean) {
    var image: Bitmap = image
        private set

    /** The box, in the picture's pixels. */
    var box: RectF? = null

    private val undo = ArrayList<Bitmap>()
    private var undoBytes = 0L

    /** How many steps back is what was last saved (or opened); null once that can't be got back to. */
    private var saved: Int? = 0

    val unsaved get() = saved != undo.size
    val canUndo get() = undo.isNotEmpty()

    private fun push(next: Bitmap) {
        // Drawn at its own pixels' size, whatever the screen's density.
        next.density = Bitmap.DENSITY_NONE
        undo += image
        undoBytes += image.allocationByteCount
        image = next
        box = null
        // Undo keeps so much; the oldest steps go past it (one is always kept).
        while (undoBytes > MAX_UNDO_BYTES && undo.size > 1) {
            undoBytes -= undo.removeAt(0).allocationByteCount
            saved = saved?.let { if (it == 0) null else it - 1 }
        }
    }

    fun undo(): Boolean {
        if (undo.isEmpty()) return false
        val last = undo.removeAt(undo.size - 1)
        undoBytes -= last.allocationByteCount
        image = last
        box = null
        return true
    }

    /** The box, in whole pixels inside the picture; null if there's none (or it's empty). */
    fun clipped(): Rect? {
        val b = box ?: return null
        val r = Rect(
            b.left.roundToInt().coerceIn(0, image.width),
            b.top.roundToInt().coerceIn(0, image.height),
            b.right.roundToInt().coerceIn(0, image.width),
            b.bottom.roundToInt().coerceIn(0, image.height),
        )
        return if (r.width() < 1 || r.height() < 1) null else r
    }

    /** Just what's in the box. */
    fun crop(): Boolean {
        val r = clipped() ?: return false
        val next = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        Canvas(next).drawBitmap(image, r, Rect(0, 0, r.width(), r.height()), null)
        push(next)
        return true
    }

    /** The box blacked out: what was under it is gone from the copy, not hidden. */
    fun blackOut(): Boolean {
        val r = clipped() ?: return false
        val next = image.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(next).drawRect(r, Paint().apply { color = Color.BLACK })
        push(next)
        return true
    }

    /** A quarter turn, clockwise or not. */
    fun rotate(clockwise: Boolean) {
        val m = Matrix().apply { postRotate(if (clockwise) 90f else -90f) }
        push(Bitmap.createBitmap(image, 0, 0, image.width, image.height, m, true).copy(Bitmap.Config.ARGB_8888, true))
    }

    /**
     * The picture as a file: a JPEG if it was one, else a PNG. Only the
     * pixels: no location, camera or other details from the original.
     */
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        image.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, out)
        return out.toByteArray()
    }

    val extension get() = if (jpeg) "jpg" else "png"
    val mime get() = if (jpeg) "image/jpeg" else "image/png"

    /** What a saved copy is called: "photo-edited.jpg". */
    val newName get() = "$stem-edited.$extension"

    fun markSaved() {
        saved = undo.size
    }

    companion object {
        /** How much Undo keeps, as on FerroOS. */
        const val MAX_UNDO_BYTES = 256L shl 20

        fun isJpegName(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg")
    }
}
