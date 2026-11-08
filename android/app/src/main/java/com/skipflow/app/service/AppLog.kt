package com.skipflow.app.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
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

    /** 调试细节：只写 logcat 和日志文件，不进主界面的日志列表。 */
    fun d(msg: String) {
        Log.d(TAG, msg)
        appendToFile("${fmt.format(Date())} [d] $msg")
    }
    fun w(msg: String) = add(msg, Log.WARN)

    /**
     * 同时追加到 /sdcard/Android/data/com.skipflow.app/files/skipflow.log。
     * vivo 等 ROM 会屏蔽第三方 App 的 logcat，用 adb shell cat 这个文件就能看。
     */
    private var file: File? = null
    private const val MAX_FILE_BYTES = 512 * 1024L

    fun init(context: Context) {
        file = context.getExternalFilesDir(null)?.let { File(it, "skipflow.log") }
    }

    private fun add(msg: String, level: Int) {
        Log.println(level, TAG, msg)
        val line = "${fmt.format(Date())} $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        appendToFile(line)
        main.post { listeners.toList().forEach { it() } }
    }

    private fun appendToFile(line: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_FILE_BYTES) f.writeText("")
            f.appendText(line + "\n")
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
}
