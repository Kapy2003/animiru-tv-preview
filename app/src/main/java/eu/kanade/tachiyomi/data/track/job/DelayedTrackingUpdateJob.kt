package eu.kanade.tachiyomi.data.track.job

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.domain.anime.interactor.GetAnime
import eu.kanade.domain.animetrack.interactor.GetAnimeTracks
import eu.kanade.domain.animetrack.interactor.InsertAnimeTrack
import eu.kanade.domain.animetrack.model.toDbTrack
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.util.system.logcat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class DelayedTrackingUpdateJob(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val getAnime = Injekt.get<GetAnime>()
        val getAnimeTracks = Injekt.get<GetAnimeTracks>()
        val insertAnimeTrack = Injekt.get<InsertAnimeTrack>()

        val trackManager = Injekt.get<TrackManager>()
        val delayedTrackingStore = Injekt.get<DelayedTrackingStore>()

        withContext(Dispatchers.IO) {
            val animeTracks = delayedTrackingStore.getAnimeItems().mapNotNull {
                val anime = getAnime.await(it.animeId) ?: return@withContext
                getAnimeTracks.await(anime.id)
                    .find { track -> track.id == it.trackId }
                    ?.copy(lastEpisodeSeen = it.lastEpisodeSeen.toDouble())
            }

            animeTracks.forEach { track ->
                try {
                    val service = trackManager.getService(track.syncId)
                    if (service != null && service.isLogged) {
                        service.update(track.toDbTrack(), true)
                        insertAnimeTrack.await(track)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                }
            }

            delayedTrackingStore.clear()
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "DelayedTrackingUpdate"

        fun setupTask(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<DelayedTrackingUpdateJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
