package com.legs.appsforaa.data

import com.legs.appsforaa.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A concrete APK to download, resolved from an [AppSource]. */
data class ResolvedRelease(
    val versionName: String,
    val downloadUrl: String,
    val assetName: String,
    val sizeBytes: Long,
)

/**
 * Turns an [AppSource] into a concrete download.
 *
 * Only publisher-hosted sources are supported by design — see `docs/standalone.md`. There is no
 * AAAD-operated mirror, so every resolution here talks to the publisher's own host.
 */
class ReleaseResolver(
    private val httpClient: OkHttpClient = defaultClient(),
) {

    private companion object {
        const val TAG = "ReleaseResolver"
        const val GITHUB_API = "https://api.github.com/repos"
        const val TIMEOUT_SECONDS = 20L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * @throws IllegalStateException when the release or a matching asset cannot be found — the
     *   caller surfaces the message, since "publisher moved their release" is a real, expected
     *   condition rather than a bug.
     */
    suspend fun resolve(source: AppSource): ResolvedRelease = withContext(Dispatchers.IO) {
        when (source) {
            is AppSource.GitHubRelease -> resolveGitHub(source)
            is AppSource.Direct -> ResolvedRelease(
                versionName = "",
                downloadUrl = source.url,
                assetName = source.url.substringAfterLast('/'),
                sizeBytes = -1L,
            )
            is AppSource.Manual -> error(
                "This app must be downloaded from its website: ${source.url}"
            )
        }
    }

    private fun resolveGitHub(source: AppSource.GitHubRelease): ResolvedRelease {
        val url = "$GITHUB_API/${source.repo}/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()

        val body = httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "GitHub returned HTTP ${response.code} for ${source.repo}"
            }
            response.body?.string().orEmpty()
        }
        check(body.isNotBlank()) { "Empty response from GitHub for ${source.repo}" }

        val release = JSONObject(body)
        // tag_name is the stable identifier; name is often decorative or absent.
        val version = release.optString("tag_name").removePrefix("v")
        val assets = release.optJSONArray("assets")
            ?: error("Release for ${source.repo} has no assets")

        val pattern = runCatching { Regex(source.assetPattern) }.getOrElse {
            error("Invalid assetPattern for ${source.repo}: ${source.assetPattern}")
        }

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!pattern.containsMatchIn(name)) continue
            val downloadUrl = asset.optString("browser_download_url").ifBlank { continue }
            Logger.d(TAG, "${source.repo} -> $name ($version)")
            return ResolvedRelease(
                versionName = version,
                downloadUrl = downloadUrl,
                assetName = name,
                sizeBytes = asset.optLong("size", -1L),
            )
        }

        error("No asset matching ${source.assetPattern} in ${source.repo} $version")
    }
}
