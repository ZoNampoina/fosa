package com.arizona.fosa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class FosaForegroundService : Service() {
  companion object {
    const val ACTION_START = "com.arizona.fosa.START_PERSISTENT"
    const val ACTION_STOP = "com.arizona.fosa.STOP_PERSISTENT"
    private const val CHANNEL_ID = "fosa_live"
    private const val NOTIFICATION_ID = 804
  }

  override fun onCreate() {
    super.onCreate()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "FOSA Live",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Maintient FOSA actif pendant une session live."
        setShowBadge(false)
      }
      getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    val openIntent = Intent(this, MainActivity::class.java).apply {
      this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val openPending = PendingIntent.getActivity(
      this, 1, openIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val stopIntent = Intent(this, FosaForegroundService::class.java).apply { action = ACTION_STOP }
    val stopPending = PendingIntent.getService(
      this, 2, stopIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }
    val notification = builder
      .setSmallIcon(R.drawable.fosa_logo)
      .setContentTitle("FOSA · LIVE actif")
      .setContentText("Talkback local maintenu en arrière-plan")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(Notification.CATEGORY_SERVICE)
      .setContentIntent(openPending)
      .addAction(Notification.Action.Builder(null, "OUVRIR", openPending).build())
      .addAction(Notification.Action.Builder(null, "ARRÊTER", stopPending).build())
      .build()

    if (Build.VERSION.SDK_INT >= 34) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
