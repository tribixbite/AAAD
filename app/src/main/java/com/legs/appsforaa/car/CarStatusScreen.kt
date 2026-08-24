package com.legs.appsforaa.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.legs.appsforaa.R
import com.legs.appsforaa.data.ConversionState
import com.legs.appsforaa.data.InstalledApp
import com.legs.appsforaa.data.InstalledAppScanner
import kotlinx.coroutines.launch

/**
 * Shows, on the car screen, which installed Android Auto apps actually registered with Android
 * Auto — the one question this project exists to answer, asked from the place where the answer
 * matters.
 *
 * Everything here is read-only by design. Installing an APK while driving is not a feature.
 */
class CarStatusScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private var apps: List<InstalledApp>? = null

    init {
        lifecycle.addObserver(this)
    }

    /**
     * The scan touches PackageManager for every installed app, so it runs off the main thread and
     * the template is invalidated when it lands. `onGetTemplate` is synchronous and may be called
     * before then, which is what the loading state is for.
     */
    override fun onCreate(owner: LifecycleOwner) {
        lifecycleScope.launch {
            apps = InstalledAppScanner(carContext).scan(includeSystemApps = false)
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val loaded = apps
            ?: return ListTemplate.Builder()
                .setTitle(carContext.getString(R.string.car_title))
                .setHeaderAction(Action.APP_ICON)
                .setLoading(true)
                .build()

        if (loaded.isEmpty()) {
            return ListTemplate.Builder()
                .setTitle(carContext.getString(R.string.car_title))
                .setHeaderAction(Action.APP_ICON)
                .setSingleList(
                    ItemList.Builder()
                        .setNoItemsMessage(carContext.getString(R.string.car_no_apps))
                        .build()
                )
                .build()
        }

        // The host caps how many rows may be shown while driving, and exceeding it throws rather
        // than truncating. Asking is the only correct way to find the limit: it varies by head
        // unit and by whether the car is moving.
        val limit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)

        val list = ItemList.Builder().apply {
            for (app in loaded.take(limit)) {
                addItem(
                    Row.Builder()
                        .setTitle(app.label)
                        .addText(describe(app))
                        .build()
                )
            }
        }.build()

        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.car_title))
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list)
            .build()
    }

    /**
     * One line per app covering both failure modes, which are independent: an app can be listed by
     * Android Auto and still refuse to open, or open fine but never be listed.
     */
    private fun describe(app: InstalledApp): CharSequence = when {
        app.carCapabilities?.parkedOnly == true ->
            carContext.getString(R.string.car_parked, app.versionName)
        app.hasCarVersion && app.state == ConversionState.CONVERTIBLE ->
            carContext.getString(R.string.car_custom_source, app.versionName)
        app.blockedWhileDriving ->
            carContext.getString(R.string.car_no_projection)
        else ->
            carContext.getString(R.string.car_ok, app.versionName)
    }
}
