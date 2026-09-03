package com.yue.tool.ui

import android.view.LayoutInflater
import android.view.View
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

    /** 当前选中的曲目（含暂停状态），null 表示无播放 */
    private var playingId: String? = null
    private var playingActive = false

    /**
     * 设置播放状态
     * @param trackId 当前曲目 id，null 表示停止
     * @param isActive true=正在播放（显示均衡器），false=已暂停（显示 ‖）
     */
    fun setPlayingState(trackId: String?, isActive: Boolean) {
        val oldId = playingId
        val oldActive = playingActive
        playingId = trackId
        playingActive = isActive
        if (oldId != trackId || oldActive != isActive) {
            (listOf(oldId, trackId)).filterNotNull().distinct().forEach { id ->
                val pos = currentList.indexOfFirst { it.id == id }
                if (pos >= 0) notifyItemChanged(pos)
            }
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
            // 软底 pill：同色系 14% 透明度背景
            val pillColor = ContextCompat.getColor(
                ctx,
                when (track.source) {
                    "joox" -> R.color.jooxPill
                    "kuwo" -> R.color.kuwoPill
                    else -> R.color.neteasePill
                }
            )
            textSource.setTextColor(pillColor)
            textSource.background?.mutate()?.setTint(
                (pillColor and 0x00FFFFFF) or 0x24000000
            )

            // 封面图加载
            imageCover.tag = track.coverUrl ?: "pending:${track.id}"
            when {
                !track.coverUrl.isNullOrEmpty() ->
                    ImageLoader.load(imageCover, track.coverUrl)
                track.source == "netease" || track.source == "joox" -> {
                    // 芸朵/绿鹅需要额外请求封面 URL
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

            // 播放状态：均衡器（播放中）/ ‖（暂停）/ ▶（未播放）
            val isCurrent = track.id == playingId
            equalizer.visibility = if (isCurrent && playingActive) View.VISIBLE else View.GONE
            textPlayIcon.visibility = if (isCurrent && playingActive) View.GONE else View.VISIBLE
            textPlayIcon.text = if (isCurrent) "‖" else "▶"

            buttonPlay.setOnClickListener { onPlay(track) }
            buttonDownload.setOnClickListener { onDownload(track) }
            // 整行点击切换播放
            root.setOnClickListener { onPlay(track) }
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
