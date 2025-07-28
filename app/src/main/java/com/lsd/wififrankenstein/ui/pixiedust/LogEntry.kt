package com.lsd.wififrankenstein.ui.pixiedust

data class LogEntry(
    val message: String,
    val colorType: LogColorType = LogColorType.NORMAL
)

enum class LogColorType {
    NORMAL,    // черный
    SUCCESS,   // зеленый
    INFO,      // синий
    ERROR,     // красный
    HIGHLIGHT
}