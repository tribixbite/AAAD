package com.legs.appsforaa.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.legs.appsforaa.R
import com.legs.appsforaa.data.RepoResult
import com.legs.appsforaa.databinding.ItemRepoBinding
import com.legs.appsforaa.utils.toDisplayText
import java.text.NumberFormat

/**
 * Renders GitHub search results.
 *
 * Archived repos are shown, flagged rather than filtered — for Android Auto apps the archived
 * project is often the only working build there is, so hiding it would remove the user's choice.
 */
class RepoAdapter(
    private val isAdded: (RepoResult) -> Boolean,
    private val onAdd: (RepoResult) -> Unit,
    private val onOpen: (RepoResult) -> Unit,
) : ListAdapter<RepoResult, RepoAdapter.ViewHolder>(DIFF) {

    private val checkingRepos = mutableSetOf<String>()

    fun setChecking(fullName: String, checking: Boolean) {
        if (checking) checkingRepos.add(fullName) else checkingRepos.remove(fullName)
        currentList.indexOfFirst { it.fullName == fullName }
            .takeIf { it >= 0 }
            ?.let(::notifyItemChanged)
    }

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
            onOpen,
            isChecking = { it.fullName in checkingRepos },
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(
        private val binding: ItemRepoBinding,
        private val isAdded: (RepoResult) -> Boolean,
        private val onAdd: (RepoResult) -> Unit,
        private val onOpen: (RepoResult) -> Unit,
        private val isChecking: (RepoResult) -> Boolean,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(repo: RepoResult) {
            val context = binding.root.context
            binding.repoName.text = repo.fullName
            binding.repoStars.text = context.getString(
                R.string.discover_stars, NumberFormat.getIntegerInstance().format(repo.stars)
            )

            binding.repoDescription.text = repo.description.ifBlank {
                context.getString(R.string.discover_no_description)
            }.toDisplayText()
            binding.repoArchived.visibility = View.VISIBLE
            binding.repoArchived.setText(
                if (repo.archived) R.string.discover_archived_chip else R.string.discover_active_chip
            )

            val meta = buildList {
                repo.language.takeIf { it.isNotBlank() }?.let(::add)
                repo.updatedAt.substringBefore('T').takeIf { it.isNotBlank() }?.let {
                    add(context.getString(R.string.discover_updated, it))
                }
            }
            binding.repoMeta.text = meta.ifEmpty {
                listOf(context.getString(R.string.discover_repo_meta_fallback))
            }.joinToString(" · ")
            binding.repoOpen.setOnClickListener { onOpen(repo) }

            val added = isAdded(repo)
            val checking = isChecking(repo)
            binding.repoAdd.isEnabled = !added && !checking
            binding.repoAdd.setText(
                when {
                    checking -> R.string.discover_checking_release
                    added -> R.string.discover_already_added
                    else -> R.string.discover_add
                }
            )
            binding.repoAdd.setOnClickListener { onAdd(repo) }
        }
    }
}
