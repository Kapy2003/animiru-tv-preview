package eu.kanade.domain.entries.anime.model

import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.cache.AnimeCoverCache
import tachiyomi.domain.entries.TriStateFilter
import tachiyomi.domain.entries.anime.model.Anime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

val Anime.downloadedFilter: TriStateFilter
    get() {
        if (forceDownloaded()) return TriStateFilter.ENABLED_IS
        return when (downloadedFilterRaw) {
            Anime.EPISODE_SHOW_DOWNLOADED -> TriStateFilter.ENABLED_IS
            Anime.EPISODE_SHOW_NOT_DOWNLOADED -> TriStateFilter.ENABLED_NOT
            else -> TriStateFilter.DISABLED
        }
    }

fun Anime.episodesFiltered(): Boolean {
    return unseenFilter != TriStateFilter.DISABLED ||
        downloadedFilter != TriStateFilter.DISABLED ||
        bookmarkedFilter != TriStateFilter.DISABLED ||
        // AM (FILLER) -->
        fillermarkedFilter != TriStateFilter.DISABLED
    // <-- AM (FILLER)
}
fun Anime.forceDownloaded(): Boolean {
    return favorite && Injekt.get<BasePreferences>().downloadedOnly().get()
}

fun Anime.toSAnime(): SAnime = SAnime.create().also {
    it.url = url
    it.title = title
    it.artist = artist
    it.author = author
    it.description = description
    it.genre = genre.orEmpty().joinToString()
    it.status = status.toInt()
    it.thumbnail_url = thumbnailUrl
    it.initialized = initialized
}

fun Anime.copyFrom(other: SAnime): Anime {
    // AM (CU) -->
    val author = other.author ?: ogAuthor
    val artist = other.artist ?: ogArtist
    val description = other.description ?: ogDescription
    val genres = if (other.genre != null) {
        other.getGenres()
    } else {
        ogGenre
    }
    // <-- AM (CU)
    val thumbnailUrl = other.thumbnail_url ?: thumbnailUrl
    return this.copy(
        // AM (CU) -->
        ogAuthor = author,
        ogArtist = artist,
        ogDescription = description,
        ogGenre = genres,
        // <-- AM (CU)
        thumbnailUrl = thumbnailUrl,
        // AM (CU) -->
        ogStatus = other.status.toLong(),
        // <-- AM (CU)
        updateStrategy = other.update_strategy,
        initialized = other.initialized && initialized,
    )
}

fun SAnime.toDomainAnime(sourceId: Long): Anime {
    return Anime.create().copy(
        url = url,
        // AM (CU) -->
        ogTitle = title,
        ogArtist = artist,
        ogAuthor = author,
        ogDescription = description,
        ogGenre = getGenres(),
        ogStatus = status.toLong(),
        // <-- AM (CU)
        thumbnailUrl = thumbnail_url,
        updateStrategy = update_strategy,
        initialized = initialized,
        source = sourceId,
    )
}

fun Anime.hasCustomCover(coverCache: AnimeCoverCache = Injekt.get()): Boolean {
    return coverCache.getCustomCoverFile(id).exists()
}
