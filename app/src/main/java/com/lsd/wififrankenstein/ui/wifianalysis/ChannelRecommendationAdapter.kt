package com.lsd.wififrankenstein.ui.wifianalysis

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemChannelRecommendationBinding

class ChannelRecommendationAdapter :
    ListAdapter<ChannelRecommendation, ChannelRecommendationAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelRecommendationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemChannelRecommendationBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(recommendation: ChannelRecommendation) {
            binding.apply {
                textViewRecommendationTitle.text = itemView.context.getString(
                    R.string.channel_with_frequency,
                    recommendation.channel,
                    recommendation.frequency
                )

                textViewRecommendationDescription.text =
                    itemView.context.getString(recommendation.reason.toResource())

                chipRecommendationScore.text =
                    itemView.context.getString(R.string.score_percent, recommendation.score)

                val iconColor = when (recommendation.interferenceLevel) {
                    InterferenceLevel.NONE -> ContextCompat.getColor(
                        itemView.context,
                        R.color.green_500
                    )

                    InterferenceLevel.LOW -> ContextCompat.getColor(
                        itemView.context,
                        R.color.green_500
                    )

                    InterferenceLevel.MODERATE -> ContextCompat.getColor(
                        itemView.context,
                        R.color.orange_500
                    )

                    InterferenceLevel.HIGH -> ContextCompat.getColor(
                        itemView.context,
                        R.color.red_500
                    )

                    InterferenceLevel.SEVERE -> ContextCompat.getColor(
                        itemView.context,
                        R.color.red_500
                    )
                }

                imageViewRecommendationIcon.setColorFilter(iconColor)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ChannelRecommendation>() {
        override fun areItemsTheSame(
            oldItem: ChannelRecommendation,
            newItem: ChannelRecommendation
        ): Boolean {
            return oldItem.channel == newItem.channel && oldItem.band == newItem.band
        }

        override fun areContentsTheSame(
            oldItem: ChannelRecommendation,
            newItem: ChannelRecommendation
        ): Boolean {
            return oldItem == newItem
        }
    }
}
