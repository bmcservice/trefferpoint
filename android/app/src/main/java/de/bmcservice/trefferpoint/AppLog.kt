package de.bmcservice.trefferpoint

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/**
 * Eigener Log-Puffer damit wir Logs direkt im WebView anzeigen können
 * (ohne adb / READ_LOGS-Permission). Schreibt parallel in Logcat.
 */
object AppLog {
    private const val MAX = 200
    private val buffer = Collections.synchronizedList(LinkedList<String>())
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMAN)

    fun i(tag: String, msg: String) { Log.i(tag, msg); add("I", tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); add("W", tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        add("E", tag, msg + (tr?.let { " — " + it.javaClass.simpleName + ": " + it.message } ?: ""))
    }

    private fun add(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        synchronized(buffer) {
            buffer.add(line)
            while (buffer.size > MAX) buffer.removeAt(0)
        }
    }

    fun snapshot(): String = synchronized(buffer) { buffer.joinToString("\n") }
}
