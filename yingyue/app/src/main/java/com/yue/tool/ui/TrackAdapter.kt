package com.yue.tool.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.yue.tool.R
import com.yue.tool.api.Track
import com.yue.tool.databinding.ItemTrackBinding
import com.yue.tool.util.CoverLoader

class TrackAdapter(
    private val scope: LifecycleCoroutineScope,
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
            // 音源 pill：截图里用的是浅米色背景 + 金色字（所有音源统一风格）
            textSource.setTextColor(ContextCompat.getColor(ctx, R.color.sourcePillText))

            // 封面
            CoverLoader.load(
                scope = scope,
                url = track.coverUrl,
                target = imageCover,
                placeholderRes = R.drawable.bg_cover_placeholder
            )

            // 播放/停止图标
            buttonPlay.setImageResource(
                if (track.id == playingId) R.drawable.ic_stop_square else R.drawable.ic_play_tri
            )
            val playTint = if (track.id == playingId)
                R.color.moonGold else R.color.textSecondary
            buttonPlay.setColorFilter(ContextCompat.getColor(ctx, playTint))

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
