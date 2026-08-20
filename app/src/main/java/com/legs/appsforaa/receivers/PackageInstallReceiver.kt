package com.legs.appsforaa.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.legs.appsforaa.utils.Logger

/**
 * Reports package install / removal / replacement so a screen can refresh its install state.
 *
 * **Registered at runtime, never in the manifest.** Since Android 8.0, manifest-declared
 * receivers do not receive `ACTION_PACKAGE_ADDED` / `_REMOVED` / `_REPLACED` for other packages —
 * they are implicit broadcasts subject to the background execution limits. A manifest entry would
 * look correct and silently never fire. Register with [register] while a screen is visible and
 * release it with [unregister].
 *
 * @param onPackageChanged invoked on the main thread with the affected package name, or null when
 *   the broadcast carries no package data.
 */
class PackageInstallReceiver(
    private val onPackageChanged: (packageName: String?) -> Unit,
) : BroadcastReceiver() {

    private companion object {
        const val TAG = "PkgInstallRecv"
    }

    private var registered = false

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        // Package broadcasts carry the target as the data URI's scheme-specific part.
        val packageName = intent.data?.schemeSpecificPart
        Logger.d(TAG, "$action -> ${packageName ?: "(no package)"}")
        onPackageChanged(packageName)
    }

    /** Idempotent; a second call while registered is a no-op. */
    fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            // Without the scheme these actions are never delivered — they are data-typed.
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            context, this, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
    }

    /** Idempotent; safe to call when never registered. */
    fun unregister(context: Context) {
        if (!registered) return
        runCatching { context.unregisterReceiver(this) }
            .onFailure { Logger.w(TAG, "unregisterReceiver failed", it) }
        registered = false
    }
}
