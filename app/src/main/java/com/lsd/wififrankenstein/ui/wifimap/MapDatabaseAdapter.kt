package com.lsd.wififrankenstein.ui.wifimap

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbType

class MapDatabaseAdapter(
    private val databases: List<DbItem>,
    private val selectedDatabases: MutableSet<DbItem>,
    private val onSelectionChanged: () -> Unit,
    private val viewModel: WiFiMapViewModel
) : RecyclerView.Adapter<MapDatabaseAdapter.ViewHolder>() {

    private val TAG = "MapDatabaseAdapter"

    init {
        Log.d(TAG, "Initialized adapter with ${databases.size} databases, none selected by default")
    }

    private fun formatSourcePath(path: String): String {
        return try {
            when {
                path.startsWith("content://") -> {
                    val uri = android.net.Uri.parse(path)
                    uri.lastPathSegment?.let { lastSegment ->
                        val decodedSegment = android.net.Uri.decode(lastSegment)
                        decodedSegment.substringAfterLast('/')
                    } ?: path
                }
                path.startsWith("file://") -> {
                    val uri = android.net.Uri.parse(path)
                    uri.lastPathSegment ?: path
                }
                else -> {
                    path.substringAfterLast('/')
                }
            }.substringAfterLast("%2F")
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting source path: $path", e)
            path
        }
    }

    class ViewHolder(val checkbox: MaterialCheckBox) : RecyclerView.ViewHolder(checkbox)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val checkbox = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_map_database, parent, false) as MaterialCheckBox
        return ViewHolder(checkbox)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val database = databases[position]
        holder.checkbox.apply {
            text = formatSourcePath(database.path)
            isChecked = selectedDatabases.contains(database)

            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    if (database.dbType == DbType.SQLITE_FILE_CUSTOM) {
                        viewModel.handleCustomDbSelection(database, true, selectedDatabases)
                    } else {
                        selectedDatabases.add(database)
                    }
                } else {
                    selectedDatabases.remove(database)
                }
                onSelectionChanged()
            }
        }
    }

    override fun getItemCount() = databases.size
}