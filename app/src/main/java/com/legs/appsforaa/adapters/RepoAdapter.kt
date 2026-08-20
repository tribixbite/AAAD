package com.legs.appsforaa.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.legs.appsforaa.R
import com.legs.appsforaa.data.RepoResult
import com.legs.appsforaa.databinding.ItemRepoBinding

/**
 * Renders GitHub search results.
 *
 * Archived repos are shown, flagged rather than filtered — for Android Auto apps the archived
 * project is often the only working build there is, so hiding it would remove the user's choice.
 */
class RepoAdapter(
    private val isAdded: (RepoResult) -> Boolean,
    private val onAdd: (RepoResult) -> Unit,
) : ListAdapter<RepoResult, RepoAdapter.ViewHolder>(DIFF) {

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<RepoResult>() {
            override fun areItemsTheSame(oldItem: RepoResult, newItem: RepoResult): Boolean =
                oldItem.fullName == newItem.fullName

            override fun areContentsTheSame(oldItem: RepoResult, newItem: RepoResult): Boolean =
                oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemRepoBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            isAdded,
            onAdd,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(
        private val binding: ItemRepoBinding,
        private val isAdded: (RepoResult) -> Boolean,
        private val onAdd: (RepoResult) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(repo: RepoResult) {
            val context = binding.root.context
            binding.repoName.text = repo.fullName
            binding.repoStars.text = context.getString(R.string.discover_stars, repo.stars)

            val description = repo.description.ifBlank {
                context.getString(R.string.discover_no_description)
            }
            binding.repoDescription.text = if (repo.archived) {
                context.getString(R.string.discover_archived, description)
            } else {
                description
            }

            val added = isAdded(repo)
            binding.repoAdd.isEnabled = !added
            binding.repoAdd.setText(if (added) R.string.discover_already_added else R.string.discover_add)
            binding.repoAdd.setOnClickListener { onAdd(repo) }
        }
    }
}
