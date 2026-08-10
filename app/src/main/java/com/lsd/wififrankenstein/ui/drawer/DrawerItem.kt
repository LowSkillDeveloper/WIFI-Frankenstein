package com.lsd.wififrankenstein.ui.drawer

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

sealed class DrawerItem {

    data class Header(
        val appName: String,
        val version: String,
        val modificationText: String? = null
    ) : DrawerItem()

    data class Category(
        val id: Int,
        @StringRes val titleRes: Int,
        var isExpanded: Boolean = true
    ) : DrawerItem()

    data class MenuItem(
        val id: Int,
        @DrawableRes val iconRes: Int,
        @StringRes val titleRes: Int,
        val navId: Int,
        val requirement: Requirement = Requirement.NONE
    ) : DrawerItem()

    enum class Requirement {
        NONE,
        ROOT,
        CHROOT,
        PROOT_CHROOT
    }
}
