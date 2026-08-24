package com.legs.appsforaa

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.legs.appsforaa.data.OnboardingStore
import com.legs.appsforaa.utils.LogFile

/**
 * The `MAIN`/`LAUNCHER` entry point.
 *
 * It exists as a routing seam, not as a screen: it decides where a cold start lands and gets out
 * of the way — first run goes to [OnboardingActivity], everything after to [MainActivityNew],
 * so neither of those has to care which case it is in.
 *
 * No layout is set: drawing one would flash an empty frame before the real screen.
 */
class LauncherActivity : AppCompatActivity() {

    private companion object {
        var dynamicColorsRegistered = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register once per process so every destination follows the system's Material color
        // scheme on Android 12+, while the DayNight theme remains the fallback on older devices.
        if (!dynamicColorsRegistered) {
            DynamicColors.applyToActivitiesIfAvailable(application)
            dynamicColorsRegistered = true
        }

        // Earliest point every cold start passes through, so the file sink is live before the
        // first catalog load logs anything worth reading.
        LogFile.install(applicationContext)

        val firstRun = !OnboardingStore(applicationContext).completed
        val destination = if (firstRun) OnboardingActivity::class.java
        else MainActivityNew::class.java

        startActivity(
            Intent(this, destination).apply {
                // Carry a deep link (the Screen2Auto handoff) through to the catalog if present.
                data = intent?.data
                if (intent?.data != null) action = Intent.ACTION_VIEW
            }
        )
        finish()
    }
}
