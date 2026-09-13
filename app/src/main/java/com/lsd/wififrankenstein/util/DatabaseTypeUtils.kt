package com.lsd.wififrankenstein.util

import android.database.sqlite.SQLiteDatabase

object DatabaseTypeUtils {
    private const val TAG = "DatabaseTypeUtils"

    fun getMainTableName(db: SQLiteDatabase): String {
        return when {
            hasTable(db, "nets") -> {
                Log.d(TAG, "Detected nets table")
                "nets"
            }

            hasTable(db, "base") -> {
                Log.d(TAG, "Detected base table")
                "base"
            }

            else -> {
                Log.e(TAG, "No valid table found in database")
                throw IllegalStateException("No valid table found in database")
            }
        }
    }

    fun getGeoTableName(db: SQLiteDatabase): String = "geo"

    fun hasTable(db: SQLiteDatabase, tableName: String): Boolean {
        return try {
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(tableName)
            ).use { it.count > 0 }
        } catch (e: Exception) {
            false
        }
    }

    fun hasColumn(db: SQLiteDatabase, tableName: String, columnName: String): Boolean {
        return try {
            db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == columnName) {
                        return true
                    }
                }
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
