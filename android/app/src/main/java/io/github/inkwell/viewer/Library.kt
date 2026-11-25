package io.github.inkwell.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** A comic you've read in, and where you got to. */
class Reading(val uri: String, val name: String, val page: Int, val pages: Int, val time: Long)

/** A folder you added (Android lets Inkwell read in it until you remove it). */
class Folder(val uri: String, val name: String)

/**
 * What Inkwell keeps, on this phone only (never in backups: no_backup.xml):
 * your choices, the folders you added, and (only as you chose, see
 * [Memory]) your page in comics, by fingerprint or with Continue reading.
 */
class Library(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)

    /** Comics: one whole page to a screen, rather than each page as tall as the screen. */
    var wholePages: Boolean
        get() = prefs.getBoolean("whole_pages", false)
        set(v) = prefs.edit().putBoolean("whole_pages", v).apply()

    /** Pictures (not comics): each whole on the screen, rather than as tall as the screen. */
    var wholePictures: Boolean
        get() = prefs.getBoolean("whole_pictures", true)
        set(v) = prefs.edit().putBoolean("whole_pictures", v).apply()

    /** Whether Inkwell has asked to see the photos (so it asks once, not every picture). */
    var askedPhotos: Boolean
        get() = prefs.getBoolean("asked_photos", false)
        set(v) = prefs.edit().putBoolean("asked_photos", v).apply()

    /** Whether Inkwell has said it can only see the photos you picked (so it says so once). */
    var askedAllPhotos: Boolean
        get() = prefs.getBoolean("asked_all_photos", false)
        set(v) = prefs.edit().putBoolean("asked_all_photos", v).apply()

    /** Kept out of screenshots, screen recording and the recent-apps view. */
    var hidden: Boolean
        get() = prefs.getBoolean("private", true)
        set(v) = prefs.edit().putBoolean("private", v).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(v) = prefs.edit().putBoolean("keep_screen_on", v).apply()

    /** How much Inkwell may keep about the comics you read. */
    enum class Memory {
        /** Nothing. */
        OFF,

        /**
         * Your page (and manga or not) in each comic, under a scrambled
         * fingerprint of the comic only: no names, no addresses, no dates.
         * Open the comic again, from anywhere, and it goes to your page.
         */
        PLACE,

        /** That, and Continue reading: each comic's name and where it is, so it's listed. */
        LIST,
    }

    /** null until you've been asked; nothing is kept until then. */
    var memory: Memory?
        get() {
            // Before there were three choices: yes was the list, no was off.
            if (prefs.contains("remember")) {
                val was = try { prefs.getBoolean("remember", false) } catch (e: ClassCastException) { false }
                prefs.edit().remove("remember").putString("memory", if (was) Memory.LIST.name else Memory.OFF.name).apply()
            }
            return prefs.getString("memory", null)?.let { n -> Memory.values().firstOrNull { it.name == n } }
        }
        set(v) {
            prefs.edit().putString("memory", v?.name).apply()
            if (v != Memory.LIST) clearReading()
            if (v == null || v == Memory.OFF) prefs.edit().remove("places").apply()
        }

    /**
     * The key the fingerprints are scrambled with: random, made on this
     * phone, and never leaves it. Without it, a fingerprint can't be
     * matched to a comic, even by someone who has the comic.
     */
    private fun key(): ByteArray {
        prefs.getString("place_key", null)?.let { return android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        val k = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit().putString("place_key", android.util.Base64.encodeToString(k, android.util.Base64.NO_WRAP)).apply()
        return k
    }

    /** A comic's [Cbz.id], keyed: what's stored instead of its name. */
    private fun mark(id: ByteArray): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key(), "HmacSHA256"))
        return mac.doFinal(id).copyOf(16).joinToString("") { "%02x".format(it) }
    }

    private class Place(val mark: String, val page: Int, val rtl: Boolean?)

    private fun places(): List<Place> {
        val arr = try { JSONArray(prefs.getString("places", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Place(o.optString("m"), o.optInt("p"), if (o.has("r")) o.optBoolean("r") else null)
        }
    }

    /** Your page in the comic [id] is, if Inkwell keeps it. */
    fun placeOf(id: ByteArray): Int? {
        if (memory != Memory.PLACE && memory != Memory.LIST) return null
        val m = mark(id)
        return places().firstOrNull { it.mark == m }?.page
    }

    /** Keeps your [page] in the comic [id] is (the latest first, the last 500 only). */
    fun setPlace(id: ByteArray, page: Int, rtl: Boolean? = null) {
        if (memory != Memory.PLACE && memory != Memory.LIST) return
        val m = mark(id)
        val old = places().firstOrNull { it.mark == m }
        val list = listOf(Place(m, page, rtl ?: old?.rtl)) + places().filter { it.mark != m }
        val arr = JSONArray()
        for (p in list.take(500)) {
            val o = JSONObject().put("m", p.mark).put("p", p.page)
            if (p.rtl != null) o.put("r", p.rtl)
            arr.put(o)
        }
        prefs.edit().putString("places", arr.toString()).apply()
    }

    /**
     * Read right to left (manga), for the comic [id] is. Off until you
     * choose; a comic you haven't chosen for goes the way you chose last.
     */
    fun rightToLeft(id: ByteArray?): Boolean {
        val last = prefs.getBoolean("rtl_last", false)
        if (id == null || (memory != Memory.PLACE && memory != Memory.LIST)) return last
        val m = mark(id)
        return places().firstOrNull { it.mark == m }?.rtl ?: last
    }

    fun setRightToLeft(id: ByteArray?, page: Int, on: Boolean) {
        prefs.edit().putBoolean("rtl_last", on).apply()
        if (id != null) setPlace(id, page, on)
    }

    /** The bars stay hidden for comics: a tap doesn't bring them (holding a page opens the menu). */
    var barsOffComics: Boolean
        get() = prefs.getBoolean("bars_off", false)
        set(v) = prefs.edit().putBoolean("bars_off", v).apply()

    /** The same, for pictures: each kind has its own. */
    var barsOffPictures: Boolean
        get() = prefs.getBoolean("bars_off_pictures", false)
        set(v) = prefs.edit().putBoolean("bars_off_pictures", v).apply()

    /** Continue reading forgotten, and Android's leave to open those comics handed back (folders you added keep theirs). */
    fun clearReading() {
        for (r in reading()) release(r.uri, keepIfFolder = true)
        val e = prefs.edit().remove("reading")
        // Older versions kept manga by address.
        for (k in prefs.all.keys) if (k.startsWith("rtl:")) e.remove(k)
        e.apply()
    }

    fun reading(): List<Reading> {
        val arr = try { JSONArray(prefs.getString("reading", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Reading(o.optString("uri"), o.optString("name"), o.optInt("page"), o.optInt("pages"), 0L)
        }
    }

    fun readingOf(uri: String) = reading().firstOrNull { it.uri == uri }

    /**
     * Where you are in a comic; the newest first, and only the last 50. No
     * dates or times, only the order. Only if you chose Continue reading ([Memory.LIST]).
     */
    fun setReading(uri: String, name: String, page: Int, pages: Int) {
        if (memory != Memory.LIST) return
        val list = reading().filter { it.uri != uri }.toMutableList()
        list.add(0, Reading(uri, name, page, pages, System.currentTimeMillis()))
        saveReading(list.take(50))
    }

    /** A comic found again under a new address: where you were in it goes with it. */
    fun moveReading(from: String, to: String) {
        val old = readingOf(from) ?: return
        saveReading(reading().filter { it.uri != from && it.uri != to })
        setReading(to, old.name, old.page, old.pages)
    }

    fun forget(uri: String) {
        saveReading(reading().filter { it.uri != uri })
        release(uri, keepIfFolder = true)
    }

    private fun saveReading(list: List<Reading>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().put("uri", r.uri).put("name", r.name).put("page", r.page).put("pages", r.pages))
        }
        prefs.edit().putString("reading", arr.toString()).apply()
    }

    fun folders(): List<Folder> {
        val arr = try { JSONArray(prefs.getString("folders", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Folder(o.optString("uri"), o.optString("name"))
        }.sortedWith { a, b -> Natural.compare(a.name, b.name) }
    }

    fun addFolder(uri: String, name: String) {
        val list = folders().filter { it.uri != uri } + Folder(uri, name)
        saveFolders(list)
    }

    fun removeFolder(uri: String) {
        saveFolders(folders().filter { it.uri != uri })
        release(uri, keepIfFolder = false)
    }

    private fun saveFolders(list: List<Folder>) {
        val arr = JSONArray()
        for (f in list) arr.put(JSONObject().put("uri", f.uri).put("name", f.name))
        prefs.edit().putString("folders", arr.toString()).apply()
    }

    /** Hands back Android's lasting permission to read it, when nothing here needs it. */
    private fun release(uri: String, keepIfFolder: Boolean) {
        if (keepIfFolder && folders().any { it.uri == uri }) return
        try {
            app.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // It had none (opened from another app): nothing to hand back.
        }
    }
}
