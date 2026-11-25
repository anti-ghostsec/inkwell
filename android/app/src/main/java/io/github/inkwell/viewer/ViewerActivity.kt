package io.github.inkwell.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.inkwell.viewer.Ui.applyPrivacy
import io.github.inkwell.viewer.Ui.dp
import io.github.inkwell.viewer.Ui.label
import io.github.inkwell.viewer.Ui.onInsets
import java.util.concurrent.Executors
import kotlin.math.abs

/** A picture in a list being looked through, and (once known) its place among the phone's photos. */
private class Item(val uri: Uri, val name: String, var media: MediaRow? = null)

/** The pictures being looked through: a comic's pages, or pictures in a folder. */
private interface Pages {
    val count: Int
    val isComic: Boolean
    fun name(i: Int): String
    fun load(i: Int): Picture?

    /** The picture's file, as it is. */
    fun bytes(i: Int): ByteArray?
    fun item(i: Int): Item? = null
    fun close() {}
}

private class ComicPages(private val cbz: Cbz) : Pages {
    override val count = cbz.pages.size
    override val isComic = true
    override fun name(i: Int) = cbz.pages[i].title
    override fun load(i: Int) = cbz.read(cbz.pages[i])?.let { Pictures.decode(it) }
    override fun bytes(i: Int) = cbz.read(cbz.pages[i])
    override fun close() = cbz.close()
}

/** Pictures: one, or a folder's. [tree] and [folderId]: a folder you added, if they're from one. */
private class FilePages(private val ctx: Context, val items: MutableList<Item>, val tree: Uri? = null, val folderId: String? = null) : Pages {
    override val count get() = items.size
    override val isComic = false
    override fun name(i: Int) = items[i].name
    override fun load(i: Int) = Pictures.decode(ctx.contentResolver, items[i].uri)
    override fun bytes(i: Int) = try { Media.bytes(ctx, items[i].uri) } catch (e: Exception) { null }
    override fun item(i: Int) = items.getOrNull(i)
}

/**
 * Looks through pictures and comics, side by side in a strip you slide
 * along (StripView). A tap shows or hides the bars and their buttons;
 * pinch or double-tap to zoom; hold for the picture's menu. A comic opens
 * where you left it. Edit mode shows the one picture on its own (ZoomView).
 */
class ViewerActivity : Activity(), ZoomView.Listener, StripView.Listener, StripView.Source {
    private lateinit var library: Library
    private lateinit var root: FrameLayout
    private lateinit var zoom: ZoomView
    private lateinit var strip: StripView
    private lateinit var actions: LinearLayout
    private lateinit var top: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var pageLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var menuButton: TextView
    private lateinit var notice: LinearLayout
    private lateinit var editBar: HorizontalScrollView
    private lateinit var editButtons: Map<String, Button>
    private lateinit var anchor: View

    private val work = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pages: Pages? = null
    private var uri: Uri? = null
    private var name = ""
    private var index = 0
    private var rightToLeft = false
    private var barsShown = false
    private var destroyed = false

    /** The pages on screen now; the loader skips pages too far from them. */
    @Volatile
    private var wantFrom = 0

    @Volatile
    private var wantTo = 0

    /** Counts up whenever the list changes, so pictures loaded for the old one are dropped. */
    private var generation = 0

    // Decoded pages, this one and those either side (main thread only).
    private val cache = HashMap<Int, Picture?>()
    private val loading = HashSet<Int>()

    private var editing: Editing? = null
    private var sliding = false

    // What "Save a copy" or Save (when there's nowhere else) writes, once a file is chosen.
    private var pendingSave: ByteArray? = null
    private var pendingSaveIsEdit = false
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        applyPrivacy(library)
        if (savedInstanceState == null) work.execute { ShareProvider.clear(this) }
        build()
        enterImmersive()
        showBars(true)
        open(intent, savedInstanceState?.getInt("index", -1) ?: -1)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        pages?.let { p -> work.execute { p.close() } }
        work.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        stopSlideshow()
    }

    // ---- The window ----

    private fun build() {
        root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.BLACK)
        strip = StripView(this)
        strip.source = this
        strip.listener = this
        strip.wholePages = library.wholePages
        root.addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Edit mode's picture, and notes ("Opening…"), on their own.
        zoom = ZoomView(this)
        zoom.listener = this
        root.addView(zoom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Where a held-down picture's menu opens from.
        anchor = View(this)
        root.addView(anchor, FrameLayout.LayoutParams(1, 1))

        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.bar)
        }
        val back = label("←", 22f).apply {
            setPadding(dp(12), dp(8), dp(16), dp(8))
            setOnClickListener { leave() }
            contentDescription = "Back"
        }
        titleText = label("", 16f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        menuButton = label("⋮", 22f).apply {
            setPadding(dp(16), dp(8), dp(12), dp(8))
            setOnClickListener { showMenu(menuButton) }
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
        // What you'd want to do with the picture, as buttons: no hunting for them.
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((key, text) in listOf("edit" to "✎ Edit", "share" to "Share", "delete" to "Delete", "more" to "More")) {
            actions.addView(Button(this).apply {
                this.text = text
                tag = key
                isAllCaps = false
                textSize = 15f
                setOnClickListener {
                    when (key) {
                        "edit" -> enterEdit()
                        "share" -> choose(SHARE)
                        "delete" -> delete()
                        "more" -> showMenu(this)
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        }
        bottom.addView(pageLabel)
        bottom.addView(seek)
        bottom.addView(actions)
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Edit mode's tools, along the bottom.
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val buttons = LinkedHashMap<String, Button>()
        for ((key, text) in listOf(
            "undo" to "Undo", "crop" to "Crop", "black" to "Black out", "zoom" to "Zoom to box",
            "left" to "⟲", "right" to "⟳", "save" to "Save", "done" to "Done",
        )) {
            val b = Button(this).apply {
                this.text = text
                isAllCaps = false
                setOnClickListener { editAction(key) }
            }
            buttons[key] = b
            tools.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })
        }
        editButtons = buttons
        editBar = HorizontalScrollView(this).apply {
            setBackgroundColor(Ui.bar)
            addView(tools)
            visibility = View.GONE
        }
        root.addView(editBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // A question or note, above the picture's bottom edge.
        notice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.card)
            setPadding(dp(16), dp(12), dp(16), dp(8))
            visibility = View.GONE
        }
        root.addView(notice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        root.onInsets { l, t, r, b ->
            top.setPadding(l, t, r, 0)
            bottom.setPadding(l + dp(8), dp(4), r + dp(8), b + dp(6))
            editBar.setPadding(l, 0, r, b)
            notice.setPadding(l + dp(16), dp(12), r + dp(16), b + dp(8))
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

    /** The bars (Inkwell's and Android's): shown, or out of the way of the picture. */
    private fun showBars(show: Boolean) {
        barsShown = show
        top.visibility = if (show || editing != null) View.VISIBLE else View.GONE
        bottom.visibility = if (show && editing == null && pages != null) View.VISIBLE else View.GONE
        val several = (pages?.count ?: 0) > 1
        seek.visibility = if (several) View.VISIBLE else View.GONE
        actions.findViewWithTag<View>("delete")?.visibility = if (pages?.item(index) != null) View.VISIBLE else View.GONE
        val system = show || editing != null
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.decorView.windowInsetsController ?: return
            if (system) {
                c.show(WindowInsets.Type.systemBars())
            } else {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            }
        } else {
            val layout = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (system) layout else layout or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
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

    /** A note with buttons above the bottom edge; any button closes it. */
    private fun showNotice(text: String, vararg actions: Pair<String, () -> Unit>) {
        notice.removeAllViews()
        notice.addView(label(text, 15f))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        for ((label, run) in actions) {
            row.addView(Button(this).apply {
                this.text = label
                isAllCaps = false
                setOnClickListener {
                    notice.visibility = View.GONE
                    run()
                }
            })
        }
        notice.addView(row)
        notice.visibility = View.VISIBLE
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- Opening ----

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
            var lone = false
            val fileName = Files.name(this, target)
            try {
                val mime = contentResolver.getType(target)
                if (tree != null && folder != null && !Files.isComic(fileName, mime)) {
                    // A picture in a folder you added: the folder's other pictures either side.
                    val docs = Files.list(this, tree, folder).filter { it.isPicture }
                    found = FilePages(this, docs.map { Item(it.uri, it.name) }.toMutableList(), tree, folder)
                    start = docs.indexOfFirst { it.uri == target }.coerceAtLeast(0)
                } else if (Files.isComic(fileName, mime) || mime == "application/zip" || !Files.isPicture(fileName, mime)) {
                    val cbz = Cbz.open(Files.source(this, target))
                    found = when {
                        cbz != null -> ComicPages(cbz)
                        Files.isPicture(fileName, mime) -> FilePages(this, mutableListOf(Item(target, fileName)))
                        else -> null
                    }
                    if (found == null) error = "This isn't a picture, or a comic\nInkwell can read (.cbz)"
                } else {
                    // A picture from another app: the rest of its folder too, if Inkwell may see it.
                    val siblings = folderOf(target, fileName)
                    if (siblings != null) {
                        found = FilePages(this, siblings.first)
                        start = siblings.second
                    } else {
                        found = FilePages(this, mutableListOf(Item(target, fileName)))
                        lone = true
                    }
                }
            } catch (e: SecurityException) {
                error = "Inkwell can't open this any more.\nOpen it again from where it's kept."
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
                if (lone && !Media.canRead(this) && !library.askedPhotos) askForFolder()
            }
        }
    }

    /** The pictures in the folder [target] is in, and where it is among them; null if that can't be seen. */
    private fun folderOf(target: Uri, fileName: String): Pair<MutableList<Item>, Int>? {
        val row = Media.find(this, target, fileName) ?: return null
        val bucket = row.bucket ?: return null
        val rows = Media.folder(this, bucket)
        if (rows.size < 2) return null
        val at = rows.indexOfFirst { it.uri == row.uri }
        if (at < 0) return null
        return rows.map { Item(it.uri, it.name, it) }.toMutableList() to at
    }

    private fun askForFolder() {
        showNotice(
            "See the other pictures in this folder too? Android will ask whether Inkwell may see your photos.",
            "Not now" to { library.askedPhotos = true },
            "Allow" to {
                library.askedPhotos = true
                requestPermissions(Media.permissions(), REQ_PHOTOS)
            },
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQ_PHOTOS && Media.canRead(this)) widenToFolder()
        if (requestCode == REQ_WRITE && results.any { it == PackageManager.PERMISSION_GRANTED }) delete()
    }

    /** Now Inkwell may see the photos: the rest of the folder, around the picture showing. */
    private fun widenToFolder() {
        val p = pages as? FilePages ?: return
        if (p.count != 1) return
        val item = p.items[0]
        work.execute {
            val siblings = folderOf(item.uri, item.name)
            runOnUiThread {
                if (destroyed || siblings == null) return@runOnUiThread
                replacePages(FilePages(this, siblings.first), siblings.second)
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
        seek.layoutDirection = if (rightToLeft) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        strip.rightToLeft = rightToLeft
        replacePages(p, first)
        if (p.isComic && first > 0) toast("Page ${first + 1} of ${p.count}, where you left off")
    }

    /** A new or changed list of pictures, showing picture [at]. */
    private fun replacePages(p: Pages, at: Int) {
        pages = p
        generation++
        cache.clear()
        loading.clear()
        seek.max = (p.count - 1).coerceAtLeast(0)
        zoom.show(null)
        zoom.visibility = View.GONE
        strip.reset(at.coerceIn(0, (p.count - 1).coerceAtLeast(0)))
        if (barsShown) showBars(true)
    }

    // ---- Turning pages ----

    private fun pageText(i: Int): String {
        val p = pages ?: return ""
        return if (p.isComic) "Page ${i + 1} of ${p.count}" else "${i + 1} of ${p.count} · ${p.name(i)}"
    }

    /** Straight to page [i] (the slider, or opening). */
    private fun showPage(i: Int) {
        val p = pages ?: return
        if (p.count == 0) return
        strip.jumpTo(i)
    }

    // StripView.Source
    override val count get() = pages?.count ?: 0
    override fun picture(i: Int) = cache[i]
    override fun failed(i: Int) = cache.containsKey(i) && cache[i] == null

    /** The page in the middle of the screen changed. */
    override fun onPage(i: Int) {
        val p = pages ?: return
        if (i !in 0 until p.count) return
        index = i
        if (!p.isComic && editing == null) titleText.text = p.name(i)
        pageLabel.text = pageText(i)
        seek.progress = i
        if (barsShown) showBars(true)
        if (p.isComic) library.setReading(uri.toString(), name, i, p.count)
    }

    /** These pages are on screen: loaded, with one more either side ready; the rest let go. */
    override fun onVisible(from: Int, to: Int) {
        wantFrom = from - 1
        wantTo = to + 1
        cache.keys.filter { it < from - 1 || it > to + 1 }.forEach { cache.remove(it) }
        for (i in from..to) request(i)
        request(to + 1)
        request(from - 1)
    }

    private fun request(i: Int) {
        val p = pages ?: return
        if (i !in 0 until p.count || i in loading || cache.containsKey(i)) return
        loading += i
        val gen = generation
        work.execute {
            // Slid past already: not worth decoding.
            val skip = destroyed || i < wantFrom || i > wantTo
            val picture = if (skip) null else {
                try { p.load(i) } catch (e: OutOfMemoryError) { null } catch (e: Exception) { null }
            }
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                loading -= i
                if (destroyed || skip || i < wantFrom || i > wantTo) return@runOnUiThread
                cache[i] = picture
                strip.pageChanged(i)
            }
        }
    }

    /** On in reading order (keys, slideshow): across a wide page first, then the next page. */
    private fun go(forward: Boolean) {
        if (!strip.canGo(forward)) {
            if ((pages?.count ?: 0) > 1 && !sliding) toast(if (forward) "That's the last one" else "That's the first one")
            return
        }
        strip.step(forward)
    }

    override fun onTap() {
        if (sliding) {
            stopSlideshow()
            return
        }
        showBars(!barsShown)
    }

    // ZoomView's, in edit mode: a tap there clears the box (ZoomView does it); no page turns.
    override fun onTap(across: Float) {}
    override fun onSwipe(leftward: Boolean) {}

    override fun onHold(x: Float, y: Float) {
        stopSlideshow()
        anchor.translationX = x
        anchor.translationY = y
        showMenu(anchor, settings = false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (editing == null) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { go(forward = !rightToLeft); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { go(forward = rightToLeft); return true }
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_DOWN -> { go(true); return true }
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> { go(false); return true }
                KeyEvent.KEYCODE_FORWARD_DEL -> { delete(); return true }
                KeyEvent.KEYCODE_E -> { enterEdit(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- Slideshow ----

    private val slide = object : Runnable {
        override fun run() {
            if (!sliding) return
            if (strip.canGo(true)) {
                strip.step(true)
                main.postDelayed(this, SLIDE_MS)
            } else {
                stopSlideshow()
            }
        }
    }

    private fun startSlideshow() {
        if ((pages?.count ?: 0) < 2) return
        sliding = true
        showBars(false)
        toast("Slideshow: tap to stop")
        main.postDelayed(slide, SLIDE_MS)
    }

    private fun stopSlideshow() {
        if (!sliding) return
        sliding = false
        main.removeCallbacks(slide)
    }

    // ---- The picture's menu ----

    private fun showMenu(from: View, settings: Boolean = true) {
        stopSlideshow()
        val p = pages
        val item = p?.item(index)
        val shown = p != null && cache[index] != null
        val menu = PopupMenu(this, from)
        val m = menu.menu
        if (shown) {
            m.add(0, SHARE, 0, "Share")
            m.add(0, OPEN_WITH, 1, "Open with…")
            m.add(0, EDIT, 2, if (p?.isComic == true) "Edit this page" else "Edit")
            m.add(0, SAVE_COPY, 3, "Save a copy…")
            m.add(0, WALLPAPER, 4, "Set as…")
            if (item != null) m.add(0, DELETE, 5, "Delete")
            if ((p?.count ?: 0) > 1) m.add(0, SLIDESHOW, 6, "Slideshow")
            m.add(0, FIT, 8, "Zoom out")
            m.add(0, INFO, 9, "Details")
        }
        if (settings) {
            m.add(1, FIT_WIDTH, 20, "Whole pages (one to a screen)").setCheckable(true).isChecked = library.wholePages
            if (p?.isComic == true) m.add(1, RTL, 21, "Right to left (manga)").setCheckable(true).isChecked = rightToLeft
            m.add(1, SCREEN_ON, 22, "Keep the screen on").setCheckable(true).isChecked = library.keepScreenOn
            m.add(1, HIDDEN, 23, "Hide from screenshots and recent apps").setCheckable(true).isChecked = library.hidden
        }
        if (m.size() == 0) return
        menu.setOnMenuItemClickListener { choose(it.itemId); true }
        menu.show()
    }

    private fun choose(id: Int) {
        when (id) {
            SHARE -> handOut { uri, mime ->
                Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share")
            }
            OPEN_WITH -> handOut { uri, mime ->
                Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Open with")
            }
            WALLPAPER -> handOut { uri, mime ->
                Intent.createChooser(Intent(Intent.ACTION_ATTACH_DATA).apply {
                    setDataAndType(uri, mime)
                    putExtra("mimeType", mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Set as")
            }
            SAVE_COPY -> saveCopy()
            EDIT -> enterEdit()
            DELETE -> delete()
            SLIDESHOW -> startSlideshow()
            FIT -> strip.fit()
            INFO -> showInfo()
            FIT_WIDTH -> {
                library.wholePages = !library.wholePages
                strip.wholePages = library.wholePages
            }
            RTL -> {
                rightToLeft = !rightToLeft
                library.setRightToLeft(uri.toString(), rightToLeft)
                seek.layoutDirection = if (rightToLeft) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                strip.rightToLeft = rightToLeft
            }
            SCREEN_ON -> {
                library.keepScreenOn = !library.keepScreenOn
                applyKeepScreenOn()
            }
            HIDDEN -> {
                library.hidden = !library.hidden
                applyPrivacy(library)
            }
        }
    }

    /** The name the picture showing is handed out or saved under. */
    private fun fileNameNow(): String {
        val p = pages ?: return "picture"
        if (!p.isComic) return p.name(index)
        val stem = name.substringBeforeLast('.')
        val ext = p.name(index).substringAfterLast('.', "jpg")
        return "$stem - page ${index + 1}.$ext"
    }

    /** The picture showing, copied where another app can be given it, then [make]'s intent started. */
    private fun handOut(make: (Uri, String) -> Intent) {
        val p = pages ?: return
        val i = index
        val fileName = fileNameNow()
        work.execute {
            val bytes = p.bytes(i)
            val shared = bytes?.let { ShareProvider.put(this, fileName, it) }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (shared == null) {
                    toast("Couldn't get the picture ready")
                    return@runOnUiThread
                }
                try {
                    startActivity(make(shared, ShareProvider.typeOf(fileName)))
                } catch (e: Exception) {
                    toast("No app here can do that")
                }
            }
        }
    }

    private fun saveCopy() {
        val p = pages ?: return
        val i = index
        val fileName = fileNameNow()
        work.execute {
            val bytes = p.bytes(i)
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (bytes == null) {
                    toast("Couldn't read the picture")
                    return@runOnUiThread
                }
                pickSaveFile(bytes, fileName, ShareProvider.typeOf(fileName), isEdit = false)
            }
        }
    }

    private fun pickSaveFile(bytes: ByteArray, fileName: String, mime: String, isEdit: Boolean) {
        pendingSave = bytes
        pendingSaveIsEdit = isEdit
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_SAVE)
    }

    private fun showInfo() {
        val p = pages ?: return
        val picture = cache[index]
        val item = p.item(index)
        val i = index
        work.execute {
            val row = item?.let { it.media ?: Media.find(this, it.uri, it.name)?.also { r -> it.media = r } }
            val size = item?.let { row?.size ?: Media.sizeOf(this, it.uri) }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                val lines = mutableListOf<String>()
                if (p.isComic) {
                    lines += "Comic: $name"
                    lines += "Page ${i + 1} of ${p.count}: ${p.name(i)}"
                } else {
                    lines += p.name(i)
                }
                if (picture != null) lines += "${picture.width} × ${picture.height} pixels"
                if (size != null && size > 0) lines += Formatter.formatFileSize(this, size)
                row?.folder?.let { lines += "In: $it" }
                if (row != null && row.modified > 0) {
                    lines += "Changed: " + DateFormat.getMediumDateFormat(this).format(row.modified) + " " +
                        DateFormat.getTimeFormat(this).format(row.modified)
                }
                AlertDialog.Builder(this)
                    .setMessage(lines.joinToString("\n"))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ---- Deleting ----

    /**
     * Deletes the picture showing: into Android's Recycle Bin where there's
     * one (Android 11 on; Android asks you first), else for good, once you've said so.
     */
    private fun delete() {
        val p = pages as? FilePages ?: return
        val item = p.item(index) ?: return
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE)
            return
        }
        work.execute {
            val row = item.media ?: Media.find(this, item.uri, item.name)?.also { item.media = it }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                when {
                    row != null -> trash(row)
                    p.tree != null -> confirm("Delete “${item.name}” for good? It can't be undone.", "Delete") {
                        work.execute {
                            val done = try { DocumentsContract.deleteDocument(contentResolver, item.uri) } catch (e: Exception) { false }
                            runOnUiThread { if (done) removeShowing("Deleted") else toast("Android didn't let Inkwell delete it") }
                        }
                    }
                    !Media.canRead(this) -> showNotice(
                        "To delete pictures, Inkwell needs to see your photos.",
                        "Not now" to {},
                        "Allow" to { requestPermissions(Media.permissions(), REQ_PHOTOS) },
                    )
                    else -> toast("Android didn't let Inkwell delete it")
                }
            }
        }
    }

    private fun trash(row: MediaRow) {
        if (Build.VERSION.SDK_INT < 30) {
            // No Recycle Bin: Inkwell asks first (Android asks too, for a picture another app made).
            confirm("Delete “${row.name}” for good? It can't be undone.", "Delete") {
                val sender = try {
                    Media.trashRequest(this, row)
                } catch (e: Exception) {
                    toast("Android didn't let Inkwell delete it")
                    return@confirm
                }
                if (sender == null) removeShowing("Deleted") else askAndroid(sender)
            }
            return
        }
        val sender = try {
            Media.trashRequest(this, row)
        } catch (e: Exception) {
            toast("Android didn't let Inkwell delete it")
            return
        }
        if (sender == null) removeShowing("Moved to the Recycle Bin") else askAndroid(sender)
    }

    private fun askAndroid(sender: IntentSender) {
        @Suppress("DEPRECATION")
        startIntentSenderForResult(sender, REQ_DELETE, null, 0, 0, 0)
    }

    /** The picture showing is gone: on to the next (or the one before, at the end). */
    private fun removeShowing(said: String) {
        val p = pages as? FilePages ?: return
        if (index !in 0 until p.count) return
        p.items.removeAt(index)
        if (p.count == 0) {
            finish()
            return
        }
        toast(said)
        replacePages(p, index.coerceAtMost(p.count - 1))
    }

    private fun confirm(question: String, yes: String, then: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(question)
            .setPositiveButton(yes) { _, _ -> then() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Edit mode ----

    /** Edit mode on, with the picture showing (an animation's frame, as a still). */
    private fun enterEdit() {
        val p = pages ?: return
        if (editing != null) return
        if (cache[index] == null) {
            toast(if (failed(index)) "This picture can't be edited" else "Still loading: try again in a moment")
            return
        }
        stopSlideshow()
        val i = index
        val fileName = fileNameNow()
        zoom.visibility = View.VISIBLE
        strip.visibility = View.INVISIBLE
        zoom.show(null, "Getting it ready to edit…")
        work.execute {
            val bmp = p.bytes(i)?.let { Pictures.editable(it) }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (bmp == null) {
                    toast("This picture can't be edited")
                    zoom.visibility = View.GONE
                    strip.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                val e = Editing(bmp, fileName.substringBeforeLast('.'), Editing.isJpegName(fileName))
                editing = e
                zoom.editing = true
                zoom.show(BitmapDrawable(resources, e.image).apply { setFilterBitmap(true) })
                bottom.visibility = View.GONE
                editBar.visibility = View.VISIBLE
                titleText.text = "Editing · drag to draw a box"
                showBars(true)
                updateEditButtons()
                updateBack()
            }
        }
    }

    override fun onBox(box: RectF?) {
        editing?.box = box
        updateEditButtons()
    }

    private fun updateEditButtons() {
        val e = editing ?: return
        val boxed = e.clipped() != null
        editButtons["undo"]?.isEnabled = e.canUndo
        editButtons["crop"]?.isEnabled = boxed
        editButtons["black"]?.isEnabled = boxed
        editButtons["zoom"]?.isEnabled = boxed
        editButtons["save"]?.isEnabled = e.unsaved
    }

    private fun editAction(key: String) {
        val e = editing ?: return
        val keepView = when (key) {
            "undo" -> if (e.undo()) false else return
            "crop" -> if (e.crop()) false else return
            "black" -> if (e.blackOut()) true else return
            "left", "right" -> {
                try { e.rotate(clockwise = key == "right") } catch (err: OutOfMemoryError) { toast("Not enough memory for that"); return }
                false
            }
            "zoom" -> {
                e.box?.let { zoom.zoomTo(it) }
                return
            }
            "save" -> {
                saveEdit()
                return
            }
            "done" -> {
                leaveEdit()
                return
            }
            else -> return
        }
        zoom.box = null
        zoom.show(BitmapDrawable(resources, e.image).apply { setFilterBitmap(true) }, keepView = keepView)
        updateEditButtons()
    }

    /** Saves the edit as a new file: next to the original if Inkwell may write there, else where you choose. */
    private fun saveEdit(then: (() -> Unit)? = null) {
        val e = editing ?: return
        val p = pages
        val item = p?.item(index)
        val files = p as? FilePages
        work.execute {
            val bytes = try { e.encode() } catch (err: OutOfMemoryError) { null }
            var saved: Uri? = null
            if (bytes != null && item != null) {
                val tree = files?.tree
                val folderId = files?.folderId
                saved = if (tree != null && folderId != null) {
                    Media.saveInFolder(this, tree, folderId, e.newName, e.mime, bytes)
                } else {
                    val row = item.media ?: Media.find(this, item.uri, item.name)?.also { item.media = it }
                    row?.let { Media.saveBeside(this, it, e.newName, e.mime, bytes) }
                }
            }
            val savedUri = saved
            val savedName = savedUri?.let { Files.name(this, it) }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                when {
                    bytes == null -> toast("Couldn't make the picture to save")
                    savedUri == null -> {
                        afterPickedSave = then
                        pickSaveFile(bytes, e.newName, e.mime, isEdit = true)
                    }
                    else -> {
                        e.markSaved()
                        updateEditButtons()
                        toast("Saved as “$savedName”")
                        // The new file goes in the list, after the original.
                        if (files != null) {
                            files.items.add(index + 1, Item(savedUri, savedName ?: e.newName))
                            generation++
                            cache.keys.filter { it > index }.forEach { cache.remove(it) }
                            loading.clear()
                            seek.max = files.count - 1
                            strip.pageChanged(index)
                        }
                        then?.invoke()
                    }
                }
            }
        }
    }

    private var afterPickedSave: (() -> Unit)? = null

    /** Out of edit mode: asks first if there are changes not saved. */
    private fun leaveEdit() {
        val e = editing ?: return
        if (!e.unsaved) {
            exitEdit()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Save the changes as a new picture?")
            .setPositiveButton("Save") { _, _ -> saveEdit { exitEdit() } }
            .setNegativeButton("Discard") { _, _ -> exitEdit() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun exitEdit() {
        editing = null
        zoom.editing = false
        editBar.visibility = View.GONE
        titleText.text = pages?.let { if (it.isComic) name else it.name(index) } ?: name
        updateBack()
        zoom.show(null)
        zoom.visibility = View.GONE
        strip.visibility = View.VISIBLE
        showBars(true)
        strip.pageChanged(index)
    }

    /** Back leaves edit mode (asking about changes) before it leaves the picture. */
    private fun leave() {
        if (editing != null) leaveEdit() else finish()
    }

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val registered = backCallback as? OnBackInvokedCallback
        if (editing != null && registered == null) {
            val callback = OnBackInvokedCallback { leaveEdit() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (editing == null && registered != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(registered)
            backCallback = null
        }
    }

    @Deprecated("Android 13 on, back is onBackInvokedDispatcher's (updateBack)")
    override fun onBackPressed() {
        if (editing != null) {
            leaveEdit()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    @Deprecated("The plain Activity way; Inkwell uses no AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_DELETE -> if (resultCode == RESULT_OK) removeShowing(if (Build.VERSION.SDK_INT >= 30) "Moved to the Recycle Bin" else "Deleted")
            REQ_SAVE -> {
                val bytes = pendingSave
                val target = data?.data
                val wasEdit = pendingSaveIsEdit
                val then = afterPickedSave
                pendingSave = null
                afterPickedSave = null
                if (resultCode != RESULT_OK || bytes == null || target == null) return
                work.execute {
                    val ok = try {
                        contentResolver.openOutputStream(target)?.use { it.write(bytes) } != null
                    } catch (e: Exception) {
                        false
                    }
                    runOnUiThread {
                        if (destroyed) return@runOnUiThread
                        if (!ok) {
                            toast("Couldn't save it there")
                            return@runOnUiThread
                        }
                        toast("Saved")
                        if (wasEdit) {
                            editing?.markSaved()
                            updateEditButtons()
                            then?.invoke()
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** A folder you added, and the folder in it the picture is in. */
        const val EXTRA_TREE = "io.github.inkwell.viewer.TREE"
        const val EXTRA_FOLDER = "io.github.inkwell.viewer.FOLDER"

        private const val SLIDE_MS = 4000L

        private const val REQ_PHOTOS = 1
        private const val REQ_DELETE = 2
        private const val REQ_SAVE = 3
        private const val REQ_WRITE = 4

        private const val SHARE = 1
        private const val OPEN_WITH = 2
        private const val EDIT = 3
        private const val SAVE_COPY = 4
        private const val WALLPAPER = 5
        private const val DELETE = 6
        private const val SLIDESHOW = 7
        private const val FIT = 9
        private const val INFO = 10
        private const val FIT_WIDTH = 20
        private const val RTL = 21
        private const val SCREEN_ON = 22
        private const val HIDDEN = 23
    }
}
