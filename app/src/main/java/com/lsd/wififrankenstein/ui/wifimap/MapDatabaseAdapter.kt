package com.lsd.wififrankenstein.ui.wifimap

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.util.Log

class MapDatabaseAdapter(
    private val databases: List<DbItem>,
    private val selectedDatabases: MutableSet<DbItem>,
    private val onSelectionChanged: () -> Unit,
    private val viewModel: WiFiMapViewModel,
    private val context: android.content.Context
) : RecyclerView.Adapter<MapDatabaseAdapter.ViewHolder>() {

    private val TAG = "MapDatabaseAdapter"
    private val colorDrawableCache = mutableMapOf<Int, android.graphics.drawable.GradientDrawable>()

    init {
        setHasStableIds(true)
        Log.d(TAG, "Initialized adapter with ${databases.size} databases, none selected by default")
    }

    override fun getItemId(position: Int): Long {
        return databases[position].id.hashCode().toLong()
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

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: MaterialCheckBox = view.findViewById(R.id.checkbox)
        val colorView: View = view.findViewById(R.id.colorView)
        val countsText: TextView = view.findViewById(R.id.countsText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_database_legend, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val database = databases[position]
        val color = viewModel.getColorForDatabase(database.id)

        holder.checkbox.apply {
            val baseName = when (database.dbType) {
                DbType.LOCAL_APP_DB -> context.getString(R.string.local_database)
                DbType.HANDSHAKE_STORAGE -> context.getString(R.string.handshake_storage)
                else -> formatSourcePath(database.path)
            }
            text = if (database.dbType == DbType.WIFI_API && database.supportsMapApi) {
                "$baseName (${context.getString(R.string.map_api_supported)})"
            } else {
                baseName
            }
            setOnCheckedChangeListener(null)
            isChecked = selectedDatabases.contains(database)
            setOnCheckedChangeListener { _, isChecked ->
                Log.d(TAG, "Database ${database.id} selection changed to: $isChecked")

                if (isChecked) {
                    if (database.dbType == DbType.SQLITE_FILE_CUSTOM || database.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM || database.dbType == DbType.LOCAL_APP_DB || database.dbType == DbType.HANDSHAKE_STORAGE) {
                        viewModel.handleCustomDbSelection(database, true, selectedDatabases)
                    } else {
                        selectedDatabases.add(database)
                    }
                } else {
                    selectedDatabases.remove(database)
                    Log.d(TAG, "Removed database ${database.id} from selection")
                }

                viewModel.clearCache()
                onSelectionChanged()
            }
        }

        holder.colorView.background = colorDrawableCache.getOrPut(color) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }

        val totalCount = viewModel.getTotalPointCount(database.id)
        holder.countsText.text = if (totalCount > 0) {
            context.getString(R.string.total_points_format, totalCount)
        } else {
            context.getString(R.string.no_points_visible)
        }
    }

    override fun getItemCount() = databases.size
}
