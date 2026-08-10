package com.lsd.wififrankenstein.ui.handshakeconverter

import androidx.annotation.StringRes
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.HandshakeFormat

enum class TargetFormat(
    @StringRes val labelRes: Int,
    val extension: String,
    val mime: String,
    val requiresChroot: Boolean = false
) {
    HASH_22000(R.string.handshake_converter_fmt_22000, "22000", "text/plain"),
    HCCAPX(R.string.handshake_converter_fmt_hccapx, "hccapx", "application/octet-stream"),
    HCCAP(R.string.handshake_converter_fmt_hccap, "hccap", "application/octet-stream"),
    PMKID(R.string.handshake_converter_fmt_pmkid, "pmkid", "text/plain"),
    HASH_16800(R.string.handshake_converter_fmt_16800, "16800", "text/plain"),
    CAP(
        R.string.handshake_converter_fmt_cap,
        "cap",
        "application/octet-stream",
        requiresChroot = true
    )
}

data class ConvertFileItem(
    val id: String,
    val filePath: String,
    val fileName: String,
    val detectedFormat: HandshakeFormat,
    val hash22000Lines: List<String>,
    val hasEapol: Boolean,
    val hasPmkid: Boolean,
    val availableTargets: List<TargetFormat>,
    var selectedTarget: TargetFormat,
    val error: String? = null
) {
    val isSupported: Boolean
        get() = error == null && availableTargets.isNotEmpty()
}

data class ConvertResultItem(
    val sourceName: String,
    val outputPath: String,
    val target: TargetFormat,
    val success: Boolean,
    val error: String? = null
) {
    val suggestedFileName: String
        get() {
            val base = sourceName.substringBeforeLast('.')
            return if (target == TargetFormat.PMKID || target == TargetFormat.HASH_16800) {
                "${base}_${target.extension}.txt"
            } else {
                "$base.${target.extension}"
            }
        }
}
