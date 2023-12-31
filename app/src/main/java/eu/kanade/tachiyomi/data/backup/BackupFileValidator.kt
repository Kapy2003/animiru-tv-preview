package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.util.BackupUtil
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BackupFileValidator(
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    private val trackManager: TrackManager = Injekt.get(),
) {

    /**
     * Checks for critical backup file data.
     *
     * @throws Exception if manga cannot be found.
     * @return List of missing sources or missing trackers.
     */
    fun validate(context: Context, uri: Uri): Results {
        val backup = try {
            BackupUtil.decodeBackup(context, uri)
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }

        val animesources = backup.backupAnimeSources.associate { it.sourceId to it.name }
        val missingSources = animesources
            .filter { animeSourceManager.get(it.key) == null }
            .values.map {
                val id = it.toLongOrNull()
                if (id == null) {
                    it
                } else {
                    animeSourceManager.getOrStub(id).toString()
                }
            }
            .distinct()
            .sorted()

        val trackers = backup.backupAnime
            .flatMap { it.tracking }
            .map { it.syncId }
            .distinct()
        val missingTrackers = trackers
            .mapNotNull { trackManager.getService(it.toLong()) }
            .filter { !it.isLogged }
            .map { context.getString(it.nameRes()) }
            .sorted()

        return Results(missingSources, missingTrackers)
    }

    data class Results(val missingSources: List<String>, val missingTrackers: List<String>)
}
