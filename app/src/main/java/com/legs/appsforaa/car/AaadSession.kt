package com.legs.appsforaa.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/** One connection to the car host, opening the menu that the other screens hang off. */
class AaadSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = CarRootScreen(carContext)
}
