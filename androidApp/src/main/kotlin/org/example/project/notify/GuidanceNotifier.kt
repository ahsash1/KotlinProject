package org.example.project.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Plain ongoing notification shown while guidance is active, so it can never
 * run silently in the background. Not a true foreground service: avoids the
 * foregroundServiceType churn on newer Android for what only needs to be a
 * visible, dismiss-proof indicator.
 */
class GuidanceNotifier(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Guidance active",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while the app is pointing out steps on screen."
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun showActive(taskTitle: String) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Guiding: $taskTitle")
                .setContentText("Guidance is active. Open the app to stop.")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            // Notification permission may be missing (API 33+); guidance still works.
        }
    }

    fun cancel() {
        try {
            manager.cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            // Nothing to do.
        }
    }

    companion object {
        private const val CHANNEL_ID = "guidance_active"
        private const val NOTIFICATION_ID = 1001
    }
}
