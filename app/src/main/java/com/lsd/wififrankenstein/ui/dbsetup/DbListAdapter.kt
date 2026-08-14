package com.lsd.wififrankenstein.ui.dbsetup

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemDbBinding
import com.lsd.wififrankenstein.util.Log

class DbListAdapter(
    private val onItemMoved: (Int, Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onItemRemoved: (Int) -> Unit,
    private val onManageIndexes: (DbItem) -> Unit,
    private val onShowDetails: (DbItem) -> Unit = {}
) : ListAdapter<DbItem, DbListAdapter.DbViewHolder>(DbDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DbViewHolder {
        val binding = ItemDbBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DbViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DbViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
        Log.d("DbListAdapter", "Binding item at position $position: $item")
    }

    inner class DbViewHolder(private val binding: ItemDbBinding) :
        RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: DbItem) {
            binding.textViewDbType.text = when {
                item.smartlinkType == "custom-auto-mapping" -> "${item.type} (${
                    binding.root.context.getString(
                        R.string.type_custom_auto
                    )
                })"

                item.dbType == DbType.WIFI_API && item.userNick != null -> {
                    val userManager = UserManager(binding.root.context)
                    val levelText = item.userLevel?.let { userManager.getTextGroup(it) } ?: ""
                    "${item.type} - ${item.userNick} ($levelText)"
                }

                else -> item.type
            }

            if (!item.tableName.isNullOrBlank()) {
                binding.textViewDbType.append(
                    binding.root.context.getString(R.string.ds_table_name_suffix, item.tableName)
                )
            }

            binding.chipDbType.text = getChipTypeText(binding.root.context, item)
            binding.chipDbType.visibility = View.VISIBLE

            if (item.oldFormatWarning != null) {
                binding.textViewOldFormatWarning.text = item.oldFormatWarning
                binding.textViewOldFormatWarning.visibility = View.VISIBLE
            } else {
                binding.textViewOldFormatWarning.visibility = View.GONE
            }
            binding.textViewDbPath.text = when {
                item.path.startsWith("content://") -> {
                    val uri = android.net.Uri.parse(item.path)
                    uri.lastPathSegment?.substringAfterLast('/')
                        ?: item.path.substringAfterLast('/')
                }

                item.path.startsWith("file://") -> item.path.substringAfterLast('/')
                else -> item.path.substringAfterLast('/')
            }
            if (item.dbType == DbType.WIFI_API) {
                binding.textViewDbSize.visibility = View.GONE
            } else {
                binding.textViewDbSize.visibility = View.VISIBLE
                binding.textViewDbSize.text = if (item.cachedSizeInMB > 0) {
                    binding.root.context.getString(
                        R.string.ds_db_size_dual,
                        "%.1f".format(item.originalSizeInMB),
                        "%.1f".format(item.cachedSizeInMB)
                    )
                } else {
                    binding.root.context.getString(
                        R.string.ds_db_size_single,
                        "%.1f".format(item.originalSizeInMB)
                    )
                }
            }

            binding.buttonDbDetails.setOnClickListener { onShowDetails(item) }


            if (item.isMain && item.dbType == DbType.WIFI_API) {
                binding.textViewMain.visibility = ViewGroup.VISIBLE
            } else {
                binding.textViewMain.visibility = ViewGroup.GONE
            }

            when (item.dbType) {
                DbType.SQLITE_FILE_CUSTOM -> {
                    binding.textViewIndexStatusLabel.visibility = View.VISIBLE
                    binding.textViewIndexStatus.visibility = View.VISIBLE
                    binding.buttonManageIndexes.visibility = View.VISIBLE

                    binding.textViewIndexStatus.text =
                        binding.root.context.getString(R.string.checking_indexes)
                    binding.textViewIndexStatus.setTextColor(
                        ContextCompat.getColor(
                            binding.root.context,
                            android.R.color.darker_gray
                        )
                    )

                    binding.root.post {
                        when (item.indexLevel) {
                            DbIndexLevel.FULL -> {
                                binding.textViewIndexStatus.text =
                                    binding.root.context.getString(R.string.full_indices_available)
                                binding.textViewIndexStatus.setTextColor(
                                    ContextCompat.getColor(
                                        binding.root.context,
                                        R.color.success_green
                                    )
                                )
                                binding.buttonManageIndexes.text =
                                    binding.root.context.getString(R.string.delete_indexes)
                            }

                            DbIndexLevel.PARTIAL -> {
                                binding.textViewIndexStatus.text =
                                    binding.root.context.getString(R.string.partial_indices_available)
                                binding.textViewIndexStatus.setTextColor(
                                    ContextCompat.getColor(
                                        binding.root.context,
                                        R.color.warning_orange
                                    )
                                )
                                binding.buttonManageIndexes.text =
                                    binding.root.context.getString(R.string.index_database)
                            }

                            else -> {
                                binding.textViewIndexStatus.text =
                                    binding.root.context.getString(R.string.no_indices_available)
                                binding.textViewIndexStatus.setTextColor(
                                    ContextCompat.getColor(
                                        binding.root.context,
                                        R.color.error_red
                                    )
                                )
                                binding.buttonManageIndexes.text =
                                    binding.root.context.getString(R.string.index_database)
                            }
                        }
                    }
                }

                DbType.SQLITE_FILE_P3WIFI -> {
                    binding.textViewIndexStatusLabel.visibility = View.VISIBLE
                    binding.textViewIndexStatus.visibility = View.VISIBLE
                    binding.buttonManageIndexes.visibility = View.GONE

                    binding.textViewIndexStatus.text =
                        binding.root.context.getString(R.string.checking_indexes)
                    binding.textViewIndexStatus.setTextColor(
                        ContextCompat.getColor(
                            binding.root.context,
                            android.R.color.darker_gray
                        )
                    )

                    binding.root.post {
                        when (item.indexLevel) {
                            DbIndexLevel.FULL -> {
                                binding.textViewIndexStatus.text =
                                    binding.root.context.getString(R.string.full_indices_available)
                                binding.textViewIndexStatus.setTextColor(
                                    ContextCompat.getColor(
                                        binding.root.context,
                                        R.color.success_green
                                    )
                                )
                            }

                            DbIndexLevel.PARTIAL -> {
                                binding.textViewIndexStatus.text =
                                    binding.root.context.getString(R.string.partial_indices_available)
                                binding.textViewIndexStatus.setTextColor(
                                    ContextCompat.getColor(
                                        binding.root.context,
                                        R.color.warning_orange
                                    )
                                )
                            }

                            else -> {
                                binding.textViewIndexStatus.text =
                                    binding.root.context.getString(R.string.no_indices_available)
                                binding.textViewIndexStatus.setTextColor(
                                    ContextCompat.getColor(
                                        binding.root.context,
                                        R.color.error_red
                                    )
                                )
                            }
                        }
                    }
                }

                else -> {
                    binding.textViewIndexStatusLabel.visibility = View.GONE
                    binding.textViewIndexStatus.visibility = View.GONE
                    binding.buttonManageIndexes.visibility = View.GONE
                }
            }

            binding.imageDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
            }

            binding.buttonRemove.setOnClickListener {
                onItemRemoved(adapterPosition)
            }

            binding.buttonManageIndexes.setOnClickListener {
                onManageIndexes(item)
            }
        }
    }

    private fun getChipTypeText(context: Context, item: DbItem): String {
        return when (item.dbType) {
            DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI ->
                context.getString(R.string.type_3wifi)

            DbType.SQLITE_FILE_CUSTOM ->
                context.getString(R.string.type_custom)

            DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                when (item.smartlinkType) {
                    "3wifi", "custom-auto-mapping" -> context.getString(R.string.type_custom)
                    else -> context.getString(R.string.db_type_smartlink)
                }
            }

            DbType.WIFI_API -> context.getString(R.string.db_type_3wifi)
            DbType.HANDSHAKE_STORAGE -> context.getString(R.string.handshake_storage)
            DbType.LOCAL_APP_DB -> context.getString(R.string.db_type_sqlite_custom)
        }
    }

    private class DbDiffCallback : DiffUtil.ItemCallback<DbItem>() {
        override fun areItemsTheSame(oldItem: DbItem, newItem: DbItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DbItem, newItem: DbItem): Boolean {
            return oldItem == newItem
        }
    }
}