package com.legs.appsforaa.utils

import android.content.Context
import android.os.Build
import com.legs.appsforaa.BuildConfig
import com.legs.appsforaa.data.CatalogRepository
import com.legs.appsforaa.data.ConversionState
import com.legs.appsforaa.data.InstalledAppScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Collects the state that actually explains why an app is or is not visible in Android Auto.
 *
 * Every field here has earned its place by having been the answer to a real question during
 * development: which Shizuku state, which installer attribution, which Android Auto build. It is
 * plain text so it can be pasted into an issue or diffed between two devices.
 *
 * Contains no personal data — no accounts, no identifiers, no installed-app inventory beyond the
 * Android-Auto-capable ones this app already reasons about.
 */
object Diagnostics {

    suspend fun collect(context: Context): String = withContext(Dispatchers.IO) {
        val shizuku = run {
            ShizukuInstaller.refreshInstalledState(context.packageManager)
            ShizukuInstaller.availability()
        }
        val androidAuto = AndroidAutoLauncher.info(context)
        val catalog = runCatching { CatalogRepository(context).loadCatalog() }.getOrNull()
        val installed = runCatching { InstalledAppScanner(context).scan(includeSystemApps = true) }.getOrDefault(emptyList())

        buildString {
            appendLine("AAAD diagnostics")
            appendLine("================")
            appendLine()
            appendLine("Device")
            appendLine("  model:        ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("  android:      ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("  build:        ${Build.DISPLAY}")
            appendLine()
            appendLine("This app")
            appendLine("  package:      ${context.packageName}")
            appendLine("  version:      ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("  debug build:  ${BuildConfig.DEBUG}")
            appendLine("  catalog url:  ${BuildConfig.CATALOG_URL.ifBlank { "(bundled only)" }}")
            appendLine()
            appendLine("Install path")
            appendLine("  shizuku:      $shizuku")
            appendLine("  attribution:  ${attributionSummary(shizuku)}")
            appendLine()
            appendLine("Android Auto")
            appendLine(
                if (androidAuto.installed) "  version:      ${androidAuto.versionName}"
                else "  version:      not installed"
            )
            appendLine()
            appendLine("Catalog")
            appendLine("  entries:      ${catalog?.apps?.size ?: "unavailable"}")
            appendLine("  origin:       ${catalog?.origin ?: "unavailable"}")
            appendLine()
            appendLine("Android Auto capable apps installed (${installed.size})")
            if (installed.isEmpty()) {
                appendLine("  none")
            } else {
                for (app in installed) {
                    val marker = when {
                        app.state == ConversionState.CONVERTIBLE -> "!"
                        app.blockedWhileDriving -> "D"
                        else -> " "
                    }
                    val uses = app.carCapabilities?.uses?.sorted()?.joinToString(",") ?: "unreadable"
                    appendLine(
                        "  $marker ${app.packageName} ${app.versionName} " +
                            "installer=${app.installerPackage ?: "none"} uses=$uses"
                    )
                }
                appendLine()
                appendLine("  ! = Android Auto will not list this app; it can be converted.")
                appendLine("  D = declares no 'projection', so Android Auto lists it but blocks it")
                appendLine("      while driving. That is the app's own manifest — installing it")
                appendLine("      differently cannot change it.")
            }
        }
    }

    /** Spells out the consequence rather than leaving the reader to infer it. */
    private fun attributionSummary(state: ShizukuInstaller.Availability): String = when (state) {
        ShizukuInstaller.Availability.Ready ->
            "installs are attributed to the Play Store, so Android Auto lists them"
        else ->
            "installs fall back to the system installer and are NOT attributed, so Android Auto " +
                "lists them only if its own \"Unknown sources\" setting is enabled"
    }
}
