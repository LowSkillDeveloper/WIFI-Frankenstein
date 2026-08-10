package com.lsd.wififrankenstein.ui.drawer

import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.drawer.DrawerItem.Requirement

object DrawerMenuProvider {

    data class MenuState(
        val enableRoot: Boolean = false,
        val showRootWithoutRoot: Boolean = false,
        val hasChroot: Boolean = false,
        val hasProot: Boolean = false
    )

    fun createMenu(collapsedCategoryIds: Set<String> = emptySet()): List<DrawerItem> = listOf(
        DrawerItem.MenuItem(
            101, R.drawable.apps_24px,
            R.string.menu_all_features, R.id.nav_all_features
        ),

        DrawerItem.Category(
            2,
            R.string.drawer_category_core_tools,
            isExpanded = "2" !in collapsedCategoryIds
        ),
        DrawerItem.MenuItem(
            201,
            R.drawable.baseline_wifi_find_24,
            R.string.menu_wifi_scanner,
            R.id.nav_wifi_scanner
        ),
        DrawerItem.MenuItem(
            202,
            R.drawable.database_search_24px,
            R.string.menu_database_finder,
            R.id.nav_database_finder
        ),
        DrawerItem.MenuItem(
            203,
            R.drawable.ic_menu_mapmode,
            R.string.menu_wifi_map,
            R.id.nav_wifi_map
        ),
        DrawerItem.MenuItem(
            204,
            R.drawable.ic_location,
            R.string.menu_mac_location,
            R.id.nav_mac_location
        ),
        DrawerItem.MenuItem(
            205,
            R.drawable.ic_database,
            R.string.menu_in_app_database,
            R.id.nav_in_app_database
        ),

        DrawerItem.Category(
            3,
            R.string.drawer_category_api_network,
            isExpanded = "3" !in collapsedCategoryIds
        ),
        DrawerItem.MenuItem(
            301,
            R.drawable.outline_arrow_upload_ready_24,
            R.string.menu_api_query,
            R.id.nav_api_query
        ),
        DrawerItem.MenuItem(
            302,
            R.drawable.cloud_download_24px,
            R.string.menu_upload_routerscan,
            R.id.nav_upload_routerscan
        ),
        DrawerItem.MenuItem(
            303,
            R.drawable.router_24px,
            R.string.menu_router_scan,
            R.id.nav_router_scan,
            Requirement.PROOT_CHROOT
        ),

        DrawerItem.Category(
            4,
            R.string.drawer_category_handshakes,
            isExpanded = "4" !in collapsedCategoryIds
        ),
        DrawerItem.MenuItem(
            401,
            R.drawable.grid_3x3_24px,
            R.string.menu_handshake_capture_selector,
            R.id.nav_handshake_capture_selector,
            Requirement.CHROOT
        ),
        DrawerItem.MenuItem(
            402,
            R.drawable.home_storage_24px,
            R.string.handshake_storage_title,
            R.id.nav_handshake_storage
        ),
        DrawerItem.MenuItem(
            403,
            R.drawable.ic_lock_open,
            R.string.menu_wpa_cracker,
            R.id.nav_wpa_cracker
        ),
        DrawerItem.MenuItem(
            404,
            R.drawable.swap_horizontal_circle_24px,
            R.string.menu_handshake_converter,
            R.id.nav_handshake_converter
        ),

        DrawerItem.Category(
            5,
            R.string.drawer_category_root_tools,
            isExpanded = "5" !in collapsedCategoryIds
        ),
        DrawerItem.MenuItem(
            501,
            R.drawable.android_wifi_3_bar_plus_24px,
            R.string.menu_iw_wifi_scanner,
            R.id.nav_iw_wifi_scanner,
            Requirement.ROOT
        ),
        DrawerItem.MenuItem(
            503,
            R.drawable.ic_lock_open,
            R.string.menu_bruteforce,
            R.id.nav_bruteforce
        ),
        DrawerItem.MenuItem(
            504,
            R.drawable.ic_key,
            R.string.menu_pixiedust,
            R.id.nav_pixie_dust,
            Requirement.ROOT
        ),

        DrawerItem.Category(
            6,
            R.string.drawer_category_network_diagnostics,
            isExpanded = "6" !in collapsedCategoryIds
        ),
        DrawerItem.MenuItem(
            601,
            R.drawable.ic_layers,
            R.string.menu_local_network,
            R.id.nav_local_network
        ),
        DrawerItem.MenuItem(
            602,
            R.drawable.vpn_lock_2_24px,
            R.string.menu_internet_blocking,
            R.id.nav_internet_blocking
        ),

        DrawerItem.Category(
            7,
            R.string.drawer_category_generators,
            isExpanded = "7" !in collapsedCategoryIds
        ),
        DrawerItem.MenuItem(
            701,
            R.drawable.ic_key,
            R.string.menu_wps_generator,
            R.id.nav_wps_generator
        ),
        DrawerItem.MenuItem(
            702,
            R.drawable.ic_lock_open,
            R.string.wpa_generator_title,
            R.id.nav_wpa_generator
        ),

        DrawerItem.Category(
            8,
            R.string.drawer_category_utilities,
            isExpanded = "8" !in collapsedCategoryIds
        ),
        DrawerItem.MenuItem(
            801,
            R.drawable.ic_wifi,
            R.string.menu_wifi_analysis,
            R.id.nav_wifi_analysis
        ),
        DrawerItem.MenuItem(
            802,
            R.drawable.ic_qr_code,
            R.string.menu_qr_generator,
            R.id.nav_qr_generator
        ),
        DrawerItem.MenuItem(
            803,
            R.drawable.ic_file,
            R.string.menu_saved_passwords,
            R.id.nav_saved_passwords,
            Requirement.ROOT
        ),

        DrawerItem.Category(
            9,
            R.string.drawer_category_footer,
            isExpanded = "9" !in collapsedCategoryIds
        ),
        DrawerItem.MenuItem(
            901,
            R.drawable.outline_archive_24,
            R.string.menu_updates,
            R.id.nav_updates
        ),
        DrawerItem.MenuItem(
            902,
            R.drawable.outline_build_circle_24,
            R.string.menu_settings,
            R.id.nav_settings
        ),
        DrawerItem.MenuItem(903, R.drawable.ic_info, R.string.menu_about, R.id.nav_about)
    )

    fun isRequirementEnabled(requirement: Requirement, state: MenuState): Boolean {
        if (state.showRootWithoutRoot) return true
        return when (requirement) {
            Requirement.NONE -> true
            Requirement.ROOT -> state.enableRoot
            Requirement.CHROOT -> state.hasChroot
            Requirement.PROOT_CHROOT -> state.hasChroot || state.hasProot
        }
    }

    fun isItemEnabled(item: DrawerItem.MenuItem?, state: MenuState): Boolean {
        if (item == null) return false
        return isRequirementEnabled(item.requirement, state)
    }
}
