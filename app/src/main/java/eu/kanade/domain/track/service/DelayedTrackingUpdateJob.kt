package eu.kanade.domain.track.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.util.lang.withIOContext
import tachiyomi.core.util.system.logcat
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class DelayedTrackingUpdateJob(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val getTracks = Injekt.get<GetTracks>()
        val insertTrack = Injekt.get<InsertTrack>()

        val trackManager = Injekt.get<TrackManager>()
        val delayedTrackingStore = Injekt.get<DelayedTrackingStore>()

        val itemsToRemove = mutableListOf<Int>()
        val results = withIOContext {
            val tracks = delayedTrackingStore.getItems()
                .map {
                    val track = getTracks.awaitOne(it.trackId)
                    if (track == null) {
                        itemsToRemove.add(it.trackId.toInt())
                        null
                    } else {
                        track.copy(lastChapterRead = it.lastChapterRead.toDouble())
                    }
                }
                .filterNotNull()

            tracks.mapNotNull { track ->
                try {
                    val service = trackManager.getService(track.syncId)
                    if (service != null && service.isLogged) {
                        logcat(LogPriority.DEBUG) { "Updating delayed track item: ${track.id}, last chapter read: ${track.lastChapterRead}" }
                        coroutineScope {
                            launch { service.update(track.toDbTrack(), true) }
                            launch { insertTrack.await(track) }
                        }
                        itemsToRemove.add(track.id.toInt())
                        null
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                    false
                }
            }
        }
        itemsToRemove.forEach { delayedTrackingStore.remove(it.toLong()) }
        return Result.success()
    }

    companion object {
        private const val TAG = "DelayedTrackingUpdate"

        fun setupTask(context: Context) {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )

            val request = OneTimeWorkRequestBuilder<DelayedTrackingUpdateJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
