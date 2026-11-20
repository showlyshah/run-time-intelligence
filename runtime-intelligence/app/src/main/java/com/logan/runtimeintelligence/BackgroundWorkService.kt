package com.logan.runtimeintelligence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps user-visible APK processing work in Android's foreground-service category.
 * This raises process importance but cannot make the process unkillable.
 */
class BackgroundWorkService : Service() {
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background processing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows when Runtime Intelligence is processing an APK"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "Processing APK in background"
        val notification = buildNotification(message)
        if (intent?.action == ACTION_UPDATE) {
            notifications.notify(NOTIFICATION_ID, notification)
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Runtime Intelligence is working")
            .setContentText(message.take(120))
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.logan.runtimeintelligence.action.START_BACKGROUND_WORK"
        const val ACTION_STOP = "com.logan.runtimeintelligence.action.STOP_BACKGROUND_WORK"
        const val ACTION_UPDATE = "com.logan.runtimeintelligence.action.UPDATE_BACKGROUND_WORK"
        const val EXTRA_MESSAGE = "background_work_message"
        private const val CHANNEL_ID = "runtime_intelligence_background"
        private const val NOTIFICATION_ID = 7314

        fun start(context: android.content.Context, message: String) {
            val intent = Intent(context, BackgroundWorkService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MESSAGE, message)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun update(context: android.content.Context, message: String) {
            val intent = Intent(context, BackgroundWorkService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_MESSAGE, message)
            }
            context.startService(intent)
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, BackgroundWorkService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
