package com.comicreader.app.data.panel

import android.content.Context
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
private const val WORK_NAME_PREFIX = "panel-detection-"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PanelDetectionWorkerEntryPoint {
    fun comicRepository(): ComicRepository
    fun panelDetector(): PanelDetector
}

/** A small batch worker. Additional batches are appended so no single job runs for too long. */
class PanelDetectionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val comicId = inputData.getLong(COMIC_ID_KEY, -1L)
        if (comicId <= 0) return Result.failure()

        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            PanelDetectionWorkerEntryPoint::class.java
        )

        return try {
            val hasMore = dependencies.comicRepository().detectNextPanelBatch(
                comicId = comicId,
                detector = dependencies.panelDetector()
            )
            if (hasMore) PanelDetectionScheduler.enqueueContinuation(applicationContext, comicId)
            Result.success()
        } catch (error: SecurityException) {
            Result.failure(workDataOf("error" to (error.message ?: "Comic permission was lost")))
        } catch (error: IOException) {
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf("error" to (error.message ?: "Comic couldn't be read")))
        } catch (error: Exception) {
            if (runAttemptCount < 2) Result.retry()
            else Result.failure(workDataOf("error" to (error.message ?: "Panel analysis failed")))
        }
    }
}

object PanelDetectionScheduler {
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

    private fun request(comicId: Long) = OneTimeWorkRequestBuilder<PanelDetectionWorker>()
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