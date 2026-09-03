package com.yue.tool.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.yue.tool.api.MusicApi
import com.yue.tool.api.Track
import com.yue.tool.data.DownloadHistory
import com.yue.tool.data.DownloadRecord
import com.yue.tool.databinding.FragmentHomeBinding
import com.yue.tool.download.Downloader
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TrackAdapter
    private var player: MediaPlayer? = null
    private var pendingDownload: Track? = null

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val track = pendingDownload
            pendingDownload = null
            if (granted && track != null) {
                startDownload(track)
            } else if (track != null) {
                toast("未授予存储权限，无法下载")
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = TrackAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onPlay = { togglePlay(it) },
            onDownload = { tryDownload(it) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.btnSearch.setOnClickListener { doSearch() }
        binding.editQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
    }

    private fun currentSource(): String =
        if (binding.chipJoox.isChecked) "joox" else "netease"

    private fun currentQuality(): Int = when {
        binding.chipQ128.isChecked -> 128
        binding.chipQ192.isChecked -> 192
        binding.chipQ320.isChecked -> 320
        binding.chipQ740.isChecked -> 740
        else -> 999
    }

    private fun doSearch() {
        val query = binding.editQuery.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            binding.editLayout.error = "想听什么歌，先输个关键词吧"
            return
        }
        binding.editLayout.error = null
        hideKeyboard()

        binding.progress.isVisible = true
        binding.textEmpty.isVisible = false
        binding.recycler.isVisible = false
        adapter.submit(emptyList())

        val source = currentSource()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = MusicApi.search(source, query)
                adapter.submit(result)
                if (result.isEmpty()) {
                    binding.textEmpty.isVisible = true
                    binding.textEmpty.text = "没有找到相关歌曲"
                } else {
                    binding.recycler.isVisible = true
                }
            } catch (e: Exception) {
                binding.textEmpty.isVisible = true
                binding.textEmpty.text = "搜索失败：${e.message}"
            } finally {
                binding.progress.isVisible = false
            }
        }
    }

    private fun tryDownload(track: Track) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = track
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startDownload(track)
    }

    private fun startDownload(track: Track) {
        val urlId = track.url_id ?: run {
            toast("该曲目缺少下载信息")
            return
        }
        when (adapter.getState(urlId)) {
            is DlState.Fetching, is DlState.Downloading -> {
                toast("正在下载中，请稍候")
                return
            }
            else -> Unit
        }

        val br = currentQuality()
        adapter.setState(urlId, DlState.Fetching)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val info = MusicApi.fetchUrl(track.source, urlId, br)
                val link = info.url
                if (link.isNullOrEmpty()) throw IllegalStateException("该歌曲暂无 ${br}k 音质")

                val displayName = Downloader.safeName("${track.artistLine} - ${track.name}")
                val format = Downloader.formatFromUrl(link)
                var received = 0L
                val uri = Downloader.download(requireContext(), link, displayName) { p ->
                    received = p.received
                    activity?.runOnUiThread {
                        if (p.percent >= 0) adapter.setState(urlId, DlState.Downloading(p.percent))
                    }
                }
                adapter.setState(urlId, DlState.Done)

                DownloadHistory.add(
                    requireContext(),
                    DownloadRecord(
                        name = track.name,
                        artist = track.artistLine,
                        format = format.ext,
                        size = received,
                        time = System.currentTimeMillis(),
                        uri = uri.toString()
                    )
                )

                Snackbar.make(
                    binding.root,
                    "已保存 ${format.ext.uppercase()} · ${track.name}",
                    Snackbar.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                adapter.setState(urlId, DlState.Error(e.message ?: "下载失败"))
                Snackbar.make(
                    binding.root,
                    "下载失败：${e.message}",
                    Snackbar.LENGTH_LONG
                ).setAction("重试") { startDownload(track) }
                    .show()
            }
        }
    }

    private fun togglePlay(track: Track) {
        val urlId = track.url_id ?: return
        if (urlId == adapter.playingId && player?.isPlaying == true) {
            player?.pause()
            adapter.setPlaying(null)
            return
        }

        releasePlayer()
        adapter.setPlaying(urlId)
        toast("正在获取播放链接…")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val info = MusicApi.fetchUrl(track.source, urlId, currentQuality())
                val link = info.url
                if (link.isNullOrEmpty()) throw IllegalStateException("暂时无法获取播放链接")

                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(link)
                    setOnCompletionListener {
                        adapter.setPlaying(null)
                        releasePlayer()
                    }
                    setOnErrorListener { _, what, extra ->
                        adapter.setPlaying(null)
                        releasePlayer()
                        toast("播放失败 ($what/$extra)")
                        true
                    }
                    prepare()
                    start()
                }
                player = mp
            } catch (e: Exception) {
                adapter.setPlaying(null)
                releasePlayer()
                toast("播放失败：${e.message}")
            }
        }
    }

    private fun releasePlayer() {
        player?.run {
            runCatching { stop() }
            runCatching { release() }
        }
        player = null
    }

    private fun hideKeyboard() {
        activity?.currentFocus?.let { focus ->
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            imm.hideSoftInputFromWindow(focus.windowToken, 0)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releasePlayer()
        _binding = null
    }
}
