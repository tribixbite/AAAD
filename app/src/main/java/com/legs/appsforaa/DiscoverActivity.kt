package com.legs.appsforaa

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.KeyEvent
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
import com.legs.appsforaa.data.ReleaseResolver
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
    private val releaseResolver = ReleaseResolver()
    private var addedIds: Set<String> = emptySet()

    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityDiscoverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.applyTopInsetPadding()
        binding.repoList.applyBottomInsetPadding()

        userStore = UserCatalogStore(applicationContext)

        addedIds = userStore.load().mapTo(mutableSetOf()) { it.id }
        adapter = RepoAdapter(
            isAdded = { idFor(it) in addedIds },
            onAdd = ::addRepo,
            onOpen = ::openRepo,
        )
        binding.repoList.layoutManager = LinearLayoutManager(this)
        binding.repoList.adapter = adapter

        // Do not key off IME_ACTION_SEARCH alone. Keyboards disagree about what they send for a
        // single-line field — Gboard here delivers a plain ENTER rather than the declared action,
        // which silently did nothing. Accept any commit-ish action, plus a real ENTER key.
        binding.queryInput.setOnEditorActionListener { _, actionId, event ->
            val committed = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_UNSPECIFIED ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (committed) {
                runSearch(binding.queryInput.text?.toString().orEmpty())
                hideKeyboard()
            }
            committed
        }

        // The keyboard is not the only way in: an explicit button never depends on IME behaviour.
        binding.searchButton.setOnClickListener {
            runSearch(binding.queryInput.text?.toString().orEmpty())
            hideKeyboard()
        }

        val suggestions = listOf(
            binding.suggestMedia to R.string.discover_query_media,
            binding.suggestNavigation to R.string.discover_query_navigation,
            binding.suggestMirroring to R.string.discover_query_mirroring,
            binding.suggestDashboard to R.string.discover_query_dashboard,
        )
        suggestions.forEach { (chip, queryRes) ->
            chip.setOnClickListener {
                val suggestion = getString(queryRes)
                binding.queryInput.setText(suggestion)
                binding.queryInput.setSelection(suggestion.length)
                runSearch(suggestion)
                hideKeyboard()
            }
        }
    }

    /**
     * A pasted repo URL is resolved directly, but never added blindly: the user sees GitHub's real
     * metadata first and Add still verifies that the latest stable release contains an APK.
     */
    private fun runSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return

        search.parseRepoReference(query)?.let { repo ->
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                showLoading(true)
                runCatching { search.lookup(repo) }
                    .onSuccess { result ->
                        adapter.submitList(listOf(result))
                        showLoading(false)
                        binding.status.visibility = View.GONE
                        binding.discoverSubtitle.text = getString(R.string.discover_verified_repo)
                    }
                    .onFailure { error ->
                        Logger.w(TAG, "Repository lookup failed", error)
                        showLoading(false)
                        binding.status.apply {
                            text = error.message ?: getString(R.string.discover_failed)
                            visibility = View.VISIBLE
                        }
                    }
            }
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
        val source = AppSource.GitHubRelease(repo.fullName, "(?i)\\.apk$")
        adapter.setChecking(repo.fullName, true)
        lifecycleScope.launch {
            runCatching { releaseResolver.resolve(source) }
                .onSuccess { release ->
                    val entry = AppEntry(
                        id = idFor(repo),
                        name = repo.fullName.substringAfterLast('/'),
                        packageName = "",
                        category = AppCategory.OTHER,
                        descriptionRes = "",
                        description = repo.description,
                        source = source,
                    )
                    userStore.add(entry)
                    addedIds = addedIds + entry.id
                    adapter.setChecking(repo.fullName, false)
                    adapter.currentList.indexOf(repo)
                        .takeIf { it >= 0 }
                        ?.let(adapter::notifyItemChanged)
                    Toast.makeText(
                        this@DiscoverActivity,
                        getString(R.string.discover_added_with_asset, entry.name, release.assetName),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                .onFailure { error ->
                    adapter.setChecking(repo.fullName, false)
                    Logger.w(TAG, "Release validation failed for ${repo.fullName}", error)
                    Toast.makeText(
                        this@DiscoverActivity,
                        getString(
                            R.string.discover_no_installable_release,
                            error.message ?: getString(R.string.discover_failed),
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun openRepo(repo: RepoResult) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(repo.htmlUrl))
        startActivity(intent)
    }

    private fun idFor(repo: RepoResult): String =
        "gh:" + repo.fullName.lowercase()

    private fun hideKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.queryInput.windowToken, 0)
    }

    private fun showLoading(loading: Boolean) {
        binding.loading.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) binding.status.visibility = View.GONE
    }
}
