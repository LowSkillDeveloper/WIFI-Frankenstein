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
import com.lsd.wififrankenstein.ui.dbsetup.DbType

class SelectedDatabaseAdapter(
    private val onRemoveClick: (DbItem) -> Unit
) : ListAdapter<DbItem, SelectedDatabaseAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_selected_database, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.textViewDatabaseName)
        private val typeTextView: TextView = itemView.findViewById(R.id.textViewDatabaseType)
        private val removeButton: Button = itemView.findViewById(R.id.buttonRemoveDatabase)

        fun bind(dbItem: DbItem) {
            nameTextView.text = dbItem.type

            val typeStringRes = when (dbItem.dbType) {
                DbType.SQLITE_FILE_3WIFI -> R.string.db_type_sqlite_3wifi
                DbType.SQLITE_FILE_CUSTOM -> R.string.db_type_sqlite_custom
                DbType.WIFI_API -> R.string.db_type_3wifi
                DbType.SMARTLINK_SQLITE_FILE_3WIFI -> R.string.db_type_smartlink
                DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> R.string.db_type_smartlink
                else -> R.string.db_type_unknown
            }

            typeTextView.text = itemView.context.getString(typeStringRes)

            removeButton.setOnClickListener {
                onRemoveClick(dbItem)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DbItem>() {
        override fun areItemsTheSame(oldItem: DbItem, newItem: DbItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DbItem, newItem: DbItem): Boolean {
            return oldItem == newItem
        }
    }
}