package com.lsd.wififrankenstein.util

import android.database.sqlite.SQLiteDatabase
import android.util.Log

object DatabaseIndices {
    const val TAG = "DatabaseIndices"

    const val GEO_BSSID = "idx_geo_BSSID"
    const val GEO_COORDS_BSSID = "idx_geo_coords_bssid"
    const val GEO_QUADKEY_FULL = "idx_geo_quadkey_full"

    const val NETS_BSSID = "idx_nets_BSSID"
    const val NETS_ESSID = "idx_nets_ESSID"
    const val NETS_ESSID_LOWER = "idx_nets_ESSID_lower"
    const val NETS_COMPOSITE = "idx_nets_composite"

    const val BASE_BSSID = "idx_base_BSSID"
    const val BASE_ESSID = "idx_base_ESSID"
    const val BASE_ESSID_LOWER = "idx_base_ESSID_lower"
    const val BASE_COMPOSITE = "idx_base_composite"


    fun getAvailableIndices(db: SQLiteDatabase): Set<String> {
        return try {
            db.rawQuery(
                """
                SELECT name FROM sqlite_master 
                WHERE type = 'index' OR type = 'table' 
                AND name LIKE 'idx_%' OR name LIKE 'fts_%'
                """.trimIndent(),
                null
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting available indices", e)
            emptySet()
        }
    }
}