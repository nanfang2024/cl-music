package com.yue.tool.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yue.tool.R
import com.yue.tool.api.MusicApi
import com.yue.tool.api.ResolvedUrl
import com.yue.tool.api.Track
import com.yue.tool.data.DownloadHistory
import com.yue.tool.data.DownloadRecord
import com.yue.tool.databinding.DialogDownloadBinding
import com.yue.tool.databinding.FragmentHomeBinding
import com.yue.tool.download.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TrackAdapter
    private val tracks = mutableListOf<Track>()

    private var player: MediaPlayer? = null
    private var playingTrackId: String? = null

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
            onDownload = { startDownload(it) },
            onPlay = { togglePlay(it) }
        )
        binding.listTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.listTracks.adapter = adapter

        binding.btnSearch.setOnClickListener { doSearch() }

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
        binding.textStatus.text = getString(R.string.status_searching)
        binding.btnSearch.isEnabled = false
        adapter.submit(emptyList())

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    MusicApi.search(keyword, sources)
                } catch (e: Exception) {
                    null
                }
            }
            binding.btnSearch.isEnabled = true
            if (result == null) {
                binding.textStatus.text = getString(R.string.status_search_failed)
                return@launch
            }
            tracks.clear()
            tracks.addAll(result)
            adapter.submit(result)
            binding.textStatus.text =
                getString(R.string.status_result_count, result.size)
            if (result.isEmpty()) toast(getString(R.string.status_empty))
        }
    }

    // ==================== 试听 ====================

    private fun togglePlay(track: Track) {
        if (playingTrackId == track.id) {
            stopPlayer()
            return
        }
        stopPlayer()
        toast(getString(R.string.status_resolving))
        lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                try {
                    MusicApi.resolveUrl(track, "128k")
                } catch (e: Exception) {
                    null
                }
            }
            if (resolved == null) {
                toast(getString(R.string.resolve_failed))
                return@launch
            }
            try {
                val mp = MediaPlayer()
                mp.setDataSource(resolved.url)
                mp.setOnCompletionListener { stopPlayer() }
                mp.setOnErrorListener { _, _, _ -> stopPlayer(); true }
                mp.prepareAsync()
                mp.setOnPreparedListener {
                    it.start()
                    playingTrackId = track.id
                    adapter.setPlaying(track.id)
                }
                player = mp
            } catch (e: Exception) {
                toast(getString(R.string.play_failed))
            }
        }
    }

    private fun stopPlayer() {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        playingTrackId = null
        adapter.setPlaying(null)
    }

    // ==================== 下载 ====================

    private fun startDownload(track: Track) {
        val quality = selectedQuality()
        val dialogBinding = DialogDownloadBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.downloading))
            .setView(dialogBinding.root)
            .setCancelable(false)
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        dialogBinding.textInfo.text = "${track.name} · ${track.artist}"
        dialogBinding.progress.max = 100
        dialogBinding.progress.progress = 0
        dialogBinding.textPercent.text = "0%"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val resolved = MusicApi.resolveUrl(track, quality)
                    val uri = Downloader.download(requireContext(), track, resolved) { pct ->
                        lifecycleScope.launch {
                            dialogBinding.progress.progress = pct
                            dialogBinding.textPercent.text = "$pct%"
                        }
                    }
                    Pair(resolved, uri)
                } catch (e: Exception) {
                    null
                }
            }
            dialog.dismiss()
            if (result == null) {
                toast(getString(R.string.download_failed))
            } else {
                val (resolved, uri) = result
                DownloadHistory.add(
                    requireContext(),
                    DownloadRecord(
                        name = track.name,
                        artist = track.artist,
                        source = track.source,
                        format = resolved.ext,
                        quality = resolved.qualityLabel,
                        time = System.currentTimeMillis(),
                        uri = uri.toString()
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
