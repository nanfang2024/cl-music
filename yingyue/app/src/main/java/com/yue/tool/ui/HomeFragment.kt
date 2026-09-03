package com.yue.tool.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yue.tool.R
import com.yue.tool.api.MusicApi
import com.yue.tool.api.Track
import com.yue.tool.data.DownloadHistory
import com.yue.tool.data.DownloadRecord
import com.yue.tool.databinding.DialogDownloadBinding
import com.yue.tool.databinding.FragmentHomeBinding
import com.yue.tool.download.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TrackAdapter
    private val tracks = mutableListOf<Track>()

    private var player: MediaPlayer? = null
    private var playingTrackId: String? = null
    private var playerJob: Job? = null

    private var searchJob: Job? = null
    private var currentPage = 1
    private var lastKeyword = ""
    private var isLoadingMore = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast("未授予存储权限，Android 8/9 无法保存文件")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = TrackAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onDownload = { startDownload(it) },
            onPlay = { togglePlay(it) }
        )
        binding.listTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.listTracks.adapter = adapter

        binding.btnSearch.setOnClickListener { doSearch() }
        // Critical #5: 搜索键 IME 回调
        binding.inputKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }

        // Major #7: 滚动到底部加载下一页
        binding.listTracks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingMore) return
                val lm = rv.layoutManager as LinearLayoutManager
                val total = lm.itemCount
                val last = lm.findLastVisibleItemPosition()
                if (total - last <= 3) loadMore()
            }
        })

        // 首页默认选中第一个音源（芸朵），不选全部
        binding.chipNetease.isChecked = true
        binding.chipJoox.isChecked = false
        binding.chipKuwo.isChecked = false

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPlayer()
        searchJob?.cancel()
        _binding = null
    }

    // ==================== 搜索 ====================

    private fun selectedSources(): List<String> {
        val sources = mutableListOf<String>()
        if (binding.chipNetease.isChecked) sources += "netease"
        if (binding.chipJoox.isChecked) sources += "joox"
        if (binding.chipKuwo.isChecked) sources += "kuwo"
        return sources
    }

    private fun selectedQuality(): String = when (binding.qualityGroup.checkedChipId) {
        R.id.chipQuality128 -> "128k"
        R.id.chipQuality192 -> "192k"
        R.id.chipQuality320 -> "320k"
        R.id.chipQuality740 -> "740k"
        else -> "999k"
    }

    private fun doSearch() {
        val keyword = binding.inputKeyword.text?.toString()?.trim().orEmpty()
        if (keyword.isEmpty()) {
            toast(getString(R.string.hint_keyword))
            return
        }
        val sources = selectedSources()
        if (sources.isEmpty()) {
            toast(getString(R.string.hint_no_source))
            return
        }
        // 重置状态
        searchJob?.cancel()
        currentPage = 1
        lastKeyword = keyword
        tracks.clear()
        adapter.submitList(emptyList())
        binding.textStatus.text = getString(R.string.status_searching)
        binding.progressSearch.visibility = View.VISIBLE
        binding.btnSearch.isEnabled = false

        searchJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { MusicApi.search(keyword, sources, 1) }.getOrNull()
            }
            binding.btnSearch.isEnabled = true
            binding.progressSearch.visibility = View.GONE
            if (result == null) {
                binding.textStatus.text = getString(R.string.status_search_failed)
                return@launch
            }
            tracks.clear()
            tracks.addAll(result)
            adapter.submitList(result)
            binding.textStatus.text = getString(R.string.status_result_count, result.size)
            if (result.isEmpty()) toast(getString(R.string.status_empty))
        }
    }

    private fun loadMore() {
        if (isLoadingMore || lastKeyword.isEmpty()) return
        val sources = selectedSources()
        if (sources.isEmpty()) return
        isLoadingMore = true
        val nextPage = currentPage + 1

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { MusicApi.search(lastKeyword, sources, nextPage) }.getOrNull()
            }
            isLoadingMore = false
            if (result == null || result.isEmpty()) return@launch
            currentPage = nextPage
            tracks.addAll(result)
            adapter.submitList(tracks.toList())
            binding.textStatus.text = getString(R.string.status_result_count, tracks.size)
        }
    }

    // ==================== 试听 ====================

    private fun togglePlay(track: Track) {
        // 同一首歌：停止
        if (playingTrackId == track.id) {
            stopPlayer()
            return
        }
        stopPlayer()
        binding.textPlayerBar.visibility = View.VISIBLE
        binding.textPlayerBar.text = getString(R.string.status_resolving) + " · " + track.name

        playerJob = lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching { MusicApi.resolveUrl(track, "128k") }.getOrNull()
            }
            if (resolved == null) {
                binding.textPlayerBar.visibility = View.GONE
                toast(getString(R.string.resolve_failed))
                return@launch
            }
            try {
                // Critical #1: 先赋值再 prepare，防止回调时 player 为 null
                val mp = MediaPlayer()
                player = mp
                mp.setDataSource(resolved.url)
                mp.setOnCompletionListener { stopPlayer() }
                mp.setOnErrorListener { _, what, extra ->
                    stopPlayer()
                    toast(getString(R.string.play_failed))
                    true
                }
                mp.setOnPreparedListener {
                    it.start()
                    playingTrackId = track.id
                    adapter.setPlaying(track.id)
                    binding.textPlayerBar.text = "▶ " + track.name + " · " + track.artist
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                stopPlayer()
                toast(getString(R.string.play_failed))
            }
        }
    }

    private fun stopPlayer() {
        playerJob?.cancel()
        playerJob = null
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
        playingTrackId = null
        adapter.setPlaying(null)
        if (_binding != null) binding.textPlayerBar.visibility = View.GONE
    }

    // ==================== 下载 ====================

    private fun startDownload(track: Track) {
        val quality = selectedQuality()
        val dialogBinding = DialogDownloadBinding.inflate(layoutInflater)
        var downloadJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.downloading))
            .setView(dialogBinding.root)
            .setCancelable(false)
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                // Critical #3: 取消下载协程
                downloadJob?.cancel()
            }
            .show()

        dialogBinding.textInfo.text = "${track.name} · ${track.artist}"
        dialogBinding.progress.max = 100
        dialogBinding.progress.progress = 0
        dialogBinding.textPercent.text = "0%"

        downloadJob = lifecycleScope.launch {
            var errorDetail: String? = null
            val result = withContext(Dispatchers.IO) {
                try {
                    val resolved = MusicApi.resolveUrl(track, quality)
                    val dl = Downloader.download(requireContext(), track, resolved) { pct ->
                        if (!isAdded) return@download
                        lifecycleScope.launch {
                            _binding?.let {
                                dialogBinding.progress.progress = pct
                                dialogBinding.textPercent.text = "$pct%"
                            }
                        }
                    }
                    Triple(resolved, dl.uri, dl.size)
                } catch (e: Exception) {
                    // Major #9: 保留错误详情
                    errorDetail = e.message ?: e.javaClass.simpleName
                    null
                }
            }
            dialog.dismiss()
            if (result == null) {
                toast(getString(R.string.download_failed) + errorDetail?.let { "：$it" }.orEmpty())
            } else {
                val (resolved, uri, size) = result
                DownloadHistory.add(
                    requireContext(),
                    DownloadRecord(
                        name = track.name,
                        artist = track.artist,
                        source = track.source,
                        format = resolved.ext,
                        quality = resolved.qualityLabel,
                        time = System.currentTimeMillis(),
                        uri = uri.toString(),
                        size = size
                    )
                )
                toast(getString(R.string.download_done, resolved.qualityLabel, resolved.ext))
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
