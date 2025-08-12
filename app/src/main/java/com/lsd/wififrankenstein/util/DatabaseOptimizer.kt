package com.lsd.wififrankenstein.util

import android.database.sqlite.SQLiteDatabase
import com.lsd.wififrankenstein.util.Log

object DatabaseOptimizer {
    private const val TAG = "DatabaseOptimizer"

    fun optimizeDatabase(db: SQLiteDatabase) {
        try {
            if (db.isReadOnly) {
                Log.d(TAG, "Database is read-only, skipping optimization")
                return
            }

            db.execSQL("PRAGMA journal_mode=WAL")
            db.execSQL("PRAGMA synchronous=NORMAL")
            db.execSQL("PRAGMA cache_size=10000")
            db.execSQL("PRAGMA temp_store=MEMORY")
            db.execSQL("PRAGMA mmap_size=30000000000")

            val indexLevel = DatabaseIndices.determineIndexLevel(db)
            when (indexLevel) {
                DatabaseIndices.IndexLevel.FULL -> {
                    db.execSQL("PRAGMA cache_size=20000")
                    db.execSQL("PRAGMA page_size=4096")
                    Log.d(TAG, "Applied FULL index level optimizations")
                }
                DatabaseIndices.IndexLevel.BASIC -> {
                    db.execSQL("PRAGMA cache_size=15000")
                    db.execSQL("PRAGMA page_size=2048")
                    Log.d(TAG, "Applied BASIC index level optimizations")
                }
                DatabaseIndices.IndexLevel.NONE -> {
                    db.execSQL("PRAGMA cache_size=5000")
                    db.execSQL("PRAGMA page_size=1024")
                    Log.d(TAG, "Applied NONE index level optimizations")
                }
            }

            Log.d(TAG, "Database optimization settings applied for index level: $indexLevel")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying optimization settings", e)
        }
    }

    fun analyzeDatabase(db: SQLiteDatabase) {
        try {
            db.execSQL("ANALYZE")
            Log.d(TAG, "Database analyzed")
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing database", e)
        }
    }

    fun getQueryPlan(db: SQLiteDatabase, query: String, indexLevel: DatabaseIndices.IndexLevel? = null): String {
        val plan = StringBuilder()
        try {
            val level = indexLevel ?: DatabaseIndices.determineIndexLevel(db)
            plan.append("Index Level: $level\n")
            plan.append("Query Plan:\n")

            db.rawQuery("EXPLAIN QUERY PLAN $query", null).use { cursor ->
                while (cursor.moveToNext()) {
                    for (i in 0 until cursor.columnCount) {
                        plan.append(cursor.getString(i)).append(" ")
                    }
                    plan.append("\n")
                }
            }

            when (level) {
                DatabaseIndices.IndexLevel.NONE -> {
                    plan.append("\nWarning: No indexes available. Consider creating indexes for better performance.")
                }
                DatabaseIndices.IndexLevel.BASIC -> {
                    if (query.contains("WiFiKey", ignoreCase = true) || query.contains("WPSPIN", ignoreCase = true)) {
                        plan.append("\nNote: Query includes fields that would benefit from FULL indexing.")
                    }
                }
                DatabaseIndices.IndexLevel.FULL -> {
                    plan.append("\nOptimal: Full indexes available for best performance.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting query plan", e)
        }
        return plan.toString()
    }

    fun shouldUseIndex(db: SQLiteDatabase, tableName: String, columnName: String): Boolean {
        return try {
            val indexLevel = DatabaseIndices.determineIndexLevel(db)
            when (indexLevel) {
                DatabaseIndices.IndexLevel.FULL -> true
                DatabaseIndices.IndexLevel.BASIC -> {
                    columnName.uppercase() in listOf("BSSID", "ESSID", "LATITUDE", "LONGITUDE",
                        "MAC", "WIFINAME", "MACADDRESS")
                }
                DatabaseIndices.IndexLevel.NONE -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if should use index", e)
            false
        }
    }
}