package com.legs.appsforaa.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.legs.appsforaa.BuildConfig

/**
 * AAAD's own presence on the car screen.
 *
 * Built on the **official** Car App Library, not the SDK the catalog apps use. CarStream and
 * Fermata are *projected* apps: they declare a service under
 * `com.google.android.gms.car.category.CATEGORY_PROJECTION` and draw a full-screen Activity on the
 * head unit. That SDK is not published to any Maven repository, so it is not something this fork
 * can depend on. The templated library is, and templates are distraction-optimised by
 * construction — which is the whole reason they are allowed to run while driving.
 *
 * The practical consequence is that this screen is a **read-only status view**. Templates cannot
 * draw arbitrary UI, and that is a good fit here: downloading and installing APKs is not something
 * anyone should be doing at 70mph. What is genuinely useful in the car is the answer to "did the
 * app I installed actually register with Android Auto", which is exactly what this shows.
 *
 * **Unverified in a car.** Written against the documented contract but never run on a head unit —
 * see TASKS.md T-44 for what still has to be proven, including whether Android Auto surfaces a
 * sideloaded templated app at all.
 */
class AaadCarAppService : CarAppService() {

    /**
     * Debug builds accept any host so the app can be driven from the desktop head unit emulator.
     * Release builds accept only the hosts the library itself vouches for — an exported service
     * that enumerates installed packages should not answer to anything that binds to it.
     */
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = AaadSession()
}
