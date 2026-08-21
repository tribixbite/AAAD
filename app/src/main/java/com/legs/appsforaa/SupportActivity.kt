package com.legs.appsforaa

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.legs.appsforaa.databinding.ActivitySupportBinding
import com.legs.appsforaa.utils.Diagnostics
import com.legs.appsforaa.utils.applyBottomInsetPadding
import com.legs.appsforaa.utils.applyTopInsetPadding
import kotlinx.coroutines.launch

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
 */
class SupportActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySupportBinding
    private var report: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySupportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.applyTopInsetPadding()
        binding.actions.applyBottomInsetPadding()

        binding.copyReport.setOnClickListener { copyReport() }
        binding.shareReport.setOnClickListener { shareReport() }
    }

    override fun onResume() {
        super.onResume()
        // Recollected each time: Shizuku and the installed set both change under this screen.
        lifecycleScope.launch {
            report = Diagnostics.collect(this@SupportActivity)
            binding.report.text = report
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
