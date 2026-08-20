package com.legs.appsforaa

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.legs.appsforaa.adapters.AppListAdapter
import com.legs.appsforaa.data.AppListItem
import com.legs.appsforaa.data.Catalog
import com.legs.appsforaa.data.CatalogRepository
import com.legs.appsforaa.data.InstallState
import com.legs.appsforaa.data.UserCatalogStore
import com.legs.appsforaa.databinding.ActivityMainNewBinding
import com.legs.appsforaa.receivers.PackageInstallReceiver
import com.legs.appsforaa.utils.InstallManager
import com.legs.appsforaa.utils.Logger
import com.legs.appsforaa.utils.ShizukuInstaller
import com.legs.appsforaa.utils.applyBottomInsetPadding
import com.legs.appsforaa.utils.applyTopInsetPadding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The catalog screen and the app's real entry point.
 *
 * Loads the catalog, resolves each entry against installed packages, and re-resolves whenever
 * [PackageInstallReceiver] reports a package change so the list never goes stale behind the user.
 *
 * Tapping a card's action launches an installed app, or downloads and installs one that is not.
 * The install goes through [InstallManager], which prefers the Play-attributed Shizuku path and
 * says plainly when it had to fall back to one Android Auto will ignore.
 *
 * Two side doors: [ConvertActivity] fixes apps already installed without attribution, and
 * [DiscoverActivity] adds new ones from GitHub.
 */
class MainActivityNew : AppCompatActivity() {

    private companion object {
        const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainNewBinding
    private lateinit var repository: CatalogRepository
    private lateinit var adapter: AppListAdapter
    private lateinit var installManager: InstallManager
    private lateinit var userStore: UserCatalogStore

    /** The single in-flight install, if any. */
    private var installJob: Job? = null

    /** Set when the catalog has loaded, so package-change refreshes can skip the network. */
    private var loadedCatalog: Catalog? = null

    private val packageChangeReceiver = PackageInstallReceiver { packageName ->
        // A discovered entry has no package name until its first install reveals one. The
        // broadcast is the only place that fact is available, so capture it here.
        pendingPackageLearnId?.let { entryId ->
            if (packageName != null) {
                userStore.learnPackageName(entryId, packageName)
                pendingPackageLearnId = null
                loadCatalog(userInitiated = true)
                return@PackageInstallReceiver
            }
        }
        refreshInstalledState()
    }

    /** Entry id awaiting a package name from the next PACKAGE_ADDED broadcast. */
    private var pendingPackageLearnId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainNewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.hero.applyTopInsetPadding()
        binding.appList.applyBottomInsetPadding()

        repository = CatalogRepository(applicationContext)
        installManager = InstallManager(applicationContext)
        userStore = UserCatalogStore(applicationContext)
        ShizukuInstaller.refreshInstalledState(packageManager)

        adapter = AppListAdapter(onAction = ::onAppAction)
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadCatalog(userInitiated = true) }
        binding.errorRetry.setOnClickListener { loadCatalog(userInitiated = true) }
        binding.openConvert.setOnClickListener {
            startActivity(Intent(this, ConvertActivity::class.java))
        }
        binding.openDiscover.setOnClickListener {
            startActivity(Intent(this, DiscoverActivity::class.java))
        }

        loadCatalog(userInitiated = false)
    }

    override fun onStart() {
        super.onStart()
        packageChangeReceiver.register(this)
    }

    override fun onStop() {
        super.onStop()
        packageChangeReceiver.unregister(this)
    }

    override fun onResume() {
        super.onResume()
        // Covers installs that completed while this activity was backgrounded, which the
        // broadcast receiver misses because it is only registered between onStart and onStop.
        if (loadedCatalog != null) refreshInstalledState()
    }

    private fun loadCatalog(userInitiated: Boolean) {
        if (!userInitiated) showState(loading = true)
        lifecycleScope.launch {
            runCatching { repository.loadCatalog() }
                .onSuccess { catalog ->
                    loadedCatalog = catalog
                    adapter.submitList(repository.resolveItems(catalog))
                    showCatalogOrigin(catalog)
                    showState(loading = false)
                    Logger.i(TAG, "Catalog loaded: ${catalog.apps.size} apps from ${catalog.origin}")
                }
                .onFailure { error ->
                    Logger.e(TAG, "Catalog load failed", error)
                    showError(error.message ?: getString(R.string.error_loading_catalog))
                }
            binding.swipeRefresh.isRefreshing = false
        }
    }

    /** Re-resolves install state without re-fetching the catalog. */
    private fun refreshInstalledState() {
        val catalog = loadedCatalog ?: return
        adapter.submitList(repository.resolveItems(catalog))
    }

    private fun showCatalogOrigin(catalog: Catalog) {
        binding.catalogOrigin.apply {
            text = when (catalog.origin) {
                Catalog.Origin.BUNDLED -> getString(R.string.catalog_origin_bundled)
                Catalog.Origin.REMOTE -> getString(R.string.catalog_origin_remote, catalog.updated)
            }
            visibility = View.VISIBLE
        }
    }

    private fun showState(loading: Boolean) {
        binding.loadingState.visibility = if (loading) View.VISIBLE else View.GONE
        binding.errorState.visibility = View.GONE
        binding.swipeRefresh.visibility = if (loading) View.GONE else View.VISIBLE
    }

    private fun showError(message: String) {
        binding.loadingState.visibility = View.GONE
        binding.swipeRefresh.visibility = View.GONE
        binding.errorState.visibility = View.VISIBLE
        binding.errorMessage.text = message
    }

    /** An installed app is launched; anything else is downloaded and installed. */
    private fun onAppAction(item: AppListItem) {
        when (item.state) {
            is InstallState.Installed, is InstallState.UpdateAvailable -> launchApp(item)
            is InstallState.NotInstalled -> startInstall(item)
        }
    }

    /**
     * Resolves, downloads and installs [item], reporting progress in the hero subtitle.
     *
     * Only one install runs at a time: they contend for the same package-installer session slot,
     * and a queue of them would make the progress line meaningless.
     */
    private fun startInstall(item: AppListItem) {
        if (installJob?.isActive == true) {
            Toast.makeText(this, R.string.install_already_running, Toast.LENGTH_SHORT).show()
            return
        }

        // Shizuku can be started or stopped at any moment, so re-check per attempt. The
        // permission prompt itself is awaited inside InstallManager.
        ShizukuInstaller.refreshInstalledState(packageManager)

        // Remember to capture the package name if this entry does not know its own yet.
        pendingPackageLearnId = item.entry.id.takeIf { item.entry.packageName.isBlank() }

        installJob = lifecycleScope.launch {
            val outcome = installManager.install(item.entry) { progress ->
                binding.heroSubtitle.text = when (progress) {
                    is InstallManager.Progress.Resolving ->
                        getString(R.string.progress_resolving, item.entry.name)
                    is InstallManager.Progress.Downloading ->
                        if (progress.fraction < 0f) getString(R.string.progress_downloading, item.entry.name)
                        else getString(
                            R.string.progress_downloading_percent,
                            item.entry.name,
                            (progress.fraction * 100).toInt(),
                        )
                    is InstallManager.Progress.Installing ->
                        getString(R.string.progress_installing, item.entry.name)
                }
            }
            binding.heroSubtitle.setText(R.string.hero_subtitle)
            reportOutcome(item, outcome)
            refreshInstalledState()
        }
    }

    private fun reportOutcome(item: AppListItem, outcome: InstallManager.Outcome) {
        val message = when (outcome) {
            is InstallManager.Outcome.InstalledAttributed ->
                getString(R.string.install_done_attributed, item.entry.name)
            is InstallManager.Outcome.HandedToSystemInstaller ->
                getString(R.string.install_handed_to_system)
            is InstallManager.Outcome.Failed ->
                getString(R.string.install_failed_reason, outcome.message)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun launchApp(item: AppListItem) {
        val intent = packageManager.getLaunchIntentForPackage(item.entry.packageName)
        if (intent == null) {
            Toast.makeText(this, R.string.error_cannot_open_app, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(intent)
    }
}
