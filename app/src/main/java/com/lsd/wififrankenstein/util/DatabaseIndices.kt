package com.lsd.wififrankenstein.util

import android.database.sqlite.SQLiteDatabase
import android.util.Log

object DatabaseIndices {
    private const val TAG = "DatabaseIndices"

    // Geo indices
    const val GEO_BSSID = "idx_geo_BSSID"
    const val GEO_LATITUDE = "idx_geo_latitude"
    const val GEO_LONGITUDE = "idx_geo_longitude"

    // Base indices
    const val BASE_BSSID = "idx_base_BSSID"
    const val BASE_ESSID = "idx_base_ESSID"
    const val BASE_WPSPIN = "idx_base_wpspin"
    const val BASE_WIFIKEY = "idx_base_wifikey"

    // Nets indices
    const val NETS_BSSID = "idx_nets_BSSID"
    const val NETS_ESSID = "idx_nets_ESSID"
    const val NETS_WPSPIN = "idx_nets_wpspin"
    const val NETS_WIFIKEY = "idx_nets_wifikey"

    enum class IndexLevel {
        NONE,
        BASIC,
        FULL
    }

    fun determineIndexLevel(db: SQLiteDatabase): IndexLevel {
        try {
            val existingIndices = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='index'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    existingIndices.add(cursor.getString(0))
                }
            }

            Log.d(TAG, "Found indices: $existingIndices")

            val hasGeoIndices = listOf(GEO_BSSID, GEO_LATITUDE, GEO_LONGITUDE).all { it in existingIndices }

            if (!hasGeoIndices) {
                Log.d(TAG, "No geo indices found - IndexLevel.NONE")
                return IndexLevel.NONE
            }

            val hasNets = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='nets'", null).use { it.count > 0 }
            val hasBase = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='base'", null).use { it.count > 0 }

            return when {
                hasNets -> {
                    val hasNetsBasic = listOf(NETS_BSSID, NETS_ESSID).all { it in existingIndices }
                    val hasNetsFull = listOf(NETS_WPSPIN, NETS_WIFIKEY).all { it in existingIndices }

                    when {
                        hasNetsBasic && hasNetsFull -> {
                            Log.d(TAG, "Full nets indices found - IndexLevel.FULL")
                            IndexLevel.FULL
                        }
                        hasNetsBasic -> {
                            Log.d(TAG, "Basic nets indices found - IndexLevel.BASIC")
                            IndexLevel.BASIC
                        }
                        else -> {
                            Log.d(TAG, "No proper nets indices - IndexLevel.NONE")
                            IndexLevel.NONE
                        }
                    }
                }
                hasBase -> {
                    val hasBaseBasic = listOf(BASE_BSSID, BASE_ESSID).all { it in existingIndices }
                    val hasBaseFull = listOf(BASE_WPSPIN, BASE_WIFIKEY).all { it in existingIndices }

                    when {
                        hasBaseBasic && hasBaseFull -> {
                            Log.d(TAG, "Full base indices found - IndexLevel.FULL")
                            IndexLevel.FULL
                        }
                        hasBaseBasic -> {
                            Log.d(TAG, "Basic base indices found - IndexLevel.BASIC")
                            IndexLevel.BASIC
                        }
                        else -> {
                            Log.d(TAG, "No proper base indices - IndexLevel.NONE")
                            IndexLevel.NONE
                        }
                    }
                }
                else -> {
                    Log.d(TAG, "No base/nets table - IndexLevel.NONE")
                    IndexLevel.NONE
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error determining index level", e)
            return IndexLevel.NONE
        }
    }

    fun getOptimalBssidQuery(indexLevel: IndexLevel, tableName: String, joinGeo: Boolean = true): String {
        return when {
            joinGeo -> {
                "SELECT n.*, g.latitude, g.longitude FROM $tableName n " +
                        "LEFT JOIN geo g ON n.BSSID = g.BSSID " +
                        "WHERE n.BSSID IN (?)"
            }
            else -> {
                "SELECT * FROM $tableName WHERE BSSID IN (?)"
            }
        }
    }

    fun getOptimalBssidSearchQuery(indexLevel: IndexLevel, tableName: String): String {
        return "SELECT DISTINCT n.*, g.latitude, g.longitude " +
                "FROM $tableName n " +
                "LEFT JOIN geo g ON n.BSSID = g.BSSID " +
                "WHERE n.BSSID = ?"
    }

    fun getOptimalBssidFallbackQuery(indexLevel: IndexLevel, tableName: String): String {
        return "SELECT DISTINCT n.*, g.latitude, g.longitude " +
                "FROM $tableName n " +
                "LEFT JOIN geo g ON n.BSSID = g.BSSID " +
                "WHERE CAST(n.BSSID AS TEXT) LIKE ?"
    }

    fun getOptimalEssidSearchQuery(indexLevel: IndexLevel, tableName: String, wholeWords: Boolean): String {
        val essidCondition = if (wholeWords) {
            "n.ESSID = ? COLLATE NOCASE"
        } else {
            "n.ESSID LIKE ? ESCAPE '\\'"
        }

        return "SELECT DISTINCT n.*, g.latitude, g.longitude " +
                "FROM $tableName n " +
                "LEFT JOIN geo g ON n.BSSID = g.BSSID " +
                "WHERE $essidCondition"
    }


    fun getOptimalWifiKeySearchQuery(indexLevel: IndexLevel, tableName: String, wholeWords: Boolean): String {
        val wifiKeyCondition = if (wholeWords) {
            "n.WiFiKey = ? COLLATE NOCASE"
        } else {
            "n.WiFiKey LIKE ? ESCAPE '\\'"
        }

        return "SELECT DISTINCT n.*, g.latitude, g.longitude " +
                "FROM $tableName n " +
                "LEFT JOIN geo g ON n.BSSID = g.BSSID " +
                "WHERE $wifiKeyCondition"
    }

    fun getOptimalWpsPinSearchQuery(indexLevel: IndexLevel, tableName: String): String {
        return "SELECT DISTINCT n.*, g.latitude, g.longitude " +
                "FROM $tableName n " +
                "LEFT JOIN geo g ON n.BSSID = g.BSSID " +
                "WHERE n.WPSPIN = ?"
    }

    fun getOptimalEssidQuery(indexLevel: IndexLevel, tableName: String, joinGeo: Boolean = true): String {
        return when {
            joinGeo -> {
                "SELECT n.*, g.latitude, g.longitude FROM $tableName n " +
                        "LEFT JOIN geo g ON n.BSSID = g.BSSID " +
                        "WHERE n.ESSID IN (?)"
            }
            else -> {
                "SELECT * FROM $tableName WHERE ESSID IN (?)"
            }
        }
    }

    fun getOptimalGeoQuery(indexLevel: IndexLevel): String {
        return "SELECT BSSID, latitude, longitude FROM geo " +
                "WHERE latitude BETWEEN ? AND ? " +
                "AND longitude BETWEEN ? AND ?"
    }

    fun getOptimalSearchQuery(
        indexLevel: IndexLevel,
        tableName: String,
        searchFields: Set<String>,
        wholeWords: Boolean
    ): String {
        val conditions = mutableListOf<String>()
        val hints = mutableListOf<String>()

        if ("BSSID" in searchFields) {
            conditions.add("n.BSSID = ?")
            if (indexLevel >= IndexLevel.BASIC) {
                hints.add(when (tableName) {
                    "nets" -> NETS_BSSID
                    "base" -> BASE_BSSID
                    else -> ""
                })
            }
        }

        if ("ESSID" in searchFields) {
            if (wholeWords) {
                conditions.add("n.ESSID = ? COLLATE NOCASE")
            } else {
                conditions.add("n.ESSID LIKE ? ESCAPE '\\'")
            }
            if (indexLevel >= IndexLevel.BASIC) {
                hints.add(when (tableName) {
                    "nets" -> NETS_ESSID
                    "base" -> BASE_ESSID
                    else -> ""
                })
            }
        }

        if ("WiFiKey" in searchFields) {
            if (wholeWords) {
                conditions.add("n.WiFiKey = ? COLLATE NOCASE")
            } else {
                conditions.add("n.WiFiKey LIKE ? ESCAPE '\\'")
            }
            if (indexLevel == IndexLevel.FULL) {
                hints.add(when (tableName) {
                    "nets" -> NETS_WIFIKEY
                    "base" -> BASE_WIFIKEY
                    else -> ""
                })
            }
        }

        if ("WPSPIN" in searchFields) {
            conditions.add("n.WPSPIN = ?")
            if (indexLevel == IndexLevel.FULL) {
                hints.add(when (tableName) {
                    "nets" -> NETS_WPSPIN
                    "base" -> BASE_WPSPIN
                    else -> ""
                })
            }
        }

        val whereClause = if (conditions.isEmpty()) "1=0" else conditions.joinToString(" OR ")
        val indexHint = if (hints.isNotEmpty() && indexLevel >= IndexLevel.BASIC) {
            " INDEXED BY ${hints.first()}"
        } else ""

        return "SELECT DISTINCT n.*, g.latitude, g.longitude " +
                "FROM $tableName n$indexHint " +
                "LEFT JOIN geo g ON n.BSSID = g.BSSID " +
                "WHERE $whereClause"
    }
}