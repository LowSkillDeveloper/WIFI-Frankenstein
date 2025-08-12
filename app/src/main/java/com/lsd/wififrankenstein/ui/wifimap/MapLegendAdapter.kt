package com.lsd.wififrankenstein.ui.wifimap

import android.graphics.drawable.GradientDrawable
import com.lsd.wififrankenstein.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.DbItem

class MapLegendAdapter : RecyclerView.Adapter<MapLegendAdapter.ViewHolder>() {
    private val items = mutableListOf<Pair<DbItem, Int>>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val colorView: View = view.findViewById(R.id.viewColor)
        val nameText: TextView = view.findViewById(R.id.textViewName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_map_legend, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (dbItem, color) = items[position]

        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        holder.colorView.background = shape
        holder.nameText.text = formatSourcePath(dbItem.path)
    }

    override fun getItemCount() = items.size

    fun updateLegend(newItems: List<Pair<DbItem, Int>>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    private fun formatSourcePath(path: String): String {
        return try {
            when {
                path.startsWith("content://") -> {
                    val uri = path.toUri()
                    uri.lastPathSegment?.let { lastSegment ->
                        val decodedSegment = android.net.Uri.decode(lastSegment)
                        decodedSegment.substringAfterLast('/')
                    } ?: path
                }
                path.startsWith("file://") -> {
                    val uri = path.toUri()
                    uri.lastPathSegment ?: path
                }
                else -> {
                    path.substringAfterLast('/')
                }
            }.substringAfterLast("%2F")
        } catch (e: Exception) {
            Log.e("MapLegendAdapter", "Error formatting source path: $path", e)
            path
        }
    }
}