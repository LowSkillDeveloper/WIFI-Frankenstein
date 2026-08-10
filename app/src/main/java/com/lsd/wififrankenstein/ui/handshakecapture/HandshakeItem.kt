package com.lsd.wififrankenstein.ui.handshakecapture

data class HandshakeItem(
    val filePath: String,
    val fileName: String,
    val bssid: String?,
    val essid: String?,
    val fileSize: Long,
    val lastModified: Long,
    val crackedPassword: String? = null,
    val isValid: Boolean? = null,
    val hash22000: String? = null,
    val hashPmkid: String? = null,
    val fileExists: Boolean = true,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val uploadedToWpaSec: Boolean = false,
    val wpasecKey: String? = null,
    val wpasecChecked: Boolean = false,
    val wpasecPasswordFound: Boolean = false,
    val wpasecPassword: String? = null,
    val originalFormat: String? = null,
    val handshakeCount: Int = 0,
    val eapolCount: Int = 0,
    val pmkidCount: Int = 0,
    val keyver: Int? = null,
    val nonceErrorCorrection: Int? = null,
    val endianness: String? = null,
    val uploadedToOhc: Boolean = false,
    val requestIdOhc: String? = null,
    val ohcEmail: String? = null,
    val hashDedupMd5: String? = null,
    val clients: String? = null,
    val channel: Int? = null,
    val band: String? = null,
    val akm: String? = null,
    val groupCipher: String? = null,
    val pairwiseCipher: String? = null,
    val rssi: Int? = null,
    val eapolM1Count: Int = 0,
    val eapolM2Count: Int = 0,
    val eapolM3Count: Int = 0,
    val eapolM4Count: Int = 0,
    val beaconCount: Int = 0,
    val assocReqCount: Int = 0,
    val authCount: Int = 0,
    val probeReqCount: Int = 0,
    val hash16800: String? = null,
    val apsInFile: String? = null
) {
    val hasPmkid: Boolean
        get() = hashPmkid != null

    val isInvalidMacFilename: Boolean
        get() {
            val nameWithoutExt = fileName.substringBeforeLast('.')
            val clean = nameWithoutExt.replace(Regex("[^a-fA-F0-9]"), "")
            if (clean.length != 12) return false
            val firstOctet = clean.substring(0, 2).toIntOrNull(16) ?: return false
            return firstOctet > 0 && clean.substring(2) == "0000000000"
        }

    val isInvalidBssid: Boolean
        get() {
            if (bssid == null) return true
            val clean = bssid.replace(Regex("[^a-fA-F0-9]"), "")
            if (clean.length != 12) return true
            val firstOctet = clean.substring(0, 2).toIntOrNull(16) ?: return true
            return firstOctet > 0 && clean.substring(2) == "0000000000"
        }

    val displayName: String
        get() = when {
            essid.isNullOrBlank() && (bssid == null || isInvalidBssid) -> fileName
            isInvalidBssid -> essid ?: fileName
            essid.isNullOrBlank() -> bssid ?: fileName
            else -> "$essid ($bssid)"
        }

    val formattedSize: String
        get() = when {
            fileSize < 1024 -> "$fileSize B"
            fileSize < 1024 * 1024 -> "${fileSize / 1024} KB"
            else -> "${"%.1f".format(fileSize / (1024.0 * 1024.0))} MB"
        }

    val dateFormatted: String
        get() {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(lastModified))
        }
}
