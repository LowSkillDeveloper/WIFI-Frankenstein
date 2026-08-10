package com.lsd.wififrankenstein.util

import androidx.annotation.DrawableRes

data class BottomSheetMenuItem(
    val id: Int,
    val title: String,
    @DrawableRes val iconResId: Int? = null,
    val enabled: Boolean = true,
    val visible: Boolean = true
)
