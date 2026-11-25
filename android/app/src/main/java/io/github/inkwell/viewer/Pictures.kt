package io.github.inkwell.viewer

import android.content.ContentResolver
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/** A picture ready to draw (animated GIFs and WebPs move), and its size as it was saved. */
class Picture(val drawable: Drawable, val width: Int, val height: Int)

object Pictures {
    /**
     * The most pixels a picture is drawn with: bigger ones are decoded at a
     * half, a quarter… (a 100-megapixel photo would take 400 MB, and Android
     * won't draw anything over about 100).
     */
    private const val MAX_PIXELS = 20_000_000L
    private const val MAX_SIDE = 8192

    fun decode(bytes: ByteArray): Picture? =
        if (Build.VERSION.SDK_INT >= 28) modern(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        else legacy { ByteArrayInputStream(bytes) }

    fun decode(resolver: ContentResolver, uri: Uri): Picture? =
        if (Build.VERSION.SDK_INT >= 28) modern(ImageDecoder.createSource(resolver, uri))
        else legacy { resolver.openInputStream(uri) ?: throw java.io.IOException("can't open") }

    private fun sample(w: Int, h: Int): Int {
        var s = 1
        while ((w.toLong() / s) * (h.toLong() / s) > MAX_PIXELS || maxOf(w, h) / s > MAX_SIDE) s *= 2
        return s
    }

    /** Android 9 on: animations too, and photos the right way up (EXIF). */
    private fun modern(src: ImageDecoder.Source): Picture? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            var w = 0
            var h = 0
            val d = ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
                w = info.size.width
                h = info.size.height
                val s = sample(w, h)
                if (s > 1) decoder.setTargetSampleSize(s)
            }
            d.isFilterBitmap = true
            Picture(d, w, h)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * A picture to edit: a still you can change (an animation's first
     * frame), at most [MAX_PIXELS], drawn at its own pixels' size.
     */
    fun editable(bytes: ByteArray): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0) {
            null
        } else {
            val bmp = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                    val s = sample(info.size.width, info.size.height)
                    if (s > 1) decoder.setTargetSampleSize(s)
                }
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                    inSampleSize = sample(opts.outWidth, opts.outHeight)
                    inMutable = true
                })
            }
            bmp?.let { if (it.config == Bitmap.Config.ARGB_8888 && it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
                ?.also { it.density = Bitmap.DENSITY_NONE }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** Android 8: still pictures (an animation shows its first frame). */
    private fun legacy(open: () -> InputStream): Picture? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight) }
            open().use { BitmapFactory.decodeStream(it, null, opts) }?.let {
                it.density = Bitmap.DENSITY_NONE
                val d = BitmapDrawable(Resources.getSystem(), it)
                d.isFilterBitmap = true
                Picture(d, bounds.outWidth, bounds.outHeight)
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
