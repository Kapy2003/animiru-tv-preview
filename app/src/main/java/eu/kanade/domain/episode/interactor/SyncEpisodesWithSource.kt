package eu.kanade.domain.episode.interactor

import eu.kanade.data.episode.NoEpisodesException
import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.anime.model.Anime
import eu.kanade.domain.episode.model.Episode
import eu.kanade.domain.episode.model.toEpisodeUpdate
import eu.kanade.domain.episode.repository.EpisodeRepository
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.LocalAnimeSource
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.download.AnimeDownloadManager
import eu.kanade.tachiyomi.util.episode.EpisodeRecognition
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Long.max
import java.util.Date
import java.util.TreeSet

class SyncEpisodesWithSource(
    private val downloadManager: AnimeDownloadManager = Injekt.get(),
    private val episodeRepository: EpisodeRepository = Injekt.get(),
    private val shouldUpdateDbEpisode: ShouldUpdateDbEpisode = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    private val updateEpisode: UpdateEpisode = Injekt.get(),
    private val getEpisodeByAnimeId: GetEpisodeByAnimeId = Injekt.get(),
) {

    suspend fun await(
        rawSourceEpisodes: List<SEpisode>,
        anime: Anime,
        source: AnimeSource,
    ): Pair<List<Episode>, List<Episode>> {
        if (rawSourceEpisodes.isEmpty() && source.id != LocalAnimeSource.ID) {
            throw NoEpisodesException()
        }

        val sourceEpisodes = rawSourceEpisodes
            .distinctBy { it.url }
            .mapIndexed { i, sEpisode ->
                Episode.create()
                    .copyFromSEpisode(sEpisode)
                    .copy(animeId = anime.id, sourceOrder = i.toLong())
            }

        // Episodes from db.
        val dbEpisodes = getEpisodeByAnimeId.await(anime.id)

        // Episodes from the source not in db.
        val toAdd = mutableListOf<Episode>()

        // Episodes whose metadata have changed.
        val toChange = mutableListOf<Episode>()

        // Episodes from the db not in source.
        val toDelete = dbEpisodes.filterNot { dbEpisode ->
            sourceEpisodes.any { sourceEpisode ->
                dbEpisode.url == sourceEpisode.url
            }
        }

        val rightNow = Date().time

        // Used to not set upload date of older episodes
        // to a higher value than newer episodes
        var maxSeenUploadDate = 0L

        val sAnime = anime.toSAnime()
        for (sourceEpisode in sourceEpisodes) {
            var episode = sourceEpisode

            // Update metadata from source if necessary.
            if (source is AnimeHttpSource) {
                val sEpisode = episode.toSEpisode()
                source.prepareNewEpisode(sEpisode, sAnime)
                episode = episode.copyFromSEpisode(sEpisode)
            }

            // Recognize episode number for the episode.
            val episodeNumber = EpisodeRecognition.parseEpisodeNumber(anime.title, episode.name, episode.episodeNumber)
            episode = episode.copy(episodeNumber = episodeNumber)

            val dbEpisode = dbEpisodes.find { it.url == episode.url }

            if (dbEpisode == null) {
                val toAddEpisode = if (episode.dateUpload == 0L) {
                    val altDateUpload = if (maxSeenUploadDate == 0L) rightNow else maxSeenUploadDate
                    episode.copy(dateUpload = altDateUpload)
                } else {
                    maxSeenUploadDate = max(maxSeenUploadDate, sourceEpisode.dateUpload)
                    episode
                }
                toAdd.add(toAddEpisode)
            } else {
                if (shouldUpdateDbEpisode.await(dbEpisode, episode)) {
                    if (dbEpisode.name != episode.name && downloadManager.isEpisodeDownloaded(dbEpisode.name, dbEpisode.scanlator, anime.title, anime.source)) {
                        downloadManager.renameEpisode(source, anime, dbEpisode, episode)
                    }
                    var toChangeEpisode = dbEpisode.copy(
                        name = episode.name,
                        episodeNumber = episode.episodeNumber,
                        scanlator = episode.scanlator,
                        sourceOrder = episode.sourceOrder,
                    )
                    if (episode.dateUpload != 0L) {
                        toChangeEpisode = toChangeEpisode.copy(dateUpload = sourceEpisode.dateUpload)
                    }
                    toChange.add(toChangeEpisode)
                }
            }
        }

        // Return if there's nothing to add, delete or change, avoiding unnecessary db transactions.
        if (toAdd.isEmpty() && toDelete.isEmpty() && toChange.isEmpty()) {
            return Pair(emptyList(), emptyList())
        }

        val reAdded = mutableListOf<Episode>()

        val deletedEpisodeNumbers = TreeSet<Float>()
        val deletedSeenEpisodeNumbers = TreeSet<Float>()

        toDelete.forEach { episode ->
            if (episode.seen) {
                deletedSeenEpisodeNumbers.add(episode.episodeNumber)
            }
            deletedEpisodeNumbers.add(episode.episodeNumber)
        }

        val deletedEpisodeNumberDateFetchMap = toDelete.sortedByDescending { it.dateFetch }
            .associate { it.episodeNumber to it.dateFetch }

        // Date fetch is set in such a way that the upper ones will have bigger value than the lower ones
        // Sources MUST return the episodes from most to less recent, which is common.

        var itemCount = toAdd.size
        var updatedToAdd = toAdd.map { toAddItem ->
            var episode = toAddItem.copy(dateFetch = rightNow + itemCount--)

            if (episode.isRecognizedNumber.not() && episode.episodeNumber !in deletedEpisodeNumbers) return@map episode

            if (episode.episodeNumber in deletedSeenEpisodeNumbers) {
                episode = episode.copy(seen = true)
            }

            // Try to to use the fetch date of the original entry to not pollute 'Updates' tab
            val oldDateFetch = deletedEpisodeNumberDateFetchMap[episode.episodeNumber]
            oldDateFetch?.let {
                episode = episode.copy(dateFetch = it)
            }

            reAdded.add(episode)

            episode
        }

        if (toDelete.isNotEmpty()) {
            val toDeleteIds = toDelete.map { it.id }
            episodeRepository.removeEpisodesWithIds(toDeleteIds)
        }

        if (updatedToAdd.isNotEmpty()) {
            updatedToAdd = episodeRepository.addAll(updatedToAdd)
        }

        if (toChange.isNotEmpty()) {
            val episodeUpdates = toChange.map { it.toEpisodeUpdate() }
            updateEpisode.awaitAll(episodeUpdates)
        }

        // Set this anime as updated since episodes were changed
        // Note that last_update actually represents last time the episode list changed at all
        updateAnime.awaitUpdateLastUpdate(anime.id)

        @Suppress("ConvertArgumentToSet") // See tachiyomiorg/tachiyomi#6372.
        return Pair(updatedToAdd.subtract(reAdded).toList(), toDelete.subtract(reAdded).toList())
    }
}
