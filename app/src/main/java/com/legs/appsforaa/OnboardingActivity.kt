package com.legs.appsforaa

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.legs.appsforaa.data.OnboardingStore
import com.legs.appsforaa.databinding.ActivityOnboardingBinding
import com.legs.appsforaa.utils.Logger
import com.legs.appsforaa.utils.ShizukuInstaller
import com.legs.appsforaa.utils.applyVerticalInsetPadding

/**
 * First-run setup.
 *
 * One scrolling screen of live status rather than the multi-page pager upstream used. A pager
 * makes the user page through advice that may not apply to their device; this shows each item's
 * actual state and only asks for what is genuinely missing.
 *
 * It closes a real gap: nothing in the app ever asked for permission to install packages, so a
 * user without Shizuku hit the system installer and got a refusal with no explanation.
 *
 * Not currently reachable again once dismissed — there is no entry point back into it, and the
 * catalog screen's button row is already full. That is a deliberate omission rather than an
 * oversight: every item here is also visible from Diagnostics, and the two that can be acted on
 * (install permission, Shizuku) are surfaced at the point they actually block something. If it
 * ever needs re-entry, TASKS.md T-08 records the string for it.
 */
class OnboardingActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "Onboarding"
    }

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var store: OnboardingStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scroll.applyVerticalInsetPadding()
        binding.footer.applyVerticalInsetPadding()

        store = OnboardingStore(applicationContext)

        binding.installAction.setOnClickListener { openInstallPermissionSettings() }
        binding.shizukuAction.setOnClickListener { openShizuku() }
        binding.finish.setOnClickListener {
            store.completed = true
            startActivity(Intent(this, MainActivityNew::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // Every item is re-checked on return, because the user has just been sent to Settings or
        // to Shizuku to change exactly these things.
        refreshInstallPermission()
        refreshShizuku()
    }

    /**
     * `canRequestPackageInstalls` is a per-app Settings toggle, not a runtime permission — there
     * is no dialog to request, only a screen to send the user to.
     */
    private fun refreshInstallPermission() {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true // Pre-O has no per-app gate; the manifest permission is enough.
        }
        binding.installStatus.setText(
            if (granted) R.string.onboarding_install_granted
            else R.string.onboarding_unknown_sources_description
        )
        binding.installAction.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun refreshShizuku() {
        ShizukuInstaller.refreshInstalledState(packageManager)
        val availability = ShizukuInstaller.availability()
        binding.shizukuStatus.setText(
            when (availability) {
                ShizukuInstaller.Availability.Ready -> R.string.onboarding_shizuku_ready
                ShizukuInstaller.Availability.PermissionRequired -> R.string.onboarding_shizuku_no_permission
                ShizukuInstaller.Availability.NotRunning -> R.string.onboarding_shizuku_not_running
                ShizukuInstaller.Availability.NotInstalled -> R.string.onboarding_shizuku_not_installed
            }
        )
        binding.shizukuAction.apply {
            visibility = if (availability == ShizukuInstaller.Availability.Ready) View.GONE
            else View.VISIBLE
            setText(
                if (availability == ShizukuInstaller.Availability.NotInstalled)
                    R.string.onboarding_shizuku_install
                else R.string.onboarding_shizuku_start
            )
        }
    }

    private fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure { error ->
            Logger.w(TAG, "No unknown-app-sources screen on this device", error)
            // Some OEM builds do not expose the per-app screen; the app list is the next best door.
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)) }
                .onFailure { Toast.makeText(this, R.string.error_cannot_open_app, Toast.LENGTH_LONG).show() }
        }
    }

    /** Opens Shizuku if present, otherwise its listing so the user can get it. */
    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launch != null) {
            startActivity(launch)
            return
        }
        val store = Intent(Intent.ACTION_VIEW)
            .setData(Uri.parse("https://shizuku.rikka.app/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(store) }
            .onFailure { Toast.makeText(this, R.string.error_cannot_open_app, Toast.LENGTH_LONG).show() }
    }
}
