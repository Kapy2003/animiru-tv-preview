package eu.kanade.domain.animesource.interactor

import eu.kanade.domain.animesource.model.AnimeSource
import eu.kanade.domain.animesource.repository.AnimeSourceRepository
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.text.Collator
import java.util.Collections
import java.util.Locale

class GetAnimeSourcesWithFavoriteCount(
    private val repository: AnimeSourceRepository,
    private val preferences: PreferencesHelper,
) {

    fun subscribe(): Flow<List<Pair<AnimeSource, Long>>> {
        return combine(
            preferences.migrationSortingDirection().asFlow(),
            preferences.migrationSortingMode().asFlow(),
            repository.getSourcesWithFavoriteCount(),
        ) { direction, mode, list ->
            list.sortedWith(sortFn(direction, mode))
        }
    }

    private fun sortFn(
        direction: SetMigrateSorting.Direction,
        sorting: SetMigrateSorting.Mode,
    ): java.util.Comparator<Pair<AnimeSource, Long>> {
        val locale = Locale.getDefault()
        val collator = Collator.getInstance(locale).apply {
            strength = Collator.PRIMARY
        }
        val sortFn: (Pair<AnimeSource, Long>, Pair<AnimeSource, Long>) -> Int = { a, b ->
            val id1 = a.first.name.toLongOrNull()
            val id2 = b.first.name.toLongOrNull()
            when (sorting) {
                SetMigrateSorting.Mode.ALPHABETICAL -> {
                    when {
                        id1 != null && id2 == null -> -1
                        id2 != null && id1 == null -> 1
                        else -> collator.compare(a.first.name.lowercase(locale), b.first.name.lowercase(locale))
                    }
                }
                SetMigrateSorting.Mode.TOTAL -> {
                    when {
                        id1 != null && id2 == null -> -1
                        id2 != null && id1 == null -> 1
                        else -> a.second.compareTo(b.second)
                    }
                }
            }
        }

        return when (direction) {
            SetMigrateSorting.Direction.ASCENDING -> Comparator(sortFn)
            SetMigrateSorting.Direction.DESCENDING -> Collections.reverseOrder(sortFn)
        }
    }
}
