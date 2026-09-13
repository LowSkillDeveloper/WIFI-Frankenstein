package com.lsd.wififrankenstein.ui.personalmap

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemRecentNetworkBinding
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.PersonalMapDbHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class RecentNetworksAdapter(
    private val onItemClick: ((PersonalMapLogEntry) -> Unit)? = null
) :
    ListAdapter<PersonalMapLogEntry, RecentNetworksAdapter.ViewHolder>(DIFF) {

    var showBadge: Boolean = true

    private val timeFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
                timeZone = TimeZone.getDefault()
            }
    }
    private val tintCache = HashMap<Int, ColorStateList>(5)

    private fun tintFor(context: Context, colorRes: Int): ColorStateList =
        tintCache.getOrPut(colorRes) {
            ColorStateList.valueOf(ContextCompat.getColor(context, colorRes))
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentNetworkBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemRecentNetworkBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClick?.invoke(getItem(pos))
                }
            }
            binding.root.isClickable = onItemClick != null
        }

        fun bind(item: PersonalMapLogEntry) {
            val context = binding.root.context
            val ssid = item.ssid.ifBlank { context.getString(R.string.unknown_ssid) }
            binding.textSsid.text = ssid
            binding.textBssid.text = item.bssid
            binding.textLevel.text = context.getString(R.string.rssi_dbm, item.level)
            binding.textTime.text = timeFormatter.get()?.format(Date(item.timestamp)).orEmpty()

            binding.signalDot.backgroundTintList = tintFor(context, signalColorRes(item.level))

            binding.textBadge.visibility = if (showBadge) View.VISIBLE else View.GONE
            if (showBadge) {
                binding.textBadge.text = context.getString(
                    if (item.isNew) R.string.badge_new else R.string.badge_updated
                )
                binding.textBadge.backgroundTintList = tintFor(
                    context,
                    if (item.isNew) R.color.signal_good else R.color.badge_updated
                )
            }

            val crossPresent = item.otherDbState == PersonalMapDbHelper.STATE_PRESENT
            val wpasecPresent = item.wpasecState == PersonalMapDbHelper.STATE_PRESENT
            if (crossPresent || wpasecPresent) {
                binding.textCrossBadge.visibility = View.VISIBLE
                binding.textCrossBadge.text = context.getString(
                    when {
                        crossPresent && wpasecPresent -> R.string.badge_in_db_and_wpasec
                        crossPresent -> R.string.badge_in_db
                        else -> R.string.badge_wpasec
                    }
                )
                binding.textCrossBadge.backgroundTintList = tintFor(
                    context,
                    if (wpasecPresent) R.color.teal_700 else R.color.signal_fair
                )
            } else {
                binding.textCrossBadge.visibility = View.GONE
            }

            if (item.isWps) {
                binding.textWpsBadge.visibility = View.VISIBLE
                binding.textWpsBadge.text = context.getString(R.string.badge_wps)
                binding.textWpsBadge.backgroundTintList = tintFor(context, R.color.blue_500)
            } else if (item.isPasspoint) {
                binding.textWpsBadge.visibility = View.VISIBLE
                binding.textWpsBadge.text = context.getString(R.string.badge_passpoint)
                binding.textWpsBadge.backgroundTintList = tintFor(context, R.color.signal_fair)
            } else if (item.isHidden) {
                binding.textWpsBadge.visibility = View.VISIBLE
                binding.textWpsBadge.text = context.getString(R.string.badge_hidden)
                binding.textWpsBadge.backgroundTintList = tintFor(context, R.color.badge_updated)
            } else {
                binding.textWpsBadge.visibility = View.GONE
            }

            if (item.isOpen) {
                binding.textOpenBadge.visibility = View.VISIBLE
                binding.textOpenBadge.text = context.getString(R.string.badge_open)
                binding.textOpenBadge.backgroundTintList = tintFor(context, R.color.signal_good)
            } else {
                binding.textOpenBadge.visibility = View.GONE
            }

            if (item.tags.isNotEmpty()) {
                binding.textTags.visibility = View.VISIBLE
                binding.textTags.text = item.tags.joinToString(" · ")
            } else {
                binding.textTags.visibility = View.GONE
            }
            val signalQuality = when {
                item.level >= SIGNAL_GOOD_THRESHOLD -> context.getString(R.string.signal_quality_strong)
                item.level >= SIGNAL_FAIR_THRESHOLD -> context.getString(R.string.signal_quality_fair)
                else -> context.getString(R.string.signal_quality_weak)
            }
            binding.root.contentDescription = context.getString(
                R.string.pm_network_item_description, ssid, item.bssid, item.level, signalQuality
            )
        }
    }

    private fun signalColorRes(level: Int): Int = when {
        level >= SIGNAL_GOOD_THRESHOLD -> R.color.signal_good
        level >= SIGNAL_FAIR_THRESHOLD -> R.color.signal_fair
        else -> R.color.signal_poor
    }

    companion object {
        private const val SIGNAL_GOOD_THRESHOLD = -60
        private const val SIGNAL_FAIR_THRESHOLD = -75

        private val DIFF = object : DiffUtil.ItemCallback<PersonalMapLogEntry>() {
            override fun areItemsTheSame(oldItem: PersonalMapLogEntry, newItem: PersonalMapLogEntry) =
                oldItem.bssid.equals(newItem.bssid, ignoreCase = true) &&
                    oldItem.timestamp == newItem.timestamp

            override fun areContentsTheSame(
                oldItem: PersonalMapLogEntry,
                newItem: PersonalMapLogEntry
            ) = oldItem == newItem
        }
    }
}
