package eu.kanade.tachiyomi.data.backup.full.models

import eu.kanade.tachiyomi.data.database.models.EpisodeImpl
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupEpisode(
    // in 1.x some of these values have different names
    // url is called key in 1.x
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var scanlator: String? = null,
    @ProtoNumber(4) var seen: Boolean = false,
    @ProtoNumber(5) var bookmark: Boolean = false,
    @ProtoNumber(15) var fillermark: Boolean = false,
    // lastPageRead is called progress in 1.x
    @ProtoNumber(6) var lastSecondSeen: Long = 0,
    @ProtoNumber(16) var totalSeconds: Long = 0,
    @ProtoNumber(7) var dateFetch: Long = 0,
    @ProtoNumber(8) var dateUpload: Long = 0,
    // episodeNumber is called number is 1.x
    @ProtoNumber(9) var episodeNumber: Float = 0F,
    @ProtoNumber(10) var sourceOrder: Long = 0,
) {
    fun toEpisodeImpl(): EpisodeImpl {
        return EpisodeImpl().apply {
            url = this@BackupEpisode.url
            name = this@BackupEpisode.name
            episode_number = this@BackupEpisode.episodeNumber
            scanlator = this@BackupEpisode.scanlator
            seen = this@BackupEpisode.seen
            bookmark = this@BackupEpisode.bookmark
            fillermark = this@BackupEpisode.fillermark
            last_second_seen = this@BackupEpisode.lastSecondSeen
            total_seconds = this@BackupEpisode.totalSeconds
            date_fetch = this@BackupEpisode.dateFetch
            date_upload = this@BackupEpisode.dateUpload
            source_order = this@BackupEpisode.sourceOrder.toInt()
        }
    }

    companion object {
        fun copyFrom(episode: Episode): BackupEpisode {
            return BackupEpisode(
                url = episode.url,
                name = episode.name,
                episodeNumber = episode.episode_number,
                scanlator = episode.scanlator,
                seen = episode.seen,
                bookmark = episode.bookmark,
                fillermark = episode.fillermark,
                lastSecondSeen = episode.last_second_seen,
                totalSeconds = episode.total_seconds,
                dateFetch = episode.date_fetch,
                dateUpload = episode.date_upload,
                sourceOrder = episode.source_order,
            )
        }
    }
}

val backupEpisodeMapper = {
        _: Long,
        _: Long,
        url: String,
        name: String,
        scanlator: String?,
        seen: Boolean,
        bookmark: Boolean,
        fillermark: Boolean,
        lastSecondSeen: Long,
        totalSeconds: Long,
        episodeNumber: Float,
        source_order: Long,
        dateFetch: Long,
        dateUpload: Long,  ->
    BackupEpisode(
        url = url,
        name = name,
        episodeNumber = episodeNumber,
        scanlator = scanlator,
        seen = seen,
        bookmark = bookmark,
        fillermark = fillermark, 
        lastSecondSeen = lastSecondSeen,
        totalSeconds = totalSeconds,
        dateFetch = dateFetch,
        dateUpload = dateUpload,
        sourceOrder = source_order,
    )
}
