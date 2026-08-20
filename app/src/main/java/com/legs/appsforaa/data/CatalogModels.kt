package com.legs.appsforaa.data

import androidx.annotation.StringRes
import org.json.JSONObject

/**
 * Catalog model. Schema and rationale: `docs/standalone.md#catalog-format`.
 *
 * Parsed with `org.json` rather than a serialization library — it is part of the platform, the
 * schema is small, and a malformed remote catalog must degrade to the bundled one rather than
 * throw somewhere unexpected.
 */

/** Section a catalog entry belongs to. Maps to the existing section string resources. */
enum class AppCategory(val jsonValue: String) {
    MULTIMEDIA("multimedia"),
    MIRRORING("mirroring"),
    OTHER("other");

    companion object {
        /** Unknown values fall back to [OTHER] so a newer catalog never drops entries. */
        fun fromJson(value: String?): AppCategory =
            entries.firstOrNull { it.jsonValue.equals(value, ignoreCase = true) } ?: OTHER
    }
}

/** Where an entry's APK comes from. */
sealed interface AppSource {

    /** Newest asset matching [assetPattern] in the latest GitHub release of [repo]. */
    data class GitHubRelease(val repo: String, val assetPattern: String) : AppSource

    /** Fetched verbatim. */
    data class Direct(val url: String) : AppSource

    /** Opened in a browser so the user picks a build (Screen2Auto works this way). */
    data class Manual(val url: String) : AppSource

    companion object {
        fun fromJson(json: JSONObject?): AppSource? {
            if (json == null) return null
            return when (json.optString("type")) {
                "github-release" -> {
                    val repo = json.optString("repo").ifBlank { return null }
                    GitHubRelease(repo, json.optString("assetPattern").ifBlank { "\\.apk$" })
                }
                "direct" -> json.optString("url").ifBlank { null }?.let { Direct(it) }
                "manual" -> json.optString("url").ifBlank { null }?.let { Manual(it) }
                else -> null
            }
        }
    }
}

/**
 * One installable Android Auto app.
 *
 * [packageName] is the id the app ends up with **once installed**, which is not always the
 * publisher's own package name — see `TASKS.md` T-07. Installed-state detection keys off it.
 */
data class AppEntry(
    val id: String,
    val name: String,
    val packageName: String,
    val category: AppCategory,
    val descriptionRes: String,
    val source: AppSource,
) {
    companion object {
        fun fromJson(json: JSONObject): AppEntry? {
            val id = json.optString("id").ifBlank { return null }
            val name = json.optString("name").ifBlank { return null }
            val packageName = json.optString("packageName").ifBlank { return null }
            val source = AppSource.fromJson(json.optJSONObject("source")) ?: return null
            return AppEntry(
                id = id,
                name = name,
                packageName = packageName,
                category = AppCategory.fromJson(json.optString("category")),
                descriptionRes = json.optString("descriptionRes"),
                source = source,
            )
        }
    }
}

/** A parsed catalog plus where it came from, so the UI can say "offline" honestly. */
data class Catalog(
    val schemaVersion: Int,
    val updated: String,
    val apps: List<AppEntry>,
    val origin: Origin,
) {
    enum class Origin { BUNDLED, REMOTE }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1

        /**
         * Returns null when the payload is unparseable or targets a schema this build does not
         * understand. Entries that fail to parse individually are skipped, not fatal.
         */
        fun parse(raw: String, origin: Origin): Catalog? {
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val schemaVersion = root.optInt("schemaVersion", -1)
            if (schemaVersion != SUPPORTED_SCHEMA_VERSION) return null
            val array = root.optJSONArray("apps") ?: return null
            val apps = buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let(AppEntry::fromJson)?.let(::add)
                }
            }
            if (apps.isEmpty()) return null
            return Catalog(schemaVersion, root.optString("updated"), apps, origin)
        }
    }
}

/** How an entry relates to what is currently on the device. */
sealed interface InstallState {
    data object NotInstalled : InstallState
    data class Installed(val versionName: String) : InstallState
    data class UpdateAvailable(val installedVersion: String, val availableVersion: String) :
        InstallState
}

/** A catalog entry paired with its resolved on-device state, ready to render. */
data class AppListItem(
    val entry: AppEntry,
    val state: InstallState,
    @param:StringRes val descriptionResId: Int,
)
