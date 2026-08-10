package com.lsd.wififrankenstein.util

data class RuntimeConfig(
    val type: RuntimeType,
    val useLink2Symlink: Boolean = true,
    val useTmpBind: Boolean = false,
    val tmpDir: String? = null
)
