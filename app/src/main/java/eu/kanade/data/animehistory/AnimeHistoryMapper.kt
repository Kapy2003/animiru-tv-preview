package eu.kanade.data.animehistory

import eu.kanade.domain.animehistory.model.AnimeHistory
import eu.kanade.domain.animehistory.model.AnimeHistoryWithRelations
import eu.kanade.domain.manga.model.MangaCover
import java.util.Date

val animehistoryMapper: (Long, Long, Date?) -> AnimeHistory = { id, episodeId, seenAt ->
    AnimeHistory(
        id = id,
        episodeId = episodeId,
        seenAt = seenAt,
    )
}

val animehistoryWithRelationsMapper: (Long, Long, Long, String, String?, Long, Boolean, Long, Float, Date?) -> AnimeHistoryWithRelations = {
        historyId, animeId, episodeId, title, thumbnailUrl, sourceId, isFavorite, coverLastModified, episodeNumber, seenAt ->
    AnimeHistoryWithRelations(
        id = historyId,
        episodeId = episodeId,
        animeId = animeId,
        title = title,
        episodeNumber = episodeNumber,
        seenAt = seenAt,
        coverData = MangaCover(
            mangaId = animeId,
            sourceId = sourceId,
            isMangaFavorite = isFavorite,
            url = thumbnailUrl,
            lastModified = coverLastModified,
        ),
    )
}
