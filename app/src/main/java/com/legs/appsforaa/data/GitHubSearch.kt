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
    val language: String = "",
    val updatedAt: String = "",
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
        const val REPO_URL = "https://api.github.com/repos"
        val OWNER_PATTERN = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
        val REPO_PATTERN = Regex("[A-Za-z0-9._-]{1,100}")
        const val PER_PAGE = 50
        const val TIMEOUT_SECONDS = 20L
        const val MAX_DESCRIPTION_CHARS = 280

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
                    val result = items.optJSONObject(i)?.let(::parseResult) ?: continue
                    if (result.stars >= minStars) add(result)
                }
            }.also { Logger.d(TAG, "\"$query\" -> ${it.size} repos") }
        }

    /** Resolves one pasted reference before it can be added to the catalog. */
    suspend fun lookup(repo: String): RepoResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$REPO_URL/$repo")
            .header("Accept", "application/vnd.github+json")
            .build()

        val body = httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) error("Repository $repo not found")
            if (response.code == 403 || response.code == 429) {
                error("GitHub rate limit reached — try again in a minute")
            }
            check(response.isSuccessful) {
                "Could not load $repo: HTTP ${response.code}"
            }
            response.body?.string().orEmpty()
        }
        val result = parseResult(JSONObject(body))
            ?: error("GitHub returned incomplete details for $repo")
        Logger.d(TAG, "$repo -> verified repository")
        result
    }

    /**
     * Accepts what a user is likely to paste: a full GitHub URL, or a bare `owner/repo`.
     * Returns null when it is neither.
     */
    fun parseRepoReference(input: String): String? {
        val trimmed = input.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return null

        val path = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
                if (uri.scheme !in setOf("http", "https")) return null
                if (uri.userInfo != null || uri.port != -1) return null
                if (uri.host?.lowercase() !in setOf("github.com", "www.github.com")) return null
                uri.path.orEmpty()
            }
            trimmed.startsWith("github.com/") -> trimmed.removePrefix("github.com/")
            else -> trimmed
        }.trim('/')

        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return null
        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")
        if (repo == "." || repo == "..") return null
        if (!OWNER_PATTERN.matches(owner)) return null
        if (!REPO_PATTERN.matches(repo)) return null
        return "$owner/$repo"
    }

    private fun parseResult(item: JSONObject): RepoResult? {
        val fullName = item.optString("full_name").ifBlank { return null }
        val htmlUrl = item.optString("html_url").ifBlank {
            "https://github.com/$fullName"
        }
        return RepoResult(
            fullName = fullName,
            description = sanitizeDescription(
                if (item.isNull("description")) "" else item.optString("description")
            ),
            stars = item.optInt("stargazers_count"),
            archived = item.optBoolean("archived"),
            htmlUrl = htmlUrl,
            language = if (item.isNull("language")) "" else item.optString("language"),
            updatedAt = item.optString("updated_at"),
        )
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    /**
     * Repository descriptions are untrusted metadata. Some repositories abuse the field with a
     * complete page/script payload; keep the useful lead sentence and bound every result card.
     * HTML tags/entities are separately reduced to plain text by the adapters.
     */
    internal fun sanitizeDescription(value: String): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        val suspiciousMarkers = listOf(
            " window.",
            " document.",
            " function(",
            "<script",
            "<style",
            " localStorage.",
        )
        val firstMarker = suspiciousMarkers
            .map { normalized.indexOf(it, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull()
            ?: normalized.length
        return normalized.substring(0, firstMarker).trim().take(MAX_DESCRIPTION_CHARS)
    }
}
