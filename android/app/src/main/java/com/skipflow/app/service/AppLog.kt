package com.skipflow.app.service

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 内存里的最近日志，供主界面显示；同时写 logcat（tag: SkipFlow）。 */
object AppLog {
    const val TAG = "SkipFlow"
    private const val MAX_LINES = 200

    private val lines = ArrayDeque<String>()
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun i(msg: String) = add(msg, Log.INFO)
    fun w(msg: String) = add(msg, Log.WARN)

    private fun add(msg: String, level: Int) {
        Log.println(level, TAG, msg)
        synchronized(lines) {
            lines.addLast("${fmt.format(Date())} $msg")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        main.post { listeners.toList().forEach { it() } }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
}
