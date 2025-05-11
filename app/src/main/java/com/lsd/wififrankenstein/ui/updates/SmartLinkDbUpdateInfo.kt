package com.lsd.wififrankenstein.ui.updates

import com.lsd.wififrankenstein.ui.dbsetup.DbItem

data class SmartLinkDbUpdateInfo(
    val dbItem: DbItem,
    val serverVersion: String,
    val downloadUrl: String,
    val needsUpdate: Boolean,
    val isUpdating: Boolean = false,
    val updateProgress: Int = 0
)