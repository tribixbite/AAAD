package com.legs.appsforaa.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.legs.appsforaa.R
import com.legs.appsforaa.data.ConversionAction
import com.legs.appsforaa.data.ConversionState
import com.legs.appsforaa.data.InstalledApp
import com.legs.appsforaa.databinding.ItemInstalledAppBinding

/** Transient queue state layered over the installed-package scan. */
sealed interface ConversionRowState {
    data class Queued(val position: Int) : ConversionRowState
    data class Running(val percent: Int, val message: String) : ConversionRowState
    data class Cancelling(val percent: Int) : ConversionRowState
    data class Complete(val message: String) : ConversionRowState
    data class Failed(val message: String) : ConversionRowState
}

/**
 * Renders installed Android-Auto-capable apps and offers to convert the ones Android Auto is
 * currently ignoring.
 */
class InstalledAppAdapter(
    private val onConvert: (InstalledApp) -> Unit,
    private val onCancel: (InstalledApp) -> Unit,
) : ListAdapter<InstalledApp, InstalledAppAdapter.ViewHolder>(DIFF) {

    private var conversionStates: Map<String, ConversionRowState> = emptyMap()

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<InstalledApp>() {
            override fun areItemsTheSame(oldItem: InstalledApp, newItem: InstalledApp): Boolean =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: InstalledApp, newItem: InstalledApp): Boolean =
                oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemInstalledAppBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onConvert,
            onCancel,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = getItem(position)
        holder.bind(app, conversionStates[app.packageName])
    }

    /** Redraws only rows whose transient conversion state changed. */
    fun submitConversionStates(states: Map<String, ConversionRowState>) {
        val changedPackages = (conversionStates.keys + states.keys).filterTo(mutableSetOf()) {
            conversionStates[it] != states[it]
        }
        conversionStates = states.toMap()
        changedPackages.forEach { packageName ->
            currentList.indexOfFirst { it.packageName == packageName }
                .takeIf { it >= 0 }
                ?.let(::notifyItemChanged)
        }
    }

    class ViewHolder(
        private val binding: ItemInstalledAppBinding,
        private val onConvert: (InstalledApp) -> Unit,
        private val onCancel: (InstalledApp) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: InstalledApp, rowState: ConversionRowState?) {
            val context = binding.root.context
            binding.appName.text = app.label

            binding.appIcon.setImageDrawable(
                runCatching { context.packageManager.getApplicationIcon(app.packageName) }
                    .getOrNull()
            )

            val installer = app.installerPackage ?: context.getString(R.string.convert_installer_none)
            val state = when {
                app.carCapabilities?.parkedOnly == true ->
                    context.getString(R.string.convert_state_car_parked, app.versionName)
                app.hasCarVersion && app.conversionAction == ConversionAction.CAR_COPY ->
                    context.getString(R.string.convert_state_car_requires_store, app.versionName)
                app.hasCarVersion && app.state == ConversionState.TRUSTED_INSTALL ->
                    context.getString(R.string.convert_state_car_ready, app.versionName)
                app.hasCarVersion ->
                    context.getString(R.string.convert_state_car_needs_registration, app.versionName)
                app.conversionAction == ConversionAction.CAR_COPY ->
                    context.getString(R.string.convert_state_needs_car_copy, app.versionName)
                app.state == ConversionState.TRUSTED_INSTALL ->
                    context.getString(R.string.convert_state_attributed_no_aa, app.versionName)
                // "Android Auto will not list it" is true of a non-AA app but for an unrelated
                // reason, and stating it next to the caveat reads as contradictory.
                app.declaresAndroidAuto ->
                    context.getString(R.string.convert_state_needed, installer)
                else ->
                    context.getString(R.string.convert_state_installer, installer)
            }

            binding.appDetail.text = state

            binding.carVersion.visibility = if (app.hasCarVersion) View.VISIBLE else View.GONE
            binding.carVersion.setText(
                if (app.carCapabilities?.parkedOnly == true) {
                    R.string.convert_parked_car_version_included
                } else R.string.convert_car_version_included
            )

            binding.conversionStatus.visibility =
                if (rowState == null) View.GONE else View.VISIBLE
            binding.conversionStatus.text = when (rowState) {
                is ConversionRowState.Queued ->
                    context.resources.getQuantityString(
                        R.plurals.convert_queue_position,
                        rowState.position,
                        rowState.position,
                    )
                is ConversionRowState.Running -> rowState.message
                is ConversionRowState.Cancelling -> context.getString(R.string.convert_cancelling)
                is ConversionRowState.Complete -> rowState.message
                is ConversionRowState.Failed -> rowState.message
                null -> ""
            }

            val showsProgress =
                rowState is ConversionRowState.Running ||
                    rowState is ConversionRowState.Cancelling
            binding.appProgress.visibility = if (showsProgress) View.VISIBLE else View.GONE
            if (rowState is ConversionRowState.Running) {
                binding.appProgress.isIndeterminate = false
                binding.appProgress.setProgressCompat(rowState.percent.coerceIn(0, 100), true)
            } else if (rowState is ConversionRowState.Cancelling) {
                binding.appProgress.isIndeterminate = false
                binding.appProgress.setProgressCompat(rowState.percent.coerceIn(0, 100), true)
            }

            val action = app.conversionAction
            when (rowState) {
                is ConversionRowState.Queued -> {
                    binding.appAction.visibility = View.VISIBLE
                    binding.appAction.isEnabled = true
                    binding.appAction.setText(R.string.action_remove_from_queue)
                    binding.appAction.setOnClickListener { onCancel(app) }
                }
                is ConversionRowState.Running -> {
                    binding.appAction.visibility = View.VISIBLE
                    binding.appAction.isEnabled = true
                    binding.appAction.setText(R.string.action_cancel_conversion)
                    binding.appAction.setOnClickListener { onCancel(app) }
                }
                is ConversionRowState.Cancelling -> {
                    binding.appAction.visibility = View.VISIBLE
                    binding.appAction.isEnabled = false
                    binding.appAction.setText(R.string.convert_cancelling)
                    binding.appAction.setOnClickListener(null)
                }
                else -> {
                    binding.appAction.visibility = if (action != null) View.VISIBLE else View.GONE
                    binding.appAction.isEnabled = action != null
                    binding.appAction.setText(
                        if (action == ConversionAction.CAR_COPY) {
                            R.string.action_create_car_compatible_copy
                        } else {
                            R.string.action_register_car_version
                        }
                    )
                    binding.appAction.setOnClickListener { onConvert(app) }
                }
            }
            binding.root.alpha = 1f
        }
    }
}
