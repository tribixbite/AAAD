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
import com.legs.appsforaa.data.ConversionAction
import com.legs.appsforaa.data.InstalledApp
import com.legs.appsforaa.data.InstalledAppScanner
import com.legs.appsforaa.data.ScanScope
import com.legs.appsforaa.utils.Logger
import com.legs.appsforaa.utils.ShizukuInstaller
import kotlinx.coroutines.launch

/**
 * Repairs an installed app's attribution from the car.
 *
 * Scoped to apps that **declare Android Auto support and are not attributed** — the rows where
 * converting changes what the car will show. The phone screen deliberately offers every installed
 * app, because on a phone "fix this app's installer" is a reasonable thing to want for its own
 * sake; in the car it is noise, and the list limit while driving is small enough that spending it
 * on apps that will never appear here would waste the whole screen.
 */
class CarConvertScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private companion object {
        const val TAG = "CarConvert"
    }

    private var apps: List<InstalledApp>? = null
    private var busy = false

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) = refresh()

    private fun refresh() {
        lifecycleScope.launch {
            apps = InstalledAppScanner(carContext)
                .scan(scope = ScanScope.ANDROID_AUTO)
                // Repacking a whole app is deliberately a phone operation. In the car, retain
                // only the quick native-AA attribution repair that cannot change its car surface.
                .filter { it.conversionAction == ConversionAction.RESTAGE }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val loaded = apps ?: return loading()

        if (loaded.isEmpty()) {
            return MessageTemplate.Builder(carContext.getString(R.string.car_convert_none))
                .setTitle(carContext.getString(R.string.car_menu_convert))
                .setHeaderAction(Action.BACK)
                .build()
        }

        val limit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)

        val items = ItemList.Builder().apply {
            for (app in loaded.take(limit)) {
                addItem(
                    Row.Builder()
                        .setTitle(app.label)
                        .addText(
                            carContext.getString(
                                R.string.car_convert_row,
                                app.installerPackage
                                    ?: carContext.getString(R.string.convert_installer_none),
                            )
                        )
                        .setOnClickListener { confirm(app) }
                        .build()
                )
            }
        }.build()

        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.car_menu_convert))
            .setHeaderAction(Action.BACK)
            .setSingleList(items)
            .build()
    }

    private fun loading(): Template = ListTemplate.Builder()
        .setTitle(carContext.getString(R.string.car_menu_convert))
        .setHeaderAction(Action.BACK)
        .setLoading(true)
        .build()

    /**
     * Reinstalling an app is not a single-tap action, in a car least of all: it stops the app
     * while it runs. The confirmation is its own screen because templates have no dialogs.
     */
    private fun confirm(app: InstalledApp) {
        screenManager.push(
            CarConfirmScreen(
                carContext = carContext,
                title = carContext.getString(R.string.car_convert_confirm_title, app.label),
                message = carContext.getString(R.string.car_convert_confirm, app.label),
                onConfirm = { convert(app) },
            )
        )
    }

    private fun convert(app: InstalledApp) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                if (!ShizukuInstaller.ensureReady()) {
                    // Shizuku's grant prompt is a phone dialog; there is no way to answer it from
                    // the car, so saying which device to go to is the only useful message.
                    CarToast.makeText(
                        carContext,
                        carContext.getString(R.string.car_needs_shizuku),
                        CarToast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                val result = ShizukuInstaller.convertInstalled(app.packageName, app.apkPaths)
                val message = when (result) {
                    is ShizukuInstaller.Result.Success -> {
                        Logger.i(TAG, "Converted ${app.packageName} from the car")
                        carContext.getString(R.string.car_convert_done, app.label)
                    }
                    is ShizukuInstaller.Result.Failure ->
                        carContext.getString(R.string.car_failed, result.message)
                }
                CarToast.makeText(carContext, message, CarToast.LENGTH_LONG).show()
                refresh()
            } finally {
                busy = false
            }
        }
    }
}
