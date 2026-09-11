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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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

    /** 试听音质偏好（首页音质选择同步），换线时以此为首选档 */
    var preferredQuality: String = "128k"

    /** 播放彻底失败回调（智能换线全部失败后触发） */
    private var failCallback: (() -> Unit)? = null

    /** 播放中途出错的连续重试轮次（防病态线路导致无限循环） */
    private var errorRetryCount = 0

    private const val MAX_ERROR_RETRY = 2
    private const val STABLE_PLAY_RESET_MS = 10_000L

    /** 稳定播放一段时间后恢复重试额度 */
    private val resetRetryRunnable = Runnable { errorRetryCount = 0 }

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
            // 界面已解绑时停止轮询，避免空转耗电
            if (miniBar == null) return
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
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(resetRetryRunnable)
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
     * 开始播放一首歌：智能换线 → MediaPlayer → 播放
     * 失效重试顺序：本源多档音质（偏好档优先）→ 跨源同名歌曲匹配
     */
    fun startPlay(track: Track, onResolveFail: () -> Unit) {
        // 先停止当前
        stopInternal()

        // 新曲目重新计算中途失效重试额度
        handler.removeCallbacks(resetRetryRunnable)
        errorRetryCount = 0

        currentTrack = track
        failCallback = onResolveFail
        // 显示加载中状态
        miniBar?.visibility = View.VISIBLE
        miniName?.text = "解析中…"
        miniName?.isSelected = false
        miniArtist?.text = "${track.name} · ${track.artist}"
        miniBtnPlay?.text = "··"
        miniProgress?.progress = 0
        miniCover?.let { ImageLoader.load(it, track.coverUrl) }

        resolveJob = CoroutineScope(Dispatchers.Main).launch {
            val ok = playWithFailover(track)
            if (!ok) {
                resetPlayback()
                failCallback?.invoke()
            }
        }
    }

    /**
     * 智能换线主流程。
     * 阶段一：本源音质阶梯（preferred → 128k → 320k → 740k）逐条尝试；
     * 阶段二：本源全失效时，用「歌名+歌手」在其他音源搜索同名歌曲并逐个尝试。
     * @return true 表示已成功起播
     */
    private suspend fun playWithFailover(track: Track): Boolean {
        // 阶段一：本源多档线路
        val ladder = listOf(preferredQuality, "128k", "320k", "740k").distinct()
        ladder.forEachIndexed { i, q ->
            currentCoroutineContext().ensureActive()
            // 首选档失败后提示切换中
            if (i > 0) setSwitchHint("线路失效，切换中…")
            val resolved = withContext(Dispatchers.IO) {
                runCatching { com.yue.tool.api.MusicApi.resolveUrl(track, q) }.getOrNull()
            } ?: return@forEachIndexed
            if (prepareAndPlay(resolved.url, track.id)) return true
        }

        // 阶段二：跨源换源
        currentCoroutineContext().ensureActive()
        setSwitchHint("本源不可用，切换其他音源…")
        val others = listOf("netease", "joox", "kuwo").filter { it != track.source }
        for (src in others) {
            currentCoroutineContext().ensureActive()
            val candidates = withContext(Dispatchers.IO) {
                runCatching {
                    com.yue.tool.api.MusicApi.search("${track.name} ${track.artist}", listOf(src), 1)
                }.getOrDefault(emptyList())
            }.filter { nameMatches(it.name, track.name) }.take(2)
            for (cand in candidates) {
                for (q in listOf("128k", "320k")) {
                    currentCoroutineContext().ensureActive()
                    val resolved = withContext(Dispatchers.IO) {
                        runCatching {
                            com.yue.tool.api.MusicApi.resolveUrl(cand, q)
                        }.getOrNull()
                    } ?: continue
                    if (prepareAndPlay(resolved.url, track.id)) return true
                }
            }
        }
        return false
    }

    /**
     * 起播单条线路：prepareAsync 成功即开始播放并返回 true；
     * prepare 失败返回 false（换下一条线路）；播放中途出错则重新走换线流程。
     */
    private suspend fun prepareAndPlay(url: String, trackId: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            player = mp
            try {
                // 熄屏后保持 CPU 唤醒，播放不中断
                appContext?.let { mp.setWakeMode(it, PowerManager.PARTIAL_WAKE_LOCK) }
                mp.setDataSource(url)
                mp.setOnCompletionListener { stopInternal() }
                mp.setOnErrorListener { _, _, _ ->
                    releasePlayerQuiet(mp)
                    if (cont.isActive) {
                        // prepare 阶段失败：换下一条线路
                        isPlaying = false
                        cont.resume(false)
                    } else {
                        // 播放中途失效：重新走智能换线
                        retryCurrentAfterError()
                    }
                    true
                }
                mp.setOnPreparedListener { p ->
                    if (cont.isActive) {
                        p.start()
                        isPlaying = true
                        // 稳定播放一段时间后恢复重试额度
                        handler.removeCallbacks(resetRetryRunnable)
                        handler.postDelayed(resetRetryRunnable, STABLE_PLAY_RESET_MS)
                        updateUI()
                        onStateChange?.invoke(trackId, true)
                        // 启动前台服务：通知栏控制 + 后台保活
                        startPlaybackService()
                        onNotifUpdate?.invoke()
                        cont.resume(true)
                    }
                }
                mp.prepareAsync()
            } catch (_: Exception) {
                releasePlayerQuiet(mp)
                isPlaying = false
                if (cont.isActive) cont.resume(false)
            }
            // 协程被取消（用户切歌/停止）时释放播放器
            cont.invokeOnCancellation { releasePlayerQuiet(mp) }
        }

    /** 播放中途线路失效：以当前曲目重新走换线流程（有重试上限防死循环） */
    private fun retryCurrentAfterError() {
        val track = currentTrack ?: return
        if (errorRetryCount >= MAX_ERROR_RETRY) {
            // 连续多轮中途失效：判定整首歌当前不可用，彻底放弃
            resetPlayback()
            failCallback?.invoke()
            return
        }
        errorRetryCount++
        isPlaying = false
        onStateChange?.invoke(track.id, false)
        resolveJob?.cancel()
        resolveJob = CoroutineScope(Dispatchers.Main).launch {
            val ok = playWithFailover(track)
            if (!ok) {
                resetPlayback()
                failCallback?.invoke()
            }
        }
    }

    /** 换线期间在迷你栏提示状态（成功起播后 updateUI 会恢复歌名） */
    private fun setSwitchHint(msg: String) {
        miniBar?.post {
            if (currentTrack != null) {
                miniName?.text = msg
                miniName?.isSelected = false
                miniBtnPlay?.text = "··"
            }
        }
    }

    /** 歌名模糊匹配（跨源换源用）：去空白/标点后互相包含即视为同一首 */
    private fun nameMatches(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase()
            .replace(Regex("[\\s()（）\\[\\]【】-—_·.,，。!！?？:：'\"]"), "")
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x.contains(y) || y.contains(x)
    }

    /** 安全释放指定播放器实例 */
    private fun releasePlayerQuiet(mp: MediaPlayer?) {
        mp ?: return
        try { mp.reset() } catch (_: Exception) {
        }
        try { mp.release() } catch (_: Exception) {
        }
        if (player === mp) player = null
    }

    /** 归位状态：释放播放器、清空曲目、更新 UI 与服务（不取消协程） */
    private fun resetPlayback() {
        handler.removeCallbacks(resetRetryRunnable)
        releasePlayerQuiet(player)
        player = null
        isPlaying = false
        currentTrack = null
        updateUI()
        onStateChange?.invoke(null, false)
        // 播放结束：移除常驻通知并停止服务
        stopPlaybackService()
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
        resetPlayback()
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
