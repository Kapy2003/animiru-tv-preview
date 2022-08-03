package eu.kanade.tachiyomi.ui.player

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import eu.kanade.domain.anime.model.Anime
import eu.kanade.domain.animehistory.interactor.UpsertAnimeHistory
import eu.kanade.domain.animehistory.model.AnimeHistoryUpdate
import eu.kanade.domain.animetrack.interactor.GetAnimeTracks
import eu.kanade.domain.animetrack.interactor.InsertAnimeTrack
import eu.kanade.domain.animetrack.model.toDbTrack
import eu.kanade.domain.episode.interactor.GetEpisodeByAnimeId
import eu.kanade.domain.episode.interactor.UpdateEpisode
import eu.kanade.domain.episode.model.Episode
import eu.kanade.domain.episode.model.EpisodeUpdate
import eu.kanade.domain.episode.model.toDbEpisode
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.LocalAnimeSource
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.database.models.toDomainEpisode
import eu.kanade.tachiyomi.data.download.AnimeDownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.job.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.job.DelayedTrackingUpdateJob
import eu.kanade.tachiyomi.ui.anime.AnimeController.Companion.EXT_ANIME
import eu.kanade.tachiyomi.ui.anime.AnimeController.Companion.EXT_EPISODE
import eu.kanade.tachiyomi.ui.anime.AnimeController.Companion.REQUEST_EXTERNAL
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.launchUI
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.logcat
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.Date
import eu.kanade.tachiyomi.data.database.models.Episode as DbEpisode

class ExternalIntents(val anime: Anime, val source: AnimeSource) {
    private val preferences: PreferencesHelper by injectLazy()

    fun getExternalIntent(episode: Episode, video: Video, context: Context): Intent? {
        val videoUrl = if (video.videoUrl == null) {
            makeErrorToast(context, Exception("video URL is null."))
            return null
        } else {
            val uri = video.videoUrl!!.toUri()
            val isOnDevice = if (anime.source == LocalAnimeSource.ID) {
                true
            } else {
                downloadManager.isEpisodeDownloaded(
                    episode.name,
                    episode.scanlator,
                    anime.title,
                    anime.source,
                )
            }
            if (isOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && uri.scheme != "content") {
                FileProvider.getUriForFile(
                    context,
                    context.applicationContext.packageName + ".provider",
                    File(uri.path!!),
                )
            } else {
                uri
            }
        }
        val pkgName = preferences.externalPlayerPreference()
        val anime = anime
        val lastSecondSeen = if (episode.seen) {
            if ((!preferences.preserveWatchingPosition()) ||
                (
                    preferences.preserveWatchingPosition() &&
                        episode.lastSecondSeen == episode.totalSeconds
                    )
            ) 1L else episode.lastSecondSeen
        } else episode.lastSecondSeen

        return if (pkgName.isNullOrEmpty()) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndTypeAndNormalize(videoUrl, getMime(videoUrl))
                putExtra("title", anime.title + " - " + episode.name)
                putExtra("position", lastSecondSeen.toInt())
                putExtra("return_result", true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val headers = video.headers ?: (source as? AnimeHttpSource)?.headers
                if (headers != null) {
                    var headersArray = arrayOf<String>()
                    for (header in headers) {
                        headersArray += arrayOf(header.first, header.second)
                    }
                    val headersString = headersArray.drop(2).joinToString(": ")
                    putExtra("headers", headersArray)
                    putExtra("http-header-fields", headersString)
                }
            }
        } else standardIntentForPackage(pkgName, context, videoUrl, episode, video)
    }

    private fun makeErrorToast(context: Context, e: Exception?) {
        launchUI { context.toast(e?.message ?: "Cannot open episode") }
    }

    private fun standardIntentForPackage(pkgName: String, context: Context, uri: Uri, episode: Episode, video: Video): Intent {
        val lastSecondSeen = if (episode.seen && !preferences.preserveWatchingPosition()) {
            0L
        } else {
            episode.lastSecondSeen
        }
        return Intent(Intent.ACTION_VIEW).apply {
            if (isPackageInstalled(pkgName, context.packageManager)) {
                component = getComponent(pkgName)
            }
            setDataAndType(uri, "video/*")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra("title", episode.name)
            putExtra("position", lastSecondSeen.toInt())
            putExtra("return_result", true)
            putExtra("secure_uri", true)

            /*val externalSubs = source.getExternalSubtitleStreams()
            val enabledSubUrl = when {
                source.selectedSubtitleStream != null -> {
                    externalSubs.find { stream -> stream.index == source.selectedSubtitleStream?.index }?.let { sub ->
                        apiClient.createUrl(sub.deliveryUrl)
                    }
                }
                else -> null
            }

            // MX Player API / MPV
            putExtra("subs", externalSubs.map { stream -> Uri.parse(apiClient.createUrl(stream.deliveryUrl)) }.toTypedArray())
            putExtra("subs.name", externalSubs.map(ExternalSubtitleStream::displayTitle).toTypedArray())
            putExtra("subs.filename", externalSubs.map(ExternalSubtitleStream::language).toTypedArray())
            putExtra("subs.enable", enabledSubUrl?.let { url -> arrayOf(Uri.parse(url)) } ?: emptyArray())

            // VLC
            if (enabledSubUrl != null) putExtra("subtitles_location", enabledSubUrl)*/

            // headers
            val headers = video.headers ?: (source as? AnimeHttpSource)?.headers
            if (headers != null) {
                var headersArray = arrayOf<String>()
                for (header in headers) {
                    headersArray += arrayOf(header.first, header.second)
                }
                putExtra("headers", headersArray)
            }
        }
    }

    private fun getMime(uri: Uri): String {
        return when (uri.path?.substringAfterLast(".")) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "m3u8" -> "application/x-mpegURL"
            else -> "video/any"
        }
    }

    /**
     * To ensure that the correct activity is called.
     */
    private fun getComponent(packageName: String): ComponentName? {
        return when (packageName) {
            MPV_PLAYER -> ComponentName(packageName, "$packageName.MPVActivity")
            MX_PLAYER_FREE, MX_PLAYER_PRO -> ComponentName(packageName, "$packageName.ActivityScreen")
            VLC_PLAYER -> ComponentName(packageName, "$packageName.gui.video.VideoPlayerActivity")
            MPV_REMOTE -> ComponentName(packageName, "$packageName.MainActivity")
            else -> null
        }
    }

    private fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode == REQUEST_EXTERNAL && resultCode == Activity.RESULT_OK) {
                val anime = EXT_ANIME ?: return
                val currentExtEpisode = EXT_EPISODE ?: return
                val currentPosition: Long
                val duration: Long
                val cause = data!!.getStringExtra("end_by") ?: ""
                if (cause.isNotEmpty()) {
                    val positionExtra = data.extras?.get("position")
                    currentPosition = if (positionExtra is Int) {
                        positionExtra.toLong()
                    } else {
                        positionExtra as? Long ?: 0L
                    }
                    val durationExtra = data.extras?.get("duration")
                    duration = if (durationExtra is Int) {
                        durationExtra.toLong()
                    } else {
                        durationExtra as? Long ?: 0L
                    }
                } else {
                    if (data.extras?.get("extra_position") != null) {
                        currentPosition = data.getLongExtra("extra_position", 0L)
                        duration = data.getLongExtra("extra_duration", 0L)
                    } else {
                        currentPosition = data.getIntExtra("position", 0).toLong()
                        duration = data.getIntExtra("duration", 0).toLong()
                    }
                }
                launchIO {
                    if (cause == "playback_completion" || (currentPosition == duration && duration == 0L)) {
                        saveEpisodeProgress(currentExtEpisode, anime, currentExtEpisode.totalSeconds, currentExtEpisode.totalSeconds)
                    } else {
                        saveEpisodeProgress(currentExtEpisode, anime, currentPosition, duration)
                    }
                    saveEpisodeHistory(currentExtEpisode)
                }
            }
        }

        private val upsertHistory: UpsertAnimeHistory = Injekt.get()
        private val updateEpisode: UpdateEpisode = Injekt.get()
        private val getEpisodeByAnimeId: GetEpisodeByAnimeId = Injekt.get()
        private val getTracks: GetAnimeTracks = Injekt.get()
        private val insertTrack: InsertAnimeTrack = Injekt.get()
        private val downloadManager: AnimeDownloadManager by injectLazy()
        private val delayedTrackingStore: DelayedTrackingStore = Injekt.get()
        private val preferences: PreferencesHelper by injectLazy()

        private suspend fun saveEpisodeHistory(episode: Episode) {
            if (preferences.incognitoMode().get()) return
            upsertHistory.await(
                AnimeHistoryUpdate(episode.id, Date()),
            )
        }

        private suspend fun saveEpisodeProgress(domainEpisode: Episode?, anime: Anime, seconds: Long, totalSeconds: Long) {
            if (preferences.incognitoMode().get()) return
            val episode = domainEpisode?.toDbEpisode() ?: return
            if (totalSeconds > 0L) {
                episode.last_second_seen = seconds
                episode.total_seconds = totalSeconds
                val progress = preferences.progressPreference()
                if (!episode.seen) episode.seen = episode.last_second_seen >= episode.total_seconds * progress
                updateEpisode.await(
                    EpisodeUpdate(
                        id = episode.id!!,
                        seen = episode.seen,
                        bookmark = episode.bookmark,
                        fillermark = episode.fillermark,
                        lastSecondSeen = episode.last_second_seen,
                        totalSeconds = episode.total_seconds,
                    ),
                )
                if (preferences.autoUpdateTrack() && episode.seen) {
                    updateTrackEpisodeSeen(episode, anime)
                }
                if (episode.seen) {
                    deleteEpisodeIfNeeded(episode.toDomainEpisode()!!, anime)
                    deleteEpisodeFromDownloadQueue(episode.toDomainEpisode()!!)
                    // AM -->
                    downloadEpisodesIfNeeded(episode.toDomainEpisode()!!, anime)
                    // AM <--
                }
            }
        }

        private fun deleteEpisodeFromDownloadQueue(episode: Episode) {
            downloadManager.getEpisodeDownloadOrNull(episode)?.let { download ->
                downloadManager.deletePendingDownload(download)
            }
        }

        // AM -->
        private suspend fun downloadEpisodesIfNeeded(currentEpisode: Episode, anime: Anime) {
            // Determine which episodes should be downloaded and enqueue them
            val sortFunction: (Episode, Episode) -> Int = when (anime.sorting) {
                Anime.EPISODE_SORTING_SOURCE -> { c1, c2 -> c2.sourceOrder.compareTo(c1.sourceOrder) }
                Anime.EPISODE_SORTING_NUMBER -> { c1, c2 -> c1.episodeNumber.compareTo(c2.episodeNumber) }
                Anime.EPISODE_SORTING_UPLOAD_DATE -> { c1, c2 -> c1.dateUpload.compareTo(c2.dateUpload) }
                else -> throw NotImplementedError("Unknown sorting method")
            }

            val episodeList = getEpisodeByAnimeId.await(anime.id)
                .sortedWith { e1, e2 -> sortFunction(e1, e2) }

            // Determine which episode should be downloaded and enqueue
            val currentEpisodePosition = episodeList.indexOf(currentEpisode)
            val downloadAfterSeenSlots = preferences.downloadAfterSeenSlots()
            var episodePositionDelta = 1
            val episodesToDownload = mutableListOf<Episode>()
            while (episodePositionDelta <= downloadAfterSeenSlots) {
                val episodeToDownload = episodeList.getOrNull(currentEpisodePosition + episodePositionDelta)
                // Check if downloading option is enabled and episode exists
                if (downloadAfterSeenSlots != 0 && episodeToDownload != null) {
                    // Check if episode is already in the downloader
                    if (downloadManager.getEpisodeDownloadOrNull(episodeToDownload) == null) episodesToDownload += episodeToDownload
                }
                episodePositionDelta++
            }
            if (episodesToDownload.isNotEmpty()) downloadManager.downloadEpisodes(anime, episodesToDownload)
        }
        // AM <--

        private suspend fun deleteEpisodeIfNeeded(episode: Episode, anime: Anime) {
            // Determine which episode should be deleted and enqueue
            val sortFunction: (Episode, Episode) -> Int = when (anime.sorting) {
                Anime.EPISODE_SORTING_SOURCE -> { c1, c2 -> c2.sourceOrder.compareTo(c1.sourceOrder) }
                Anime.EPISODE_SORTING_NUMBER -> { c1, c2 -> c1.episodeNumber.compareTo(c2.episodeNumber) }
                Anime.EPISODE_SORTING_UPLOAD_DATE -> { c1, c2 -> c1.dateUpload.compareTo(c2.dateUpload) }
                else -> throw NotImplementedError("Unknown sorting method")
            }

            val episodes = getEpisodeByAnimeId.await(anime.id)
                .sortedWith { e1, e2 -> sortFunction(e1, e2) }

            val currentEpisodePosition = episodes.indexOf(episode)
            val removeAfterSeenSlots = preferences.removeAfterSeenSlots()
            val episodeToDelete = episodes.getOrNull(currentEpisodePosition - removeAfterSeenSlots)

            // Check if deleting option is enabled and chapter exists
            if (removeAfterSeenSlots != -1 && episodeToDelete != null) {
                enqueueDeleteSeenEpisodes(episodeToDelete, anime)
            }
        }

        private fun updateTrackEpisodeSeen(episode: DbEpisode, anime: Anime) {
            if (!preferences.autoUpdateTrack()) return

            val episodeSeen = episode.episode_number.toDouble()

            val trackManager = Injekt.get<TrackManager>()
            val context = Injekt.get<Application>()

            launchIO {
                getTracks.await(anime.id)
                    .mapNotNull { track ->
                        val service = trackManager.getService(track.syncId)
                        if (service != null && service.isLogged && episodeSeen > track.lastEpisodeSeen) {
                            val updatedTrack = track.copy(lastEpisodeSeen = episodeSeen)

                            // We want these to execute even if the presenter is destroyed and leaks
                            // for a while. The view can still be garbage collected.
                            async {
                                runCatching {
                                    if (context.isOnline()) {
                                        service.update(updatedTrack.toDbTrack(), true)
                                        insertTrack.await(updatedTrack)
                                    } else {
                                        delayedTrackingStore.addItem(updatedTrack)
                                        DelayedTrackingUpdateJob.setupTask(context)
                                    }
                                }
                            }
                        } else {
                            null
                        }
                    }
                    .awaitAll()
                    .mapNotNull { it.exceptionOrNull() }
                    .forEach { logcat(LogPriority.INFO, it) }
            }
        }

        private fun enqueueDeleteSeenEpisodes(episode: Episode, anime: Anime) {
            if (!episode.seen) return

            launchIO {
                downloadManager.enqueueDeleteEpisodes(listOf(episode), anime)
            }
        }
    }
}

private const val MPV_PLAYER = "is.xyz.mpv"
private const val MX_PLAYER_FREE = "com.mxtech.videoplayer.ad"
private const val MX_PLAYER_PRO = "com.mxtech.videoplayer.pro"
private const val VLC_PLAYER = "org.videolan.vlc"
private const val MPV_REMOTE = "com.husudosu.mpvremote"
