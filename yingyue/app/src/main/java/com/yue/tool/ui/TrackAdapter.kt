package com.yue.tool.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yue.tool.R
import com.yue.tool.api.MusicApi
import com.yue.tool.api.Track
import com.yue.tool.databinding.ItemTrackBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed class DlState {
    data object Idle : DlState()
    data object Fetching : DlState()
    data class Downloading(val percent: Int) : DlState()
    data object Done : DlState()
    data class Error(val message: String) : DlState()
}

class TrackAdapter(
    private val scope: CoroutineScope,
    private val onPlay: (Track) -> Unit,
    private val onDownload: (Track) -> Unit
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    private val items = mutableListOf<Track>()
    private val covers = HashMap<String, String>()   // pic_id -> url
    private val states = HashMap<String, DlState>() // url_id -> state
    private val picJobs = HashMap<String, Job>()

    var playingId: String? = null
        private set

    fun submit(list: List<Track>) {
        cancelPicJobs()
        items.clear()
        items.addAll(list)
        states.clear()
        playingId = null
        notifyDataSetChanged()
    }

    fun setState(urlId: String, state: DlState) {
        states[urlId] = state
        notifyItemChanged(indexOf(urlId))
    }

    fun getState(urlId: String?): DlState = states[urlId] ?: DlState.Idle

    fun setPlaying(urlId: String?) {
        val old = playingId
        playingId = urlId
        old?.let { notifyItemChanged(indexOf(it)) }
        urlId?.let { notifyItemChanged(indexOf(it)) }
    }

    private fun indexOf(urlId: String): Int {
        val i = items.indexOfFirst { it.url_id == urlId }
        return if (i >= 0) i else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTrackBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val track = items[position]
        val binding = holder.binding

        binding.textTitle.text = track.name
        binding.textSubtitle.text = buildString {
            append(track.artistLine)
            if (!track.album.isNullOrEmpty()) {
                append(" · ")
                append(track.album)
            }
        }
        binding.textSource.text = if (track.source == "joox") "JOOX" else "网易云"

        bindCover(binding, track)
        bindState(binding, track)
        bindPlayIcon(binding, track)

        binding.btnPlay.setOnClickListener { onPlay(track) }
        binding.btnDownload.setOnClickListener { onDownload(track) }
    }

    private fun bindCover(binding: ItemTrackBinding, track: Track) {
        val picId = track.pic_id ?: return
        val cached = covers[picId]
        if (cached != null) {
            binding.imageCover.load(cached) { crossfade(true) }
            return
        }
        picJobs[picId]?.cancel()
        picJobs[picId] = scope.launch {
            val pic = MusicApi.fetchPic(track.source, picId)
            if (!pic.url.isNullOrEmpty()) {
                covers[picId] = pic.url
                if (holderAlive(binding, track)) {
                    binding.imageCover.load(pic.url) { crossfade(true) }
                }
            }
        }
    }

    private fun holderAlive(binding: ItemTrackBinding, track: Track): Boolean =
        binding.root.isAttachedToWindow && items.any { it.url_id == track.url_id }

    private fun bindState(binding: ItemTrackBinding, track: Track) {
        val urlId = track.url_id ?: return
        when (val s = getState(urlId)) {
            is DlState.Idle -> {
                binding.btnDownload.isVisible = true
                binding.progressDownload.isVisible = false
                binding.progressDownload.isIndeterminate = false
                binding.progressDownload.progress = 0
                binding.barItem.isVisible = false
                binding.btnDownload.setImageResource(R.drawable.ic_download)
                binding.btnDownload.imageTintList = colorState(binding, R.color.moonGold)
            }
            is DlState.Fetching -> {
                binding.btnDownload.isVisible = false
                binding.barItem.isVisible = false
                binding.progressDownload.isVisible = true
                binding.progressDownload.isIndeterminate = true
            }
            is DlState.Downloading -> {
                binding.btnDownload.isVisible = false
                binding.progressDownload.isVisible = true
                binding.progressDownload.isIndeterminate = false
                binding.progressDownload.max = 100
                binding.progressDownload.progress = s.percent.coerceAtLeast(0)
                binding.barItem.isVisible = true
                binding.barItem.max = 100
                binding.barItem.progress = s.percent.coerceAtLeast(0)
            }
            is DlState.Done -> {
                binding.btnDownload.isVisible = true
                binding.progressDownload.isVisible = false
                binding.barItem.isVisible = false
                binding.btnDownload.setImageResource(R.drawable.ic_check)
                binding.btnDownload.imageTintList = colorState(binding, R.color.mintGreen)
            }
            is DlState.Error -> {
                binding.btnDownload.isVisible = true
                binding.progressDownload.isVisible = false
                binding.barItem.isVisible = false
                binding.btnDownload.setImageResource(R.drawable.ic_download)
                binding.btnDownload.imageTintList = colorState(binding, R.color.textSecondary)
            }
        }
    }

    private fun bindPlayIcon(binding: ItemTrackBinding, track: Track) {
        val playingThis = playingId != null && playingId == track.url_id
        binding.btnPlay.setImageResource(
            if (playingThis) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.btnPlay.imageTintList = colorState(
            binding,
            if (playingThis) R.color.moonGold else R.color.textSecondary
        )
    }

    private fun colorState(binding: ItemTrackBinding, colorRes: Int) =
        androidx.core.content.ContextCompat.getColorStateList(binding.root.context, colorRes)

    private fun cancelPicJobs() {
        picJobs.values.forEach { it.cancel() }
        picJobs.clear()
    }

    class VH(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)
}
