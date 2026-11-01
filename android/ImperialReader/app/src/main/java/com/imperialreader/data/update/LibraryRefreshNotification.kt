package com.imperialreader.data.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.imperialreader.R

object LibraryRefreshNotification {
    private const val CHANNEL_ID = "library_refresh"
    private const val NOTIFICATION_ID = 1002
    private var lastDone = 0
    private var currentTotal = 0

    @Synchronized
    fun show(context: Context, done: Int, total: Int) {
        ensureChannel(context)
        if (done == 0 || total != currentTotal) {
            lastDone = 0
            currentTotal = total
        }
        val reportedDone = if (total > 0) maxOf(done.coerceIn(0, total), lastDone) else 0
        lastDone = reportedDone
        val percent = if (total > 0) reportedDone * 100 / total else 0
        val text = if (total > 0) {
            "$percent% · $reportedDone of $total entries"
        } else {
            "Preparing library refresh…"
        }
        val notification = notification(context, text, total, reportedDone)
        manager(context).notify(NOTIFICATION_ID, notification)
    }

    @Synchronized
    fun clear(context: Context) {
        manager(context).cancel(NOTIFICATION_ID)
        lastDone = 0
        currentTotal = 0
    }

    private fun notification(context: Context, text: String, total: Int, done: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_updates)
            .setContentTitle("Refreshing library")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total <= 0)
            .build()

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager(context).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Library refresh", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
