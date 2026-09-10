package com.yue.tool.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yue.tool.MainActivity
import com.yue.tool.R

/**
 * 前台播放服务：保证熄屏/切后台时 MediaPlayer 不被系统回收，
 * 并提供常驻通知栏控制（上一首 / 播放暂停 / 下一首 / 关闭）
 */
class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        PlayerManager.addStateListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> PlayerManager.toggleCurrent()
            ACTION_NEXT -> PlayerManager.playNext()
            ACTION_PREV -> PlayerManager.playPrev()
            ACTION_STOP -> PlayerManager.stop()
        }
        if (PlayerManager.isAlive()) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        PlayerManager.removeStateListener(stateListener)
        super.onDestroy()
    }

    /** 播放状态变化时静默刷新通知（不重复进前台） */
    private val stateListener: () -> Unit = {
        if (PlayerManager.isAlive()) {
            notifyQuietly()
        }
    }

    private fun notifyQuietly() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val track = PlayerManager.current
        val playing = PlayerManager.playing

        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun actionPi(action: String, requestCode: Int): PendingIntent =
            PendingIntent.getService(
                this, requestCode,
                Intent(this, PlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_moon)
            .setContentTitle(track?.name ?: getString(R.string.app_name))
            .setContentText(track?.artist ?: "")
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_prev, getString(R.string.notif_action_prev), actionPi(ACTION_PREV, 1))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.notif_action_toggle_pause else R.string.notif_action_toggle_play),
                actionPi(ACTION_TOGGLE, 2)
            )
            .addAction(R.drawable.ic_next, getString(R.string.notif_action_next), actionPi(ACTION_NEXT, 3))
            .addAction(R.drawable.ic_stop, getString(R.string.notif_action_close), actionPi(ACTION_STOP, 4))
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 1001
        const val ACTION_TOGGLE = "com.yue.tool.playback.TOGGLE"
        const val ACTION_NEXT = "com.yue.tool.playback.NEXT"
        const val ACTION_PREV = "com.yue.tool.playback.PREV"
        const val ACTION_STOP = "com.yue.tool.playback.STOP"
    }
}
