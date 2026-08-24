package com.comicreader.app.data.bubble

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.comicreader.app.data.repository.ComicRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException

private const val COMIC_ID_KEY = "comic_id"
private const val WORK_NAME_PREFIX = "bubble-detection-"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BubbleDetectionWorkerEntryPoint {
    fun comicRepository(): ComicRepository
    fun bubbleDetector(): BubbleDetector
}

/**
 * Indexes a small batch at a time so the visible page and navigation remain
 * responsive. Continuations gradually make the entire comic instant on later
 * Bubble Zoom taps and survive normal process/app restarts through WorkManager.
 */
class BubbleDetectionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val comicId = inputData.getLong(COMIC_ID_KEY, -1L)
        if (comicId <= 0) return Result.failure()

        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            BubbleDetectionWorkerEntryPoint::class.java
        )

        return try {
            val hasMore = dependencies.comicRepository().detectNextBubbleBatch(
                comicId = comicId,
                detector = dependencies.bubbleDetector()
            )
            Log.d(
                BubbleDetectionContract.INDEX_TAG,
                "stage=INDEX_BATCH outcome=${if (hasMore) "CONTINUE" else "COMPLETE"} comic=$comicId"
            )
            if (hasMore) BubbleDetectionScheduler.enqueueContinuation(applicationContext, comicId)
            Result.success()
        } catch (error: SecurityException) {
            Result.failure(workDataOf("error" to (error.message ?: "Comic permission was lost")))
        } catch (error: IOException) {
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf("error" to (error.message ?: "Comic couldn't be read")))
        } catch (error: Exception) {
            if (runAttemptCount < 2) Result.retry()
            else Result.failure(workDataOf("error" to (error.message ?: "Bubble indexing failed")))
        }
    }
}

object BubbleDetectionScheduler {
    fun enqueue(context: Context, comicId: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(comicId),
            ExistingWorkPolicy.KEEP,
            request(comicId)
        )
    }

    fun enqueueContinuation(context: Context, comicId: Long) {
        WorkManager.getInstance(context)
            .beginUniqueWork(
                workName(comicId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request(comicId)
            )
            .enqueue()
    }

    fun cancel(context: Context, comicId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(comicId))
    }

    private fun request(comicId: Long) = OneTimeWorkRequestBuilder<BubbleDetectionWorker>()
        .setInputData(workDataOf(COMIC_ID_KEY to comicId))
        .setConstraints(
            Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
        )
        .addTag(workName(comicId))
        .build()

    private fun workName(comicId: Long) = "$WORK_NAME_PREFIX$comicId"
}
