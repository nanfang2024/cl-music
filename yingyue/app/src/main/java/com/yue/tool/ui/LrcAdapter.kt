package com.yue.tool.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.yue.tool.R
import com.yue.tool.player.LrcLine

class LrcAdapter(private val onSeek: (Long) -> Unit) :
    RecyclerView.Adapter<LrcAdapter.VH>() {

    private val lines = mutableListOf<LrcLine>()
    private var current = -1

    fun submit(newLines: List<LrcLine>) {
        lines.clear()
        lines.addAll(newLines)
        current = -1
        notifyDataSetChanged()
    }

    fun setCurrent(index: Int) {
        if (index == current) return
        val old = current
        current = index
        if (old in lines.indices) notifyItemChanged(old)
        if (index in lines.indices) notifyItemChanged(index)
    }

    fun currentIndex(): Int = current

    fun lineCount(): Int = lines.size

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context)
        tv.setPadding(
            parent.context.resources.displayMetrics.density.toInt() * 24,
            parent.context.resources.displayMetrics.density.toInt() * 10,
            parent.context.resources.displayMetrics.density.toInt() * 24,
            parent.context.resources.displayMetrics.density.toInt() * 10
        )
        return VH(tv)
    }

    override fun getItemCount(): Int = lines.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val line = lines[position]
        holder.text.text = line.text
        holder.text.textSize = if (position == current) 16f else 15f
        holder.text.setTypeface(null, if (position == current) Typeface.BOLD else Typeface.NORMAL)
        holder.text.setTextColor(
            if (position == current) ContextCompat.getColor(holder.text.context, R.color.moonGold)
            else ContextCompat.getColor(holder.text.context, R.color.textSecondary)
        )
        holder.text.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p in lines.indices) onSeek(lines[p].timeMs)
        }
    }
}
