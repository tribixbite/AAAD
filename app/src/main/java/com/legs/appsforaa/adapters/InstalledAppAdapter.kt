package com.legs.appsforaa.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.legs.appsforaa.R
import com.legs.appsforaa.data.ConversionState
import com.legs.appsforaa.data.InstalledApp
import com.legs.appsforaa.databinding.ItemInstalledAppBinding

/**
 * Renders installed Android-Auto-capable apps and offers to convert the ones Android Auto is
 * currently ignoring.
 *
 * Apps already attributed to the Play Store are shown too, greyed out — seeing that an app is
 * *already fine* is as useful as being told which ones are not.
 */
class InstalledAppAdapter(
    private val onConvert: (InstalledApp) -> Unit,
) : ListAdapter<InstalledApp, InstalledAppAdapter.ViewHolder>(DIFF) {

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
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(
        private val binding: ItemInstalledAppBinding,
        private val onConvert: (InstalledApp) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: InstalledApp) {
            val context = binding.root.context
            binding.appName.text = app.label

            binding.appIcon.setImageDrawable(
                runCatching { context.packageManager.getApplicationIcon(app.packageName) }
                    .getOrNull()
            )

            val installer = app.installerPackage ?: context.getString(R.string.convert_installer_none)
            val state = when (app.state) {
                ConversionState.ALREADY_ATTRIBUTED ->
                    context.getString(R.string.convert_state_ok, app.versionName)
                ConversionState.CONVERTIBLE ->
                    context.getString(R.string.convert_state_needed, installer)
            }
            // Said on the row itself, because otherwise a converted app that still will not open
            // in the car looks like the conversion failed. It did not — the app declares no
            // projection support, and no install can add it. See AutomotiveDescriptor.
            binding.appDetail.text = if (app.blockedWhileDriving) {
                state + "\n" + context.getString(R.string.convert_no_projection)
            } else {
                state
            }

            val convertible = app.state == ConversionState.CONVERTIBLE
            binding.appAction.visibility = if (convertible) View.VISIBLE else View.GONE
            binding.appAction.setOnClickListener { onConvert(app) }
            binding.root.alpha = if (convertible) 1f else 0.6f
        }
    }
}
