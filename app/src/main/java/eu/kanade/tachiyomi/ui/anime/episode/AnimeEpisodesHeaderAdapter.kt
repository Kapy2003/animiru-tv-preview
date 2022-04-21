package eu.kanade.tachiyomi.ui.anime.episode

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.AnimeEpisodesHeaderBinding
import eu.kanade.tachiyomi.ui.anime.AnimeController
import eu.kanade.tachiyomi.util.system.getResourceColor
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import reactivecircus.flowbinding.android.view.clicks

class AnimeEpisodesHeaderAdapter(
    private val controller: AnimeController,
) :
    RecyclerView.Adapter<AnimeEpisodesHeaderAdapter.HeaderViewHolder>() {

    private var numEpisodes: Int? = null
    private var hasActiveFilters: Boolean = false

    private lateinit var binding: AnimeEpisodesHeaderBinding

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        binding = AnimeEpisodesHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HeaderViewHolder(binding.root)
    }

    override fun getItemCount(): Int = 1

    override fun getItemId(position: Int): Long = hashCode().toLong()

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.bind()
    }

    fun setNumEpisodes(numEpisodes: Int) {
        this.numEpisodes = numEpisodes

        notifyItemChanged(0, this)
    }

    fun setHasActiveFilters(hasActiveFilters: Boolean) {
        this.hasActiveFilters = hasActiveFilters

        notifyItemChanged(0, this)
    }

    inner class HeaderViewHolder(private val view: View) : RecyclerView.ViewHolder(view) {
        fun bind() {
            binding.chaptersLabel.text = if (numEpisodes == null) {
                view.context.getString(R.string.episodes)
            } else {
                view.context.resources.getQuantityString(R.plurals.anime_num_episodes, numEpisodes!!, numEpisodes)
            }

            val filterColor = if (hasActiveFilters) {
                view.context.getResourceColor(R.attr.colorFilterActive)
            } else {
                view.context.getResourceColor(R.attr.colorOnBackground)
            }
            binding.btnChaptersFilter.drawable.setTint(filterColor)

            merge(view.clicks(), binding.btnChaptersFilter.clicks())
                .onEach { controller.showSettingsSheet() }
                .launchIn(controller.viewScope)
        }
    }
}
