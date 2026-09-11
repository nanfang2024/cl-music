package com.yue.tool.player

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.yue.tool.api.Track
import com.yue.tool.util.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局播放器管理器：单例，跨 Fragment 生命周期存活
 * 持有 MediaPlayer + 当前曲目 + UI 绑定
 * 配合 PlaybackService 前台服务实现熄屏/后台持续播放
 */
object PlayerManager {

    private var appContext: Context? = null
    private var player: MediaPlayer? = null
    private var currentTrack: Track? = null
    private var isPlaying = false
    private var resolveJob: Job? = null

    /** 服务通知刷新回调（PlaybackService 注册） */
    var onNotifUpdate: (() -> Unit)? = null

    /** Application 启动时初始化，用于启动/停止前台服务 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // UI 绑定（由 MainActivity 设置）
    private var miniBar: View? = null
    private var miniCover: ImageView? = null
    private var miniName: TextView? = null
    private var miniArtist: TextView? = null
    private var miniBtnPlay: TextView? = null
    private var miniProgress: ProgressBar? = null

    // 进度刷新
    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            val mp = player
            if (mp != null && isPlaying) {
                try {
                    val dur = mp.duration
                    if (dur > 0) {
                        miniProgress?.progress = (mp.currentPosition * 100 / dur)
                    }
                } catch (_: IllegalStateException) {
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    // 外部回调：播放状态变化时通知（如 TrackAdapter 更新图标）
    var onStateChange: ((trackId: String?, isPlaying: Boolean) -> Unit)? = null

    fun bind(
        bar: View,
        cover: ImageView,
        name: TextView,
        artist: TextView,
        btnPlay: TextView,
        progress: ProgressBar
    ) {
        miniBar = bar
        miniCover = cover
        miniName = name
        miniArtist = artist
        miniBtnPlay = btnPlay
        miniProgress = progress

        btnPlay.setOnClickListener {
            if (isPlaying) pause() else resume()
        }

        updateUI()
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    fun unbind() {
        miniBar = null
        miniCover = null
        miniName = null
        miniArtist = null
        miniBtnPlay = null
        miniProgress = null
    }

    fun getCurrentTrackId(): String? = currentTrack?.id
    fun getCurrentTrack(): Track? = currentTrack
    fun isPlaying(): Boolean = isPlaying

    /** 通知栏「播放/暂停」按钮 */
    fun toggleCurrent() {
        if (isPlaying) pause() else resume()
    }

    /** 启动前台服务（播放成功后调用） */
    private fun startPlaybackService() {
        val ctx = appContext ?: return
        try {
            val intent = Intent(ctx, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (_: Exception) {
        }
    }

    /** 停止前台服务（停止播放时调用） */
    private fun stopPlaybackService() {
        val ctx = appContext ?: return
        try {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        } catch (_: Exception) {
        }
    }

    /**
     * 开始播放一首歌：解析链接 → MediaPlayer → 播放
     */
    fun startPlay(track: Track, onResolveFail: () -> Unit) {
        // 先停止当前
        stopInternal()

        currentTrack = track
        // 显示加载中状态
        miniBar?.visibility = View.VISIBLE
        miniName?.text = "解析中…"
        miniName?.isSelected = false
        miniArtist?.text = "${track.name} · ${track.artist}"
        miniBtnPlay?.text = "··"
        miniProgress?.progress = 0
        miniCover?.let { ImageLoader.load(it, track.coverUrl) }

        resolveJob = CoroutineScope(Dispatchers.Main).launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching {
                    com.yue.tool.api.MusicApi.resolveUrl(track, "128k")
                }.getOrNull()
            }
            if (resolved == null) {
                onResolveFail()
                stopInternal()
                return@launch
            }
            try {
                val mp = MediaPlayer()
                player = mp
                // 熄屏后保持 CPU 唤醒，播放不中断
                appContext?.let { mp.setWakeMode(it, PowerManager.PARTIAL_WAKE_LOCK) }
                mp.setDataSource(resolved.url)
                mp.setOnCompletionListener { stopInternal() }
                mp.setOnErrorListener { _, _, _ ->
                    stopInternal()
                    true
                }
                mp.setOnPreparedListener {
                    it.start()
                    isPlaying = true
                    updateUI()
                    onStateChange?.invoke(currentTrack?.id, true)
                    // 启动前台服务：通知栏控制 + 后台保活
                    startPlaybackService()
                    onNotifUpdate?.invoke()
                }
                mp.prepareAsync()
            } catch (_: Exception) {
                stopInternal()
                onResolveFail()
            }
        }
    }

    fun pause() {
        player?.let { mp ->
            try {
                if (mp.isPlaying) mp.pause()
            } catch (_: Exception) {
            }
        }
        isPlaying = false
        updateUI()
        onStateChange?.invoke(currentTrack?.id, false)
        onNotifUpdate?.invoke()
    }

    fun resume() {
        player?.let { mp ->
            try {
                mp.start()
                isPlaying = true
                updateUI()
                onStateChange?.invoke(currentTrack?.id, true)
                onNotifUpdate?.invoke()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 切换播放/暂停（外部调用，如 TrackAdapter 点击同一首歌）
     * 返回 true 表示已处理，false 表示需要 startPlay
     */
    fun togglePlay(track: Track): Boolean {
        if (currentTrack?.id == track.id) {
            if (isPlaying) pause() else resume()
            return true
        }
        return false
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        resolveJob?.cancel()
        resolveJob = null
        player?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Exception) {
            }
            try {
                mp.release()
            } catch (_: Exception) {
            }
        }
        player = null
        isPlaying = false
        currentTrack = null
        updateUI()
        onStateChange?.invoke(null, false)
        // 播放结束：移除常驻通知并停止服务
        stopPlaybackService()
    }

    private fun updateUI() {
        miniBar?.post {
            val track = currentTrack
            if (track == null) {
                miniBar?.visibility = View.GONE
                miniProgress?.progress = 0
            } else {
                miniBar?.visibility = View.VISIBLE
                miniName?.text = track.name
                miniName?.isSelected = true  // 跑马灯需要 selected 状态
                miniArtist?.text = track.artist
                miniBtnPlay?.text = if (isPlaying) "‖" else "▶"
                miniCover?.let {
                    if (track.coverUrl != null) {
                        ImageLoader.load(it, track.coverUrl)
                    }
                }
            }
        }
    }
}
