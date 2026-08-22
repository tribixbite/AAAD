package com.legs.appsforaa.data

import android.content.Context

/**
 * Remembers whether first-run setup has been shown.
 *
 * A single flag, deliberately: onboarding is a one-off, and anything more elaborate (per-step
 * progress, versioned re-onboarding) is state that has to be migrated later for no benefit.
 *
 * Clearing this flag is what makes first run testable: `rm shared_prefs/onboarding.xml`.
 */
class OnboardingStore(context: Context) {

    private companion object {
        const val PREFS_NAME = "onboarding"
        const val KEY_COMPLETED = "completed"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var completed: Boolean
        get() = prefs.getBoolean(KEY_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_COMPLETED, value).apply()
}
