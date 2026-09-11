package com.yue.tool.player

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
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
 */
object PlayerManager {

    private var player: MediaPlayer? = null
    private var currentTrack: Track? = null
    private var isPlaying = false
    private var resolveJob: Job? = null

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
    }

    fun resume() {
        player?.let { mp ->
            try {
                mp.start()
                isPlaying = true
                updateUI()
                onStateChange?.invoke(currentTrack?.id, true)
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
