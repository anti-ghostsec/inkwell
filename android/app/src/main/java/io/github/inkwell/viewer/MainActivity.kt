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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
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
    private fun row(main: String, detail: String? = null, progress: Pair<Int, Int>? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }
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
        list.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
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
                row(r.name, "Page ${r.page + 1} of ${r.pages}", (r.page + 1) to r.pages, onLongClick = {
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
        val ticket = ++shown
        work.execute {
            val docs = try { Files.list(this, level.tree, level.id) } catch (e: Exception) { null }
            runOnUiThread {
                if (ticket != shown || isDestroyed) return@runOnUiThread
                list.removeAllViews()
                when {
                    docs == null -> list.addView(label("Inkwell can't look in this folder any more. Remove it and add it again.", 15f, Ui.dim).apply {
                        setPadding(dp(4), dp(16), dp(4), 0)
                    })
                    docs.isEmpty() -> list.addView(label("No pictures or comics here.", 15f, Ui.dim).apply {
                        setPadding(dp(4), dp(16), dp(4), 0)
                    })
                    else -> for (doc in docs) folderRow(level, doc)
                }
            }
        }
    }

    private fun folderRow(level: Level, doc: Doc) {
        when {
            doc.isFolder -> row("📁  ${doc.name}") { open(Level(level.tree, doc.id, doc.name)) }
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
        val intent = Intent(this, ViewerActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri)
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
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER)
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
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Only this time, then.
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
