package com.legs.appsforaa

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
import com.legs.appsforaa.databinding.ActivityMainNewBinding
import com.legs.appsforaa.receivers.PackageInstallReceiver
import com.legs.appsforaa.utils.Logger
import com.legs.appsforaa.utils.applyBottomInsetPadding
import com.legs.appsforaa.utils.applyTopInsetPadding
import kotlinx.coroutines.launch

/**
 * The catalog screen and the app's real entry point.
 *
 * Loads the catalog, resolves each entry against installed packages, and re-resolves whenever
 * [PackageInstallReceiver] reports a package change so the list never goes stale behind the user.
 *
 * Downloading and installing are not implemented here yet — see TASKS.md T-06. Tapping a card's
 * action either launches an installed app or reports that installation is not wired up; it never
 * pretends to have done something.
 */
class MainActivityNew : AppCompatActivity() {

    private companion object {
        const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainNewBinding
    private lateinit var repository: CatalogRepository
    private lateinit var adapter: AppListAdapter

    /** Set when the catalog has loaded, so package-change refreshes can skip the network. */
    private var loadedCatalog: Catalog? = null

    private val packageChangeReceiver = PackageInstallReceiver { refreshInstalledState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainNewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.hero.applyTopInsetPadding()
        binding.appList.applyBottomInsetPadding()

        repository = CatalogRepository(applicationContext)

        adapter = AppListAdapter(onAction = ::onAppAction)
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadCatalog(userInitiated = true) }
        binding.errorRetry.setOnClickListener { loadCatalog(userInitiated = true) }

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

    /**
     * An installed app is launched; anything else reports that install is not implemented.
     *
     * TODO(T-06): resolve the entry's source, download the APK, and install it through a
     * Play-attributed session so Android Auto lists it. See docs/aa-visibility.md.
     */
    private fun onAppAction(item: AppListItem) {
        when (item.state) {
            is InstallState.Installed, is InstallState.UpdateAvailable -> launchApp(item)
            is InstallState.NotInstalled -> Toast.makeText(
                this, R.string.install_not_implemented, Toast.LENGTH_LONG
            ).show()
        }
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
