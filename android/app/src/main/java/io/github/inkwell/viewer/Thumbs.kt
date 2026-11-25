package io.github.inkwell.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Small pictures for lists: a picture's own (from Android when it has one
 * ready), or a comic's cover (its first page). Kept in memory only, never
 * written anywhere.
 */
class Thumbs(context: Context, private val size: Int) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(24 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Puts [uri]'s small picture in [view] once it's ready (if [view] is still showing that one by then). */
    fun load(view: ImageView, uri: Uri, comic: Boolean) {
        val key = uri.toString()
        view.tag = key
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        work.execute {
            val bmp = try {
                if (comic) cover(uri) else picture(uri)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            if (bmp != null) cache.put(key, bmp)
            main.post { if (view.tag == key && bmp != null) view.setImageBitmap(bmp) }
        }
    }

    fun close() = work.shutdownNow()

    private fun picture(uri: Uri): Bitmap? {
        if (DocumentsContract.isDocumentUri(app, uri)) {
            try {
                DocumentsContract.getDocumentThumbnail(app.contentResolver, uri, Point(size, size), null)?.let { return it }
            } catch (e: Exception) {
                // Not every folder's app makes them: decoded here instead.
            }
        }
        return sampled { app.contentResolver.openInputStream(uri) }
    }

    private fun cover(uri: Uri): Bitmap? {
        val cbz = Cbz.open(Files.source(app, uri)) ?: return null
        cbz.use {
            val bytes = it.read(it.pages[0]) ?: return null
            return sampled { bytes.inputStream() }
        }
    }

    private fun sampled(open: () -> java.io.InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var s = 1
        while (bounds.outWidth / (s * 2) >= size && bounds.outHeight / (s * 2) >= size) s *= 2
        return open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
    }
}
