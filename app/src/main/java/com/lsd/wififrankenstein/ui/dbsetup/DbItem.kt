package com.lsd.wififrankenstein.ui.dbsetup

import kotlinx.serialization.Serializable

@Serializable
enum class DbType {
    SQLITE_FILE_P3WIFI,
    SQLITE_FILE_CUSTOM,
    WIFI_API,
    SMARTLINK_SQLITE_FILE_P3WIFI,
    SMARTLINK_SQLITE_FILE_CUSTOM,
    LOCAL_APP_DB,
    HANDSHAKE_STORAGE
}

@Serializable
enum class AuthMethod {
    API_KEYS,
    LOGIN_PASSWORD,
    NO_AUTH
}

@Serializable
enum class DbIndexLevel {
    NONE,
    PARTIAL,
    FULL
}

@Serializable
data class DbItem(
    val id: String,
    val path: String,
    val directPath: String?,
    val type: String,
    val dbType: DbType,
    var isMain: Boolean = false,
    val apiKey: String? = null,
    val apiReadKey: String? = null,
    val apiWriteKey: String? = null,
    val login: String? = null,
    val password: String? = null,
    val authMethod: AuthMethod? = null,
    val userNick: String? = null,
    val userLevel: Int? = null,
    val originalSizeInMB: Float,
    var cachedSizeInMB: Float,
    val tableName: String? = null,
    val columnMap: Map<String, String>? = null,
    val idJson: String? = null,
    val version: String? = null,
    val updateUrl: String? = null,
    val smartlinkType: String? = null,
    var indexLevel: DbIndexLevel = DbIndexLevel.NONE,
    var supportsMapApi: Boolean = false,
    var supportsQuadkey: Boolean = false,
    val quadkeyLatColumn: String? = null,
    val quadkeyLonColumn: String? = null,
    val apiProtocol: String? = null,
    val jwtToken: String? = null,
    var oldFormatWarning: String? = null
)