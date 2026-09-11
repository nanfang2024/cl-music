package com.yue.tool.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * v1.5.0 播放前台服务
 * 保证后台播放时 MediaPlayer 不被系统回收
 *
 * 审计 P1-3：
 * - 正确创建 NotificationChannel（Android 8.0+）
 * - 声明 FOREGROUND_SERVICE 权限
 * - 服务生命周期与 PlayerManager 联动
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "yingyue_playback"
        const val CHANNEL_NAME = "播放服务"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.yue.tool.START_PLAYBACK"
        const val ACTION_STOP = "com.yue.tool.STOP_PLAYBACK"

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // 启动前台通知
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "映月音乐播放服务"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val track = PlayerManager.getCurrentTrack()
        val title = track?.name ?: "映月"
        val artist = track?.artist ?: "月下听歌"
        val isPlaying = PlayerManager.isPlaying()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
