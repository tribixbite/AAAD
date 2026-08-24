package com.legs.appsforaa.data

import android.content.Context
import com.legs.appsforaa.BuildConfig
import com.legs.appsforaa.utils.Logger
import com.legs.appsforaa.utils.VersionCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Loads the catalog and resolves each entry against the packages actually installed.
 *
 * Standalone by design: with no `CATALOG_URL` configured this class makes **zero** network
 * calls — the bundled `assets/catalog.json` is the catalog. A configured URL is an override, and
 * any failure to fetch or parse it falls back to the bundled copy rather than failing the screen.
 * See `docs/standalone.md`.
 *
 * A file pushed to the app's external files directory outranks both, so a test run can supply its
 * own catalog without a rebuild (T-42).
 */
class CatalogRepository(
    private val context: Context,
    private val userStore: UserCatalogStore = UserCatalogStore(context),
) {

    private companion object {
        const val TAG = "CatalogRepo"
        const val BUNDLED_ASSET = "catalog.json"
        const val NETWORK_TIMEOUT_SECONDS = 15L

        /**
         * Override file in the app's own external files directory:
         * `/sdcard/Android/data/<applicationId>/files/catalog.json`.
         *
         * That directory is chosen because `adb push` can write it with no permission at all —
         * no storage permission, no `MANAGE_EXTERNAL_STORAGE`, no root — which is the whole point
         * of an override meant to be dropped in from a test harness.
         */
        const val OVERRIDE_FILE = "catalog.json"
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Remote catalog if one is configured and usable, otherwise the bundled one.
     *
     * @throws IllegalStateException only if the bundled asset itself is missing or invalid, which
     *   is a packaging bug rather than a runtime condition.
     */
    suspend fun loadCatalog(): Catalog = withContext(Dispatchers.IO) {
        // Precedence, weakest first: bundled < remote < device override. Each layer is something
        // a person chose more deliberately than the one before it.
        val base = loadOverrideCatalog() ?: fetchRemoteCatalog() ?: loadBundledCatalog()
        val userApps = userStore.load()
        if (userApps.isEmpty()) return@withContext base
        // User entries win on id collision: someone who added a repo by hand meant it.
        val merged = base.apps.filterNot { app -> userApps.any { it.id == app.id } } + userApps
        base.copy(apps = merged)
    }

    /**
     * A catalog pushed to the device, or null when there is none.
     *
     * This exists so an unlisted APK can be tested without editing the bundled catalog and
     * rebuilding — see `docs/testing-harness.md`. It replaces the catalog rather than merging into
     * it: a test run that wants three specific apps should get exactly those three, not those
     * three plus seven it did not ask for.
     *
     * A malformed override is logged and ignored rather than fatal, because the fix is to push a
     * corrected file, and an app that will not start is a poor way to report a typo.
     */
    private fun loadOverrideCatalog(): Catalog? {
        val file = File(context.getExternalFilesDir(null), OVERRIDE_FILE)
        if (!file.isFile) return null

        return runCatching {
            Catalog.parse(file.readText(), Catalog.Origin.DEVICE_OVERRIDE).also {
                if (it == null) {
                    Logger.w(TAG, "Override ${file.path} is unparseable or targets an unsupported " +
                        "schema; ignoring it")
                } else {
                    Logger.i(TAG, "Using device override ${file.path} (${it.apps.size} apps)")
                }
            }
        }.onFailure {
            Logger.w(TAG, "Could not read override ${file.path}", it)
        }.getOrNull()
    }

    private fun loadBundledCatalog(): Catalog {
        val raw = context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
        return Catalog.parse(raw, Catalog.Origin.BUNDLED)
            ?: error("Bundled $BUNDLED_ASSET is missing or does not match schema " +
                "${Catalog.SUPPORTED_SCHEMA_VERSION}")
    }

    private fun fetchRemoteCatalog(): Catalog? {
        val url = BuildConfig.CATALOG_URL
        if (url.isBlank()) return null

        return runCatching {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.w(TAG, "Remote catalog HTTP ${response.code}; using bundled")
                    return@use null
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    Logger.w(TAG, "Remote catalog empty; using bundled")
                    return@use null
                }
                Catalog.parse(body, Catalog.Origin.REMOTE).also {
                    if (it == null) Logger.w(TAG, "Remote catalog unparseable; using bundled")
                }
            }
        }.onFailure {
            Logger.w(TAG, "Remote catalog fetch failed; using bundled", it)
        }.getOrNull()
    }

    /**
     * Pairs each entry with its on-device state and resolves its description resource.
     *
     * [latestVersions] maps entry id to the newest published version, as gathered by
     * [UpdateChecker] on an explicit refresh. It is empty on a normal load, which is the point:
     * resolving releases costs a network request per app, so the list renders from local state
     * and only learns about updates when the user asks.
     */
    fun resolveItems(
        catalog: Catalog,
        latestVersions: Map<String, String> = emptyMap(),
    ): List<AppListItem> =
        catalog.apps.map { entry ->
            AppListItem(
                entry = entry,
                state = installStateOf(entry, latestVersions[entry.id]),
                descriptionText = descriptionTextOf(entry),
            )
        }

    private fun installStateOf(entry: AppEntry, latestVersion: String?): InstallState {
        // A user-added entry has no package name until its first install teaches us one.
        if (entry.packageName.isBlank()) return InstallState.NotInstalled
        val installedPackage = installedPackageName(entry.packageName)
            ?: return InstallState.NotInstalled
        val installedVersion = installedVersionName(installedPackage)
            ?: return InstallState.NotInstalled

        // Only claim an update when the comparison is confident. VersionCompare returns null for
        // versions it cannot order — "beta1.1", an untagged commit hash — and a phantom update
        // badge is worse than none, because it teaches people to ignore the badge.
        val newer = VersionCompare.isNewer(installedVersion, latestVersion)
        return if (newer == true && latestVersion != null) {
            InstallState.UpdateAvailable(installedVersion, latestVersion)
        } else {
            InstallState.Installed(installedVersion)
        }
    }

    /** Null when the package is absent. Requires the `<queries>` entry or QUERY_ALL_PACKAGES. */
    private fun installedVersionName(packageName: String): String? = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrNull()

    /**
     * Resolves a catalog package to its driving-compatible clone when one exists.
     *
     * Known catalog entries retain the publisher package id, while media-only/parked downloads
     * install as a side-by-side Carify clone. User-discovered entries may already have learned the
     * clone id from PACKAGE_ADDED, so suffixes are never appended twice.
     */
    fun installedPackageName(packageName: String): String? {
        if (packageName.isBlank()) return null
        val candidates = if (
            packageName.endsWith(".aaad") || packageName.endsWith(".aaaddev")
        ) {
            listOf(packageName)
        } else {
            val preferredSuffix = if (BuildConfig.DEBUG) ".aaaddev" else ".aaad"
            val otherSuffix = if (BuildConfig.DEBUG) ".aaad" else ".aaaddev"
            listOf(packageName + preferredSuffix, packageName + otherSuffix, packageName)
        }
        return candidates.firstOrNull { installedVersionName(it) != null }
    }

    /** Literal text is used for discovered repos; bundled entries keep localized resources. */
    private fun descriptionTextOf(entry: AppEntry): String {
        if (entry.description.isNotBlank()) return entry.description
        if (entry.descriptionRes.isBlank()) return ""
        @Suppress("DiscouragedApi") // by-name lookup is the point: the catalog is data, not code
        val id = context.resources.getIdentifier(
            entry.descriptionRes, "string", context.packageName
        )
        return if (id == 0) "" else context.getString(id)
    }
}
