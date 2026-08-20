package com.lsd.wififrankenstein.ui.localnetwork

import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R

class LocalNetworkConsoleAdapter(
    private val maxLines: Int = 500
) : RecyclerView.Adapter<LocalNetworkConsoleAdapter.ViewHolder>() {

    private val lines = mutableListOf<String>()

    private fun isDarkTheme(context: android.content.Context): Boolean {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun getConsoleTextColor(context: android.content.Context): Int {
        return ContextCompat.getColor(
            context,
            if (isDarkTheme(context)) R.color.console_text_dark_theme
            else R.color.console_text_light_theme
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val tv = TextView(parent.context).apply {
            textSize = 10f
            setTextColor(getConsoleTextColor(parent.context))
            setPadding(4, 2, 4, 2)
            typeface = android.graphics.Typeface.MONOSPACE
            isSingleLine = false
            maxLines = Int.MAX_VALUE
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.textView.text = lines[position]
        holder.textView.setTextColor(getConsoleTextColor(holder.textView.context))
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
