package com.legs.appsforaa.data

import android.content.Context
import com.legs.appsforaa.utils.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Apps the user added themselves, stored on device and merged into the catalog.
 *
 * Persisted as the same JSON shape as `assets/catalog.json` (`docs/standalone.md`) so an entry
 * can be moved between the bundled catalog, a remote catalog and this store without translation —
 * and so the store is inspectable with `adb shell run-as ... cat`.
 *
 * SharedPreferences rather than DataStore: this is a single small list read once per screen, and
 * a synchronous read keeps [CatalogRepository] free of a second async source.
 */
class UserCatalogStore(context: Context) {

    private companion object {
        const val TAG = "UserCatalogStore"
        const val PREFS_NAME = "user_catalog"
        const val KEY_APPS = "apps"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<AppEntry> {
        val raw = prefs.getString(KEY_APPS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let(AppEntry::fromJson)?.let(::add)
                }
            }
        }.getOrElse {
            Logger.e(TAG, "User catalog is corrupt; ignoring it", it)
            emptyList()
        }
    }

    /** Adding an entry whose id already exists replaces it, so re-adding a repo updates it. */
    fun add(entry: AppEntry) {
        val merged = load().filterNot { it.id == entry.id } + entry
        save(merged)
        Logger.i(TAG, "Added ${entry.id} (${entry.packageName})")
    }

    fun remove(id: String) {
        save(load().filterNot { it.id == id })
        Logger.i(TAG, "Removed $id")
    }

    /**
     * Records the package name an entry turned out to install as.
     *
     * A repo found by search does not advertise its package name — that only becomes knowable
     * once its APK is installed and the system reports it. Until then the entry cannot resolve
     * its own install state, so learning it is what makes the entry behave like a catalog app.
     */
    fun learnPackageName(id: String, packageName: String) {
        val existing = load().firstOrNull { it.id == id } ?: return
        if (existing.packageName == packageName) return
        save(load().map { if (it.id == id) it.copy(packageName = packageName) else it })
        Logger.i(TAG, "Learned package name for $id: $packageName")
    }

    fun contains(id: String): Boolean = load().any { it.id == id }

    private fun save(entries: List<AppEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_APPS, array.toString()).apply()
    }
}

/** Inverse of [AppEntry.fromJson]; kept next to the store that needs it. */
internal fun AppEntry.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("packageName", packageName)
    put("category", category.jsonValue)
    put("descriptionRes", descriptionRes)
    put("description", description)
    put(
        "source",
        when (val entrySource = source) {
            is AppSource.GitHubRelease -> JSONObject().apply {
                put("type", "github-release")
                put("repo", entrySource.repo)
                put("assetPattern", entrySource.assetPattern)
            }
            is AppSource.Direct -> JSONObject().apply {
                put("type", "direct")
                put("url", entrySource.url)
            }
            is AppSource.Manual -> JSONObject().apply {
                put("type", "manual")
                put("url", entrySource.url)
            }
        },
    )
}
