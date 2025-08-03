package com.lsd.wififrankenstein.ui.welcome

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.DbSource

class DbSourceAdapter(
    private val onSourceSelected: (DbSource) -> Unit
) : ListAdapter<DbSource, DbSourceAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_db_source, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.textViewSourceName)
        private val descriptionTextView: TextView = itemView.findViewById(R.id.textViewSourceDescription)
        private val selectButton: Button = itemView.findViewById(R.id.buttonSelectSource)

        fun bind(source: DbSource) {
            nameTextView.text = source.name

            if (source.description.isNullOrBlank()) {
                descriptionTextView.visibility = View.GONE
            } else {
                descriptionTextView.visibility = View.VISIBLE
                descriptionTextView.text = source.description
            }

            selectButton.setOnClickListener {
                onSourceSelected(source)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DbSource>() {
        override fun areItemsTheSame(oldItem: DbSource, newItem: DbSource): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DbSource, newItem: DbSource): Boolean {
            return oldItem == newItem
        }
    }
}