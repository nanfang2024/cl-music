package com.yue.tool.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yue.tool.data.DownloadRecord
import com.yue.tool.databinding.ItemDownloadBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadHistoryAdapter(
    private val onPlay: (DownloadRecord) -> Unit,
    private val onShare: (DownloadRecord) -> Unit,
    private val onDelete: (DownloadRecord) -> Unit
) : RecyclerView.Adapter<DownloadHistoryAdapter.VH>() {

    private val items = mutableListOf<DownloadRecord>()
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun submit(list: List<DownloadRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val record = items[position]
        val binding = holder.binding

        binding.textName.text = record.name
        binding.textMeta.text = buildString {
            append(record.artist)
            append(" · ")
            append(record.format.uppercase())
            if (record.size > 0) {
                append(" · ")
                append(formatSize(record.size))
            }
            append(" · ")
            append(dateFormat.format(Date(record.time)))
        }

        binding.btnPlay.setOnClickListener { onPlay(record) }
        binding.btnShare.setOnClickListener { onShare(record) }
        binding.btnDelete.setOnClickListener { onDelete(record) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1024f / 1024f)
        bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }

    class VH(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)
}
