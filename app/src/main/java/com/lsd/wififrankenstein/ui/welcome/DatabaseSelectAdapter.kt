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
            val typeLabel = if (knownType) {
                when (dbInfo.type) {
                    "3wifi" -> binding.root.context.getString(R.string.type_3wifi)
                    else -> binding.root.context.getString(R.string.type_custom)
                }
            } else {
                null
            }
            val versionLabel =
                dbInfo.version.takeIf { it.isNotBlank() }?.let { "v$it" }
            val typeText = listOfNotNull(typeLabel, versionLabel).joinToString(" · ")
            binding.textViewDbType.text = typeText
            binding.textViewDbType.visibility =
                if (typeText.isBlank()) View.GONE else View.VISIBLE
            binding.textViewDbDescription.text = dbInfo.description
            binding.textViewDbDescription.visibility =
                if (dbInfo.description.isNullOrBlank()) View.GONE else View.VISIBLE
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
