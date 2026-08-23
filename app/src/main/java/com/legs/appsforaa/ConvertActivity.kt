package com.legs.appsforaa

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.legs.appsforaa.adapters.InstalledAppAdapter
import com.legs.appsforaa.data.InstalledApp
import com.legs.appsforaa.data.InstalledAppScanner
import com.legs.appsforaa.data.ScanScope
import com.legs.appsforaa.databinding.ActivityConvertBinding
import com.legs.appsforaa.receivers.PackageInstallReceiver
import com.legs.appsforaa.utils.Logger
import com.legs.appsforaa.utils.ShizukuInstaller
import com.legs.appsforaa.utils.applyBottomInsetPadding
import com.legs.appsforaa.utils.applyTopInsetPadding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Converts apps that are already installed but invisible to Android Auto.
 *
 * An app sideloaded from anywhere other than the Play Store carries that installer's attribution,
 * and Android Auto will not list it however AA-capable it is. Attribution cannot be edited after
 * the fact, so the fix is to reinstall the app's own APKs through an attributed session. The
 * signature is unchanged, so it is an update over the top and **app data survives**.
 *
 * Full rationale: `docs/aa-visibility.md`.
 */
class ConvertActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "ConvertActivity"
    }

    private lateinit var binding: ActivityConvertBinding
    private lateinit var adapter: InstalledAppAdapter
    private lateinit var scanner: InstalledAppScanner

    private var conversionJob: Job? = null

    /**
     * Every installed app, scanned once. The scope toggle and the search box filter this in
     * memory rather than rescanning — enumerating several hundred packages takes long enough that
     * doing it on every keystroke would be unusable.
     */
    private var allApps: List<InstalledApp> = emptyList()
    private var showAll = false
    private var query = ""

    private val packageChangeReceiver = PackageInstallReceiver { scan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityConvertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.applyTopInsetPadding()
        binding.appList.applyBottomInsetPadding()

        scanner = InstalledAppScanner(applicationContext)
        adapter = InstalledAppAdapter(onConvert = ::confirmConversion)
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter

        binding.scopeToggle.check(R.id.scope_aa)
        binding.scopeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            showAll = checkedId == R.id.scope_all
            render()
        }
        binding.search.doAfterTextChanged {
            query = it?.toString()?.trim().orEmpty()
            render()
        }

        ShizukuInstaller.refreshInstalledState(packageManager)
        scan()
    }

    override fun onStart() {
        super.onStart()
        packageChangeReceiver.register(this)
    }

    override fun onStop() {
        super.onStop()
        packageChangeReceiver.unregister(this)
    }

    private fun scan() {
        lifecycleScope.launch {
            // Scanned at ALL scope once; the Android Auto view is a filter over the same list.
            allApps = scanner.scan(scope = ScanScope.ALL)
            binding.loadingState.visibility = View.GONE
            render()
        }
    }

    /** Applies the scope toggle and the search box to the already-scanned list. */
    private fun render() {
        val scoped = if (showAll) allApps else allApps.filter { it.declaresAndroidAuto }
        val visible = if (query.isEmpty()) {
            scoped
        } else {
            scoped.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }

        adapter.submitList(visible)
        binding.emptyState.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        // The unfiltered count is worded per scope: "N Android Auto capable apps" is simply
        // false once every installed app is on screen.
        binding.convertSubtitle.text = when {
            visible.size != allApps.size ->
                getString(R.string.convert_filtered, visible.size, allApps.size)
            showAll ->
                resources.getQuantityString(R.plurals.convert_found_all, visible.size, visible.size)
            else ->
                resources.getQuantityString(R.plurals.convert_found, visible.size, visible.size)
        }
    }

    /**
     * Conversion reinstalls a package the user did not get from this app, so it asks first and
     * says exactly what will happen — including that data is preserved, which is the thing a user
     * would reasonably be worried about.
     */
    private fun confirmConversion(app: InstalledApp) {
        if (conversionJob?.isActive == true) {
            Toast.makeText(this, R.string.convert_already_running, Toast.LENGTH_SHORT).show()
            return
        }
        // Everything the tap is about to do, said before it happens: what conversion is, that
        // the app gets stopped, and — for an app with no Android Auto support — that it will not
        // achieve the thing someone is most likely converting it for.
        val message = buildList {
            add(
                getString(
                    if (app.isSplit) R.string.convert_confirm_message_split
                    else R.string.convert_confirm_message,
                    app.label,
                )
            )
            add(getString(R.string.convert_confirm_stopped, app.label))
            if (!app.declaresAndroidAuto) add(getString(R.string.convert_no_aa_metadata))
        }.joinToString("\n\n")

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.convert_confirm_title, app.label))
            .setMessage(message)
            .setPositiveButton(R.string.action_convert) { _, _ -> convert(app) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun convert(app: InstalledApp) {
        conversionJob = lifecycleScope.launch {
            binding.convertSubtitle.text = getString(R.string.convert_running, app.label)

            if (!ShizukuInstaller.ensureReady()) {
                binding.convertSubtitle.setText(R.string.convert_subtitle)
                // Conversion has no fallback: the whole point is the attribution, and the system
                // installer cannot provide it. Saying so is more useful than a generic failure.
                AlertDialog.Builder(this@ConvertActivity)
                    .setTitle(R.string.convert_needs_shizuku_title)
                    .setMessage(R.string.convert_needs_shizuku_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            val result = ShizukuInstaller.convertInstalled(app.packageName, app.apkPaths)
            binding.convertSubtitle.setText(R.string.convert_subtitle)
            when (result) {
                is ShizukuInstaller.Result.Success -> {
                    Logger.i(TAG, "Converted ${app.packageName}")
                    Toast.makeText(
                        this@ConvertActivity,
                        getString(R.string.convert_done, app.label),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                is ShizukuInstaller.Result.Failure -> Toast.makeText(
                    this@ConvertActivity,
                    getString(R.string.convert_failed, result.message),
                    Toast.LENGTH_LONG,
                ).show()
            }
            scan()
        }
    }
}
