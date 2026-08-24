package com.legs.appsforaa.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * Opens Android Auto, and reports what is known about it.
 *
 * **Deliberately resolves intents instead of naming activities.** Google renames these classes
 * between releases — upstream v2.8.5 hardcodes
 * `com.google.android.gearhead.vanmoof.VanmoofSettingsActivity` and
 * `com.google.android.gearhead.setupwizard.DeveloperSettingsActivity`, and on the test device
 * (AA 17.3) **neither resolves**; the launcher there is `gearhead.vanagon.
 * VnDrivingModeLauncherActivity`. A hardcoded class name is a silent dead button on some
 * fraction of devices, which is the worst outcome for a screen whose whole job is
 * "press this to fix your problem".
 */
object AndroidAutoLauncher {

    private const val TAG = "AALauncher"
    const val PACKAGE = "com.google.android.projection.gearhead"

    data class Info(
        val installed: Boolean,
        val versionName: String?,
    )

    fun info(context: Context): Info = runCatching {
        val packageInfo = context.packageManager.getPackageInfo(PACKAGE, 0)
        Info(installed = true, versionName = packageInfo.versionName)
    }.getOrDefault(Info(installed = false, versionName = null))

    /**
     * Brings Android Auto to the foreground.
     *
     * @return false when AA is absent or exposes no launchable entry point, so the caller can say
     *   something useful instead of appearing to do nothing.
     */
    fun open(context: Context): Boolean {
        return openSettings(context)
    }

    /**
     * Opens Android Auto's own settings. Current AA does not export its Customize launcher
     * activity, so callers should tell the user to tap that row after this screen opens.
     */
    fun openSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent(Intent.ACTION_APPLICATION_PREFERENCES)
                .setPackage(PACKAGE)
                .addCategory(Intent.CATEGORY_DEFAULT),
            Intent("com.google.android.projection.gearhead.SETTINGS").setPackage(PACKAGE),
        )
        for (candidate in candidates) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (candidate.resolveActivity(context.packageManager) != null &&
                runCatching { context.startActivity(candidate); true }.getOrDefault(false)
            ) {
                return true
            }
        }
        Logger.w(TAG, "Android Auto exposes no public settings entry; using app details")
        return openAppSettings(context)
    }

    /**
     * Android Auto's entry in system Settings. The fallback when AA itself will not open — the
     * user can still reach "Open app" from there.
     */
    fun openAppSettings(context: Context): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", PACKAGE, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Play Store listing, for a device where Android Auto is not installed at all. */
    fun openInStore(context: Context): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW)
            .setData(Uri.parse("market://details?id=$PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            context.startActivity(intent)
        }
        true
    }.getOrDefault(false)

    @Suppress("unused") // kept next to its siblings; used by the setup screen's diagnostics
    fun isInstalled(packageManager: PackageManager): Boolean =
        runCatching { packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess
}
