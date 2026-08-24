package com.legs.appsforaa.utils

import android.content.Context
import com.legs.appsforaa.data.AppEntry
import com.legs.appsforaa.data.AutomotiveDescriptor
import com.legs.appsforaa.data.ReleaseResolver
import java.io.File

/**
 * Orchestrates resolve → download → install for one catalog entry.
 *
 * The install method is chosen per attempt rather than configured, because Shizuku's state
 * changes underneath the app: the user can start or stop it at any time. The two methods are not
 * equivalent and the difference is not cosmetic — only the Shizuku path produces an install that
 * Android Auto will list. See `docs/aa-visibility.md`.
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
        /** Installed with Play Store attribution — Android Auto will list it. */
        data class InstalledAttributed(val versionName: String) : Outcome

        /** Installed as a driving-capable side-by-side clone instead of a parked/media-only app. */
        data class InstalledCarCompatible(
            val versionName: String,
            val packageName: String,
            val usedSystemInstaller: Boolean,
        ) : Outcome

        /**
         * Handed to the system installer. The user still has to confirm, and the result will not
         * carry Play Store attribution, so Android Auto lists it only when AA's *Unknown sources*
         * developer setting is enabled.
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
            val capabilities = AutomotiveDescriptor.forApkFile(
                context.packageManager,
                apk.absolutePath,
            )
            // Waits for the permission dialog if one is needed, so the first install of a session
            // does not silently fall back to an unattributed install.
            val shizukuReady = ShizukuInstaller.ensureReady()
            if (capabilities?.hasDrivingUi != true) {
                if (!shizukuReady && !allowSystemFallback) {
                    Logger.i(
                        TAG,
                        "Shizuku is not ready and " + entry.name +
                            " needs a car-compatible copy; " +
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
                        Outcome.InstalledAttributed(release.versionName)
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
            Logger.w(TAG, "Attributed compatible install failed; trying Android installer: " +
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
