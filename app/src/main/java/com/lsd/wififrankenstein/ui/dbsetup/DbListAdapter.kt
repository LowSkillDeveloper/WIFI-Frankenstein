package com.lsd.wififrankenstein.ui.dbsetup

import android.annotation.SuppressLint
import android.util.Log
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

class DbListAdapter(
    private val onItemMoved: (Int, Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onItemRemoved: (Int) -> Unit,
    private val onManageIndexes: (DbItem) -> Unit
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
            binding.textViewDbType.text = item.type
            binding.textViewDbPath.text =
                // хакодед, перед релизом исправить, а вообще пофигу
                "Content: ${item.path}\nDirect: ${item.directPath ?: "N/A"}"
            binding.textViewOriginalSize.text = "Original size: %.2f MB".format(item.originalSizeInMB)
            binding.textViewCachedSize.text = "Cached size: %.2f MB".format(item.cachedSizeInMB)

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

                    binding.textViewIndexStatus.text = binding.root.context.getString(R.string.checking_indexes)
                    binding.textViewIndexStatus.setTextColor(ContextCompat.getColor(binding.root.context, android.R.color.darker_gray))

                    binding.root.post {
                        if (item.isIndexed) {
                            binding.textViewIndexStatus.text = binding.root.context.getString(R.string.basic_indices_available)
                            binding.textViewIndexStatus.setTextColor(ContextCompat.getColor(binding.root.context, R.color.success_green))
                            binding.buttonManageIndexes.text = binding.root.context.getString(R.string.delete_indexes)
                        } else {
                            binding.textViewIndexStatus.text = binding.root.context.getString(R.string.no_indices_available)
                            binding.textViewIndexStatus.setTextColor(ContextCompat.getColor(binding.root.context, R.color.error_red))
                            binding.buttonManageIndexes.text = binding.root.context.getString(R.string.index_database)
                        }
                    }
                }
                DbType.SQLITE_FILE_3WIFI -> {
                    binding.textViewIndexStatusLabel.visibility = View.VISIBLE
                    binding.textViewIndexStatus.visibility = View.VISIBLE
                    binding.buttonManageIndexes.visibility = View.GONE

                    binding.textViewIndexStatus.text = binding.root.context.getString(R.string.checking_indexes)
                    binding.textViewIndexStatus.setTextColor(ContextCompat.getColor(binding.root.context, android.R.color.darker_gray))

                    binding.root.post {
                        if (item.isIndexed) {
                            binding.textViewIndexStatus.text = binding.root.context.getString(R.string.basic_indices_available)
                            binding.textViewIndexStatus.setTextColor(ContextCompat.getColor(binding.root.context, R.color.success_green))
                        } else {
                            binding.textViewIndexStatus.text = binding.root.context.getString(R.string.no_indices_available)
                            binding.textViewIndexStatus.setTextColor(ContextCompat.getColor(binding.root.context, R.color.error_red))
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

    private class DbDiffCallback : DiffUtil.ItemCallback<DbItem>() {
        override fun areItemsTheSame(oldItem: DbItem, newItem: DbItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DbItem, newItem: DbItem): Boolean {
            return oldItem == newItem
        }
    }
}