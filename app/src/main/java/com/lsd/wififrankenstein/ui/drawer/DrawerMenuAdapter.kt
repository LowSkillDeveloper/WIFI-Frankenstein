package com.lsd.wififrankenstein.ui.drawer

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.drawer.DrawerItem.Requirement

class DrawerMenuAdapter(
    private val onItemClick: (DrawerItem.MenuItem) -> Unit,
    private val onCategoryToggle: (Int) -> Unit
) : ListAdapter<DrawerItem, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_CATEGORY = 1
        private const val VIEW_TYPE_MENU_ITEM = 2
    }

    var currentNavId: Int? = null
    private var menuState = DrawerMenuProvider.MenuState()
    private var fullMenuList: List<DrawerItem> = emptyList()

    fun setMenu(items: List<DrawerItem>) {
        fullMenuList = items
        submitList(buildVisibleList(items))
    }

    fun updateRootStatus(enableRoot: Boolean) {
        menuState = menuState.copy(enableRoot = enableRoot)
        notifyItemRangeChanged(0, itemCount, PAYLOAD_ENABLED_STATE)
    }

    fun updateShowWithoutRoot(showWithoutRoot: Boolean) {
        menuState = menuState.copy(showRootWithoutRoot = showWithoutRoot)
        notifyItemRangeChanged(0, itemCount, PAYLOAD_ENABLED_STATE)
    }

    fun updateChrootState(hasChroot: Boolean, hasProot: Boolean) {
        menuState = menuState.copy(hasChroot = hasChroot, hasProot = hasProot)
        notifyItemRangeChanged(0, itemCount, PAYLOAD_ENABLED_STATE)
    }

    fun toggleCategory(categoryAdapterPosition: Int): Pair<Int, Boolean>? {
        val toggledCategory =
            getItem(categoryAdapterPosition) as? DrawerItem.Category ?: return null
        val updatedList = fullMenuList.map { item ->
            if (item is DrawerItem.Category && item.id == toggledCategory.id) {
                item.also { it.isExpanded = !it.isExpanded }
            } else {
                item
            }
        }
        fullMenuList = updatedList
        submitList(buildVisibleList(updatedList))
        return toggledCategory.id to toggledCategory.isExpanded
    }

    private fun buildVisibleList(items: List<DrawerItem>): List<DrawerItem> {
        val visible = mutableListOf<DrawerItem>()
        var currentCategoryExpanded = true
        for (item in items) {
            when (item) {
                is DrawerItem.Header -> {
                    visible.add(item)
                }

                is DrawerItem.Category -> {
                    visible.add(item)
                    currentCategoryExpanded = item.isExpanded
                }

                is DrawerItem.MenuItem -> {
                    if (currentCategoryExpanded) {
                        visible.add(item)
                    }
                }
            }
        }
        return visible
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is DrawerItem.Header -> VIEW_TYPE_HEADER
            is DrawerItem.Category -> VIEW_TYPE_CATEGORY
            is DrawerItem.MenuItem -> VIEW_TYPE_MENU_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.nav_header_main, parent, false)
                HeaderViewHolder(view)
            }

            VIEW_TYPE_CATEGORY -> {
                val view = inflater.inflate(R.layout.item_drawer_category, parent, false)
                CategoryViewHolder(view, onCategoryToggle)
            }

            else -> {
                val view = inflater.inflate(R.layout.item_drawer_menu, parent, false)
                MenuItemViewHolder(view, onItemClick)
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_ENABLED_STATE) && holder is MenuItemViewHolder) {
            val item = getItem(position) as? DrawerItem.MenuItem ?: return
            val enabled = DrawerMenuProvider.isItemEnabled(item, menuState)
            holder.bindEnabledState(item, enabled)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> {
                val header = getItem(position) as? DrawerItem.Header
                holder.bind(header)
            }

            is CategoryViewHolder -> {
                val category = getItem(position) as? DrawerItem.Category
                holder.bind(category)
            }

            is MenuItemViewHolder -> {
                val item = getItem(position) as? DrawerItem.MenuItem
                val enabled = DrawerMenuProvider.isItemEnabled(item, menuState)
                val isSelected = item?.navId == currentNavId
                holder.bind(item, enabled, isSelected)
            }
        }
    }

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.nav_header_title)
        private val subtitleView: TextView = itemView.findViewById(R.id.nav_header_subtitle)
        private val modificationView: TextView = itemView.findViewById(R.id.nav_header_modification)

        fun bind(header: DrawerItem.Header?) {
            val ctx = itemView.context
            titleView.text = header?.appName ?: ctx.getString(R.string.nav_header_title)
            subtitleView.text = header?.version ?: ""
            val modText = header?.modificationText
            if (modText != null) {
                modificationView.text = modText
                modificationView.setTextColor(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.error_red)
                )
                modificationView.visibility = View.VISIBLE
            } else {
                modificationView.visibility = View.GONE
            }
        }
    }

    class CategoryViewHolder(
        itemView: View,
        private val onToggle: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.category_title)
        private val chevron: ImageView = itemView.findViewById(R.id.chevron)

        init {
            itemView.setOnClickListener {
                onToggle(absoluteAdapterPosition)
            }
        }

        fun bind(category: DrawerItem.Category?) {
            if (category == null) return
            titleView.setText(category.titleRes)
            val rotation = if (category.isExpanded) 0f else -180f
            chevron.rotation = rotation
        }
    }

    class MenuItemViewHolder(
        itemView: View,
        private val onItemClick: (DrawerItem.MenuItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.icon)
        private val titleView: TextView = itemView.findViewById(R.id.title)
        private val badgeView: TextView = itemView.findViewById(R.id.badge)
        private var itemData: DrawerItem.MenuItem? = null

        fun bind(item: DrawerItem.MenuItem?, enabled: Boolean, isSelected: Boolean) {
            if (item == null) return
            itemData = item

            titleView.text = itemView.context.getString(item.titleRes)
            iconView.setImageResource(item.iconRes)
            val ctx = itemView.context
            val drawable = iconView.drawable?.mutate()
            if (drawable != null) {
                if (isSelected) {
                    val tv = TypedValue()
                    ctx.theme.resolveAttribute(android.R.attr.colorPrimary, tv, true)
                    val activeColor = if (tv.resourceId != 0) ContextCompat.getColor(
                        ctx,
                        tv.resourceId
                    ) else tv.data
                    DrawableCompat.setTint(drawable, activeColor)
                } else {
                    DrawableCompat.setTint(
                        drawable,
                        ContextCompat.getColor(ctx, R.color.text_secondary)
                    )
                }
            }
            bindEnabledState(item, enabled)
            itemView.isActivated = isSelected

            itemView.isEnabled = enabled
            itemView.setOnClickListener {
                if (enabled) onItemClick(item)
            }
        }

        fun bindEnabledState(item: DrawerItem.MenuItem, enabled: Boolean) {
            val ctx = itemView.context
            val alpha = if (enabled) 1.0f else 0.38f

            iconView.alpha = alpha
            titleView.alpha = alpha
            itemView.isEnabled = enabled
            itemView.setOnClickListener {
                if (enabled) onItemClick(item)
            }

            if (enabled) {
                badgeView.visibility = View.GONE
            } else {
                val badgeText = when (item.requirement) {
                    Requirement.ROOT -> ctx.getString(R.string.drawer_badge_root)
                    Requirement.CHROOT -> ctx.getString(R.string.drawer_badge_chroot)
                    Requirement.PROOT_CHROOT -> ctx.getString(R.string.drawer_badge_proot)
                    Requirement.NONE -> return
                }
                val bgColor = when (item.requirement) {
                    Requirement.ROOT -> R.color.drawer_badge_root_bg
                    Requirement.CHROOT -> R.color.drawer_badge_chroot_bg
                    Requirement.PROOT_CHROOT -> R.color.drawer_badge_proot_bg
                    Requirement.NONE -> return
                }
                badgeView.text = badgeText
                badgeView.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(ctx, bgColor))
                badgeView.visibility = View.VISIBLE
            }
        }

    }

    class DiffCallback : DiffUtil.ItemCallback<DrawerItem>() {
        override fun areItemsTheSame(oldItem: DrawerItem, newItem: DrawerItem): Boolean {
            return when {
                oldItem is DrawerItem.Header && newItem is DrawerItem.Header -> true
                oldItem is DrawerItem.Category && newItem is DrawerItem.Category ->
                    oldItem.id == newItem.id

                oldItem is DrawerItem.MenuItem && newItem is DrawerItem.MenuItem ->
                    oldItem.id == newItem.id

                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: DrawerItem, newItem: DrawerItem): Boolean {
            return when {
                oldItem is DrawerItem.Header && newItem is DrawerItem.Header ->
                    oldItem.appName == newItem.appName && oldItem.version == newItem.version

                oldItem is DrawerItem.Category && newItem is DrawerItem.Category ->
                    oldItem.titleRes == newItem.titleRes && oldItem.isExpanded == newItem.isExpanded

                oldItem is DrawerItem.MenuItem && newItem is DrawerItem.MenuItem ->
                    oldItem.navId == newItem.navId && oldItem.titleRes == newItem.titleRes &&
                            oldItem.iconRes == newItem.iconRes && oldItem.requirement == newItem.requirement

                else -> false
            }
        }

        override fun getChangePayload(oldItem: DrawerItem, newItem: DrawerItem): Any? {
            if (oldItem is DrawerItem.MenuItem && newItem is DrawerItem.MenuItem) {
                if (oldItem.id == newItem.id) return PAYLOAD_ENABLED_STATE
            }
            return null
        }
    }

    private object PAYLOAD_ENABLED_STATE
}
