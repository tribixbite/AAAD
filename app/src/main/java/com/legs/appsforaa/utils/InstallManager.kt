package com.legs.appsforaa.utils

import android.content.Context
import com.legs.appsforaa.data.AppEntry
import com.legs.appsforaa.data.AutomotiveDescriptor
import com.legs.appsforaa.data.InstallPolicy
import com.legs.appsforaa.data.ReleaseResolver
import java.io.File

/**
 * Orchestrates resolve → download → install for one catalog entry.
 *
 * The install method is chosen per attempt because Shizuku's state changes underneath the app.
 * Shizuku makes installation unattended; it does not make a shell-initiated install a genuine
 * Play Store install. Android Auto visibility still follows the app surface and its developer
 * Unknown sources setting. See `docs/aa-visibility.md`.
 */
class InstallManager(
    private val context: Context,
    private val downloader: ApkDownloader = ApkDownloader(context),
    private val resolver: ReleaseResolver = ReleaseResolver(),
) {

    private companion object {
        const val TAG = "InstallManager"
    }

    /** Progress reported to the UI. */
    sealed interface Progress {
        data object Resolving : Progress
        data class Downloading(val fraction: Float) : Progress
        data class MakingCompatible(val percent: Int) : Progress
        data object Installing : Progress
    }

    sealed interface Outcome {
        /** Installed unchanged through the unattended package-manager path. */
        data class InstalledUnattended(val versionName: String) : Outcome

        /** Installed as a parked, side-by-side car-compatible copy. */
        data class InstalledCarCompatible(
            val versionName: String,
            val packageName: String,
            val usedSystemInstaller: Boolean,
        ) : Outcome

        /**
         * Handed to the system installer. The user still has to confirm, and the result will not
         * be visible in Android Auto only where its developer *Unknown sources* setting permits
         * that app category.
         */
        data object HandedToSystemInstaller : Outcome

        /**
         * Shizuku was not available and the caller does not allow the system-installer fallback.
         *
         * Distinct from [Failed] because nothing went wrong: the install simply cannot proceed
         * without a person present to confirm it, which is a precondition rather than a fault.
         */
        data object NeedsShizuku : Outcome

        data class Failed(val message: String) : Outcome
    }

    /**
     * Runs the full flow. Cancelling the calling coroutine aborts an in-flight download.
     *
     * Never throws for expected conditions — a moved release, a dead network, or a refused
     * install all come back as [Outcome.Failed] with a message worth showing the user.
     *
     * @param allowSystemFallback whether to hand off to the system installer when Shizuku is not
     *   available. True for anything a person is watching. **False for automation**: the system
     *   installer needs a tap that will never come, and handing off would leave a dialog sitting
     *   on the device and report a "success" for an install that never happened.
     */
    suspend fun install(
        entry: AppEntry,
        allowSystemFallback: Boolean = true,
        onProgress: (Progress) -> Unit,
    ): Outcome {
        val release = runCatching {
            onProgress(Progress.Resolving)
            resolver.resolve(entry.source)
        }.getOrElse { error ->
            Logger.e(TAG, "Resolve failed for ${entry.id}", error)
            return Outcome.Failed(error.message ?: "Could not find a download")
        }

        val apk = runCatching {
            downloader.download(release) { fraction ->
                onProgress(Progress.Downloading(fraction))
            }
        }.getOrElse { error ->
            Logger.e(TAG, "Download failed for ${entry.id}", error)
            return Outcome.Failed(error.message ?: "Download failed")
        }

        return try {
            // A publisher-unchanged app depends on its own package/signature and may rely on
            // Android Auto's user-enabled Unknown sources route. For a foreground install, use
            // Android's visible installer instead of a shell/Shizuku initiator. Automation still
            // uses Shizuku because no person is present to confirm the dialog.
            if (
                entry.installPolicy == InstallPolicy.PUBLISHER_UNCHANGED &&
                allowSystemFallback
            ) {
                onProgress(Progress.Installing)
                return systemInstall(apk)
            }

            val capabilities = AutomotiveDescriptor.forApkFile(
                context.packageManager,
                apk.absolutePath,
            )
            // Waits for the permission dialog if one is needed, so the first install of a session
            // does not silently fall back to an interactive install.
            val shizukuReady = ShizukuInstaller.ensureReady()
            // A sideloaded Car App Library template is not admitted as a driving app merely
            // because the APK declares one. Preserve known legacy projection apps and publisher
            // parked apps; turn every other GitHub/catalog APK into an honest parked copy.
            val canInstallUnchanged =
                entry.installPolicy == InstallPolicy.PUBLISHER_UNCHANGED ||
                    capabilities?.projects == true || capabilities?.parkedOnly == true
            if (!canInstallUnchanged) {
                if (!shizukuReady && !allowSystemFallback) {
                    Logger.i(
                        TAG,
                        "Shizuku is not ready and " + entry.name +
                            " needs a parked car-compatible copy; " +
                            "unattended installation cannot show Android's confirmation dialog",
                    )
                    return Outcome.NeedsShizuku
                }
                installCarCompatible(
                    entry = entry,
                    apk = apk,
                    versionName = release.versionName,
                    shizukuReady = shizukuReady,
                    allowSystemFallback = allowSystemFallback,
                    onProgress = onProgress,
                )
            } else if (shizukuReady) {
                onProgress(Progress.Installing)
                when (val result = ShizukuInstaller.install(apk)) {
                    is ShizukuInstaller.Result.Success ->
                        Outcome.InstalledUnattended(release.versionName)
                    is ShizukuInstaller.Result.Failure -> if (allowSystemFallback) {
                        Logger.w(TAG, "Shizuku install failed, falling back: ${result.message}")
                        systemInstall(apk)
                    } else {
                        Logger.w(TAG, "Shizuku install failed and no fallback is permitted: " +
                            result.message)
                        Outcome.Failed(result.message)
                    }
                }
            } else if (!allowSystemFallback) {
                Logger.i(TAG, "Shizuku is not ready and the system installer needs a person to " +
                    "confirm, so ${entry.name} is not installed")
                Outcome.NeedsShizuku
            } else {
                onProgress(Progress.Installing)
                Logger.i(TAG, "Falling back to the system installer — the result will NOT be " +
                    "attributed to the Play Store, so Android Auto will not list ${entry.name} " +
                    "unless AA's Unknown sources is enabled")
                systemInstall(apk)
            }
        } finally {
            // The APK is reproducible; keeping it only costs cache space.
            apk.delete()
        }
    }

    private suspend fun installCarCompatible(
        entry: AppEntry,
        apk: File,
        versionName: String,
        shizukuReady: Boolean,
        allowSystemFallback: Boolean,
        onProgress: (Progress) -> Unit,
    ): Outcome {
        val repackager = CarifyRepackager(context)
        suspend fun run(mode: CarifyRepackager.InstallMode): CarifyRepackager.Result =
            repackager.convertApk(apk, entry.name, mode) { stage ->
                onProgress(Progress.MakingCompatible(stage.percent))
            }

        val firstMode = if (shizukuReady) {
            CarifyRepackager.InstallMode.SHIZUKU
        } else {
            CarifyRepackager.InstallMode.SYSTEM
        }
        var result = run(firstMode)
        if (
            result is CarifyRepackager.Result.Failure &&
            firstMode == CarifyRepackager.InstallMode.SHIZUKU &&
            allowSystemFallback
        ) {
                    Logger.w(TAG, "Unattended compatible install failed; trying Android installer: " +
                result.message)
            result = run(CarifyRepackager.InstallMode.SYSTEM)
        }

        return when (result) {
            is CarifyRepackager.Result.Success -> Outcome.InstalledCarCompatible(
                versionName = versionName,
                packageName = result.packageName,
                usedSystemInstaller = result.usedSystemInstaller,
            )
            is CarifyRepackager.Result.Failure -> Outcome.Failed(result.message)
        }
    }

    private suspend fun systemInstall(apk: File): Outcome =
        when (val result = SystemInstaller.install(context, apk)) {
            is SystemInstaller.Result.HandedOffToSystem -> Outcome.HandedToSystemInstaller
            is SystemInstaller.Result.Failure -> Outcome.Failed(result.message)
        }
}
