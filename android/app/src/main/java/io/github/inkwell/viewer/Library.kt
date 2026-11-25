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
 * the comics you're reading and the page you were on, the folders you
 * added, and your choices.
 */
class Library(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)

    /** One whole page to a screen, rather than each page as tall as the screen. */
    var wholePages: Boolean
        get() = prefs.getBoolean("whole_pages", false)
        set(v) = prefs.edit().putBoolean("whole_pages", v).apply()

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

    /**
     * Read right to left (manga), for this comic. Off until you choose;
     * a comic you haven't chosen for goes the way you chose last.
     */
    fun rightToLeft(uri: String) = prefs.getBoolean("rtl:$uri", prefs.getBoolean("rtl_last", false))
    fun setRightToLeft(uri: String, on: Boolean) = prefs.edit().putBoolean("rtl:$uri", on).putBoolean("rtl_last", on).apply()

    /** The bars stay hidden: a tap doesn't bring them (holding a page opens the menu). */
    var barsOff: Boolean
        get() = prefs.getBoolean("bars_off", false)
        set(v) = prefs.edit().putBoolean("bars_off", v).apply()

    fun reading(): List<Reading> {
        val arr = try { JSONArray(prefs.getString("reading", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Reading(o.optString("uri"), o.optString("name"), o.optInt("page"), o.optInt("pages"), o.optLong("time"))
        }.sortedByDescending { it.time }
    }

    fun readingOf(uri: String) = reading().firstOrNull { it.uri == uri }

    /** Where you are in a comic; the newest first, and only the last 50. */
    fun setReading(uri: String, name: String, page: Int, pages: Int) {
        val list = reading().filter { it.uri != uri }.toMutableList()
        list.add(0, Reading(uri, name, page, pages, System.currentTimeMillis()))
        saveReading(list.take(50))
    }

    fun forget(uri: String) {
        saveReading(reading().filter { it.uri != uri })
        prefs.edit().remove("rtl:$uri").apply()
        release(uri, keepIfFolder = true)
    }

    private fun saveReading(list: List<Reading>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().put("uri", r.uri).put("name", r.name).put("page", r.page).put("pages", r.pages).put("time", r.time))
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
