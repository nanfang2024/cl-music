package com.yue.tool.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.yue.tool.R
import com.yue.tool.api.MusicApi
import com.yue.tool.api.Track
import com.yue.tool.databinding.ItemTrackBinding
import com.yue.tool.util.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrackAdapter(
    private val onDownload: (Track) -> Unit,
    private val onPlay: (Track) -> Unit
) : ListAdapter<Track, TrackAdapter.Holder>(DIFF) {

    private var playingId: String? = null

    fun setPlaying(trackId: String?) {
        val old = playingId
        playingId = trackId
        if (old != null) {
            val oldPos = currentList.indexOfFirst { it.id == old }
            if (oldPos >= 0) notifyItemChanged(oldPos)
        }
        if (trackId != null) {
            val newPos = currentList.indexOfFirst { it.id == trackId }
            if (newPos >= 0) notifyItemChanged(newPos)
        }
    }

    class Holder(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = getItem(position)
        val ctx = holder.binding.root.context
        with(holder.binding) {
            textIndex.text = (position + 1).toString()
            textName.text = track.name
            textArtist.text = buildString {
                append(track.artist)
                if (track.album.isNotEmpty()) append(" · ").append(track.album)
            }
            textSource.text = ctx.getString(
                when (track.source) {
                    "joox" -> R.string.source_joox
                    "kuwo" -> R.string.source_kuwo
                    else -> R.string.source_netease
                }
            )
            val colorRes = when (track.source) {
                "joox" -> R.color.jooxPill
                "kuwo" -> R.color.kuwoPill
                else -> R.color.neteasePill
            }
            textSource.setTextColor(ContextCompat.getColor(ctx, colorRes))

            // 封面图加载
            imageCover.tag = track.coverUrl ?: "pending:${track.id}"
            when {
                !track.coverUrl.isNullOrEmpty() ->
                    ImageLoader.load(imageCover, track.coverUrl)
                track.source == "netease" && !track.picId.isNullOrEmpty() -> {
                    // 芸朵需要额外请求封面 URL
                    imageCover.setImageResource(R.drawable.ic_cover_placeholder)
                    CoroutineScope(Dispatchers.IO).launch {
                        val coverUrl = runCatching { MusicApi.resolveCover(track) }.getOrNull()
                        withContext(Dispatchers.Main) {
                            if (imageCover.tag == "pending:${track.id}" && coverUrl != null) {
                                imageCover.tag = coverUrl
                                ImageLoader.load(imageCover, coverUrl)
                            }
                        }
                    }
                }
                track.source == "joox" -> {
                    // 绿鹅：通过 gdstudio 搜索获取封面
                    imageCover.setImageResource(R.drawable.ic_cover_placeholder)
                    CoroutineScope(Dispatchers.IO).launch {
                        val coverUrl = runCatching { MusicApi.resolveCover(track) }.getOrNull()
                        withContext(Dispatchers.Main) {
                            if (imageCover.tag == "pending:${track.id}" && coverUrl != null) {
                                imageCover.tag = coverUrl
                                ImageLoader.load(imageCover, coverUrl)
                            }
                        }
                    }
                }
                else -> imageCover.setImageResource(R.drawable.ic_cover_placeholder)
            }

            // 播放/暂停按钮：▶ 和 ‖
            val isPlaying = track.id == playingId
            buttonPlay.text = if (isPlaying) "‖" else "▶"
            buttonPlay.setOnClickListener { onPlay(track) }
            buttonDownload.setOnClickListener { onDownload(track) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Track>() {
            override fun areItemsTheSame(a: Track, b: Track) =
                a.id == b.id && a.source == b.source

            override fun areContentsTheSame(a: Track, b: Track) = a == b
        }
    }
}
