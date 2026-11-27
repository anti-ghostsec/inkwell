package io.github.inkwell.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.inkwell.viewer.Ui.applyPrivacy
import io.github.inkwell.viewer.Ui.button
import io.github.inkwell.viewer.Ui.dp
import io.github.inkwell.viewer.Ui.label
import io.github.inkwell.viewer.Ui.onInsets
import io.github.inkwell.viewer.Ui.panel
import java.util.concurrent.Executors

/**
 * Inkwell's home: the comics you're reading (each opens where you left off),
 * the folders you added, and a way to open anything else. Inside a folder:
 * its folders, comics and pictures.
 */
class MainActivity : Activity() {
    private class Level(val tree: Uri, val id: String, val title: String)

    private lateinit var library: Library
    private lateinit var heading: TextView
    private lateinit var upButton: TextView
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private val work = Executors.newSingleThreadExecutor()
    private val stack = ArrayList<Level>()
    private var backCallback: Any? = null
    private var shown = 0
    private lateinit var thumbs: Thumbs

    /** The folder showing, and what's in it, to show it again without asking Android. */
    private var lastDocs: List<Doc>? = null

    /** Pictures picked (held) for a comic, by document id. */
    private val picked = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        Quiet.install()
        super.onCreate(savedInstanceState)
        library = Library(this)
        thumbs = Thumbs(this, dp(160))
        // Copies handed to other apps last time aren't kept.
        if (savedInstanceState == null) work.execute { ShareProvider.clear(this) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.background)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        upButton = label("←", 24f).apply {
            setPadding(0, 0, dp(16), 0)
            visibility = View.GONE
            contentDescription = "Back"
            setOnClickListener { up() }
        }
        heading = label("Inkwell", 26f).apply {
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        header.addView(upButton)
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.onInsets { l, t, r, b -> root.setPadding(l, t, r, b) }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        applyPrivacy(library)
        refresh()
    }

    override fun onDestroy() {
        work.shutdown()
        thumbs.close()
        super.onDestroy()
    }

    private fun refresh() {
        val level = stack.lastOrNull()
        upButton.visibility = if (level == null) View.GONE else View.VISIBLE
        heading.text = level?.title ?: "Inkwell"
        updateBack()
        if (level == null) showHome() else showFolder(level)
    }

    private fun up() {
        if (stack.isEmpty()) return
        stack.removeAt(stack.size - 1)
        refresh()
        scroll.scrollTo(0, 0)
    }

    /** Back goes up a folder before it leaves Inkwell. */
    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val registered = backCallback as? OnBackInvokedCallback
        if (stack.isNotEmpty() && registered == null) {
            val callback = OnBackInvokedCallback { up() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (stack.isEmpty() && registered != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(registered)
            backCallback = null
        }
    }

    @Deprecated("Android 13 on, back is onBackInvokedDispatcher's (updateBack)")
    override fun onBackPressed() {
        if (stack.isNotEmpty()) {
            up()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun section(text: String) {
        list.addView(label(text, 14f, Ui.accent).apply {
            setPadding(dp(4), dp(20), 0, dp(8))
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    /** A row: a line in bold, maybe a second one, and a bar for how far you've read. */
    private fun row(main: String, detail: String? = null, progress: Pair<Int, Int>? = null, cover: Uri? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panel()
            setPadding(dp(10), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }
        if (cover != null) {
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Ui.background)
            }
            thumbs.load(image, cover, comic = true)
            outer.addView(image, LinearLayout.LayoutParams(dp(48), dp(68)).apply { marginEnd = dp(12) })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(if (cover == null) dp(4) else 0, dp(2), 0, dp(2))
        }
        outer.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(label(main, 16f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        if (detail != null) box.addView(label(detail, 13f, Ui.dim).apply { setPadding(0, dp(2), 0, 0) })
        if (progress != null) {
            box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = progress.second.coerceAtLeast(1)
                this.progress = progress.first
                progressTintList = ColorStateList.valueOf(Ui.accent)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8) })
        }
        list.addView(outer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    /** Comics and pictures as a grid of small pictures, three across. */
    private fun grid(level: Level, docs: List<Doc>) {
        var line: LinearLayout? = null
        docs.forEachIndexed { i, doc ->
            if (i % 3 == 0) {
                val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                line = l
                list.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
            val pickable = doc.isPicture && Cbz.isPicture(doc.name)
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                setOnClickListener {
                    when {
                        picked.isNotEmpty() && pickable -> togglePick(level, doc)
                        picked.isNotEmpty() -> {}
                        doc.isComic -> view(doc.uri)
                        else -> view(doc.uri, level)
                    }
                }
                // Hold pictures to pick them for a comic.
                if (pickable) setOnLongClickListener { togglePick(level, doc); true }
            }
            val image = SquareImage(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = panel()
                clipToOutline = true
            }
            thumbs.load(image, doc.uri, doc.isComic)
            if (doc.id in picked) {
                image.foreground = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(4), Ui.accent)
                    setColor(android.graphics.Color.argb(0x50, 0xe0, 0xa0, 0x30))
                }
            }
            cell.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val reading = if (doc.isComic) library.readingOf(doc.uri.toString()) else null
            cell.addView(label((if (doc.isComic) "📖 " else if (doc.id in picked) "✓ " else "") + doc.name, 12f, if (doc.id in picked) Ui.accent else Ui.dim).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(dp(2), dp(4), dp(2), 0)
            })
            if (reading != null) {
                cell.addView(label("Page ${reading.page + 1} of ${reading.pages}", 11f, Ui.accent).apply { setPadding(dp(2), 0, dp(2), 0) })
            }
            line?.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (i % 3 == 2) 0 else dp(8)
            })
        }
        // The last line's empty places keep the others' width.
        val rest = (3 - docs.size % 3) % 3
        repeat(rest) { n ->
            line?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = if (n == rest - 1) 0 else dp(8) })
        }
    }

    /** A picture as tall as it is wide. */
    private class SquareImage(context: android.content.Context) : ImageView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        }
    }

    private fun showHome() {
        list.removeAllViews()
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("Open a file") { pickFile() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        buttons.addView(button("Add a folder") { pickFolder() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        list.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val reading = library.reading()
        val folders = library.folders()
        if (reading.isNotEmpty()) {
            section("Continue reading")
            for (r in reading) {
                row(r.name, "Page ${r.page + 1} of ${r.pages}", (r.page + 1) to r.pages, cover = Uri.parse(r.uri), onLongClick = {
                    confirm("Remove “${r.name}” from this list?", "Remove") {
                        library.forget(r.uri)
                        refresh()
                    }
                }) { view(Uri.parse(r.uri)) }
            }
        }
        if (folders.isNotEmpty()) {
            section("Folders")
            for (f in folders) {
                row(f.name, onLongClick = {
                    confirm("Remove the folder “${f.name}” from Inkwell? Nothing in it is deleted; Inkwell just stops being allowed to look in it.", "Remove") {
                        library.removeFolder(f.uri)
                        refresh()
                    }
                }) {
                    val tree = Uri.parse(f.uri)
                    open(Level(tree, DocumentsContract.getTreeDocumentId(tree), f.name))
                }
            }
        }
        if (reading.isEmpty() && folders.isEmpty()) {
            list.addView(label(
                "Open a picture or a comic book (.cbz), or add a folder of them.\n\n" +
                    "Inkwell doesn't use the internet, and sees only the files and folders you choose.",
                15f, Ui.dim,
            ).apply { setPadding(dp(4), dp(24), dp(4), 0) })
        } else {
            list.addView(label("Hold a comic or folder to remove it.", 13f, Ui.dim).apply {
                setPadding(dp(4), dp(12), dp(4), 0)
            })
        }
    }

    private fun open(level: Level) {
        stack += level
        refresh()
        scroll.scrollTo(0, 0)
    }

    private fun showFolder(level: Level) {
        list.removeAllViews()
        list.addView(label("Loading…", 15f, Ui.dim).apply { setPadding(dp(4), dp(16), 0, 0) })
        picked.clear()
        lastDocs = null
        val ticket = ++shown
        work.execute {
            val docs = try { Files.list(this, level.tree, level.id) } catch (e: Exception) { null }
            runOnUiThread {
                if (ticket != shown || isDestroyed) return@runOnUiThread
                lastDocs = docs
                render(level, docs)
            }
        }
    }

    /** A folder's contents, as [showFolder] found them. */
    private fun render(level: Level, docs: List<Doc>?) {
        val y = scroll.scrollY
        list.removeAllViews()
        when {
            docs == null -> list.addView(label("Inkwell can't look in this folder any more. Remove it and add it again.", 15f, Ui.dim).apply {
                setPadding(dp(4), dp(16), dp(4), 0)
            })
            docs.isEmpty() -> list.addView(label("No pictures or comics here.", 15f, Ui.dim).apply {
                setPadding(dp(4), dp(16), dp(4), 0)
            })
            else -> {
                comicBar(level, docs)
                for (doc in docs.filter { it.isFolder }) folderRow(level, doc)
                grid(level, docs.filter { !it.isFolder })
            }
        }
        scroll.post { scroll.scrollTo(0, y) }
    }

    // ---- Making comics ----

    /**
     * Above a folder's contents: make a comic of its pictures, or (once
     * some are picked by holding them) of the ones picked.
     */
    private fun comicBar(level: Level, docs: List<Doc>) {
        val pages = MakeComic.pages(docs)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val wide = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (picked.isNotEmpty()) {
            val chosen = pages.filter { it.id in picked }
            bar.addView(button("Make a comic of ${chosen.size} picked") {
                makeComic(level.tree, level.id, level.title, chosen)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            bar.addView(button("Cancel") {
                picked.clear()
                render(level, lastDocs)
            })
        } else if (pages.isNotEmpty()) {
            bar.addView(button("Make a comic (.cbz) of ${pages.size} picture${if (pages.size == 1) "" else "s"}") {
                // Beside the folder, as on the computer; a folder you added
                // has nothing above it Inkwell may write in, so then inside it.
                val parent = stack.getOrNull(stack.size - 2)?.takeIf { it.tree == level.tree }
                makeComic(level.tree, parent?.id ?: level.id, level.title, pages)
            }, wide)
        } else {
            return
        }
        list.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
        list.addView(label(
            if (picked.isNotEmpty()) "Tap pictures to pick or unpick them." else "Or hold pictures to pick some, or hold a folder for a comic of it.",
            12f, Ui.dim,
        ).apply { setPadding(dp(4), 0, dp(4), dp(8)) })
    }

    private fun togglePick(level: Level, doc: Doc) {
        if (!picked.remove(doc.id)) picked += doc.id
        render(level, lastDocs)
    }

    /** A comic of [folder]'s pictures, beside it in [level]. */
    private fun comicOfFolder(level: Level, folder: Doc) {
        val ticket = shown
        work.execute {
            val pages = try { MakeComic.pages(Files.list(this, level.tree, folder.id)) } catch (e: Exception) { null }
            runOnUiThread {
                if (ticket != shown || isDestroyed) return@runOnUiThread
                when {
                    pages == null -> toast("Inkwell can't look in “${folder.name}”")
                    pages.isEmpty() -> toast("There are no pictures in “${folder.name}”")
                    else -> makeComic(level.tree, level.id, folder.name, pages)
                }
            }
        }
    }

    /** [pages] into "[stem].cbz" in folder [intoId], with how far it's got, and Cancel. */
    private fun makeComic(tree: Uri, intoId: String, stem: String, pages: List<Doc>) {
        if (pages.isEmpty()) return
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val text = label("Making “${MakeComic.safeStem(stem)}.cbz”…", 15f).apply { setPadding(dp(24), dp(20), dp(24), dp(8)) }
        val dialog = AlertDialog.Builder(this)
            .setView(text)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .show()
        val total = pages.size
        work.execute {
            val result = runCatching {
                MakeComic.make(this, tree, intoId, stem, pages, { cancelled.get() }) { n ->
                    runOnUiThread { text.text = "Making “${MakeComic.safeStem(stem)}.cbz”: $n of $total pictures" }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dialog.dismiss()
                result.onSuccess { name ->
                    toast("Made “$name” ($total picture${if (total == 1) "" else "s"}). The pictures are still where they were.")
                }.onFailure { e ->
                    if (e !is MakeComic.Cancelled) toast("Couldn't make the comic: ${e.message ?: e.javaClass.simpleName}")
                }
                stack.lastOrNull()?.let { showFolder(it) }
            }
        }
    }

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    private fun folderRow(level: Level, doc: Doc) {
        when {
            doc.isFolder -> row("📁  ${doc.name}", onLongClick = {
                AlertDialog.Builder(this)
                    .setTitle(doc.name)
                    .setItems(arrayOf("Make a comic (.cbz) of its pictures")) { _, _ -> comicOfFolder(level, doc) }
                    .show()
            }) { open(Level(level.tree, doc.id, doc.name)) }
            doc.isComic -> {
                val r = library.readingOf(doc.uri.toString())
                if (r != null) {
                    row("📖  ${doc.name}", "Page ${r.page + 1} of ${r.pages}", (r.page + 1) to r.pages) { view(doc.uri) }
                } else {
                    row("📖  ${doc.name}") { view(doc.uri) }
                }
            }
            else -> row("🖼  ${doc.name}") { view(doc.uri, level) }
        }
    }

    private fun view(uri: Uri, inFolder: Level? = null) {
        // Opened from Inkwell itself: it may go under Continue reading.
        val intent = Intent(this, ViewerActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri)
            .putExtra(ViewerActivity.EXTRA_INSIDE, true)
        if (inFolder != null) {
            intent.putExtra(ViewerActivity.EXTRA_TREE, inFolder.tree.toString())
            intent.putExtra(ViewerActivity.EXTRA_FOLDER, inFolder.id)
        }
        startActivity(intent)
    }

    private fun confirm(question: String, yes: String, then: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(question)
            .setPositiveButton(yes) { _, _ -> then() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/vnd.comicbook+zip", "application/x-cbz", "application/zip", "application/octet-stream"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, PICK_FILE)
    }

    private fun pickFolder() {
        @Suppress("DEPRECATION")
        // Write too: deleting pictures, and saving edits next to them.
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        ), PICK_FOLDER)
    }

    @Deprecated("The plain Activity way; Inkwell uses no AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        // Folders, and comics (to pick up again from "Continue reading"), stay
        // readable; a picture is only looked at this once.
        val keep = requestCode == PICK_FOLDER || Files.isComic(Files.name(this, uri), contentResolver.getType(uri))
        if (keep) {
            try {
                if (requestCode == PICK_FOLDER) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } else {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (e: SecurityException) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e2: SecurityException) {
                    // Only this time, then.
                }
            }
        }
        when (requestCode) {
            PICK_FILE -> view(uri)
            PICK_FOLDER -> {
                val id = DocumentsContract.getTreeDocumentId(uri)
                val name = Files.name(this, DocumentsContract.buildDocumentUriUsingTree(uri, id))
                library.addFolder(uri.toString(), name)
                stack.clear()
                open(Level(uri, id, name))
            }
        }
    }

    companion object {
        private const val PICK_FILE = 1
        private const val PICK_FOLDER = 2
    }
}
