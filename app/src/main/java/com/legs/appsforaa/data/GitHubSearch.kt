package com.legs.appsforaa.data

import com.legs.appsforaa.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A candidate app found on GitHub. */
data class RepoResult(
    val fullName: String,
    val description: String,
    val stars: Int,
    val archived: Boolean,
    val htmlUrl: String,
)

/**
 * Finds candidate Android Auto apps on GitHub.
 *
 * Modelled on Obtainium's GitHub source (`lib/app_sources/github.dart`): the same
 * `/search/repositories` endpoint, the same star-count floor, and the same practice of surfacing
 * archived repos rather than hiding them — an archived project is often still the only build of a
 * working Android Auto app, so the user should decide.
 *
 * Discovery deliberately does **not** try to prove a repo ships an AA-capable app: that would mean
 * downloading and parsing every candidate's APK. Capability is confirmed after install, by
 * [InstalledAppScanner], which reads the manifest metadata for real.
 */
class GitHubSearch(
    private val httpClient: OkHttpClient = defaultClient(),
) {

    private companion object {
        const val TAG = "GitHubSearch"
        const val SEARCH_URL = "https://api.github.com/search/repositories"
        const val PER_PAGE = 50
        const val TIMEOUT_SECONDS = 20L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Searches GitHub for [query], keeping repos with at least [minStars].
     *
     * @throws IllegalStateException on a non-2xx response. GitHub rate-limits unauthenticated
     *   search to roughly 10 requests/minute and answers 403 when exceeded, which is a normal
     *   condition worth showing the user rather than a crash.
     */
    suspend fun search(query: String, minStars: Int = 0): List<RepoResult> =
        withContext(Dispatchers.IO) {
            val url = "$SEARCH_URL?q=${encode(query)}&per_page=$PER_PAGE&sort=stars&order=desc"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .build()

            val body = httpClient.newCall(request).execute().use { response ->
                if (response.code == 403 || response.code == 429) {
                    error("GitHub search rate limit reached — try again in a minute")
                }
                check(response.isSuccessful) { "GitHub search failed: HTTP ${response.code}" }
                response.body?.string().orEmpty()
            }

            val items = JSONObject(body).optJSONArray("items") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val stars = item.optInt("stargazers_count")
                    if (stars < minStars) continue
                    val fullName = item.optString("full_name").ifBlank { continue }
                    add(
                        RepoResult(
                            fullName = fullName,
                            description = item.optString("description").ifBlank { "" },
                            stars = stars,
                            archived = item.optBoolean("archived"),
                            htmlUrl = item.optString("html_url"),
                        )
                    )
                }
            }.also { Logger.d(TAG, "\"$query\" -> ${it.size} repos") }
        }

    /**
     * Accepts what a user is likely to paste: a full GitHub URL, or a bare `owner/repo`.
     * Returns null when it is neither.
     */
    fun parseRepoReference(input: String): String? {
        val trimmed = input.trim().removeSuffix("/").removeSuffix(".git")
        if (trimmed.isEmpty()) return null

        val path = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                runCatching { java.net.URI(trimmed).path.orEmpty() }.getOrDefault("")
            trimmed.startsWith("github.com/") -> trimmed.removePrefix("github.com/")
            else -> trimmed
        }.trim('/')

        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return null
        // A release or tree URL carries more segments; owner/repo is always the first two.
        return "${segments[0]}/${segments[1]}"
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
