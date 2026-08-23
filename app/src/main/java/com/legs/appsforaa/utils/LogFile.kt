package com.legs.appsforaa.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mirrors [Logger] output to a file the harness can `adb pull`.
 *
 * logcat is a ring buffer shared with the whole device: a long install, a chatty system service,
 * or simply waiting a few minutes can push this app's lines out of it before anything reads them.
 * A run that took ten minutes and then reported "no RESULT line" was indistinguishable from a run
 * that failed — that ambiguity is what this removes.
 *
 * JSONL, one object per line, because it is appended from many threads and read after the fact:
 * a truncated final line costs one record instead of the file.
 *
 * Written to the app's external files directory, which is the same place the catalog override
 * lives — `adb pull` reaches it with no permission at all.
 */
object LogFile {

    private const val FILE_NAME = "aaad-log.jsonl"

    /** Past this the file is rotated to `.1`, keeping one generation. A run is minutes, not days. */
    private const val MAX_BYTES = 2L * 1024 * 1024

    private val timestamps = object : ThreadLocal<SimpleDateFormat>() {
        // SimpleDateFormat is not thread-safe and this is written from every coroutine dispatcher.
        override fun initialValue() =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    }

    @Volatile
    private var file: File? = null

    /** Lines logged before [install] runs, so nothing from early startup is lost. */
    private val pending = ConcurrentLinkedQueue<String>()
    private val draining = AtomicBoolean(false)

    /** Call once from Application/Activity start. Safe to call again; later calls are ignored. */
    fun install(context: Context) {
        if (file != null) return
        val target = runCatching { File(context.getExternalFilesDir(null), FILE_NAME) }.getOrNull()
            ?: return
        file = target
        drain()
    }

    fun append(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val line = buildString {
            append('{')
            append("\"ts\":\"").append(timestamps.get()!!.format(Date())).append("\",")
            append("\"level\":\"").append(level).append("\",")
            append("\"tag\":").append(quote(tag)).append(',')
            append("\"msg\":").append(quote(message))
            if (throwable != null) {
                append(",\"error\":").append(quote("${throwable.javaClass.simpleName}: ${throwable.message}"))
            }
            append("}\n")
        }
        pending.add(line)
        drain()
    }

    /**
     * One writer at a time; everyone else leaves their line in the queue and returns. Logging must
     * never become a lock the install path waits on.
     */
    private fun drain() {
        val target = file ?: return
        if (!draining.compareAndSet(false, true)) return
        try {
            rotateIfNeeded(target)
            val batch = StringBuilder()
            while (true) {
                val line = pending.poll() ?: break
                batch.append(line)
            }
            if (batch.isNotEmpty()) {
                runCatching { target.appendText(batch.toString()) }
            }
        } finally {
            draining.set(false)
        }
    }

    private fun rotateIfNeeded(target: File) {
        if (!target.isFile || target.length() < MAX_BYTES) return
        runCatching {
            val previous = File(target.parentFile, "$FILE_NAME.1")
            if (previous.exists()) previous.delete()
            target.renameTo(previous)
        }
    }

    /** Minimal JSON string escaping — no dependency for four cases. */
    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** Absolute on-device path, for telling a human where to look. */
    fun pathOn(context: Context): String =
        File(context.getExternalFilesDir(null), FILE_NAME).absolutePath
}
