package com.legs.appsforaa.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.legs.appsforaa.R
import com.legs.appsforaa.data.AppListItem
import com.legs.appsforaa.data.CatalogRepository
import com.legs.appsforaa.data.InstallState
import com.legs.appsforaa.utils.InstallManager
import com.legs.appsforaa.utils.Logger
import kotlinx.coroutines.launch

/**
 * Installs a catalog app from the car.
 *
 * Only apps that are **not installed** are listed: an update is not urgent enough to belong on a
 * car screen, and the list limit while driving is small.
 *
 * The install runs with `allowSystemFallback = false`. Without Shizuku the fallback is the system
 * installer, whose confirmation dialog appears on the *phone* — from the driver's seat that is a
 * dialog nobody can answer, and reporting it as progress would be a lie. It says so instead.
 */
class CarInstallScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private companion object {
        const val TAG = "CarInstall"
    }

    private var items: List<AppListItem>? = null
    private var installing: String? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) = refresh()

    private fun refresh() {
        lifecycleScope.launch {
            val repository = CatalogRepository(carContext)
            items = runCatching {
                val catalog = repository.loadCatalog()
                repository.resolveItems(catalog).filter { it.state is InstallState.NotInstalled }
            }.onFailure { Logger.w(TAG, "Could not load the catalog in the car", it) }
                .getOrDefault(emptyList())
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val loaded = items ?: return loading()

        installing?.let { name ->
            // A single-item loading list would still be tappable; a message template cannot be
            // mis-tapped into starting a second install while the first is running.
            return MessageTemplate.Builder(carContext.getString(R.string.car_installing, name))
                .setTitle(carContext.getString(R.string.car_menu_install))
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        }

        if (loaded.isEmpty()) {
            return MessageTemplate.Builder(carContext.getString(R.string.car_install_none))
                .setTitle(carContext.getString(R.string.car_menu_install))
                .setHeaderAction(Action.BACK)
                .build()
        }

        val limit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)

        val list = ItemList.Builder().apply {
            for (item in loaded.take(limit)) {
                addItem(
                    Row.Builder()
                        .setTitle(item.entry.name)
                        .addText(carContext.getString(R.string.car_install_row))
                        .setOnClickListener { confirm(item) }
                        .build()
                )
            }
        }.build()

        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.car_menu_install))
            .setHeaderAction(Action.BACK)
            .setSingleList(list)
            .build()
    }

    private fun loading(): Template = ListTemplate.Builder()
        .setTitle(carContext.getString(R.string.car_menu_install))
        .setHeaderAction(Action.BACK)
        .setLoading(true)
        .build()

    private fun confirm(item: AppListItem) {
        screenManager.push(
            CarConfirmScreen(
                carContext = carContext,
                title = carContext.getString(R.string.car_install_confirm_title, item.entry.name),
                message = carContext.getString(R.string.car_install_confirm, item.entry.name),
                onConfirm = { install(item) },
            )
        )
    }

    private fun install(item: AppListItem) {
        if (installing != null) return
        installing = item.entry.name
        invalidate()

        lifecycleScope.launch {
            try {
                val outcome = InstallManager(carContext)
                    .install(item.entry, allowSystemFallback = false) { /* progress: car shows a spinner */ }
                val message = when (outcome) {
                    is InstallManager.Outcome.InstalledUnattended ->
                        carContext.getString(R.string.car_install_done, item.entry.name)
                    is InstallManager.Outcome.InstalledCarCompatible ->
                        carContext.getString(
                            R.string.car_install_done_compatible,
                            item.entry.name,
                        )
                    is InstallManager.Outcome.NeedsShizuku ->
                        carContext.getString(R.string.car_needs_shizuku)
                    is InstallManager.Outcome.HandedToSystemInstaller ->
                        carContext.getString(R.string.car_install_needs_phone)
                    is InstallManager.Outcome.Failed ->
                        carContext.getString(R.string.car_failed, outcome.message)
                }
                CarToast.makeText(carContext, message, CarToast.LENGTH_LONG).show()
            } finally {
                installing = null
                refresh()
            }
        }
    }
}
