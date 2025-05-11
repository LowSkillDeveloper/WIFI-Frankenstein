package com.lsd.wififrankenstein.ui.databasefinder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.lsd.wififrankenstein.R

data class DetailItem(val key: String, val value: String, val sortOrder: Int = 1)

class DetailItemAdapter : ListAdapter<DetailItem, DetailItemAdapter.DetailViewHolder>(DetailDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DetailViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_detail_row, parent, false)
        return DetailViewHolder(view)
    }

    override fun onBindViewHolder(holder: DetailViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DetailViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textViewKey: TextView = itemView.findViewById(R.id.textViewKey)
        private val textViewValue: TextView = itemView.findViewById(R.id.textViewValue)
        private val buttonCopy: ImageButton = itemView.findViewById(R.id.buttonCopy)
        private val cardView: MaterialCardView = itemView as MaterialCardView

        fun bind(item: DetailItem) {
            textViewKey.text = item.key
            textViewValue.text = item.value

            if (adapterPosition % 2 == 1) {

                val typedValue = TypedValue()

                itemView.context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
                val surfaceColor = typedValue.data

                val nightMode = when (itemView.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                    Configuration.UI_MODE_NIGHT_YES -> true
                    else -> false
                }

                val newColor = if (nightMode) {
                    ColorUtils.blendARGB(surfaceColor, Color.WHITE, 0.05f)
                } else {
                    ColorUtils.blendARGB(surfaceColor, Color.BLACK, 0.03f)
                }

                cardView.setCardBackgroundColor(newColor)
            }

            buttonCopy.setOnClickListener {
                copyToClipboard(item.key, item.value)
            }

            textViewValue.setOnLongClickListener {
                copyToClipboard(item.key, item.value)
                true
            }
        }

        private fun copyToClipboard(label: String, text: String) {
            val context = itemView.context
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, context.getString(R.string.copied_to_clipboard, label), Toast.LENGTH_SHORT).show()
        }
    }

    class DetailDiffCallback : DiffUtil.ItemCallback<DetailItem>() {
        override fun areItemsTheSame(oldItem: DetailItem, newItem: DetailItem): Boolean {
            return oldItem.key == newItem.key
        }

        override fun areContentsTheSame(oldItem: DetailItem, newItem: DetailItem): Boolean {
            return oldItem == newItem
        }
    }
}