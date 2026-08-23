package com.legs.appsforaa.utils

import android.util.Log
import com.legs.appsforaa.BuildConfig

/**
 * Thin logging facade.
 *
 * Debug and verbose output is compiled against [BuildConfig.DEBUG] so release builds stay quiet,
 * while warnings and errors always reach logcat — a silent failure in the install path is far
 * more expensive than a stray log line.
 *
 * All output uses a single [BASE_TAG] prefix so the harness can filter the whole app with
 * `adb logcat -s AAAD:V`.
 *
 * Everything is also mirrored to [LogFile], because logcat is a ring buffer shared with the whole
 * device: a long install or a chatty system service can evict this app's lines before anything
 * reads them, and a run that lost its verdict that way is indistinguishable from a run that
 * failed. Debug and verbose are excluded from the file in release builds for the same reason they
 * are excluded from logcat.
 */
object Logger {

    private const val BASE_TAG = "AAAD"

    /** logcat truncates tags over 23 chars on older releases; keep them short and predictable. */
    private fun tagFor(tag: String): String =
        "$BASE_TAG/$tag".take(23)

    fun d(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(tagFor(tag), message)
        LogFile.append("D", tag, message)
    }

    fun v(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        Log.v(tagFor(tag), message)
        LogFile.append("V", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tagFor(tag), message)
        LogFile.append("I", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tagFor(tag), message, throwable)
        else Log.w(tagFor(tag), message)
        LogFile.append("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tagFor(tag), message, throwable)
        else Log.e(tagFor(tag), message)
        LogFile.append("E", tag, message, throwable)
    }
}
