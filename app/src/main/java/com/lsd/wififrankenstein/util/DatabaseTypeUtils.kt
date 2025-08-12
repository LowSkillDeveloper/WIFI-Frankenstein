package com.lsd.wififrankenstein.util

import android.database.sqlite.SQLiteDatabase
import com.lsd.wififrankenstein.util.Log

object DatabaseTypeUtils {
    private const val TAG = "DatabaseTypeUtils"

    enum class WiFiDbType {
        TYPE_NETS,    // Первый тип (с таблицей nets)
        TYPE_BASE,    // Второй тип (с таблицей base)
        UNKNOWN       // Неизвестный тип
    }

    fun determineDbType(db: SQLiteDatabase): WiFiDbType {
        return try {
            val tables = getTableNames(db)
            Log.d(TAG, "Tables in database: $tables")

            when {
                tables.contains("nets") -> {
                    Log.d(TAG, "Detected TYPE_NETS database")
                    WiFiDbType.TYPE_NETS
                }
                tables.contains("base") -> {
                    Log.d(TAG, "Detected TYPE_BASE database")
                    WiFiDbType.TYPE_BASE
                }
                else -> {
                    Log.w(TAG, "Unknown database type, tables: $tables")
                    WiFiDbType.UNKNOWN
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error determining database type", e)
            WiFiDbType.UNKNOWN
        }
    }

    fun getRecommendedIndexLevel(db: SQLiteDatabase): String {
        return try {
            val cursor = db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table'", null)
            cursor.use {
                if (it.moveToFirst()) {
                    val tableCount = it.getInt(0)

                    var totalRecords = 0L
                    val mainTables = when (determineDbType(db)) {
                        WiFiDbType.TYPE_NETS -> listOf("geo", "nets")
                        WiFiDbType.TYPE_BASE -> listOf("geo", "base")
                        else -> emptyList()
                    }

                    mainTables.forEach { tableName ->
                        db.rawQuery("SELECT COUNT(*) FROM $tableName", null).use { countCursor ->
                            if (countCursor.moveToFirst()) {
                                totalRecords += countCursor.getLong(0)
                            }
                        }
                    }

                    Log.d(TAG, "Total records in database: $totalRecords")

                    when {
                        totalRecords < 100_000 -> {
                            Log.d(TAG, "Small database, recommending NONE")
                            "NONE"
                        }
                        totalRecords < 1_000_000 -> {
                            Log.d(TAG, "Medium database, recommending BASIC")
                            "BASIC"
                        }
                        else -> {
                            Log.d(TAG, "Large database, recommending FULL")
                            "FULL"
                        }
                    }
                } else {
                    "BASIC"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error determining recommended index level", e)
            "BASIC"
        }
    }

    private fun getTableNames(db: SQLiteDatabase): List<String> {
        return db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        } ?: emptyList()
    }

    fun getMainTableName(db: SQLiteDatabase): String {
        return when (determineDbType(db)) {
            WiFiDbType.TYPE_NETS -> "nets"
            WiFiDbType.TYPE_BASE -> "base"
            WiFiDbType.UNKNOWN -> "unknown"
        }
    }
}