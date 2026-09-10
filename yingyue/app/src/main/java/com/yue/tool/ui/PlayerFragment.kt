package com.yue.tool.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yue.tool.R
import com.yue.tool.api.MusicApi
import com.yue.tool.databinding.FragmentPlayerBinding
import com.yue.tool.player.LrcLine
import com.yue.tool.player.LrcParser
import com.yue.tool.player.PlayerManager
import com.yue.tool.util.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 播放器页面：封面 + 逐行歌词 + 可拖动进度条 + 上一首/播放暂停/下一首
 */
class PlayerFragment : androidx.fragment.app.Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var lrcAdapter: LrcAdapter
    private val lrcLines = mutableListOf<LrcLine>()
    private var lrcJob: Job? = null
    private var lastLrcIndex = -1
    private var dragging = false
    private var autoScroll = true
    private var loadedKey: String? = null

    // 状态变化：切歌时刷新封面 + 重新加载歌词；仅播放/暂停时只刷新按钮
    private val stateListener = {
        if (_binding != null) {
            val track = PlayerManager.current
            val key = track?.let { "${it.source}:${it.id}" }
            if (key != loadedKey) {
                loadedKey = key
                renderState()
                if (track != null) loadLyrics()
            } else {
                renderState()
            }
        }
    }
    private val progressListener = { pos: Int, dur: Int ->
        if (_binding != null) renderProgress(pos, dur)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lrcAdapter = LrcAdapter { timeMs -> PlayerManager.seekTo(timeMs.toInt()) }
        binding.lrcList.layoutManager = LinearLayoutManager(requireContext())
        binding.lrcList.adapter = lrcAdapter
        binding.lrcList.setHasFixedSize(true)

        // 用户手动滚动歌词时暂停自动跟随，4 秒后恢复
        binding.lrcList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    autoScroll = false
                    viewLifecycleOwner.lifecycleScope.launch {
                        delay(4000)
                        autoScroll = true
                    }
                }
                return false
            }
        })

        binding.btnCollapse.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnPlayWrap.setOnClickListener {
            PlayerManager.toggleCurrent()
        }
        binding.btnNext.setOnClickListener { PlayerManager.playNext() }
        binding.btnPrev.setOnClickListener { PlayerManager.playPrev() }

        setupSeekBar()

        PlayerManager.addStateListener(stateListener)
        PlayerManager.addProgressListener(progressListener)
        // addStateListener 会立即回调一次 stateListener，完成首次渲染 + 歌词加载
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PlayerManager.removeStateListener(stateListener)
        PlayerManager.removeProgressListener(progressListener)
        lrcJob?.cancel()
        _binding = null
    }

    // ==================== UI ====================

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = PlayerManager.duration()
                    if (dur > 0) {
                        binding.textCurTime.text = formatTime((progress.toLong() * dur / 1000L).toInt())
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                val dur = PlayerManager.duration()
                if (dur > 0) {
                    PlayerManager.seekTo((sb.progress.toLong() * dur / 1000L).toInt())
                }
            }
        })
    }

    private fun renderState() {
        val track = PlayerManager.current ?: return
        binding.textTitle.text = track.name
        binding.textArtist.text = track.artist
        ImageLoader.load(binding.playerCover, track.coverUrl)

        binding.btnPlayToggle.setImageResource(
            if (PlayerManager.playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.btnNext.isEnabled = PlayerManager.hasNext
        binding.btnPrev.isEnabled = PlayerManager.hasPrev
        binding.btnNext.alpha = if (PlayerManager.hasNext) 1f else 0.35f
        binding.btnPrev.alpha = if (PlayerManager.hasPrev) 1f else 0.35f
    }

    private fun renderProgress(pos: Int, dur: Int) {
        if (!dragging) {
            if (dur > 0) {
                binding.seekBar.progress = (pos.toLong() * 1000L / dur).toInt()
                binding.textDurTime.text = formatTime(dur)
            }
            binding.textCurTime.text = formatTime(pos)
        }
        updateLrcHighlight(pos)
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
    }

    // ==================== 歌词 ====================

    private fun loadLyrics() {
        val track = PlayerManager.current ?: return
        lrcJob?.cancel()
        lastLrcIndex = -1
        lrcLines.clear()
        lrcAdapter.submit(emptyList())
        binding.lrcHint.visibility = View.VISIBLE
        binding.lrcHint.text = getString(R.string.player_lrc_loading)

        lrcJob = viewLifecycleOwner.lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching { MusicApi.fetchLyric(track) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { LrcParser.parse(it) }
            }
            if (_binding == null || PlayerManager.current?.id != track.id) return@launch
            if (parsed.isNullOrEmpty()) {
                binding.lrcHint.text = getString(R.string.player_lrc_empty)
                binding.lrcHint.visibility = View.VISIBLE
            } else {
                binding.lrcHint.visibility = View.GONE
                lrcLines.clear()
                lrcLines.addAll(parsed)
                lrcAdapter.submit(lrcLines)
            }
        }
    }

    private fun updateLrcHighlight(posMs: Int) {
        if (lrcLines.isEmpty()) return
        var idx = lastLrcIndex.coerceAtLeast(0)
        // 从上次位置向后/向前线性查找（列表已按时间排序）
        while (idx < lrcLines.size - 1 && lrcLines[idx + 1].timeMs <= posMs) idx++
        while (idx > 0 && lrcLines[idx].timeMs > posMs) idx--
        if (idx == lastLrcIndex) return
        lastLrcIndex = idx
        lrcAdapter.setCurrent(idx)
        if (autoScroll && _binding != null) {
            val lm = binding.lrcList.layoutManager as? LinearLayoutManager ?: return
            val offset = binding.lrcList.height / 2 - 60
            lm.scrollToPositionWithOffset(idx, offset)
        }
    }
}
