package com.imperialreader.data.cover

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.imperialreader.util.app
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class AutomaticCoverRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.app.prefs
        if (!prefs.automaticCoverRefresh) return Result.success()

        val entries = applicationContext.app.db.mangaDao().getFavoritesList()
        var failures = 0
        for (entry in entries) {
            try {
                applicationContext.app.repo.refreshCover(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "Could not refresh cover for '${entry.title}'", e)
            }
        }

        if (failures > 0) {
            if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            Log.w(TAG, "Cover refresh finished with $failures failed entries")
            return Result.failure()
        }

        applicationContext.app.prefs.automaticCoverRefreshAt = System.currentTimeMillis()
        return Result.success()
    }

    companion object {
        private const val TAG = "CoverRefreshWorker"
        private const val MAX_ATTEMPTS = 3
    }
}

object AutomaticCoverRefreshScheduler {
    private const val UNIQUE_NAME = "imperial_reader_automatic_cover_refresh_on_app_start"
    private const val LEGACY_PERIODIC_NAME = "imperial_reader_automatic_cover_refresh"

    fun onAppStart(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.app.prefs
        val workManager = WorkManager.getInstance(appContext)
        workManager.cancelUniqueWork(LEGACY_PERIODIC_NAME)
        if (!prefs.automaticCoverRefresh) return

        val intervalMillis = TimeUnit.MINUTES.toMillis(prefs.automaticCoverRefreshIntervalMinutes)
        val elapsedMillis = System.currentTimeMillis() - prefs.automaticCoverRefreshAt
        if (intervalMillis > 0L && elapsedMillis < intervalMillis) return

        val request = OneTimeWorkRequestBuilder<AutomaticCoverRefreshWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(UNIQUE_NAME)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun onSettingChanged(context: Context, enabled: Boolean) {
        if (!enabled) {
            WorkManager.getInstance(context.applicationContext).apply {
                cancelUniqueWork(UNIQUE_NAME)
                cancelUniqueWork(LEGACY_PERIODIC_NAME)
            }
        }
    }
}
