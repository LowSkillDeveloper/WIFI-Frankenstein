package com.lsd.wififrankenstein.ui.allfeatures

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemAllFeatureBinding
import com.lsd.wififrankenstein.databinding.ItemAllFeatureCompactBinding
import com.lsd.wififrankenstein.databinding.ItemAllFeatureGridBinding
import com.lsd.wififrankenstein.databinding.ItemAllFeatureGridCompactBinding
import com.lsd.wififrankenstein.ui.drawer.DrawerItem.Requirement
import com.lsd.wififrankenstein.ui.drawer.DrawerMenuProvider
import com.lsd.wififrankenstein.ui.drawer.DrawerMenuProvider.MenuState

class AllFeaturesAdapter(
    private val onFeatureClick: (FeatureItem) -> Unit
) : ListAdapter<FeatureItem, RecyclerView.ViewHolder>(DiffCallback) {

    var menuState: MenuState = MenuState()

    var viewMode: ViewMode = ViewMode.LIST_NORMAL
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun getItemViewType(position: Int): Int {
        return viewMode.ordinal
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewMode) {
            ViewMode.LIST_NORMAL -> {
                val binding = ItemAllFeatureBinding.inflate(inflater, parent, false)
                NormalViewHolder(binding, onFeatureClick)
            }

            ViewMode.LIST_COMPACT -> {
                val binding = ItemAllFeatureCompactBinding.inflate(inflater, parent, false)
                CompactViewHolder(binding, onFeatureClick)
            }

            ViewMode.GRID -> {
                val binding = ItemAllFeatureGridBinding.inflate(inflater, parent, false)
                GridViewHolder(binding, onFeatureClick)
            }

            ViewMode.GRID_COMPACT -> {
                val binding = ItemAllFeatureGridCompactBinding.inflate(inflater, parent, false)
                CompactGridViewHolder(binding, onFeatureClick)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val enabled = DrawerMenuProvider.isRequirementEnabled(
            getItem(position).requirement, menuState
        )
        when (holder) {
            is NormalViewHolder -> holder.bind(getItem(position), enabled)
            is CompactViewHolder -> holder.bind(getItem(position), enabled)
            is GridViewHolder -> holder.bind(getItem(position), enabled)
            is CompactGridViewHolder -> holder.bind(getItem(position), enabled)
        }
    }

    inner class NormalViewHolder(
        private val binding: ItemAllFeatureBinding,
        private val onFeatureClick: (FeatureItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FeatureItem, enabled: Boolean) {
            val context = binding.root.context
            binding.featureIcon.setImageDrawable(ContextCompat.getDrawable(context, item.iconRes))
            binding.featureTitle.setText(item.titleRes)
            binding.featureDescription.setText(item.descriptionRes)
            binding.root.alpha = if (enabled) 1.0f else 0.55f
            binding.root.isEnabled = enabled
            binding.root.setOnClickListener {
                if (enabled) onFeatureClick(item)
            }
            bindBadge(item, enabled, binding.badge)
        }
    }

    inner class CompactViewHolder(
        private val binding: ItemAllFeatureCompactBinding,
        private val onFeatureClick: (FeatureItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FeatureItem, enabled: Boolean) {
            val context = binding.root.context
            binding.featureIcon.setImageDrawable(ContextCompat.getDrawable(context, item.iconRes))
            binding.featureTitle.setText(item.titleRes)
            binding.root.alpha = if (enabled) 1.0f else 0.55f
            binding.root.isEnabled = enabled
            binding.root.setOnClickListener {
                if (enabled) onFeatureClick(item)
            }
            bindBadge(item, enabled, binding.badge)
        }
    }

    inner class GridViewHolder(
        private val binding: ItemAllFeatureGridBinding,
        private val onFeatureClick: (FeatureItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FeatureItem, enabled: Boolean) {
            val context = binding.root.context
            binding.featureIcon.setImageDrawable(ContextCompat.getDrawable(context, item.iconRes))
            binding.featureTitle.setText(item.titleRes)
            binding.featureDescription.setText(item.descriptionRes)
            binding.root.alpha = if (enabled) 1.0f else 0.55f
            binding.root.isEnabled = enabled
            binding.root.setOnClickListener {
                if (enabled) onFeatureClick(item)
            }
            bindBadge(item, enabled, binding.badge)
        }
    }

    inner class CompactGridViewHolder(
        private val binding: ItemAllFeatureGridCompactBinding,
        private val onFeatureClick: (FeatureItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FeatureItem, enabled: Boolean) {
            val context = binding.root.context
            binding.featureIcon.setImageDrawable(ContextCompat.getDrawable(context, item.iconRes))
            binding.featureTitle.setText(item.titleRes)
            binding.root.alpha = if (enabled) 1.0f else 0.55f
            binding.root.isEnabled = enabled
            binding.root.setOnClickListener {
                if (enabled) onFeatureClick(item)
            }
            bindBadge(item, enabled, binding.badge)
        }
    }

    private fun bindBadge(item: FeatureItem, enabled: Boolean, badge: TextView) {
        if (enabled || item.requirement == Requirement.NONE) {
            badge.visibility = View.GONE
            return
        }
        val context = badge.context
        badge.text = when (item.requirement) {
            Requirement.ROOT -> context.getString(R.string.drawer_badge_root)
            Requirement.CHROOT -> context.getString(R.string.drawer_badge_chroot)
            Requirement.PROOT_CHROOT -> context.getString(R.string.drawer_badge_proot)
        }
        val bgColor = when (item.requirement) {
            Requirement.ROOT -> R.color.drawer_badge_root_bg
            Requirement.CHROOT -> R.color.drawer_badge_chroot_bg
            Requirement.PROOT_CHROOT -> R.color.drawer_badge_proot_bg
        }
        badge.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, bgColor)
        )
        badge.visibility = View.VISIBLE
    }

    enum class ViewMode {
        LIST_NORMAL,
        LIST_COMPACT,
        GRID,
        GRID_COMPACT
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<FeatureItem>() {
            override fun areItemsTheSame(old: FeatureItem, new: FeatureItem) =
                old.navId == new.navId

            override fun areContentsTheSame(old: FeatureItem, new: FeatureItem) = old == new
        }
    }
}
