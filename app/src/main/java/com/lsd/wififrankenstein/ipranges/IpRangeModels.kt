package com.lsd.wififrankenstein.ui.ipranges

import com.lsd.wififrankenstein.ui.dbsetup.DbItem

data class IpRangeSource(
    val id: String,
    val name: String,
    val type: IpRangeSourceType,
    val isSelected: Boolean,
    val dbItem: DbItem? = null
)

enum class IpRangeSourceType {
    LOCAL_DATABASE,
    API
}

data class IpRangeResult(
    val range: String,
    val netname: String,
    val description: String,
    val country: String,
    val sourceName: String
)