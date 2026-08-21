package com.legs.appsforaa

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.legs.appsforaa.databinding.ActivityAndroidAutoSetupBinding
import com.legs.appsforaa.utils.AndroidAutoLauncher
import com.legs.appsforaa.utils.ShizukuInstaller
import com.legs.appsforaa.utils.applyVerticalInsetPadding

/**
 * Walks the user through enabling *Unknown sources* in Android Auto's developer settings.
 *
 * This screen matters more than it looks. Without Shizuku, this is the **only** route to Android
 * Auto listing a sideloaded app — the platform installer cannot set the Play Store attribution AA
 * looks for (`docs/aa-visibility.md`). So the screen leads with whether the user actually needs
 * it: if Shizuku is ready, it says so rather than sending them through a fiddly manual procedure
 * for nothing.
 *
 * The steps are instructions rather than automation on purpose. Upstream v2.8.5 flips the setting
 * by shell-editing gearhead's own `shared_prefs` (backup, `sed`, restore) which needs root and
 * writes into another app's private data. Telling the user four taps is a better trade.
 */
class AndroidAutoSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAndroidAutoSetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAndroidAutoSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // On the ScrollView, not its child: a ScrollView does not pass the inset dispatch down,
        // so padding the inner LinearLayout left the title under the status bar. One combined
        // call, because a view holds only one inset listener — see applyVerticalInsetPadding.
        binding.scroll.applyVerticalInsetPadding()

        binding.step1.text = getString(R.string.android_auto_step1_description)
        binding.step2.text = getString(R.string.android_auto_step2_description)
        binding.step3.text = getString(R.string.android_auto_step3_description)
        binding.step4.text = getString(R.string.android_auto_step4_description)

        binding.openAa.setOnClickListener { openAndroidAuto() }
    }

    override fun onResume() {
        super.onResume()
        // Recomputed on every return: the user may have started Shizuku, or installed Android
        // Auto, while this screen was in the background.
        refreshStatus()
    }

    private fun refreshStatus() {
        ShizukuInstaller.refreshInstalledState(packageManager)
        binding.statusShizuku.text = when (ShizukuInstaller.availability()) {
            ShizukuInstaller.Availability.Ready ->
                getString(R.string.aa_setup_shizuku_ready)
            ShizukuInstaller.Availability.PermissionRequired ->
                getString(R.string.aa_setup_shizuku_permission)
            ShizukuInstaller.Availability.NotRunning ->
                getString(R.string.aa_setup_shizuku_not_running)
            ShizukuInstaller.Availability.NotInstalled ->
                getString(R.string.aa_setup_shizuku_absent)
        }

        val info = AndroidAutoLauncher.info(this)
        binding.statusAa.text = when {
            !info.installed -> getString(R.string.aa_setup_aa_missing)
            else -> getString(R.string.aa_setup_aa_version, info.versionName ?: "?")
        }
        binding.openAa.setText(
            if (info.installed) R.string.android_auto_open_settings
            else R.string.aa_setup_install_aa
        )
    }

    private fun openAndroidAuto() {
        val info = AndroidAutoLauncher.info(this)
        val opened = if (info.installed) AndroidAutoLauncher.open(this)
        else AndroidAutoLauncher.openInStore(this)

        if (!opened) {
            Toast.makeText(this, R.string.android_auto_not_installed, Toast.LENGTH_LONG).show()
            return
        }
        if (info.installed) {
            Toast.makeText(this, R.string.android_auto_settings_opened, Toast.LENGTH_LONG).show()
        }
    }
}
