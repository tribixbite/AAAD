package com.legs.appsforaa.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import com.legs.appsforaa.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The metadata key an app must declare for Android Auto to consider it at all.
 * See `docs/aa-visibility.md`.
 */
private const val AA_METADATA_KEY = "com.google.android.gms.car.application"

/** The installer attribution Android Auto trusts. */
private const val PLAY_STORE_PACKAGE = "com.android.vending"

/** Which installed apps a scan should return. */
enum class ScanScope {
    /** Only apps declaring the Android Auto metadata key. */
    ANDROID_AUTO,

    /**
     * Every installed app. Hundreds on a real phone, so callers need a filter in front of it —
     * but conversion is a property of *any* package, not just an AA-capable one, and refusing to
     * list the rest hides that.
     */
    ALL,
}

/** How an installed, Android-Auto-capable app stands with respect to AA visibility. */
enum class ConversionState {
    /** Attributed to the Play Store already — nothing to do. */
    ALREADY_ATTRIBUTED,

    /** AA-capable but attributed to something else, so AA will not list it. Convertible. */
    CONVERTIBLE,
}

/**
 * An installed app that declares Android Auto support.
 *
 * [apkPaths] is the base APK followed by any split APKs. Conversion has to re-stage **all** of
 * them: committing a session containing only the base of a split app fails, or worse produces an
 * app missing its resources.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val installerPackage: String?,
    val apkPaths: List<String>,
    val state: ConversionState,
    /**
     * Preinstalled or a system-image update. Google's own Android Auto apps — Maps, Messages,
     * Dialer, the Play Store — are all system apps, and every one of them is already attributed.
     */
    val isSystemApp: Boolean = false,
    /**
     * What the app told Android Auto it can do, or null when it declares nothing readable.
     * Independent of [state]: see [AutomotiveDescriptor].
     */
    val carCapabilities: AutomotiveDescriptor.Capabilities? = null,
) {
    val isSplit: Boolean get() = apkPaths.size > 1

    /**
     * Whether the app declares the Android Auto metadata key at all.
     *
     * Decisive for what conversion buys: attribution is what Android Auto checks *second*. An app
     * that never declares AA support will not be listed however it was installed, so converting it
     * is legitimate — it fixes the attribution — but it will not put the app in the car.
     */
    val declaresAndroidAuto: Boolean get() = carCapabilities != null

    /**
     * Android Auto will list it but refuse to open it while driving. Nothing on this phone can
     * change that — it is a statement the app makes in its own manifest.
     */
    val blockedWhileDriving: Boolean
        get() = carCapabilities?.let { !it.hasCarUi && !it.isEmpty } == true
}

/**
 * Finds installed apps that Android Auto could show but currently will not, because they were
 * not installed with Play Store attribution.
 *
 * This is the discovery half of "conversion": an app sideloaded from anywhere — F-Droid, a
 * browser download, another installer, or an earlier AAAD build's fallback path — declares AA
 * support but is invisible in the car. It cannot be repaired in place
 * (`pm set-installer` is impossible, see `docs/aa-visibility.md`), so the fix is to reinstall the
 * very same APKs through an attributed session, which preserves data because the signature is
 * unchanged.
 */
class InstalledAppScanner(private val context: Context) {

    private companion object {
        const val TAG = "InstalledAppScanner"

        /** Enough chunks to saturate the IO pool without one coroutine per app. */
        const val CHUNK_SIZE = 32
    }

    /**
     * Installed apps with their conversion state.
     *
     * Requires `QUERY_ALL_PACKAGES`, which the manifest declares — without it this returns only
     * the handful of packages listed in `<queries>`.
     *
     * Uses `getInstalledPackages` rather than `getInstalledApplications` so each package's
     * `versionName` arrives in the same call. Under [ScanScope.ALL] that is the difference between
     * one binder round trip and one per app, and on a phone with several hundred apps the per-app
     * version lookup alone was the bulk of the scan.
     */
    /**
     * @param includeSystemApps whether to report preinstalled apps. False for the convert screen:
     *   on a stock phone ten of the eleven Android-Auto-capable apps installed are Google's own,
     *   all already attributed, and listing them buries the one app the user can actually act on
     *   under nine greyed-out rows that make the screen look broken. True for diagnostics, where
     *   the complete picture is the point.
     */
    suspend fun scan(
        scope: ScanScope = ScanScope.ANDROID_AUTO,
        includeSystemApps: Boolean = false,
    ): List<InstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val installed = runCatching {
            packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        }.getOrElse {
            Logger.e(TAG, "Could not enumerate installed packages", it)
            return@withContext emptyList()
        }

        val ourPackage = context.packageName
        val candidates = installed.asSequence()
            .mapNotNull { pkg -> pkg.applicationInfo?.let { pkg to it } }
            .filter { (_, info) -> info.packageName != ourPackage }
            .filter { (_, info) -> scope == ScanScope.ALL || declaresAndroidAuto(info) }
            .filter { (_, info) -> includeSystemApps || !isSystemApp(info) }
            .toList()

        // Each app still costs an installer lookup and a label load, both of which cross into
        // system_server or the target app's resources. At ALL scope that is ~800 apps, and doing
        // it serially took seconds on a real phone. The work is IO-bound and independent per app,
        // so it fans out; chunking keeps the coroutine count proportionate to the pool.
        val started = SystemClock.elapsedRealtime()
        val apps = coroutineScope {
            candidates
                .chunked(CHUNK_SIZE)
                .map { chunk ->
                    async { chunk.mapNotNull { (pkg, info) -> toInstalledApp(packageManager, pkg, info) } }
                }
                .awaitAll()
                .flatten()
        }

        apps.asSequence()
            // Convertible first: the actionable rows belong at the top, not interleaved
            // alphabetically with rows that have nothing to do.
            // Convertible first, then apps Android Auto could actually list, then by name. The
            // rows a user can act on belong at the top rather than interleaved alphabetically.
            .sortedWith(
                compareBy(
                    { it.state != ConversionState.CONVERTIBLE },
                    { !it.declaresAndroidAuto },
                    { it.label.lowercase() },
                )
            )
            .toList()
            .also {
                Logger.i(TAG, "Found ${it.size} apps (scope=$scope " +
                    "includeSystemApps=$includeSystemApps) in " +
                    "${SystemClock.elapsedRealtime() - started}ms")
            }
    }

    private fun declaresAndroidAuto(info: ApplicationInfo): Boolean =
        info.metaData?.containsKey(AA_METADATA_KEY) == true

    /** Covers both a preinstalled app and one that has since been updated over its system copy. */
    private fun isSystemApp(info: ApplicationInfo): Boolean =
        (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    private fun toInstalledApp(
        packageManager: PackageManager,
        packageInfo: PackageInfo,
        info: ApplicationInfo,
    ): InstalledApp? {
        val installer = installerPackageOf(packageManager, info.packageName)
        val apkPaths = buildList {
            info.sourceDir?.let(::add)
            info.splitSourceDirs?.let(::addAll)
        }
        if (apkPaths.isEmpty()) {
            Logger.w(TAG, "Skipping ${info.packageName}: no APK paths")
            return null
        }

        return InstalledApp(
            packageName = info.packageName,
            label = runCatching { packageManager.getApplicationLabel(info).toString() }
                .getOrDefault(info.packageName),
            versionName = packageInfo.versionName.orEmpty(),
            installerPackage = installer,
            apkPaths = apkPaths,
            state = if (installer == PLAY_STORE_PACKAGE) ConversionState.ALREADY_ATTRIBUTED
            else ConversionState.CONVERTIBLE,
            isSystemApp = isSystemApp(info),
            // Guarded by the cheap metadata check: reading a descriptor opens the app's resources,
            // and doing that for several hundred packages that plainly declare no car support
            // would dominate the scan.
            carCapabilities = if (declaresAndroidAuto(info)) {
                AutomotiveDescriptor.forInstalled(packageManager, info.packageName)
            } else {
                null
            },
        )
    }

    /** `getInstallSourceInfo` replaced the deprecated call in API 30. */
    private fun installerPackageOf(
        packageManager: PackageManager,
        packageName: String,
    ): String? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
    }.getOrNull()
}
