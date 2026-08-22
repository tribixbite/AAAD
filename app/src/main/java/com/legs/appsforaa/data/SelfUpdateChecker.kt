package com.legs.appsforaa.data

import com.legs.appsforaa.BuildConfig
import com.legs.appsforaa.utils.Logger
import com.legs.appsforaa.utils.VersionCompare

/**
 * Checks whether a newer build of AAAD itself has been published.
 *
 * Upstream had an update check pointing at upstream's releases, which is wrong for a fork: it
 * would offer the user a different app. This one targets `BuildConfig.UPDATE_REPO`.
 *
 * Two deliberate limits:
 *
 * - **Stable releases only.** CI publishes a `dev-<sha>` prerelease on every push; offering those
 *   would turn the update card into a notification treadmill that fires several times a day.
 *   GitHub's `releases/latest` already excludes prereleases, so this needs no extra filtering —
 *   but it does mean a fork with no tagged release yet reports [Result.NoRelease], which is the
 *   honest answer rather than an error.
 * - **Only when asked.** There is no periodic check and no background worker. A sideloaded app
 *   that phones home on a timer is exactly what this fork removed.
 */
class SelfUpdateChecker(
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val repo: String = BuildConfig.UPDATE_REPO,
    private val currentPackage: String = BuildConfig.APPLICATION_ID,
    private val resolver: ReleaseResolver = ReleaseResolver(),
) {

    private companion object {
        const val TAG = "SelfUpdate"
        const val ENTRY_ID = "aaad-self"
    }

    sealed interface Result {
        /** No repo configured, so there is nothing to check against. */
        data object Disabled : Result

        /** The repo has published no stable release yet. Expected on a fork before its first tag. */
        data object NoRelease : Result

        data class UpToDate(val version: String) : Result

        /**
         * [entry] is a synthetic catalog entry for AAAD itself, so the caller can hand it to
         * `InstallManager` and reuse the download/install path rather than growing a second one.
         *
         * [sidesteps] is true when the published build carries a different applicationId than the
         * running one — a `.dev` build cannot be updated *by* a release build, it installs
         * alongside it. Saying so beats letting the user discover two AAAD icons.
         */
        data class Available(
            val version: String,
            val entry: AppEntry,
            val sidesteps: Boolean,
        ) : Result

        data class Failed(val message: String) : Result
    }

    suspend fun check(): Result {
        if (repo.isBlank()) return Result.Disabled

        val source = AppSource.GitHubRelease(repo, assetPattern = "\\.apk$")
        val release = try {
            resolver.resolve(source)
        } catch (e: NoReleaseException) {
            Logger.i(TAG, "No stable release in $repo yet")
            return Result.NoRelease
        } catch (e: Exception) {
            Logger.w(TAG, "Update check against $repo failed", e)
            return Result.Failed(e.message ?: "Update check failed")
        }

        // VersionCompare returns null when it cannot confidently order the two, and an update
        // prompt the user cannot verify is worse than no prompt: treat unknown as up to date.
        val newer = VersionCompare.isNewer(currentVersion, release.versionName)
        if (newer != true) {
            Logger.d(TAG, "Current $currentVersion vs published ${release.versionName}: no update")
            return Result.UpToDate(currentVersion)
        }

        return Result.Available(
            version = release.versionName,
            entry = AppEntry(
                id = ENTRY_ID,
                name = "AAAD",
                packageName = currentPackage,
                category = AppCategory.OTHER,
                descriptionRes = "",
                source = source,
            ),
            // A debug build carries the `.dev` applicationId suffix; the published release does
            // not, so the two coexist rather than replacing each other.
            sidesteps = !currentPackage.equals(releasePackageOf(currentPackage), ignoreCase = true),
        )
    }

    /**
     * The applicationId the published release is built with: the running id minus the debug
     * suffix. Derived rather than hardcoded so renaming the app does not silently break the check.
     */
    private fun releasePackageOf(running: String): String = running.removeSuffix(".dev")
}
