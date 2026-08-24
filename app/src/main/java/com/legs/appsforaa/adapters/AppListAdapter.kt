package com.legs.appsforaa.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.legs.appsforaa.R
import com.legs.appsforaa.data.AppListItem
import com.legs.appsforaa.data.InstallState
import com.legs.appsforaa.databinding.ItemAppCardBinding
import com.legs.appsforaa.utils.toDisplayText

/**
 * Renders the catalog.
 *
 * [onAction] fires when a card's action button is tapped. Download and install are not wired yet
 * (TASKS.md T-06); the host activity decides what to do, so this adapter stays free of any
 * install policy.
 */
class AppListAdapter(
    private val onAction: (AppListItem) -> Unit,
) : ListAdapter<AppListItem, AppListAdapter.AppViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppListItem>() {
            override fun areItemsTheSame(oldItem: AppListItem, newItem: AppListItem): Boolean =
                oldItem.entry.id == newItem.entry.id

            override fun areContentsTheSame(oldItem: AppListItem, newItem: AppListItem): Boolean =
                oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return AppViewHolder(binding, onAction)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class AppViewHolder(
        private val binding: ItemAppCardBinding,
        private val onAction: (AppListItem) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppListItem) {
            val context = binding.root.context
            binding.appName.text = item.entry.name

            binding.appDescription.apply {
                if (item.descriptionText.isNotBlank()) {
                    text = item.descriptionText.toDisplayText()
                    visibility = android.view.View.VISIBLE
                } else {
                    visibility = android.view.View.GONE
                }
            }

            binding.appStatus.text = statusLabel(context, item)
            binding.appAction.setText(actionLabel(item))
            binding.appAction.setOnClickListener { onAction(item) }
        }

        private fun statusLabel(
            context: android.content.Context,
            item: AppListItem,
        ): String = when (val state = item.state) {
            is InstallState.NotInstalled -> context.getString(R.string.status_not_installed)
            is InstallState.Installed ->
                if (state.versionName.isBlank()) context.getString(R.string.status_installed)
                else context.getString(R.string.installed_version_format, state.versionName)
            is InstallState.UpdateAvailable ->
                context.getString(R.string.status_update_to_version, state.availableVersion)
        }

        private fun actionLabel(item: AppListItem): Int = when (item.state) {
            is InstallState.NotInstalled -> R.string.action_install
            is InstallState.Installed -> R.string.action_open
            is InstallState.UpdateAvailable -> R.string.action_update
        }
    }
}
