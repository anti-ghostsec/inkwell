package io.github.inkwell.viewer

import android.os.Process
import kotlin.system.exitProcess

/**
 * No logs: if Inkwell ever crashes, it just closes. Android's own crash
 * handler would write what went wrong (which can name your files) to the
 * phone's log, where other tools can read it; this one writes nothing.
 */
object Quiet {
    @Volatile private var installed = false

    fun install() {
        if (installed) return
        installed = true
        Thread.setDefaultUncaughtExceptionHandler { _, _ ->
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
