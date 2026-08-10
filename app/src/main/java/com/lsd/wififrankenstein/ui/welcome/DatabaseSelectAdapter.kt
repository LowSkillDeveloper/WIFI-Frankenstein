package com.lsd.wififrankenstein.ui.welcome

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemDatabaseSelectBinding
import com.lsd.wififrankenstein.ui.dbsetup.SmartLinkDbInfo

class DatabaseSelectAdapter(
    private val databases: List<SmartLinkDbInfo>,
    private val checkedState: BooleanArray,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<DatabaseSelectAdapter.ViewHolder>() {

    inner class ViewHolder(private val binding: ItemDatabaseSelectBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(dbInfo: SmartLinkDbInfo, isChecked: Boolean, position: Int) {
            binding.textViewDbName.text = dbInfo.name
            val knownType = dbInfo.type == "3wifi" || dbInfo.type == "custom-auto-mapping"
            if (knownType) {
                val typeLabel = when (dbInfo.type) {
                    "3wifi" -> binding.root.context.getString(R.string.type_3wifi)
                    else -> binding.root.context.getString(R.string.type_custom)
                }
                binding.textViewDbType.text = typeLabel
                binding.textViewDbType.visibility = View.VISIBLE
                binding.chipType.text = typeLabel
                binding.chipType.visibility = View.VISIBLE
            } else {
                binding.textViewDbType.visibility = View.GONE
                binding.chipType.visibility = View.GONE
            }
            binding.root.setOnClickListener {
                binding.checkBox.isChecked = !binding.checkBox.isChecked
                checkedState[position] = binding.checkBox.isChecked
                onSelectionChanged()
            }
            binding.checkBox.setOnCheckedChangeListener(null)
            binding.checkBox.isChecked = isChecked
            binding.checkBox.setOnCheckedChangeListener { _, _ ->
                checkedState[position] = binding.checkBox.isChecked
                onSelectionChanged()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDatabaseSelectBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(databases[position], checkedState[position], position)
    }

    override fun getItemCount(): Int = databases.size
}
