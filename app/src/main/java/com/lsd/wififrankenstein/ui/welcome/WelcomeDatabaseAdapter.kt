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
import com.lsd.wififrankenstein.ui.dbsetup.DbItem

class WelcomeDatabaseAdapter(
    private val onAddDatabase: (DbItem) -> Unit,
    private val isSelectedList: Boolean = false
) : ListAdapter<DbItem, WelcomeDatabaseAdapter.DatabaseViewHolder>(DatabaseDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DatabaseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_welcome_database, parent, false)
        return DatabaseViewHolder(view)
    }

    override fun onBindViewHolder(holder: DatabaseViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DatabaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val databaseNameTextView: TextView = itemView.findViewById(R.id.textViewDatabaseName)
        private val databaseUrlTextView: TextView = itemView.findViewById(R.id.textViewDatabaseUrl)
        private val addButton: Button = itemView.findViewById(R.id.buttonAddThisDatabase)

        fun bind(dbItem: DbItem) {
            databaseNameTextView.text = dbItem.type
            databaseUrlTextView.text = dbItem.path

            if (isSelectedList) {
                addButton.text = itemView.context.getString(R.string.remove)
                addButton.isEnabled = true
            } else {
                addButton.text = itemView.context.getString(R.string.add)
                addButton.isEnabled = true
            }

            addButton.setOnClickListener {
                onAddDatabase(dbItem)

                if (!isSelectedList) {
                    addButton.text = itemView.context.getString(R.string.added)
                    addButton.isEnabled = false
                }
            }
        }
    }

    class DatabaseDiffCallback : DiffUtil.ItemCallback<DbItem>() {
        override fun areItemsTheSame(oldItem: DbItem, newItem: DbItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DbItem, newItem: DbItem): Boolean {
            return oldItem == newItem
        }
    }
}