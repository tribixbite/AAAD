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
 */
object Logger {

    private const val BASE_TAG = "AAAD"

    /** logcat truncates tags over 23 chars on older releases; keep them short and predictable. */
    private fun tagFor(tag: String): String =
        "$BASE_TAG/$tag".take(23)

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tagFor(tag), message)
    }

    fun v(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.v(tagFor(tag), message)
    }

    fun i(tag: String, message: String) {
        Log.i(tagFor(tag), message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tagFor(tag), message, throwable)
        else Log.w(tagFor(tag), message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tagFor(tag), message, throwable)
        else Log.e(tagFor(tag), message)
    }
}
