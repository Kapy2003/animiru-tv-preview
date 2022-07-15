package eu.kanade.domain

import eu.kanade.data.anime.AnimeRepositoryImpl
import eu.kanade.data.animehistory.AnimeHistoryRepositoryImpl
import eu.kanade.data.animesource.AnimeSourceRepositoryImpl
import eu.kanade.data.animetrack.AnimeTrackRepositoryImpl
import eu.kanade.data.category.CategoryRepositoryImplAnime
import eu.kanade.data.episode.EpisodeRepositoryImpl
import eu.kanade.domain.anime.interactor.GetAnime
import eu.kanade.domain.anime.interactor.GetAnimeWithEpisodes
import eu.kanade.domain.anime.interactor.GetAnimelibAnime
import eu.kanade.domain.anime.interactor.GetDuplicateLibraryAnime
import eu.kanade.domain.anime.interactor.InsertAnime
import eu.kanade.domain.anime.interactor.SetAnimeEpisodeFlags
import eu.kanade.domain.anime.interactor.SetAnimeViewerFlags
import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.anime.repository.AnimeRepository
import eu.kanade.domain.animedownload.interactor.DeleteAnimeDownload
import eu.kanade.domain.animeextension.interactor.GetAnimeExtensionLanguages
import eu.kanade.domain.animeextension.interactor.GetAnimeExtensionSources
import eu.kanade.domain.animeextension.interactor.GetAnimeExtensionUpdates
import eu.kanade.domain.animeextension.interactor.GetAnimeExtensions
import eu.kanade.domain.animehistory.interactor.DeleteAnimeHistoryTable
import eu.kanade.domain.animehistory.interactor.GetAnimeHistory
import eu.kanade.domain.animehistory.interactor.GetNextEpisode
import eu.kanade.domain.animehistory.interactor.RemoveAnimeHistoryByAnimeId
import eu.kanade.domain.animehistory.interactor.RemoveAnimeHistoryById
import eu.kanade.domain.animehistory.interactor.UpsertAnimeHistory
import eu.kanade.domain.animehistory.repository.AnimeHistoryRepository
import eu.kanade.domain.animesource.interactor.GetAnimeSourceData
import eu.kanade.domain.animesource.interactor.GetAnimeSourcesWithFavoriteCount
import eu.kanade.domain.animesource.interactor.GetAnimeSourcesWithNonLibraryAnime
import eu.kanade.domain.animesource.interactor.GetEnabledAnimeSources
import eu.kanade.domain.animesource.interactor.GetLanguagesWithAnimeSources
import eu.kanade.domain.animesource.interactor.SetMigrateSorting
import eu.kanade.domain.animesource.interactor.ToggleAnimeSource
import eu.kanade.domain.animesource.interactor.ToggleAnimeSourcePin
import eu.kanade.domain.animesource.interactor.ToggleLanguage
import eu.kanade.domain.animesource.interactor.UpsertAnimeSourceData
import eu.kanade.domain.animesource.repository.AnimeSourceRepository
import eu.kanade.domain.animetrack.interactor.DeleteAnimeTrack
import eu.kanade.domain.animetrack.interactor.GetAnimeTracks
import eu.kanade.domain.animetrack.interactor.InsertAnimeTrack
import eu.kanade.domain.animetrack.repository.AnimeTrackRepository
import eu.kanade.domain.category.interactor.DeleteCategoryAnime
import eu.kanade.domain.category.interactor.GetCategoriesAnime
import eu.kanade.domain.category.interactor.InsertCategoryAnime
import eu.kanade.domain.category.interactor.SetAnimeCategories
import eu.kanade.domain.category.interactor.UpdateCategoryAnime
import eu.kanade.domain.category.repository.CategoryRepositoryAnime
import eu.kanade.domain.episode.interactor.GetEpisode
import eu.kanade.domain.episode.interactor.GetEpisodeByAnimeId
import eu.kanade.domain.episode.interactor.SetSeenStatus
import eu.kanade.domain.episode.interactor.ShouldUpdateDbEpisode
import eu.kanade.domain.episode.interactor.SyncEpisodesWithSource
import eu.kanade.domain.episode.interactor.SyncEpisodesWithTrackServiceTwoWay
import eu.kanade.domain.episode.interactor.UpdateEpisode
import eu.kanade.domain.episode.repository.EpisodeRepository
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get
import eu.kanade.domain.anime.interactor.GetFavorites as GetFavoritesAnime
import eu.kanade.domain.anime.interactor.ResetViewerFlags as ResetViewerFlagsAnime

class DomainModule : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory<AnimeRepository> { AnimeRepositoryImpl(get()) }
        addFactory { GetDuplicateLibraryAnime(get()) }
        addFactory { GetFavoritesAnime(get()) }
        addFactory { GetAnimelibAnime(get()) }
        addFactory { GetAnimeWithEpisodes(get(), get()) }
        addFactory { GetAnime(get()) }
        addFactory { GetNextEpisode(get()) }
        addFactory { ResetViewerFlagsAnime(get()) }
        addFactory { SetAnimeEpisodeFlags(get()) }
        addFactory { SetAnimeViewerFlags(get()) }
        addFactory { InsertAnime(get()) }
        addFactory { UpdateAnime(get()) }
        addFactory { SetAnimeCategories(get()) }

        addSingletonFactory<EpisodeRepository> { EpisodeRepositoryImpl(get()) }
        addFactory { GetEpisode(get()) }
        addFactory { GetEpisodeByAnimeId(get()) }
        addFactory { UpdateEpisode(get()) }
        addFactory { SetSeenStatus(get(), get(), get(), get()) }
        addFactory { ShouldUpdateDbEpisode() }
        addFactory { SyncEpisodesWithSource(get(), get(), get(), get()) }
        addFactory { SyncEpisodesWithTrackServiceTwoWay(get(), get()) }

        addSingletonFactory<CategoryRepositoryAnime> { CategoryRepositoryImplAnime(get()) }
        addFactory { GetCategoriesAnime(get()) }
        addFactory { InsertCategoryAnime(get()) }
        addFactory { UpdateCategoryAnime(get()) }
        addFactory { DeleteCategoryAnime(get()) }

        addSingletonFactory<AnimeTrackRepository> { AnimeTrackRepositoryImpl(get()) }
        addFactory { DeleteAnimeTrack(get()) }
        addFactory { GetAnimeTracks(get()) }
        addFactory { InsertAnimeTrack(get()) }

        addSingletonFactory<AnimeHistoryRepository> { AnimeHistoryRepositoryImpl(get()) }
        addFactory { DeleteAnimeHistoryTable(get()) }
        addFactory { GetAnimeHistory(get()) }
        addFactory { UpsertAnimeHistory(get()) }
        addFactory { RemoveAnimeHistoryById(get()) }
        addFactory { RemoveAnimeHistoryByAnimeId(get()) }

        addSingletonFactory<AnimeSourceRepository> { AnimeSourceRepositoryImpl(get(), get()) }
        addFactory { GetEnabledAnimeSources(get(), get()) }
        addFactory { GetLanguagesWithAnimeSources(get(), get()) }
        addFactory { GetAnimeSourceData(get()) }
        addFactory { GetAnimeSourcesWithFavoriteCount(get(), get()) }
        addFactory { GetAnimeSourcesWithNonLibraryAnime(get()) }
        addFactory { ToggleAnimeSource(get()) }
        addFactory { ToggleAnimeSourcePin(get()) }
        addFactory { UpsertAnimeSourceData(get()) }

        addFactory { GetAnimeExtensions(get(), get()) }
        addFactory { GetAnimeExtensionSources(get()) }
        addFactory { GetAnimeExtensionUpdates(get(), get()) }
        addFactory { GetAnimeExtensionLanguages(get(), get()) }

        addFactory { DeleteAnimeDownload(get(), get()) }

        addFactory { SetMigrateSorting(get()) }
        addFactory { ToggleLanguage(get()) }
    }
}
