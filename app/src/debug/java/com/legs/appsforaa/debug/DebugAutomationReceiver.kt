package com.legs.appsforaa.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.legs.appsforaa.BuildConfig
import com.legs.appsforaa.data.CatalogRepository
import com.legs.appsforaa.data.ConversionAction
import com.legs.appsforaa.data.InstalledAppScanner
import com.legs.appsforaa.data.ScanScope
import com.legs.appsforaa.data.SelfUpdateChecker
import com.legs.appsforaa.utils.InstallManager
import com.legs.appsforaa.utils.CarifyRepackager
import com.legs.appsforaa.utils.LogFile
import com.legs.appsforaa.utils.Logger
import com.legs.appsforaa.utils.ShizukuInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the app from `adb` so it can be tested without touching the screen.
 *
 * **Debug builds only** — this file lives in `src/debug`, so it is not merged into a release
 * build at all. That is deliberate: the receiver is exported, and an exported receiver that
 * installs packages has no business shipping to users.
 *
 * This exists because UI automation is the wrong tool for the job here. Driving installs by
 * simulated taps needs the device unlocked, the right scroll offset, and stable pixel
 * coordinates; none of that survives a lock screen or a layout change, and all of it is
 * incidental to what is being tested. It is also what the test harness needs
 * (`docs/testing-harness.md`, T-20/T-21): a way to trigger one app's install and read back the
 * result.
 *
 * ```bash
 * adb shell am broadcast -p sksa.aa.customapps.dev \
 *     -a com.legs.appsforaa.DEBUG_STATUS
 * adb shell am broadcast -p sksa.aa.customapps.dev \
 *     -a com.legs.appsforaa.DEBUG_INSTALL --es id n2c
 * adb shell am broadcast -p sksa.aa.customapps.dev \
 *     -a com.legs.appsforaa.DEBUG_CONVERT --es package nl.frankkie.nav2contacts
 * adb shell am broadcast -p sksa.aa.customapps.dev \
 *     -a com.legs.appsforaa.DEBUG_UPDATE_CHECK --es repo owner/name --es version 0.1
 * ```
 *
 * `DEBUG_UPDATE_CHECK` takes optional `repo` and `version` overrides. Without them it checks
 * exactly what the UI checks. With them, every branch of the check is reachable without
 * rebuilding — which matters because the interesting branch (an update *is* available) cannot
 * otherwise be reached until this fork publishes a release newer than the running build.
 *
 * Results go to logcat under `AAAD/DebugAutomation`; follow with
 * `adb logcat -s AAAD/DebugAutomation:V`.
 */
class DebugAutomationReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "DebugAutomation"
        const val ACTION_INSTALL = "com.legs.appsforaa.DEBUG_INSTALL"
        const val ACTION_CONVERT = "com.legs.appsforaa.DEBUG_CONVERT"
        const val ACTION_STATUS = "com.legs.appsforaa.DEBUG_STATUS"
        const val ACTION_UPDATE_CHECK = "com.legs.appsforaa.DEBUG_UPDATE_CHECK"

        /** Generous: a cold download of a large APK over a slow link still has to finish. */
        const val TIMEOUT_MILLIS = 5 * 60 * 1000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        // A broadcast can start the process with no Activity involved; without this the whole
        // harness path would write nothing to the file.
        LogFile.install(appContext)
        // goAsync keeps the process alive past onReceive; without it the coroutine is killed
        // the moment this method returns.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(TIMEOUT_MILLIS) {
                    when (intent.action) {
                        ACTION_INSTALL -> install(appContext, intent.getStringExtra("id"))
                        ACTION_CONVERT -> convert(appContext, intent.getStringExtra("package"))
                        ACTION_STATUS -> status(appContext)
                        ACTION_UPDATE_CHECK -> updateCheck(intent)
                        else -> Logger.w(TAG, "Unknown action: ${intent.action}")
                    }
                } ?: Logger.e(TAG, "RESULT=TIMEOUT")
            } catch (error: Throwable) {
                Logger.e(TAG, "RESULT=ERROR ${error.message}", error)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun install(context: Context, id: String?) {
        if (id.isNullOrBlank()) {
            Logger.e(TAG, "RESULT=ERROR missing --es id")
            return
        }
        val repository = CatalogRepository(context)
        val entry = repository.loadCatalog().apps.firstOrNull { it.id == id }
        if (entry == null) {
            Logger.e(TAG, "RESULT=ERROR no catalog entry with id=$id")
            return
        }

        Logger.i(TAG, "INSTALL id=$id name=${entry.name}")
        // No fallback: nobody is watching a harness run, so a system-installer dialog would sit
        // on the device forever and be reported as though something had happened.
        val outcome = InstallManager(context).install(entry, allowSystemFallback = false) { progress ->
            Logger.d(TAG, "progress: $progress")
        }
        when (outcome) {
            // ATTRIBUTED is retained as a wire-compatible harness token; current Android Auto
            // separately observes the shell initiator, so it is not a trusted-store guarantee.
            is InstallManager.Outcome.InstalledUnattended ->
                Logger.i(TAG, "RESULT=ATTRIBUTED version=${outcome.versionName}")
            is InstallManager.Outcome.InstalledCarCompatible ->
                Logger.i(
                    TAG,
                    "RESULT=ATTRIBUTED version=${outcome.versionName} " +
                        "carified=${outcome.packageName} " +
                        "system=${outcome.usedSystemInstaller}",
                )
            is InstallManager.Outcome.HandedToSystemInstaller ->
                Logger.i(TAG, "RESULT=SYSTEM_INSTALLER (not Play-attributed)")
            is InstallManager.Outcome.NeedsShizuku ->
                Logger.e(TAG, "RESULT=NEEDS_SHIZUKU Shizuku is not ready; unattended install " +
                    "cannot fall back to the system installer")
            is InstallManager.Outcome.Failed ->
                Logger.e(TAG, "RESULT=FAILED ${outcome.message}")
        }
    }

    private suspend fun convert(context: Context, packageName: String?) {
        if (packageName.isNullOrBlank()) {
            Logger.e(TAG, "RESULT=ERROR missing --es package")
            return
        }
        // ALL scope: conversion applies to any package, and an explicit convert-by-name request
        // must not answer "not installed" merely because the app declares no Android Auto support.
        val app = InstalledAppScanner(context)
            .scan(scope = ScanScope.ALL, includeSystemApps = true)
            .firstOrNull { it.packageName == packageName }
        if (app == null) {
            Logger.e(TAG, "RESULT=ERROR $packageName is not installed")
            return
        }

        Logger.i(TAG, "CONVERT $packageName action=${app.conversionAction} " +
            "state=${app.state} apks=${app.apkPaths.size}")
        if (!ShizukuInstaller.ensureReady()) {
            Logger.e(TAG, "RESULT=FAILED Shizuku not ready")
            return
        }
        when (app.conversionAction) {
            ConversionAction.CAR_COPY -> when (val result = CarifyRepackager(context).convert(app)) {
                is CarifyRepackager.Result.Success ->
                    Logger.i(TAG, "RESULT=CARIFIED ${result.packageName}")
                is CarifyRepackager.Result.Failure ->
                    Logger.e(TAG, "RESULT=FAILED ${result.message}")
            }
            null -> Logger.i(TAG, "RESULT=ALREADY_CONVERTED $packageName")
        }
    }

    /**
     * Overrides are applied only when the extra is actually present, so `--es repo ''` is a
     * distinct instruction ("no update source") rather than "use the default".
     */
    private suspend fun updateCheck(intent: Intent) {
        val checker = SelfUpdateChecker(
            currentVersion = intent.getStringExtra("version") ?: BuildConfig.VERSION_NAME,
            repo = intent.getStringExtra("repo") ?: BuildConfig.UPDATE_REPO,
        )
        when (val result = checker.check()) {
            is SelfUpdateChecker.Result.Disabled -> Logger.i(TAG, "RESULT=UPDATE_DISABLED")
            is SelfUpdateChecker.Result.NoRelease -> Logger.i(TAG, "RESULT=UPDATE_NONE")
            is SelfUpdateChecker.Result.UpToDate ->
                Logger.i(TAG, "RESULT=UPDATE_CURRENT version=${result.version}")
            is SelfUpdateChecker.Result.Available ->
                Logger.i(TAG, "RESULT=UPDATE_AVAILABLE version=${result.version} " +
                    "alongside=${result.sidesteps}")
            is SelfUpdateChecker.Result.Failed ->
                Logger.e(TAG, "RESULT=UPDATE_FAILED ${result.message}")
        }
    }

    private suspend fun status(context: Context) {
        ShizukuInstaller.refreshInstalledState(context.packageManager)
        // Deliberately does NOT call ensureReady(): that prompts, and a prompt needs a screen.
        // A status query has to be answerable on a locked device.
        val availability = ShizukuInstaller.availability()
        val catalog = runCatching { CatalogRepository(context).loadCatalog() }.getOrNull()
        val installed = InstalledAppScanner(context).scan(includeSystemApps = true)
        Logger.i(
            TAG,
            "RESULT=STATUS availability=$availability " +
                "catalogApps=${catalog?.apps?.size ?: -1} aaCapableInstalled=${installed.size} " +
                "convertible=${installed.count { it.state == com.legs.appsforaa.data.ConversionState.CONVERTIBLE }}"
        )
        installed.forEach {
            Logger.i(TAG, "  installed: ${it.packageName} state=${it.state} installer=${it.installerPackage}")
        }
    }
}
