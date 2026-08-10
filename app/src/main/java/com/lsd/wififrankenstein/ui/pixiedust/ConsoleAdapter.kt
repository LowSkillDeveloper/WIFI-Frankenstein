package com.lsd.wififrankenstein.ui.pixiedust

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R

class ConsoleAdapter(
    private val autoScroll: Boolean = false
) : RecyclerView.Adapter<ConsoleAdapter.ConsoleViewHolder>() {

    private val lines = mutableListOf<String>()

    fun setLines(newLines: List<String>) {
        lines.clear()
        lines.addAll(newLines)
        notifyDataSetChanged()
        if (autoScroll && lines.isNotEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                recyclerView?.scrollToPosition(lines.size - 1)
            }, 100)
        }
    }

    fun addLine(line: String) {
        lines.add(line)
        notifyItemInserted(lines.size - 1)
        if (autoScroll) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                recyclerView?.scrollToPosition(lines.size - 1)
            }, 100)
        }
    }

    fun addLines(newLines: List<String>) {
        if (newLines.isEmpty()) return
        val startIdx = lines.size
        lines.addAll(newLines)
        notifyItemRangeInserted(startIdx, newLines.size)
        if (autoScroll) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                recyclerView?.scrollToPosition(lines.size - 1)
            }, 100)
        }
    }

    fun getLines(): List<String> = lines.toList()

    private var recyclerView: RecyclerView? = null

    fun attachToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConsoleViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_console_line, parent, false)
        return ConsoleViewHolder(view)
    }

    override fun onBindViewHolder(holder: ConsoleViewHolder, position: Int) {
        holder.bind(lines[position])
    }

    override fun getItemCount(): Int = lines.size

    class ConsoleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(android.R.id.text1)

        fun bind(line: String) {
            textView.text = line
            textView.setTextColor(
                when {
                    line.startsWith("[+]") -> android.graphics.Color.GREEN
                    line.startsWith("[-]") -> android.graphics.Color.RED
                    line.startsWith("[!]") -> android.graphics.Color.RED
                    line.startsWith("[?]") -> android.graphics.Color.YELLOW
                    line.startsWith("[*]") -> android.graphics.Color.parseColor("#FF6600")
                    line.startsWith("[stderr]") -> android.graphics.Color.LTGRAY
                    line.contains("invalid handshake", ignoreCase = true) ||
                            line.contains(
                                "invalid pmkid",
                                ignoreCase = true
                            ) -> android.graphics.Color.YELLOW

                    else -> android.graphics.Color.LTGRAY
                }
            )
        }
    }
}
