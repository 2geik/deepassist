package com.deepassist.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Small on-device event journal for bugs that happen while no logcat is
 * attached (triggers that do nothing, service crashes). Only event names and
 * error classes go in — never speech, messages or screen content.
 *
 * Read it with: adb exec-out run-as com.deepassist cat files/diag.log
 */
object DiagLog {

    private const val TAG = "DiagLog"
    private const val FILE = "diag.log"
    private const val MAX_BYTES = 200_000L

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-log").apply { isDaemon = true }
    }
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        dir = context.applicationContext.filesDir
    }

    fun event(tag: String, message: String) {
        Log.i(TAG, "$tag: $message")
        val target = dir ?: return
        val line = "${timeFormat.format(Date())} $tag $message\n"
        runCatching { writer.execute { append(target, line) } }
    }

    /** Synchronous variant for the crash handler — the process is about to die. */
    fun eventNow(tag: String, message: String) {
        Log.e(TAG, "$tag: $message")
        val target = dir ?: return
        append(target, "${timeFormat.format(Date())} $tag $message\n")
    }

    /** Class and the first app frames of [t]; messages are cut short to keep content out. */
    fun describe(t: Throwable): String {
        val frames = t.stackTrace
            .filter { it.className.startsWith("com.deepassist") }
            .take(3)
            .joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
        val cause = t.cause?.let { " cause=${it.javaClass.simpleName}" }.orEmpty()
        return "${t.javaClass.name}: ${t.message?.take(160).orEmpty()}$cause @ $frames"
    }

    @Synchronized
    private fun append(dir: File, line: String) {
        runCatching {
            val file = File(dir, FILE)
            if (file.length() > MAX_BYTES) {
                val old = File(dir, "$FILE.1")
                old.delete()
                file.renameTo(old)
            }
            file.appendText(line)
        }
    }
}
