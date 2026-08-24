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

/** A genuine Google Play install has Play as both installer-of-record and initiating package. */
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
    /** Initiated by the Play Store (or a system app), not merely labelled as Play-installed. */
    TRUSTED_INSTALL,

    /** Sideloaded or initiated by another package. Some routes can become parked copies. */
    CONVERTIBLE,
}

/** What the Convert button must do for this package. */
enum class ConversionAction {
    /** Keep the publisher APK/signature and only repair its Play Store install attribution. */
    RESTAGE,

    /** Rewrite and sign a side-by-side copy with AAAD's car-compatible template bridge. */
    CAR_COPY,
}

/**
 * An installed app that can be offered by the Convert screen.
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
    /** The package that actually performed the install on Android 11+, if still available. */
    val initiatingPackage: String? = null,
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
     * Decisive for which conversion path is needed. Apps with their own usable car surface only
     * need their install attribution repaired. Everything else needs a rewritten side-by-side
     * copy carrying AAAD's template bridge.
     */
    val declaresAndroidAuto: Boolean get() = carCapabilities != null

    /** The publisher APK already declares a car implementation of some category. */
    val hasCarVersion: Boolean get() = carCapabilities?.hasCarUi == true

    /**
     * The declared experience is parked-only or has no launchable car UI.
     */
    val blockedWhileDriving: Boolean
        get() = carCapabilities?.let { it.parkedOnly || !it.hasCarUi } == true

    /**
     * Native car apps keep their publisher signature and data. Apps without a usable car surface
     * cannot be modified in place (their signature would no longer match), so they get a separate
     * Car copy instead. A Play-installed phone-only app is therefore actionable too.
     */
    val conversionAction: ConversionAction?
        get() = when {
            carCapabilities == null -> ConversionAction.CAR_COPY
            carCapabilities.parkedOnly -> null
            carCapabilities.templated && state == ConversionState.CONVERTIBLE ->
                ConversionAction.CAR_COPY
            !carCapabilities.hasCarUi -> ConversionAction.CAR_COPY
            state == ConversionState.CONVERTIBLE -> ConversionAction.RESTAGE
            else -> null
        }
}

/**
 * Finds installed apps and reports their declared car surface plus their real install provenance.
 *
 * This is the discovery half of "conversion": an app sideloaded from anywhere — F-Droid, a
 * browser download, another installer, or an earlier AAAD build's fallback path — declares AA
 * support but may be invisible in the car. Legacy projection apps can be re-staged unchanged;
 * untrusted templates and phone-only apps need a separate parked copy.
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

        val candidates = installed.asSequence()
            .mapNotNull { pkg -> pkg.applicationInfo?.let { pkg to it } }
            // These are outputs, not new conversion inputs. The original remains in the list and
            // its button updates the same copy on a later run.
            .filter { (_, info) ->
                !info.packageName.endsWith(".aaad") &&
                    !info.packageName.endsWith(".aaaddev")
            }
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
                    { it.conversionAction == null },
                    { it.conversionAction != ConversionAction.CAR_COPY },
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
        val installSource = installSourceOf(packageManager, info.packageName)
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
            installerPackage = installSource.installer,
            initiatingPackage = installSource.initiator,
            apkPaths = apkPaths,
            state = if (isSystemApp(info) || installSource.isGenuinePlayInstall) {
                ConversionState.TRUSTED_INSTALL
            } else ConversionState.CONVERTIBLE,
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

    private data class InstallSource(
        val installer: String?,
        val initiator: String?,
        val isGenuinePlayInstall: Boolean,
    )

    /**
     * Reads both install-source identities. `installingPackageName` is a mutable label: shell can
     * set it to Play with `pm install -i`. `initiatingPackageName` identifies who performed the
     * install and is what current Android Auto uses for its trusted-source check.
     */
    private fun installSourceOf(
        packageManager: PackageManager,
        packageName: String,
    ): InstallSource = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val source = packageManager.getInstallSourceInfo(packageName)
            InstallSource(
                installer = source.installingPackageName,
                initiator = source.initiatingPackageName,
                isGenuinePlayInstall = source.initiatingPackageName == PLAY_STORE_PACKAGE,
            )
        } else {
            @Suppress("DEPRECATION")
            val installer = packageManager.getInstallerPackageName(packageName)
            InstallSource(installer, null, installer == PLAY_STORE_PACKAGE)
        }
    }.getOrElse { InstallSource(null, null, false) }
}
