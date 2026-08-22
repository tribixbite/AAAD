package com.legs.appsforaa

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.legs.appsforaa.data.SelfUpdateChecker
import com.legs.appsforaa.databinding.ActivitySupportBinding
import com.legs.appsforaa.utils.Diagnostics
import com.legs.appsforaa.utils.InstallManager
import com.legs.appsforaa.utils.applyBottomInsetPadding
import com.legs.appsforaa.utils.applyTopInsetPadding
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Shows a diagnostics report, and lets the user copy or share it.
 *
 * Upstream's equivalent mails `help.aaad@gmail.com`. That is wrong for this fork twice over:
 * there is no support team behind a personal build, and sending upstream reports about a modified
 * app would waste their time. So this screen collects the state instead and hands it to the user,
 * who decides where it goes.
 *
 * What it reports is chosen to answer the question this project keeps asking — why is an app
 * visible in Android Auto, or not — so it leads with the Shizuku state and each installed app's
 * installer attribution. See [Diagnostics].
 *
 * It also carries the self-update check ([SelfUpdateChecker]). That sits here rather than on the
 * catalog screen because updating AAAD and updating the apps AAAD installs are different actions,
 * and a single screen offering both is how people update the wrong one.
 */
class SupportActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySupportBinding
    private lateinit var installManager: InstallManager
    private var report: String = ""

    /** The update found by the last check, if any. Cleared once it has been acted on. */
    private var pendingUpdate: SelfUpdateChecker.Result.Available? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySupportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.applyTopInsetPadding()
        binding.actions.applyBottomInsetPadding()

        installManager = InstallManager(applicationContext)

        binding.copyReport.setOnClickListener { copyReport() }
        binding.shareReport.setOnClickListener { shareReport() }
        binding.checkUpdate.setOnClickListener { checkForUpdate() }
        binding.installUpdate.setOnClickListener { installUpdate() }

        binding.updateStatus.text = getString(R.string.update_current, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        // Recollected each time: Shizuku and the installed set both change under this screen.
        lifecycleScope.launch {
            report = Diagnostics.collect(this@SupportActivity)
            binding.report.text = report
        }
    }

    /**
     * Runs only on tap. The result is reported in full, including the two cases that are not
     * failures — no repo configured, and no stable release published — because "check failed" for
     * a fork that simply has not tagged a release yet is misleading.
     */
    private fun checkForUpdate() {
        pendingUpdate = null
        binding.installUpdate.visibility = View.GONE
        binding.checkUpdate.isEnabled = false
        binding.updateStatus.setText(R.string.update_checking)

        lifecycleScope.launch {
            when (val result = SelfUpdateChecker().check()) {
                is SelfUpdateChecker.Result.Disabled ->
                    binding.updateStatus.setText(R.string.update_disabled)

                is SelfUpdateChecker.Result.NoRelease ->
                    binding.updateStatus.setText(R.string.update_none)

                is SelfUpdateChecker.Result.UpToDate ->
                    binding.updateStatus.text = getString(R.string.update_current,
                        result.version) + "\n" + getString(R.string.update_up_to_date)

                is SelfUpdateChecker.Result.Available -> {
                    pendingUpdate = result
                    binding.updateStatus.text = getString(
                        if (result.sidesteps) R.string.update_available_alongside
                        else R.string.update_available,
                        result.version,
                    )
                    binding.installUpdate.visibility = View.VISIBLE
                }

                is SelfUpdateChecker.Result.Failed ->
                    binding.updateStatus.text = result.message
            }
            binding.checkUpdate.isEnabled = true
        }
    }

    /** Reuses the catalog install path rather than growing a second download-and-install flow. */
    private fun installUpdate() {
        val update = pendingUpdate ?: return
        binding.installUpdate.isEnabled = false
        binding.checkUpdate.isEnabled = false

        lifecycleScope.launch {
            val outcome = installManager.install(update.entry) { progress ->
                binding.updateStatus.text = when (progress) {
                    is InstallManager.Progress.Resolving -> getString(R.string.update_checking)
                    is InstallManager.Progress.Downloading -> getString(
                        R.string.update_downloading, (progress.fraction * 100).roundToInt())
                    is InstallManager.Progress.Installing -> getString(R.string.update_installing)
                }
            }
            binding.updateStatus.text = when (outcome) {
                is InstallManager.Outcome.InstalledAttributed ->
                    getString(R.string.update_installed, update.version)
                is InstallManager.Outcome.HandedToSystemInstaller ->
                    getString(R.string.update_handed_off)
                is InstallManager.Outcome.NeedsShizuku -> getString(R.string.install_needs_shizuku)
                is InstallManager.Outcome.Failed -> outcome.message
            }
            // On success this process is about to be replaced; on failure the user may want to
            // retry, so both buttons come back either way.
            binding.installUpdate.isEnabled = true
            binding.checkUpdate.isEnabled = true
        }
    }

    private fun copyReport() {
        if (report.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostics_title), report))
        // Android 13+ shows its own copy confirmation; a toast on top of it is noise.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareReport() {
        if (report.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_title))
            .putExtra(Intent.EXTRA_TEXT, report)
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.diagnostics_share))) }
            .onFailure { Toast.makeText(this, R.string.diagnostics_no_target, Toast.LENGTH_LONG).show() }
    }
}
