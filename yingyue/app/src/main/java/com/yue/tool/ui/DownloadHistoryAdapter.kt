package com.yue.tool.ui

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.yue.tool.R
import com.yue.tool.data.DownloadRecord
import com.yue.tool.databinding.ItemDownloadBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadHistoryAdapter(
    private val records: MutableList<DownloadRecord> = mutableListOf(),
    private val onPlay: (DownloadRecord) -> Unit,
    private val onShare: (DownloadRecord) -> Unit,
    private val onDelete: (DownloadRecord) -> Unit
) : RecyclerView.Adapter<DownloadHistoryAdapter.Holder>() {

    fun submit(list: List<DownloadRecord>) {
        records.clear()
        records.addAll(list)
        notifyDataSetChanged()
    }

    fun remove(record: DownloadRecord) {
        val idx = records.indexOfFirst {
            it.name == record.name && it.time == record.time && it.format == record.format
        }
        if (idx >= 0) {
            records.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    class Holder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = records.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val record = records[position]
        val ctx = holder.binding.root.context
        with(holder.binding) {
            textName.text = record.name
            textArtist.text = record.artist
            // Minor #15: 显示文件大小
            val sizeText = if (record.size > 0)
                Formatter.formatFileSize(ctx, record.size) else ""
            textFormat.text = buildString {
                append(record.format.uppercase())
                append(" · ").append(record.quality)
                if (sizeText.isNotEmpty()) append(" · ").append(sizeText)
            }
            textTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(Date(record.time))

            textSource.text = ctx.getString(
                when (record.source) {
                    "joox" -> R.string.source_joox
                    "kuwo" -> R.string.source_kuwo
                    else -> R.string.source_netease
                }
            )
            textSource.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    when (record.source) {
                        "joox" -> R.color.jooxPill
                        "kuwo" -> R.color.kuwoPill
                        else -> R.color.neteasePill
                    }
                )
            )

            buttonPlay.setOnClickListener { onPlay(record) }
            buttonShare.setOnClickListener { onShare(record) }
            buttonDelete.setOnClickListener { onDelete(record) }
        }
    }
}
