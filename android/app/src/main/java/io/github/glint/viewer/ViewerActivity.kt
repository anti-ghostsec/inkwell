package io.github.glint.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.github.glint.viewer.Ui.applyPrivacy
import io.github.glint.viewer.Ui.dp
import io.github.glint.viewer.Ui.label
import io.github.glint.viewer.Ui.onInsets
import java.util.concurrent.Executors
import kotlin.math.abs

/** The pictures being looked through: a comic's pages, or pictures in a folder. */
private interface Pages {
    val count: Int
    val isComic: Boolean
    fun name(i: Int): String
    fun load(i: Int): Picture?
    fun close() {}
}

private class ComicPages(private val cbz: Cbz) : Pages {
    override val count = cbz.pages.size
    override val isComic = true
    override fun name(i: Int) = cbz.pages[i].title
    override fun load(i: Int) = cbz.read(cbz.pages[i])?.let { Pictures.decode(it) }
    override fun close() = cbz.close()
}

private class FilePages(private val resolver: ContentResolver, private val files: List<Pair<Uri, String>>) : Pages {
    override val count = files.size
    override val isComic = false
    override fun name(i: Int) = files[i].second
    override fun load(i: Int) = Pictures.decode(resolver, files[i].first)
}

/**
 * Looks through pictures and comics. Tap the sides (or swipe) to turn the
 * page, the middle to show or hide the bars; pinch or double-tap to zoom.
 * A comic opens where you left it.
 */
class ViewerActivity : Activity(), ZoomView.Listener {
    private lateinit var library: Library
    private lateinit var zoom: ZoomView
    private lateinit var top: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var pageLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var menuButton: TextView

    private val work = Executors.newSingleThreadExecutor()
    private var pages: Pages? = null
    private var uri: Uri? = null
    private var name = ""
    private var index = 0
    private var rightToLeft = false
    private var barsShown = false
    private var destroyed = false

    /** The page wanted now; the loader skips pages too far from it. */
    @Volatile
    private var wanted = 0

    // Decoded pages, this one and those either side (main thread only).
    private val cache = HashMap<Int, Picture?>()
    private val loading = HashSet<Int>()
    private var showFromBottom = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        applyPrivacy(library)
        build()
        enterImmersive()
        showBars(false)
        open(intent, savedInstanceState?.getInt("index", -1) ?: -1)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    override fun onDestroy() {
        destroyed = true
        pages?.let { p -> work.execute { p.close() } }
        work.shutdown()
        super.onDestroy()
    }

    private fun build() {
        val root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.BLACK)
        zoom = ZoomView(this)
        zoom.listener = this
        zoom.fitWidth = library.fitWidth
        root.addView(zoom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.bar)
        }
        val back = label("←", 22f).apply {
            setPadding(dp(12), dp(8), dp(16), dp(8))
            setOnClickListener { finish() }
            contentDescription = "Back"
        }
        titleText = label("", 16f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        menuButton = label("⋮", 22f).apply {
            setPadding(dp(16), dp(8), dp(12), dp(8))
            setOnClickListener { showMenu() }
            contentDescription = "Menu"
        }
        top.addView(back)
        top.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(menuButton)
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bar)
        }
        pageLabel = label("", 14f, Ui.dim).apply { gravity = Gravity.CENTER_HORIZONTAL }
        seek = SeekBar(this).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) pageLabel.text = pageText(progress)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {
                    if (bar.progress != index) showPage(bar.progress)
                }
            })
        }
        bottom.addView(pageLabel)
        bottom.addView(seek)
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        root.onInsets { l, t, r, b ->
            top.setPadding(l, t, r, 0)
            bottom.setPadding(l + dp(8), dp(4), r + dp(8), b + dp(6))
        }
        setContentView(root)
        applyKeepScreenOn()
    }

    /** Pictures edge to edge, under Android's bars. Only once the window has its view. */
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    /** The bars (Glint's and Android's): shown, or out of the way of the picture. */
    private fun showBars(show: Boolean) {
        barsShown = show
        top.visibility = if (show) View.VISIBLE else View.GONE
        bottom.visibility = if (show && (pages?.count ?: 0) > 1) View.VISIBLE else View.GONE
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.decorView.windowInsetsController ?: return
            if (show) {
                c.show(WindowInsets.Type.systemBars())
            } else {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            }
        } else {
            val layout = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (show) layout else layout or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    private fun applyKeepScreenOn() {
        if (library.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun incomingUri(intent: Intent): Uri? {
        if (intent.action == Intent.ACTION_SEND) {
            return if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        }
        return intent.data
    }

    /** Works out what it was given (off the main thread: reading a comic's contents takes a moment). */
    private fun open(intent: Intent, restoreIndex: Int) {
        val target = incomingUri(intent)
        if (target == null) {
            zoom.show(null, "Nothing to show")
            return
        }
        uri = target
        zoom.show(null, "Opening…")
        val tree = intent.getStringExtra(EXTRA_TREE)?.let { Uri.parse(it) }
        val folder = intent.getStringExtra(EXTRA_FOLDER)
        work.execute {
            var found: Pages? = null
            var start = 0
            var error: String? = null
            val fileName = Files.name(this, target)
            try {
                val mime = contentResolver.getType(target)
                if (tree != null && folder != null && !Files.isComic(fileName, mime)) {
                    // A picture in a folder you added: the folder's other pictures either side.
                    val docs = Files.list(this, tree, folder).filter { it.isPicture }
                    found = FilePages(contentResolver, docs.map { it.uri to it.name })
                    start = docs.indexOfFirst { it.uri == target }.coerceAtLeast(0)
                } else if (Files.isComic(fileName, mime) || mime == "application/zip" || !Files.isPicture(fileName, mime)) {
                    val cbz = Cbz.open(Files.source(this, target))
                    found = when {
                        cbz != null -> ComicPages(cbz)
                        Files.isPicture(fileName, mime) -> FilePages(contentResolver, listOf(target to fileName))
                        else -> null
                    }
                    if (found == null) error = "This isn't a picture, or a comic\nGlint can read (.cbz)"
                } else {
                    found = FilePages(contentResolver, listOf(target to fileName))
                }
            } catch (e: SecurityException) {
                error = "Glint can't open this any more.\nOpen it again from where it's kept."
            } catch (e: Exception) {
                error = "This couldn't be opened"
            }
            val result = found
            runOnUiThread {
                if (destroyed) {
                    result?.let { p -> work.execute { p.close() } }
                    return@runOnUiThread
                }
                if (result == null) {
                    zoom.show(null, error ?: "This couldn't be opened")
                    showBars(true)
                    return@runOnUiThread
                }
                opened(result, fileName, if (restoreIndex >= 0) restoreIndex else start)
            }
        }
    }

    private fun opened(p: Pages, fileName: String, start: Int) {
        pages = p
        name = fileName
        titleText.text = fileName
        val key = uri.toString()
        var first = start
        if (p.isComic) {
            rightToLeft = library.rightToLeft(key)
            val saved = library.readingOf(key)
            if (saved != null && start == 0) first = saved.page
        }
        seek.max = (p.count - 1).coerceAtLeast(0)
        seek.layoutDirection = if (rightToLeft) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        showPage(first.coerceIn(0, p.count - 1))
        if (p.isComic && first > 0) Toast.makeText(this, "Page ${first + 1} of ${p.count}, where you left off", Toast.LENGTH_SHORT).show()
    }

    private fun pageText(i: Int): String {
        val p = pages ?: return ""
        return if (p.isComic) "Page ${i + 1} of ${p.count}" else "${i + 1} of ${p.count} · ${p.name(i)}"
    }

    private fun showPage(i: Int, fromBottom: Boolean = false) {
        val p = pages ?: return
        index = i
        wanted = i
        showFromBottom = fromBottom
        if (!p.isComic) titleText.text = p.name(i)
        pageLabel.text = pageText(i)
        seek.progress = i
        if (barsShown) showBars(true)
        // Only this page and those either side are kept.
        cache.keys.filter { abs(it - i) > 1 }.forEach { cache.remove(it) }
        if (cache.containsKey(i)) display(i) else {
            zoom.show(null, "")
            request(i)
        }
        // The pages either side, ready for a quick turn: the next first.
        request(i + 1)
        request(i - 1)
        if (p.isComic) library.setReading(uri.toString(), name, i, p.count)
    }

    private fun display(i: Int) {
        val picture = cache[i]
        if (picture == null) {
            zoom.show(null, "Page ${i + 1} can't be shown:\nit's damaged, or not a picture Glint can read")
        } else {
            zoom.show(picture.drawable, fromBottom = showFromBottom)
        }
    }

    private fun request(i: Int) {
        val p = pages ?: return
        if (i !in 0 until p.count || i in loading || cache.containsKey(i)) return
        loading += i
        work.execute {
            // Flicked past already: not worth decoding.
            val picture = if (destroyed || abs(i - wanted) > 1) null else {
                try { p.load(i) } catch (e: OutOfMemoryError) { null }
            }
            val skipped = destroyed || abs(i - wanted) > 1
            runOnUiThread {
                loading -= i
                if (destroyed || skipped || abs(i - index) > 1) return@runOnUiThread
                cache[i] = picture
                if (i == index) display(i)
            }
        }
    }

    /** On in reading order: down a tall page first, then the next page. */
    private fun go(forward: Boolean) {
        if (zoom.step(forward)) return
        turn(forward)
    }

    private fun turn(forward: Boolean) {
        val p = pages ?: return
        val next = index + if (forward) 1 else -1
        if (next !in 0 until p.count) {
            if (p.count > 1) Toast.makeText(this, if (forward) "That's the last page" else "That's the first page", Toast.LENGTH_SHORT).show()
            return
        }
        showPage(next, fromBottom = !forward && library.fitWidth)
    }

    override fun onTap(across: Float) {
        when {
            across < 0.3f -> go(forward = rightToLeft)
            across > 0.7f -> go(forward = !rightToLeft)
            else -> showBars(!barsShown)
        }
    }

    override fun onSwipe(leftward: Boolean) {
        // Left to right, a swipe to the left is the next page; in manga, to the right.
        turn(forward = leftward != rightToLeft)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> if (library.volumeKeys && pages != null) {
                go(forward = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { go(forward = !rightToLeft); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { go(forward = rightToLeft); return true }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_DOWN -> { go(true); return true }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> { go(false); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // No volume change (or click) for a key that turned the page.
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && library.volumeKeys && pages != null) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun showMenu() {
        val p = pages
        val menu = PopupMenu(this, menuButton)
        val m = menu.menu
        m.add(0, FIT_WIDTH, 0, "Fit the width").setCheckable(true).isChecked = library.fitWidth
        if (p?.isComic == true) m.add(0, RTL, 1, "Right to left (manga)").setCheckable(true).isChecked = rightToLeft
        m.add(0, VOLUME, 2, "Volume keys turn pages").setCheckable(true).isChecked = library.volumeKeys
        m.add(0, SCREEN_ON, 3, "Keep the screen on").setCheckable(true).isChecked = library.keepScreenOn
        m.add(0, HIDDEN, 4, "Hide from screenshots and recent apps").setCheckable(true).isChecked = library.hidden
        if (p != null) m.add(0, INFO, 5, "About this picture")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                FIT_WIDTH -> {
                    library.fitWidth = !library.fitWidth
                    zoom.fitWidth = library.fitWidth
                }
                RTL -> {
                    rightToLeft = !rightToLeft
                    library.setRightToLeft(uri.toString(), rightToLeft)
                    seek.layoutDirection = if (rightToLeft) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                }
                VOLUME -> library.volumeKeys = !library.volumeKeys
                SCREEN_ON -> {
                    library.keepScreenOn = !library.keepScreenOn
                    applyKeepScreenOn()
                }
                HIDDEN -> {
                    library.hidden = !library.hidden
                    applyPrivacy(library)
                }
                INFO -> showInfo()
            }
            true
        }
        menu.show()
    }

    private fun showInfo() {
        val p = pages ?: return
        val picture = cache[index]
        val lines = mutableListOf<String>()
        if (p.isComic) {
            lines += "Comic: $name"
            lines += "Page ${index + 1} of ${p.count}: ${p.name(index)}"
        } else {
            lines += p.name(index)
        }
        if (picture != null) lines += "${picture.width} × ${picture.height} pixels"
        AlertDialog.Builder(this)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    companion object {
        /** A folder you added, and the folder in it the picture is in. */
        const val EXTRA_TREE = "io.github.glint.viewer.TREE"
        const val EXTRA_FOLDER = "io.github.glint.viewer.FOLDER"

        private const val FIT_WIDTH = 1
        private const val RTL = 2
        private const val VOLUME = 3
        private const val SCREEN_ON = 4
        private const val HIDDEN = 5
        private const val INFO = 6
    }
}
