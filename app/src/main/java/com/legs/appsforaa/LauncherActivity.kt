package com.legs.appsforaa

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * The `MAIN`/`LAUNCHER` entry point.
 *
 * It exists as a routing seam, not as a screen: it decides where a cold start lands and gets out
 * of the way. Today that is always the catalog. When onboarding is implemented (TASKS.md T-08)
 * the first-run check belongs here — permissions, Play Protect, and Shizuku setup — so that
 * [MainActivityNew] never has to care whether it is a first run.
 *
 * No layout is set: drawing one would flash an empty frame before the real screen.
 */
class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startActivity(
            Intent(this, MainActivityNew::class.java).apply {
                // Carry a deep link (the Screen2Auto handoff) through to the catalog if present.
                data = intent?.data
                if (intent?.data != null) action = Intent.ACTION_VIEW
            }
        )
        finish()
    }
}
