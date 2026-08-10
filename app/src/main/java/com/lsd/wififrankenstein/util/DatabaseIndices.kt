package com.lsd.wififrankenstein.util

import android.database.sqlite.SQLiteDatabase
import com.lsd.wififrankenstein.ui.databasefinder.SearchMode

object DatabaseIndices {
    private const val TAG = "DatabaseIndices"


    const val GEO_QUADKEY = "idx_geo_quadkey"


    const val NETS_BSSID = "idx_nets_bssid"
    const val NETS_ESSID = "idx_nets_essid"
    const val NETS_TIME = "idx_nets_time"
    const val NETS_WIFI = "idx_nets_wifi"
    const val NETS_WIFIKEY = "idx_nets_wifipin"
    const val NETS_WPSPIN = "idx_nets_wpspin"


    const val BASE_BSSID = "idx_base_bssid"
    const val BASE_ESSID = "idx_base_essid"
    const val BASE_TIME = "idx_base_time"
    const val BASE_WIFI = "idx_base_wifi"
    const val BASE_WIFIKEY = "idx_base_wifipin"
    const val BASE_WPSPIN = "idx_base_wpspin"



    const val MAIN_TABLE_COLUMNS =
        "BSSID, ESSID, WiFiKey, WPSPIN, name, Authorization, RadioOff, Hidden, LANIP, WANIP, time, cmtid, iprange, ip, port, Security, NoWiFiKey, NoWPS, NoBSSID, LANMask, WANMask, WANGateway, DNS1, DNS2, DNS3"
    const val SEARCH_COLUMNS =
        "n.BSSID, n.ESSID, n.WiFiKey, n.WPSPIN, n.name, n.Authorization, n.Security, n.NoWiFiKey, n.NoBSSID, n.NoWPS, n.RadioOff, n.Hidden, n.cmtid, n.time, n.port, n.iprange, n.ip, n.LANIP, n.LANMask, n.WANIP, n.WANMask, n.WANGateway, n.DNS1, n.DNS2, n.DNS3"

    fun getOptimalGeoQuery(hasQuadkey: Boolean): String {
        return if (hasQuadkey) {
            "SELECT BSSID, latitude, longitude FROM geo WHERE quadkey >= ? AND quadkey <= ?"
        } else {
            "SELECT BSSID, latitude, longitude FROM geo WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?"
        }
    }

    fun getGeoQueryWithPrefix(prefix: String, zoomLevel: Int = 23): String {
        val totalChars = zoomLevel
        val paddingLength = totalChars - prefix.length
        val paddedMin = prefix + "0".repeat(paddingLength)
        val paddedMax = prefix + "3".repeat(paddingLength)
        val decimalMin = java.lang.Long.parseLong(paddedMin, 4).toString()
        val decimalMax = java.lang.Long.parseLong(paddedMax, 4).toString()
        return "SELECT BSSID, latitude, longitude FROM geo WHERE quadkey >= $decimalMin AND quadkey <= $decimalMax"
    }

    fun getOptimalBssidQuery(tableName: String): String {
        return "SELECT $SEARCH_COLUMNS, g.latitude, g.longitude FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE n.BSSID = ?"
    }

    fun getOptimalBssidFallbackQuery(tableName: String): String {
        return "SELECT $SEARCH_COLUMNS, g.latitude, g.longitude FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE CAST(n.BSSID AS TEXT) LIKE ?"
    }

    fun getOptimalEssidQuery(tableName: String, searchMode: SearchMode): String {
        val essidCondition = when (searchMode) {
            SearchMode.EXACT -> "(n.ESSID = ? OR n.ESSID = ? OR n.ESSID = ?)"
            SearchMode.PREFIX -> "(n.ESSID >= ? AND n.ESSID < ?) OR (n.ESSID >= ? AND n.ESSID < ?) OR (n.ESSID >= ? AND n.ESSID < ?)"
            SearchMode.SUBSTRING -> "n.ESSID LIKE ? ESCAPE '\\'"
        }
        return "SELECT $SEARCH_COLUMNS, g.latitude, g.longitude FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE $essidCondition"
    }

    fun getOptimalWifiKeyQuery(tableName: String, searchMode: SearchMode): String {
        val wifiKeyCondition = when (searchMode) {
            SearchMode.EXACT -> "(n.WiFiKey = ? OR n.WiFiKey = ? OR n.WiFiKey = ?)"
            SearchMode.PREFIX -> "(n.WiFiKey >= ? AND n.WiFiKey < ?) OR (n.WiFiKey >= ? AND n.WiFiKey < ?) OR (n.WiFiKey >= ? AND n.WiFiKey < ?)"
            SearchMode.SUBSTRING -> "n.WiFiKey LIKE ? ESCAPE '\\'"
        }
        return "SELECT $SEARCH_COLUMNS, g.latitude, g.longitude FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE $wifiKeyCondition"
    }

    fun getOptimalWpsPinQuery(tableName: String): String {
        return "SELECT $SEARCH_COLUMNS, g.latitude, g.longitude FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE n.WPSPIN = ?"
    }

    fun getOptimalSearchQuery(
        tableName: String,
        searchFields: Set<String>,
        searchMode: SearchMode
    ): String {
        val conditions = mutableListOf<String>()

        if ("BSSID" in searchFields) {
            conditions.add("n.BSSID = ?")
        }

        if ("ESSID" in searchFields) {
            when (searchMode) {
                SearchMode.EXACT -> conditions.add("(n.ESSID = ? OR n.ESSID = ? OR n.ESSID = ?)")
                SearchMode.PREFIX -> conditions.add("(n.ESSID >= ? AND n.ESSID < ?) OR (n.ESSID >= ? AND n.ESSID < ?) OR (n.ESSID >= ? AND n.ESSID < ?)")
                SearchMode.SUBSTRING -> conditions.add("n.ESSID LIKE ? ESCAPE '\'")
            }
        }

        if ("WiFiKey" in searchFields) {
            when (searchMode) {
                SearchMode.EXACT -> conditions.add("(n.WiFiKey = ? OR n.WiFiKey = ? OR n.WiFiKey = ?)")
                SearchMode.PREFIX -> conditions.add("(n.WiFiKey >= ? AND n.WiFiKey < ?) OR (n.WiFiKey >= ? AND n.WiFiKey < ?) OR (n.WiFiKey >= ? AND n.WiFiKey < ?)")
                SearchMode.SUBSTRING -> conditions.add("n.WiFiKey LIKE ? ESCAPE '\'")
            }
        }

        if ("WPSPIN" in searchFields) {
            conditions.add("n.WPSPIN = ?")
        }

        val whereClause = if (conditions.isEmpty()) "1=0" else conditions.joinToString(" OR ")

        return "SELECT $SEARCH_COLUMNS, g.latitude, g.longitude FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE $whereClause"
    }

    fun getOptimalPaginatedSearchQuery(
        tableName: String,
        searchFields: Set<String>,
        searchMode: SearchMode,
        limit: Int,
        offset: Int
    ): String {
        val baseQuery = getOptimalSearchQuery(tableName, searchFields, searchMode)
        return "$baseQuery LIMIT $limit OFFSET $offset"
    }

    fun checkIndexExists(db: SQLiteDatabase, indexName: String): Boolean {
        return try {
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?",
                arrayOf(indexName)
            ).use { it.count > 0 }
        } catch (e: Exception) {
            false
        }
    }

    enum class IndexLevel { NONE, BASIC, FULL }

    fun determineIndexLevel(db: SQLiteDatabase): IndexLevel {
        return try {
            val existingIndices = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='index'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    existingIndices.add(cursor.getString(0))
                }
            }

            Log.d(TAG, "Found indices: $existingIndices")

            val tableName = DatabaseTypeUtils.getMainTableName(db)
            Log.d(TAG, "Main table name: $tableName")

            val fullIndexes = when (tableName) {
                "nets" -> listOf(
                    GEO_QUADKEY,
                    NETS_BSSID,
                    NETS_ESSID,
                    NETS_TIME,
                    NETS_WIFI,
                    NETS_WIFIKEY,
                    NETS_WPSPIN
                )

                "base" -> listOf(
                    GEO_QUADKEY,
                    BASE_BSSID,
                    BASE_ESSID,
                    BASE_TIME,
                    BASE_WIFI,
                    BASE_WIFIKEY,
                    BASE_WPSPIN
                )

                else -> {
                    Log.w(TAG, "Unknown table name: $tableName")
                    emptyList()
                }
            }

            Log.d(TAG, "Required full indexes: $fullIndexes")


            val hasAnyIndex = existingIndices.any { !it.startsWith("sqlite_autoindex_") }
            Log.d(TAG, "hasAnyIndex: $hasAnyIndex")

            val result = when {
                fullIndexes.all { it in existingIndices } -> IndexLevel.FULL
                hasAnyIndex -> IndexLevel.BASIC
                else -> IndexLevel.NONE
            }
            Log.d(TAG, "Determined index level: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error determining index level", e)
            IndexLevel.NONE
        }
    }

    fun createRequiredIndexes(db: SQLiteDatabase, tableName: String) {
        val indexes = when (tableName) {
            "nets" -> listOf(
                "CREATE INDEX IF NOT EXISTS $NETS_BSSID ON nets(BSSID)",
                "CREATE INDEX IF NOT EXISTS $NETS_ESSID ON nets(ESSID)",
                "CREATE INDEX IF NOT EXISTS $NETS_WIFIKEY ON nets(WiFiKey)",
                "CREATE INDEX IF NOT EXISTS $NETS_WPSPIN ON nets(WPSPIN)"
            )

            "base" -> listOf(
                "CREATE INDEX IF NOT EXISTS $BASE_BSSID ON base(BSSID)",
                "CREATE INDEX IF NOT EXISTS $BASE_ESSID ON base(ESSID)",
                "CREATE INDEX IF NOT EXISTS $BASE_WIFIKEY ON base(WiFiKey)",
                "CREATE INDEX IF NOT EXISTS $BASE_WPSPIN ON base(WPSPIN)"
            )

            else -> emptyList()
        }

        val geoIndexes = listOf(
            "CREATE INDEX IF NOT EXISTS $GEO_QUADKEY ON geo(quadkey)"
        )

        indexes.forEach { sql ->
            try {
                db.execSQL(sql)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create index: $sql", e)
            }
        }

        geoIndexes.forEach { sql ->
            try {
                db.execSQL(sql)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create geo index: $sql", e)
            }
        }
    }
}
