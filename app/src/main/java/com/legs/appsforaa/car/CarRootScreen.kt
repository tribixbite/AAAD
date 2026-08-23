package com.legs.appsforaa.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.legs.appsforaa.R

/**
 * What AAAD offers on the car screen.
 *
 * Three entries rather than one status list, because the two useful actions — repairing an app
 * Android Auto refuses to list, and installing one that is missing — are exactly the things you
 * discover you need *while sitting in the car*, which is the worst place to be told to go and
 * find your phone.
 *
 * Everything below is still gated on Shizuku, and every action confirms on its own screen first.
 * Templates cap how much can be shown while moving, and the host enforces that; this code asks for
 * the limit rather than assuming one.
 */
class CarRootScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_menu_status))
                    .addText(carContext.getString(R.string.car_menu_status_detail))
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarStatusScreen(carContext)) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_menu_convert))
                    .addText(carContext.getString(R.string.car_menu_convert_detail))
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarConvertScreen(carContext)) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_menu_install))
                    .addText(carContext.getString(R.string.car_menu_install_detail))
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarInstallScreen(carContext)) }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.car_title))
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items)
            .build()
    }
}
