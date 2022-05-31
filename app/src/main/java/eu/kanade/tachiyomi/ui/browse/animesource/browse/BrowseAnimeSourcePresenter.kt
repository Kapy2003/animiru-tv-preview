package eu.kanade.tachiyomi.ui.browse.animesource.browse

import android.os.Bundle
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSourceManager
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.toSAnime
import eu.kanade.tachiyomi.data.cache.AnimeCoverCache
import eu.kanade.tachiyomi.data.database.AnimeDatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Anime
import eu.kanade.tachiyomi.data.database.models.AnimeCategory
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.toAnimeInfo
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.ui.browse.animesource.filter.CheckboxItem
import eu.kanade.tachiyomi.ui.browse.animesource.filter.CheckboxSectionItem
import eu.kanade.tachiyomi.ui.browse.animesource.filter.GroupItem
import eu.kanade.tachiyomi.ui.browse.animesource.filter.HeaderItem
import eu.kanade.tachiyomi.ui.browse.animesource.filter.SelectItem
import eu.kanade.tachiyomi.ui.browse.animesource.filter.SelectSectionItem
import eu.kanade.tachiyomi.ui.browse.animesource.filter.SeparatorItem
import eu.kanade.tachiyomi.ui.browse.animesource.filter.SortGroup
import eu.kanade.tachiyomi.ui.browse.animesource.filter.SortItem
import eu.kanade.tachiyomi.ui.browse.animesource.filter.TextItem
import eu.kanade.tachiyomi.ui.browse.animesource.filter.TextSectionItem
import eu.kanade.tachiyomi.ui.browse.animesource.filter.TriStateItem
import eu.kanade.tachiyomi.ui.browse.animesource.filter.TriStateSectionItem
import eu.kanade.tachiyomi.util.episode.EpisodeSettingsHelper
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.withUIContext
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.logcat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

open class BrowseAnimeSourcePresenter(
    private val sourceId: Long,
    searchQuery: String? = null,
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val db: AnimeDatabaseHelper = Injekt.get(),
    private val prefs: PreferencesHelper = Injekt.get(),
    private val coverCache: AnimeCoverCache = Injekt.get(),
) : BasePresenter<BrowseAnimeSourceController>() {

    /**
     * Selected source.
     */
    lateinit var source: AnimeCatalogueSource

    /**
     * Modifiable list of filters.
     */
    var sourceFilters = AnimeFilterList()
        set(value) {
            field = value
            filterItems = value.toItems()
        }

    var filterItems: List<IFlexible<*>> = emptyList()

    /**
     * List of filters used by the [Pager]. If empty alongside [query], the popular query is used.
     */
    var appliedFilters = AnimeFilterList()

    /**
     * Pager containing a list of anime results.
     */
    private lateinit var pager: AnimePager

    /**
     * Subscription for the pager.
     */
    private var pagerSubscription: Subscription? = null

    /**
     * Subscription for one request from the pager.
     */
    private var nextPageJob: Job? = null

    private val loggedServices by lazy { Injekt.get<TrackManager>().services.filter { it.isLogged } }

    init {
        query = searchQuery ?: ""
    }

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        source = sourceManager.get(sourceId) as? AnimeCatalogueSource ?: return

        sourceFilters = source.getFilterList()

        if (savedState != null) {
            query = savedState.getString(::query.name, "")
        }

        restartPager()
    }

    override fun onSave(state: Bundle) {
        state.putString(::query.name, query)
        super.onSave(state)
    }

    /**
     * Restarts the pager for the active source with the provided query and filters.
     *
     * @param query the query.
     * @param filters the current state of the filters (for search mode).
     */
    fun restartPager(query: String = this.query, filters: AnimeFilterList = this.appliedFilters) {
        this.query = query
        this.appliedFilters = filters

        // Create a new pager.
        pager = createPager(query, filters)

        val sourceId = source.id

        val sourceDisplayMode = prefs.sourceDisplayMode()

        // Prepare the pager.
        pagerSubscription?.let { remove(it) }
        pagerSubscription = pager.results()
            .observeOn(Schedulers.io())
            .map { (first, second) -> first to second.map { networkToLocalAnime(it, sourceId) } }
            .doOnNext { initializeAnimes(it.second) }
            .map { (first, second) -> first to second.map { AnimeSourceItem(it, sourceDisplayMode) } }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeReplay(
                { view, (page, animes) ->
                    view.onAddPage(page, animes)
                },
                { _, error ->
                    logcat(LogPriority.ERROR, error)
                },
            )

        // Request first page.
        requestNext()
    }

    /**
     * Requests the next page for the active pager.
     */
    fun requestNext() {
        if (!hasNextPage()) return

        nextPageJob?.cancel()
        nextPageJob = launchIO {
            try {
                pager.requestNextPage()
            } catch (e: Throwable) {
                withUIContext { view?.onAddPageError(e) }
            }
        }
    }

    /**
     * Returns true if the last fetched page has a next page.
     */
    fun hasNextPage(): Boolean {
        return pager.hasNextPage
    }

    /**
     * Returns a anime from the database for the given anime from network. It creates a new entry
     * if the anime is not yet in the database.
     *
     * @param sAnime the anime from the source.
     * @return a anime from the database.
     */
    private fun networkToLocalAnime(sAnime: SAnime, sourceId: Long): Anime {
        var localAnime = db.getAnime(sAnime.url, sourceId).executeAsBlocking()
        if (localAnime == null) {
            val newAnime = Anime.create(sAnime.url, sAnime.title, sourceId)
            newAnime.copyFrom(sAnime)
            val result = db.insertAnime(newAnime).executeAsBlocking()
            newAnime.id = result.insertedId()
            localAnime = newAnime
        } else if (!localAnime.favorite) {
            // if the anime isn't a favorite, set its display title from source
            // if it later becomes a favorite, updated title will go to db
            localAnime.title = sAnime.title
        }
        return localAnime
    }

    /**
     * Initialize a list of anime.
     *
     * @param animes the list of anime to initialize.
     */
    fun initializeAnimes(animes: List<Anime>) {
        presenterScope.launchIO {
            animes.asFlow()
                .filter { it.thumbnail_url == null && !it.initialized }
                .map { getAnimeDetails(it) }
                .onEach {
                    withUIContext {
                        @Suppress("DEPRECATION")
                        view?.onAnimeInitialized(it)
                    }
                }
                .catch { e -> logcat(LogPriority.ERROR, e) }
                .collect()
        }
    }

    /**
     * Returns the initialized anime.
     *
     * @param anime the anime to initialize.
     * @return the initialized anime
     */
    private suspend fun getAnimeDetails(anime: Anime): Anime {
        try {
            val networkAnime = source.getAnimeDetails(anime.toAnimeInfo())
            anime.copyFrom(networkAnime.toSAnime())
            anime.initialized = true
            db.insertAnime(anime).executeAsBlocking()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
        return anime
    }

    /**
     * Adds or removes a anime from the library.
     *
     * @param anime the anime to update.
     */
    fun changeAnimeFavorite(anime: Anime) {
        anime.favorite = !anime.favorite
        anime.date_added = when (anime.favorite) {
            true -> Date().time
            false -> 0
        }

        if (!anime.favorite) {
            anime.removeCovers(coverCache)
        } else {
            EpisodeSettingsHelper.applySettingDefaults(anime)
        }

        db.insertAnime(anime).executeAsBlocking()
    }

    /**
     * Set the filter states for the current source.
     *
     * @param filters a list of active filters.
     */
    fun setSourceFilter(filters: AnimeFilterList) {
        restartPager(filters = filters)
    }

    open fun createPager(query: String, filters: AnimeFilterList): AnimePager {
        return AnimeSourcePager(source, query, filters)
    }

    private fun AnimeFilterList.toItems(): List<IFlexible<*>> {
        return mapNotNull { filter ->
            when (filter) {
                is AnimeFilter.Header -> HeaderItem(filter)
                is AnimeFilter.Separator -> SeparatorItem(filter)
                is AnimeFilter.CheckBox -> CheckboxItem(filter)
                is AnimeFilter.TriState -> TriStateItem(filter)
                is AnimeFilter.Text -> TextItem(filter)
                is AnimeFilter.Select<*> -> SelectItem(filter)
                is AnimeFilter.Group<*> -> {
                    val group = GroupItem(filter)
                    val subItems = filter.state.mapNotNull {
                        when (it) {
                            is AnimeFilter.CheckBox -> CheckboxSectionItem(it)
                            is AnimeFilter.TriState -> TriStateSectionItem(it)
                            is AnimeFilter.Text -> TextSectionItem(it)
                            is AnimeFilter.Select<*> -> SelectSectionItem(it)
                            else -> null
                        }
                    }
                    subItems.forEach { it.header = group }
                    group.subItems = subItems
                    group
                }
                is AnimeFilter.Sort -> {
                    val group = SortGroup(filter)
                    val subItems = filter.values.map {
                        SortItem(it, group)
                    }
                    group.subItems = subItems
                    group
                }
            }
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    fun getCategories(): List<Category> {
        return db.getCategories().executeAsBlocking()
    }

    fun getDuplicateAnimelibAnime(anime: Anime): Anime? {
        return db.getDuplicateAnimelibAnime(anime).executeAsBlocking()
    }

    /**
     * Gets the category id's the anime is in, if the anime is not in a category, returns the default id.
     *
     * @param anime the anime to get categories from.
     * @return Array of category ids the anime is in, if none returns default id
     */
    fun getAnimeCategoryIds(anime: Anime): Array<Int?> {
        val categories = db.getCategoriesForAnime(anime).executeAsBlocking()
        return categories.mapNotNull { it.id }.toTypedArray()
    }

    /**
     * Move the given anime to categories.
     *
     * @param categories the selected categories.
     * @param anime the anime to move.
     */
    private fun moveAnimeToCategories(anime: Anime, categories: List<Category>) {
        val mc = categories.filter { it.id != 0 }.map { AnimeCategory.create(anime, it) }
        db.setAnimeCategories(mc, listOf(anime))
    }

    /**
     * Move the given anime to the category.
     *
     * @param category the selected category.
     * @param anime the anime to move.
     */
    fun moveAnimeToCategory(anime: Anime, category: Category?) {
        moveAnimeToCategories(anime, listOfNotNull(category))
    }

    /**
     * Update anime to use selected categories.
     *
     * @param anime needed to change
     * @param selectedCategories selected categories
     */
    fun updateAnimeCategories(anime: Anime, selectedCategories: List<Category>) {
        if (!anime.favorite) {
            changeAnimeFavorite(anime)
        }

        moveAnimeToCategories(anime, selectedCategories)
    }
}
