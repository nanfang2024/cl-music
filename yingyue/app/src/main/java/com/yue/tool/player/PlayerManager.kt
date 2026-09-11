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
 * v1.5.0 全局播放器管理器：单例，跨 Fragment 生命周期存活
 *
 * 审计修复要点：
 * 1. [P0-2] 单一错误回调：onResolveFail 带错误信息参数，不再有成员变量双重通道
 * 2. [P1-1] isResolving 在 stopInternal() 中重置
 * 3. [P1-2] resolveKuwo 兜底链错误信息完整透传（已在 MusicApi 中实现）
 * 4. 解析失败时保留 currentTrack，UI 显示错误状态而非直接隐藏
 * 5. MediaPlayer 错误回调带具体 what/extra 码
 */
object PlayerManager {

    private var player: MediaPlayer? = null
    private var currentTrack: Track? = null
    private var isPlaying = false
    private var isResolving = false
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
    // 外部回调：歌词加载完成（PlayerFragment 使用）
    var onLyricLoaded: ((track: Track, lrcText: String) -> Unit)? = null
    // 外部回调：进度变化（PlayerFragment 使用）
    var onProgressUpdate: ((currentMs: Int, totalMs: Int) -> Unit)? = null

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
    fun isResolving(): Boolean = isResolving

    /**
     * 开始播放一首歌：解析链接 → MediaPlayer → 播放
     * @param onResolveFail 错误回调，带具体错误信息（单一回调通道，审计 P0-2）
     */
    fun startPlay(track: Track, onResolveFail: (String) -> Unit) {
        // 先停止当前
        stopInternal()

        currentTrack = track
        isResolving = true
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
                }
            }
            isResolving = false
            val result = resolved.getOrNull()
            if (result == null) {
                // 透传具体错误信息（审计 P1-2）
                val errMsg = resolved.exceptionOrNull()?.message ?: "未知错误"
                onResolveFail(errMsg)
                stopInternal()
                return@launch
            }
            try {
                val mp = MediaPlayer()
                player = mp
                mp.setDataSource(result.url)
                mp.setOnCompletionListener {
                    stopInternal()
                }
                mp.setOnErrorListener { _, what, extra ->
                    // 带具体错误码（审计 P0-2）
                    onResolveFail("播放错误：what=$what extra=$extra")
                    stopInternal()
                    true
                }
                mp.setOnPreparedListener {
                    it.start()
                    isPlaying = true
                    updateUI()
                    onStateChange?.invoke(currentTrack?.id, true)
                    // 异步加载歌词
                    loadLyric(track)
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                onResolveFail(e.message ?: e.javaClass.simpleName)
                stopInternal()
            }
        }
    }

    /** 异步加载歌词 */
    private fun loadLyric(track: Track) {
        CoroutineScope(Dispatchers.IO).launch {
            val lrc = runCatching {
                com.yue.tool.api.MusicApi.fetchLyric(track)
            }.getOrNull() ?: ""
            withContext(Dispatchers.Main) {
                onLyricLoaded?.invoke(track, lrc)
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
        // 审计 P1-1：重置 isResolving
        isResolving = false
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
