package eu.kanade.tachiyomi.ui.setting.database

import android.view.View
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.LocalAnimeSource
import eu.kanade.tachiyomi.animesource.icon
import eu.kanade.tachiyomi.databinding.ClearDatabaseSourceItemBinding

class ClearDatabaseItemHolder(view: View, adapter: FlexibleAdapter<*>) : FlexibleViewHolder(view, adapter) {
    private val binding = ClearDatabaseSourceItemBinding.bind(view)

    fun bind(source: AnimeSource, count: Long) {
        binding.title.text = source.toString()
        binding.description.text = itemView.context.resources
            .getQuantityString(R.plurals.clear_database_source_item_count, count.toInt(), count.toInt())

        itemView.post {
            when {
                source.icon() != null && source.id != LocalAnimeSource.ID ->
                    binding.thumbnail.setImageDrawable(source.icon())
                else -> binding.thumbnail.setImageResource(R.mipmap.ic_local_source)
            }
        }
        binding.checkbox.isChecked = (bindingAdapter as FlexibleAdapter<*>).isSelected(bindingAdapterPosition)
    }
}
