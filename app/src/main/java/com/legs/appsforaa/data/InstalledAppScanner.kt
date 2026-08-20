package com.legs.appsforaa.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.legs.appsforaa.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The metadata key an app must declare for Android Auto to consider it at all.
 * See `docs/aa-visibility.md`.
 */
private const val AA_METADATA_KEY = "com.google.android.gms.car.application"

/** The installer attribution Android Auto trusts. */
private const val PLAY_STORE_PACKAGE = "com.android.vending"

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
) {
    val isSplit: Boolean get() = apkPaths.size > 1
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
    }

    /**
     * Every installed app declaring the Android Auto metadata key, with its conversion state.
     *
     * Requires `QUERY_ALL_PACKAGES`, which the manifest declares — without it this returns only
     * the handful of packages listed in `<queries>`.
     */
    suspend fun scan(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val installed = runCatching {
            packageManager.getInstalledApplications(flags)
        }.getOrElse {
            Logger.e(TAG, "Could not enumerate installed applications", it)
            return@withContext emptyList()
        }

        val ourPackage = context.packageName
        installed.asSequence()
            .filter { it.packageName != ourPackage }
            .filter { declaresAndroidAuto(it) }
            .mapNotNull { info -> toInstalledApp(packageManager, info) }
            .sortedBy { it.label.lowercase() }
            .toList()
            .also { Logger.i(TAG, "Found ${it.size} Android Auto capable apps installed") }
    }

    private fun declaresAndroidAuto(info: ApplicationInfo): Boolean =
        info.metaData?.containsKey(AA_METADATA_KEY) == true

    private fun toInstalledApp(
        packageManager: PackageManager,
        info: ApplicationInfo,
    ): InstalledApp? {
        val packageInfo = runCatching {
            packageManager.getPackageInfo(info.packageName, 0)
        }.getOrElse {
            Logger.w(TAG, "Skipping ${info.packageName}: no PackageInfo", it)
            return null
        }

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
