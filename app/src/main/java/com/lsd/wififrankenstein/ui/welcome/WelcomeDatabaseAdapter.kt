package com.lsd.wififrankenstein.ui.welcome

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbType

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
        private val databaseNameTextView: TextView =
            itemView.findViewById(R.id.textViewDatabaseName)
        private val databaseUrlTextView: TextView = itemView.findViewById(R.id.textViewDatabaseUrl)
        private val addButton: Button = itemView.findViewById(R.id.buttonAddThisDatabase)
        private val chipDbType: Chip = itemView.findViewById(R.id.chipDbType)

        fun bind(dbItem: DbItem) {
            databaseNameTextView.text = dbItem.type
            databaseUrlTextView.text = dbItem.path

            val chipText = getChipText(itemView.context, dbItem)
            if (chipText != null) {
                chipDbType.text = chipText
                chipDbType.visibility = View.VISIBLE
            } else {
                chipDbType.visibility = View.GONE
            }

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

    private fun getChipText(context: Context, item: DbItem): String? {
        return when (item.dbType) {
            DbType.WIFI_API -> context.getString(R.string.db_type_3wifi)
            DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI ->
                context.getString(R.string.type_3wifi)

            DbType.SQLITE_FILE_CUSTOM -> context.getString(R.string.type_custom)
            DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> when (item.smartlinkType) {
                "3wifi", "custom-auto-mapping" -> context.getString(R.string.type_custom)
                else -> context.getString(R.string.db_type_smartlink)
            }

            DbType.HANDSHAKE_STORAGE -> context.getString(R.string.handshake_storage)
            DbType.LOCAL_APP_DB -> context.getString(R.string.db_type_sqlite_custom)
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