package com.legs.appsforaa.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import com.legs.appsforaa.R

/**
 * A yes/no step for an action that changes the device.
 *
 * Templates have no dialogs, so a confirmation has to be a screen of its own. Both actions that
 * reach here — converting an app and installing one — stop or replace a package, and a single
 * mis-tap on a moving car's touchscreen is a very easy thing to do.
 */
class CarConfirmScreen(
    carContext: CarContext,
    private val title: String,
    private val message: String,
    private val onConfirm: () -> Unit,
) : Screen(carContext) {

    override fun onGetTemplate(): Template = MessageTemplate.Builder(message)
        .setTitle(title)
        .setHeaderAction(Action.BACK)
        .addAction(
            Action.Builder()
                .setTitle(carContext.getString(R.string.action_cancel))
                .setOnClickListener { screenManager.pop() }
                .build()
        )
        .addAction(
            Action.Builder()
                .setTitle(carContext.getString(R.string.car_confirm))
                .setOnClickListener {
                    // Popped first so the result toast lands on the list the user came from
                    // rather than on a confirmation screen that is now meaningless.
                    screenManager.pop()
                    onConfirm()
                }
                .build()
        )
        .build()
}
