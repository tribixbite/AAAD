package com.legs.appsforaa

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.legs.appsforaa.adapters.ConversionRowState
import com.legs.appsforaa.adapters.InstalledAppAdapter
import com.legs.appsforaa.data.ConversionAction
import com.legs.appsforaa.data.InstalledApp
import com.legs.appsforaa.data.InstalledAppScanner
import com.legs.appsforaa.data.ScanScope
import com.legs.appsforaa.databinding.ActivityConvertBinding
import com.legs.appsforaa.receivers.PackageInstallReceiver
import com.legs.appsforaa.utils.Logger
import com.legs.appsforaa.utils.CarifyRepackager
import com.legs.appsforaa.utils.ShizukuInstaller
import com.legs.appsforaa.utils.applyBottomInsetPadding
import com.legs.appsforaa.utils.applyTopInsetPadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Makes installed apps usable from Android Auto.
 *
 * Phone-only and untrusted templated apps get a separately signed, side-by-side parked copy.
 * Shizuku provides unattended installation when available; Android's standard installer is the
 * confirmation-based fallback. A local reinstall is never presented as trusted registration.
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

    private data class QueuedConversion(
        val app: InstalledApp,
        val action: ConversionAction,
    )

    private sealed interface ConversionOutcome {
        data class Success(val message: String) : ConversionOutcome
        data class Failure(val message: String) : ConversionOutcome
        data object Cancelled : ConversionOutcome
    }

    private val conversionQueue = ArrayDeque<QueuedConversion>()
    private val conversionStates = mutableMapOf<String, ConversionRowState>()
    private var queueProcessor: Job? = null
    private var activeWork: Deferred<ConversionOutcome>? = null
    private var activeConversion: QueuedConversion? = null

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
        adapter = InstalledAppAdapter(
            onConvert = ::confirmConversion,
            onCancel = ::cancelConversion,
        )
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter

        TooltipCompat.setTooltipText(binding.copyHelp, getString(R.string.convert_copy_help_title))
        binding.copyHelp.setOnClickListener {
            showCopyHelp()
        }
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
        binding.cancelCurrent.setOnClickListener {
            activeConversion?.app?.let(::cancelConversion)
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
            // System apps are safe inputs to Carify because the result is always side-by-side;
            // this is what makes built-in apps such as Samsung Calculator available under All.
            // They remain hidden from the default AA-only scope to avoid burying useful rows
            // under Google's already-working system packages.
            allApps = scanner.scan(scope = ScanScope.ALL, includeSystemApps = true)
            binding.loadingState.visibility = View.GONE
            render()
        }
    }

    /** Applies the scope toggle and the search box to the already-scanned list. */
    private fun render() {
        val scoped = if (showAll) {
            allApps
        } else {
            allApps.filter { it.declaresAndroidAuto && !it.isSystemApp }
        }
        val visible = if (query.isEmpty()) {
            scoped
        } else {
            scoped.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }

        adapter.submitList(visible) {
            adapter.submitConversionStates(conversionStates)
        }
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

    private fun showCopyHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.convert_copy_help_title)
            .setMessage(R.string.convert_copy_help_message)
            .setPositiveButton(R.string.action_got_it, null)
            .show()
    }

    /**
     * Conversion reinstalls a package the user did not get from this app, so it asks first and
     * says exactly what will happen — including that data is preserved, which is the thing a user
     * would reasonably be worried about.
     */
    private fun confirmConversion(app: InstalledApp) {
        val action = app.conversionAction ?: return
        val message = getString(
            R.string.convert_carify_confirm_message,
            app.label,
            getString(R.string.convert_system_installer_note),
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(
                getString(
                    R.string.convert_carify_confirm_title,
                    app.label,
                )
            )
            .setMessage(message)
            .setPositiveButton(
                R.string.action_create_car_compatible_copy
            ) { _, _ -> enqueueConversion(app, action) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun enqueueConversion(app: InstalledApp, action: ConversionAction) {
        val packageName = app.packageName
        if (
            activeConversion?.app?.packageName == packageName ||
            conversionQueue.any { it.app.packageName == packageName }
        ) {
            return
        }
        conversionStates.remove(packageName)
        conversionQueue.addLast(QueuedConversion(app, action))
        refreshQueuedStates()
        startQueueProcessor()
    }

    private fun startQueueProcessor() {
        if (queueProcessor?.isActive == true) return
        queueProcessor = lifecycleScope.launch {
            while (conversionQueue.isNotEmpty()) {
                val item = conversionQueue.removeFirst()
                activeConversion = item
                conversionStates[item.app.packageName] = ConversionRowState.Running(
                    percent = 0,
                    message = getString(R.string.convert_stage_preparing),
                )
                refreshQueuedStates()

                val work = async { runConversion(item) }
                activeWork = work
                val outcome = try {
                    work.await()
                } catch (cancelled: CancellationException) {
                    if (!isActive) throw cancelled
                    ConversionOutcome.Cancelled
                } finally {
                    activeWork = null
                }

                when (outcome) {
                    is ConversionOutcome.Success -> {
                        conversionStates[item.app.packageName] =
                            ConversionRowState.Complete(outcome.message)
                        Logger.i(TAG, "Conversion finished for ${item.app.packageName}")
                        Toast.makeText(
                            this@ConvertActivity,
                            outcome.message,
                            Toast.LENGTH_LONG,
                        ).show()
                        scan()
                    }
                    is ConversionOutcome.Failure -> {
                        conversionStates[item.app.packageName] =
                            ConversionRowState.Failed(outcome.message)
                        Toast.makeText(
                            this@ConvertActivity,
                            outcome.message,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    ConversionOutcome.Cancelled -> {
                        conversionStates[item.app.packageName] = ConversionRowState.Complete(
                            getString(R.string.convert_cancelled)
                        )
                    }
                }
                activeConversion = null
                refreshQueuedStates()
            }
            renderQueuePanel()
        }
    }

    private suspend fun runConversion(item: QueuedConversion): ConversionOutcome {
        val app = item.app
        val useShizuku = ShizukuInstaller.ensureReady()
        return when (item.action) {
            ConversionAction.CAR_COPY -> {
                val mode = if (useShizuku) {
                    CarifyRepackager.InstallMode.SHIZUKU
                } else {
                    CarifyRepackager.InstallMode.SYSTEM
                }
                when (
                    val result = CarifyRepackager(applicationContext).convert(
                        app,
                        installMode = mode,
                    ) { stage ->
                        postProgress(
                            app.packageName,
                            stage.percent,
                            stageMessage(stage),
                        )
                    }
                ) {
                    is CarifyRepackager.Result.Success -> {
                        val message = if (result.usedSystemInstaller) {
                            getString(R.string.convert_done_standard_installer, app.label)
                        } else {
                            getString(R.string.convert_carify_done, app.label)
                        }
                        ConversionOutcome.Success(message)
                    }
                    is CarifyRepackager.Result.Failure -> ConversionOutcome.Failure(
                        getString(R.string.convert_carify_failed, result.message)
                    )
                }
            }
        }
    }

    private fun stageMessage(stage: CarifyRepackager.Stage): String = getString(
        when (stage) {
            CarifyRepackager.Stage.PREPARING -> R.string.convert_stage_preparing
            CarifyRepackager.Stage.MERGING -> R.string.convert_stage_merging
            CarifyRepackager.Stage.READING -> R.string.convert_stage_reading
            CarifyRepackager.Stage.PATCHING -> R.string.convert_stage_patching
            CarifyRepackager.Stage.BUILDING -> R.string.convert_stage_building
            CarifyRepackager.Stage.SIGNING -> R.string.convert_stage_signing
            CarifyRepackager.Stage.STAGING -> R.string.convert_stage_staging
            CarifyRepackager.Stage.INSTALLING -> R.string.convert_stage_installing
            CarifyRepackager.Stage.WAITING_FOR_CONFIRMATION ->
                R.string.convert_stage_waiting_for_confirmation
        }
    )

    private fun postProgress(packageName: String, percent: Int, message: String) {
        binding.root.post {
            val currentState = conversionStates[packageName]
            if (
                activeConversion?.app?.packageName != packageName ||
                currentState is ConversionRowState.Cancelling
            ) {
                return@post
            }
            conversionStates[packageName] = ConversionRowState.Running(percent, message)
            adapter.submitConversionStates(conversionStates)
            renderQueuePanel()
        }
    }

    private fun cancelConversion(app: InstalledApp) {
        if (activeConversion?.app?.packageName == app.packageName) {
            val percent = when (val state = conversionStates[app.packageName]) {
                is ConversionRowState.Running -> state.percent
                is ConversionRowState.Cancelling -> state.percent
                else -> 0
            }
            conversionStates[app.packageName] = ConversionRowState.Cancelling(percent)
            adapter.submitConversionStates(conversionStates)
            renderQueuePanel()
            activeWork?.cancel()
            return
        }

        if (conversionQueue.removeAll { it.app.packageName == app.packageName }) {
            conversionStates[app.packageName] = ConversionRowState.Complete(
                getString(R.string.convert_removed_from_queue)
            )
            refreshQueuedStates()
        }
    }

    private fun refreshQueuedStates() {
        conversionStates.entries.removeAll { it.value is ConversionRowState.Queued }
        conversionQueue.forEachIndexed { index, item ->
            conversionStates[item.app.packageName] = ConversionRowState.Queued(index + 1)
        }
        adapter.submitConversionStates(conversionStates)
        renderQueuePanel()
    }

    private fun renderQueuePanel() {
        val current = activeConversion
        binding.conversionPanel.visibility = if (current == null) View.GONE else View.VISIBLE
        if (current == null) return

        binding.queueTitle.text = getString(R.string.convert_queue_title, current.app.label)
        val state = conversionStates[current.app.packageName]
        val progress = when (state) {
            is ConversionRowState.Running -> state.percent
            is ConversionRowState.Cancelling -> state.percent
            else -> 0
        }
        val stateMessage = when (state) {
            is ConversionRowState.Running -> state.message
            is ConversionRowState.Cancelling -> getString(R.string.convert_cancelling)
            else -> getString(R.string.convert_stage_preparing)
        }
        val queued = conversionQueue.size
        binding.queueDetail.text = if (queued == 0) {
            stateMessage
        } else {
            stateMessage + " · " + resources.getQuantityString(
                R.plurals.convert_queue_count,
                queued,
                queued,
            )
        }
        binding.queueProgress.isIndeterminate = false
        binding.queueProgress.setProgressCompat(progress.coerceIn(0, 100), true)
        binding.cancelCurrent.isEnabled = state !is ConversionRowState.Cancelling
    }
}
