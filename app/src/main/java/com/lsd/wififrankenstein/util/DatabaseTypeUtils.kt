package com.lsd.wififrankenstein.util

import android.database.sqlite.SQLiteDatabase
import android.util.Log

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