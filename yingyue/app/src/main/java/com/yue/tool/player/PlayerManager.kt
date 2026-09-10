package com.yue.tool.player

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.yue.tool.api.MusicApi
import com.yue.tool.api.Track
import com.yue.tool.util.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局播放器管理器：单例，跨 Fragment 生命周期存活
 * - 持有 MediaPlayer + 播放队列 + 音频焦点
 * - 配合 PlaybackService 前台服务实现熄屏/后台持续播放
 * - UI（迷你条 / 播放器页）通过 bindMini / 监听器 获取状态
 */
object PlayerManager {

    private var appContext: Context? = null
    private var player: MediaPlayer? = null
    private var currentTrack: Track? = null
    private var isPlaying = false
    private var isResolving = false
    private var resolveJob: Job? = null

    // 播放队列（来自搜索结果列表）
    private var queue: List<Track> = emptyList()
    private var queueIndex = -1

    // 音频焦点
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    // ==================== 监听器 ====================

    private val stateListeners = CopyOnWriteArrayList<() -> Unit>()
    private val progressListeners = CopyOnWriteArrayList<(Int, Int) -> Unit>()

    fun addStateListener(l: () -> Unit) {
        stateListeners.addIfAbsent(l)
        l()
    }

    fun removeStateListener(l: () -> Unit) {
        stateListeners.remove(l)
    }

    fun addProgressListener(l: (Int, Int) -> Unit) {
        progressListeners.addIfAbsent(l)
    }

    fun removeProgressListener(l: (Int, Int) -> Unit) {
        progressListeners.remove(l)
    }

    // 兼容旧接口：TrackAdapter 用
    var onStateChange: ((trackId: String?, isPlaying: Boolean) -> Unit)? = null

    // 解析失败回调（HomeFragment 设置用于 toast）
    var onResolveFail: (() -> Unit)? = null

    // ==================== 生命周期 ====================

    fun init(context: Context) {
        appContext = context.applicationContext
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        handler.post(progressRunnable)
    }

    // 进度刷新（300ms 推送一次）
    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (player != null && currentTrack != null) {
                pushProgress()
            }
            handler.postDelayed(this, 300)
        }
    }

    private fun pushProgress() {
        val pos = position()
        val dur = duration()
        progressListeners.forEach { it(pos, dur) }
        miniProgress?.let { p ->
            if (dur > 0) p.progress = pos * 100 / dur else p.progress = 0
        }
    }

    // ==================== 状态查询 ====================

    val current: Track? get() = currentTrack
    val playing: Boolean get() = isPlaying
    val resolving: Boolean get() = isResolving

    val hasNext: Boolean get() = queueIndex in queue.indices && queueIndex < queue.size - 1
    val hasPrev: Boolean get() = queueIndex > 0

    fun isAlive(): Boolean = currentTrack != null || isResolving

    fun getCurrentTrackId(): String? = currentTrack?.id

    fun position(): Int = try {
        if (player != null) player!!.currentPosition else 0
    } catch (_: Exception) {
        0
    }

    fun duration(): Int = try {
        if (player != null) player!!.duration else 0
    } catch (_: Exception) {
        0
    }

    // ==================== 播放控制 ====================

    /**
     * 开始播放一首歌
     * @param queue 搜索结果列表作为播放队列（用于上一首/下一首）
     */
    fun startPlay(track: Track, queue: List<Track> = emptyList(), onFail: () -> Unit = {}) {
        onResolveFail = onFail

        if (queue.isNotEmpty()) {
            this.queue = queue
            queueIndex = queue.indexOfFirst { it.id == track.id && it.source == track.source }
            if (queueIndex < 0) {
                this.queue = listOf(track)
                queueIndex = 0
            }
        } else {
            this.queue = listOf(track)
            queueIndex = 0
        }

        // 停掉当前播放（保留队列与 currentTrack 逻辑由下方重设）
        releasePlayer()

        currentTrack = track
        isResolving = true
        isPlaying = false
        showMiniResolving(track)
        notifyState()

        requestAudioFocus()

        resolveJob = CoroutineScope(Dispatchers.Main).launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching { MusicApi.resolveUrl(track, "128k") }.getOrNull()
            }
            isResolving = false
            if (resolved == null) {
                resetToIdle()
                onResolveFail?.invoke()
                return@launch
            }
            // 解析期间被新的 startPlay / stop 取代
            if (currentTrack !== track) return@launch
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                // 熄屏后保持 CPU 唤醒以持续缓冲/解码
                appContext?.let { ctx ->
                    mp.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
                }
                mp.setDataSource(resolved.url)
                mp.setOnPreparedListener { prepared ->
                    if (player === prepared && currentTrack === track) {
                        prepared.start()
                        isPlaying = true
                        notifyState()
                        startService()
                    }
                }
                mp.setOnCompletionListener {
                    // 当前歌播完 → 队列下一首；没有下一首则停止
                    if (hasNext) playNext() else resetToIdle()
                }
                mp.setOnErrorListener { _, _, _ ->
                    resetToIdle()
                    onResolveFail?.invoke()
                    true
                }
                player = mp
                mp.prepareAsync()
            } catch (_: Exception) {
                resetToIdle()
                onResolveFail?.invoke()
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
        notifyState()
    }

    fun resume() {
        if (player == null) return
        try {
            requestAudioFocus()
            player!!.start()
            isPlaying = true
            notifyState()
        } catch (_: Exception) {
        }
    }

    /** 切换播放/暂停（当前有歌时） */
    fun toggleCurrent(): Boolean {
        if (currentTrack == null) return false
        if (isPlaying) pause() else resume()
        return true
    }

    /** 兼容旧接口：点击当前歌 = 暂停/继续 */
    fun togglePlay(track: Track): Boolean {
        if (currentTrack?.id == track.id && currentTrack?.source == track.source) {
            return toggleCurrent()
        }
        return false
    }

    fun playNext() {
        if (hasNext) startPlay(queue[queueIndex + 1], queue, onResolveFail ?: {})
    }

    fun playPrev() {
        if (hasPrev) startPlay(queue[queueIndex - 1], queue, onResolveFail ?: {})
    }

    fun seekTo(ms: Int) {
        player?.let { mp ->
            try {
                mp.seekTo(ms)
            } catch (_: Exception) {
            }
        }
        pushProgress()
    }

    fun stop() {
        resetToIdle()
    }

    // ==================== 内部 ====================

    private fun releasePlayer() {
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
    }

    /** 完全空闲：无当前曲目，隐藏迷你条，停掉前台服务 */
    private fun resetToIdle() {
        releasePlayer()
        isResolving = false
        isPlaying = false
        currentTrack = null
        queue = emptyList()
        queueIndex = -1
        abandonAudioFocus()
        stopService()
        notifyState()
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        try {
            if (focusRequest == null) {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { change ->
                        if (change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        ) {
                            pause()
                        }
                    }
                    .build()
            }
            am.requestAudioFocus(focusRequest!!)
        } catch (_: Exception) {
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {
        }
    }

    private fun startService() {
        val ctx = appContext ?: return
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun stopService() {
        val ctx = appContext ?: return
        try {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun notifyState() {
        miniBar?.post { updateMini() }
        stateListeners.forEach { it() }
        onStateChange?.invoke(currentTrack?.id, isPlaying)
    }

    // ==================== 迷你播放器绑定 ====================

    private var miniBar: View? = null
    private var miniCover: ImageView? = null
    private var miniName: TextView? = null
    private var miniArtist: TextView? = null
    private var miniBtnPlay: TextView? = null
    private var miniProgress: ProgressBar? = null

    fun bindMini(
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
            toggleCurrent()
        }

        updateMini()
    }

    fun unbindMini() {
        miniBar = null
        miniCover = null
        miniName = null
        miniArtist = null
        miniBtnPlay = null
        miniProgress = null
    }

    private fun showMiniResolving(track: Track) {
        miniBar?.post {
            miniBar?.visibility = View.VISIBLE
            miniName?.text = "解析中…"
            miniName?.isSelected = false
            miniArtist?.text = track.name
            miniBtnPlay?.text = "··"
            miniProgress?.progress = 0
            miniCover?.let { ImageLoader.load(it, track.coverUrl) }
        }
    }

    private fun updateMini() {
        val bar = miniBar ?: return
        val track = currentTrack
        if (track == null) {
            bar.visibility = View.GONE
            miniProgress?.progress = 0
        } else {
            bar.visibility = View.VISIBLE
            miniName?.text = track.name
            miniName?.isSelected = true
            miniArtist?.text = track.artist
            miniBtnPlay?.text = when {
                isResolving -> "··"
                isPlaying -> "‖"
                else -> "▶"
            }
            miniCover?.let {
                if (track.coverUrl != null) ImageLoader.load(it, track.coverUrl)
            }
        }
    }
}
