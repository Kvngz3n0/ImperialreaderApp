package com.imperialreader.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.imperialreader.util.app
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class AutomaticBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val count = BackupManager(applicationContext).createAutomaticBackup()
            applicationContext.app.prefs.automaticBackupAt = System.currentTimeMillis()
            applicationContext.app.prefs.automaticBackupStatus = "Saved $count library entries"
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            applicationContext.app.prefs.automaticBackupStatus =
                "Backup failed: ${e.message.orEmpty().take(100)}"
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

object AutomaticBackupScheduler {
    private const val UNIQUE_NAME = "imperial_reader_automatic_library_backup"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AutomaticBackupWorker>(12, TimeUnit.HOURS)
            .addTag(UNIQUE_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
