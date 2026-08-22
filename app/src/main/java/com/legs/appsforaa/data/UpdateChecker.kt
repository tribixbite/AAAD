package com.legs.appsforaa.data

import com.legs.appsforaa.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Finds the latest published version of each installed catalog app.
 *
 * The README upstream has promised an update checker for years and never shipped one; this is it,
 * and it is deliberately modest.
 *
 * Two constraints shape it:
 *
 * - **Only installed apps are checked.** Resolving an app the user does not have tells them
 *   nothing and still costs a request against GitHub's unauthenticated rate limit.
 * - **It runs only when the user asks** (pull to refresh), never on every list render. A catalog
 *   screen that fires a network request per app each time it is drawn is how a standalone app
 *   quietly becomes a chatty one.
 */
class UpdateChecker(private val resolver: ReleaseResolver = ReleaseResolver()) {

    private companion object {
        const val TAG = "UpdateChecker"
    }

    /**
     * @return entry id → latest published version, for entries that resolved. Entries that fail
     *   are simply absent: a publisher who moved their release should not turn into an error on
     *   the catalog screen, and the previous state stays visible.
     */
    suspend fun latestVersions(entries: List<AppEntry>): Map<String, String> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                entries
                    .map { entry ->
                        async {
                            runCatching { entry.id to resolver.resolve(entry.source).versionName }
                                .onFailure { Logger.d(TAG, "No release for ${entry.id}: ${it.message}") }
                                .getOrNull()
                        }
                    }
                    .mapNotNull { it.await() }
                    .filter { (_, version) -> version.isNotBlank() }
                    .toMap()
                    .also { Logger.i(TAG, "Resolved ${it.size}/${entries.size} latest versions") }
            }
        }
}
