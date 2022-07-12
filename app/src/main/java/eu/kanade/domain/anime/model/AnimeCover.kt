package eu.kanade.domain.anime.model

/**
 * Contains the required data for MangaCoverFetcher
 */
data class AnimeCover(
    val animeId: Long,
    val sourceId: Long,
    val isAnimeFavorite: Boolean,
    val url: String?,
    val lastModified: Long,
)
