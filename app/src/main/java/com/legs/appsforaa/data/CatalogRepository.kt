package com.legs.appsforaa.data

import android.content.Context
import com.legs.appsforaa.BuildConfig
import com.legs.appsforaa.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Loads the catalog and resolves each entry against the packages actually installed.
 *
 * Standalone by design: with no `CATALOG_URL` configured this class makes **zero** network
 * calls — the bundled `assets/catalog.json` is the catalog. A configured URL is an override, and
 * any failure to fetch or parse it falls back to the bundled copy rather than failing the screen.
 * See `docs/standalone.md`.
 */
class CatalogRepository(
    private val context: Context,
    private val userStore: UserCatalogStore = UserCatalogStore(context),
) {

    private companion object {
        const val TAG = "CatalogRepo"
        const val BUNDLED_ASSET = "catalog.json"
        const val NETWORK_TIMEOUT_SECONDS = 15L
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
        val base = fetchRemoteCatalog() ?: loadBundledCatalog()
        val userApps = userStore.load()
        if (userApps.isEmpty()) return@withContext base
        // User entries win on id collision: someone who added a repo by hand meant it.
        val merged = base.apps.filterNot { app -> userApps.any { it.id == app.id } } + userApps
        base.copy(apps = merged)
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
     * Available-version comparison is deliberately absent here: it would need a network round trip
     * per entry on every list refresh. Update detection belongs in a background worker
     * (TASKS.md T-40), so an installed app reports [InstallState.Installed], never
     * [InstallState.UpdateAvailable], for now.
     */
    fun resolveItems(catalog: Catalog): List<AppListItem> =
        catalog.apps.map { entry ->
            AppListItem(
                entry = entry,
                state = installStateOf(entry),
                descriptionResId = descriptionResIdOf(entry),
            )
        }

    private fun installStateOf(entry: AppEntry): InstallState {
        // A user-added entry has no package name until its first install teaches us one.
        if (entry.packageName.isBlank()) return InstallState.NotInstalled
        val installedVersion = installedVersionName(entry.packageName)
            ?: return InstallState.NotInstalled
        return InstallState.Installed(installedVersion)
    }

    /** Null when the package is absent. Requires the `<queries>` entry or QUERY_ALL_PACKAGES. */
    private fun installedVersionName(packageName: String): String? = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrNull()

    /**
     * Resolves `descriptionRes` (a string resource *name*, so one catalog serves all 30 locales)
     * to an id. Returns 0 when absent, which callers treat as "no description".
     */
    private fun descriptionResIdOf(entry: AppEntry): Int {
        if (entry.descriptionRes.isBlank()) return 0
        @Suppress("DiscouragedApi") // by-name lookup is the point: the catalog is data, not code
        return context.resources.getIdentifier(
            entry.descriptionRes, "string", context.packageName
        )
    }
}
