package com.lsd.wififrankenstein.ui.localnetwork

import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LocalNetworkConsoleAdapter(
    private val maxLines: Int = 500
) : RecyclerView.Adapter<LocalNetworkConsoleAdapter.ViewHolder>() {

    private val lines = mutableListOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val tv = TextView(parent.context).apply {
            textSize = 10f
            setTextColor(0xFF00FF00.toInt())
            setPadding(4, 2, 4, 2)
            typeface = android.graphics.Typeface.MONOSPACE
            isSingleLine = false
            maxLines = Int.MAX_VALUE
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.textView.text = lines[position]
    }

    override fun getItemCount(): Int = lines.size

    fun addLines(newLines: List<String>) {
        val startIndex = lines.size
        lines.addAll(newLines)
        if (lines.size > maxLines) {
            val excess = lines.size - maxLines
            lines.subList(0, excess).clear()
            notifyDataSetChanged()
        } else {
            notifyItemRangeInserted(startIndex, newLines.size)
        }
    }

    fun clear() {
        lines.clear()
        notifyDataSetChanged()
    }

    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
}
