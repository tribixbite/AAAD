package com.legs.appsforaa

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.legs.appsforaa.adapters.RepoAdapter
import com.legs.appsforaa.data.AppCategory
import com.legs.appsforaa.data.AppEntry
import com.legs.appsforaa.data.AppSource
import com.legs.appsforaa.data.GitHubSearch
import com.legs.appsforaa.data.RepoResult
import com.legs.appsforaa.data.UserCatalogStore
import com.legs.appsforaa.databinding.ActivityDiscoverBinding
import com.legs.appsforaa.utils.Logger
import com.legs.appsforaa.utils.applyBottomInsetPadding
import com.legs.appsforaa.utils.applyTopInsetPadding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Finds Android Auto apps on GitHub and adds them to the catalog.
 *
 * Modelled on Obtainium: a repo is the unit, its releases are the source of APKs, and the user
 * decides what to trust. Accepts a search phrase or a pasted repo URL.
 *
 * A discovered entry is added with **no package name** — a repo does not advertise one. The
 * catalog screen learns it from the first install, after which the entry behaves like any bundled
 * app. Whether the app really supports Android Auto is confirmed by
 * [com.legs.appsforaa.data.InstalledAppScanner] once installed, which reads the manifest for real
 * rather than trusting a repo description.
 */
class DiscoverActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "DiscoverActivity"

        /** Narrows a bare phrase towards Android projects that ship APKs. */
        const val QUERY_SUFFIX = " android auto"
    }

    private lateinit var binding: ActivityDiscoverBinding
    private lateinit var adapter: RepoAdapter
    private val search = GitHubSearch()
    private lateinit var userStore: UserCatalogStore

    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityDiscoverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.applyTopInsetPadding()
        binding.repoList.applyBottomInsetPadding()

        userStore = UserCatalogStore(applicationContext)

        adapter = RepoAdapter(
            isAdded = { userStore.contains(idFor(it)) },
            onAdd = ::addRepo,
        )
        binding.repoList.layoutManager = LinearLayoutManager(this)
        binding.repoList.adapter = adapter

        binding.queryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(binding.queryInput.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
    }

    /**
     * A pasted repo URL is added directly — searching for it would be a pointless round trip and
     * GitHub's search API is rate-limited to roughly ten requests a minute unauthenticated.
     */
    private fun runSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return

        search.parseRepoReference(query)?.let { repo ->
            addRepo(
                RepoResult(
                    fullName = repo,
                    description = getString(R.string.discover_added_directly),
                    stars = 0,
                    archived = false,
                    htmlUrl = "https://github.com/$repo",
                )
            )
            return
        }

        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            showLoading(true)
            runCatching { search.search(query + QUERY_SUFFIX) }
                .onSuccess { results ->
                    adapter.submitList(results)
                    showLoading(false)
                    binding.status.apply {
                        text = if (results.isEmpty()) getString(R.string.discover_no_results)
                        else ""
                        visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                    }
                    binding.discoverSubtitle.text =
                        resources.getQuantityString(
                            R.plurals.discover_results, results.size, results.size
                        )
                }
                .onFailure { error ->
                    Logger.w(TAG, "Search failed", error)
                    showLoading(false)
                    binding.status.apply {
                        text = error.message ?: getString(R.string.discover_failed)
                        visibility = View.VISIBLE
                    }
                }
        }
    }

    private fun addRepo(repo: RepoResult) {
        val entry = AppEntry(
            id = idFor(repo),
            name = repo.fullName.substringAfterLast('/'),
            packageName = "",
            category = AppCategory.OTHER,
            descriptionRes = "",
            // Same default filter Obtainium uses when the user gives no APK pattern.
            source = AppSource.GitHubRelease(repo.fullName, "\\.apk$"),
        )
        userStore.add(entry)
        adapter.notifyDataSetChanged()
        Toast.makeText(
            this,
            getString(R.string.discover_added, entry.name),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun idFor(repo: RepoResult): String =
        "gh:" + repo.fullName.lowercase()

    private fun showLoading(loading: Boolean) {
        binding.loading.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) binding.status.visibility = View.GONE
    }
}
