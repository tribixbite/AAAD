package com.legs.appsforaa.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/** One connection to the car host. The screen it opens is the only one this app has. */
class AaadSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = CarStatusScreen(carContext)
}
