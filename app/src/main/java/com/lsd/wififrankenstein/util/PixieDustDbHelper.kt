package com.lsd.wififrankenstein.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class PixieDustDbHelper(context: Context) : SQLiteOpenHelper(
    context,
    "pixie_dust_results.db",
    null,
    2
) {
    companion object {
        private val lock = ReentrantLock()
    }

    private val CREATE_TABLE = """
        CREATE TABLE pixie_results (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            bssid TEXT NOT NULL,
            essid TEXT NOT NULL,
            wps_pin TEXT,
            wpa_psk TEXT,
            latitude DOUBLE,
            longitude DOUBLE,
            timestamp INTEGER NOT NULL,
            UNIQUE(bssid, essid)
        )
    """.trimIndent()

    init {
        lock.withLock {
            writableDatabase
        }
    }

    override fun onCreate(db: SQLiteDatabase?) {
        db?.execSQL(CREATE_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {

            db?.execSQL("CREATE TABLE pixie_results_v2 AS SELECT * FROM pixie_results")
            db?.execSQL("DROP TABLE pixie_results")
            db?.execSQL(CREATE_TABLE)
        }
    }

    fun insertResult(
        bssid: String,
        essid: String,
        wpsPin: String?,
        wpaPsk: String?,
        latitude: Double?,
        longitude: Double?,
        timestamp: Long
    ): Long {
        return lock.withLock {
            val db = writableDatabase
            val values = android.content.ContentValues().apply {
                put("bssid", bssid)
                put("essid", essid)
                put("wps_pin", wpsPin)
                put("wpa_psk", wpaPsk)
                put("latitude", latitude)
                put("longitude", longitude)
                put("timestamp", timestamp)
            }
            val id = db.insert("pixie_results", null, values)

            values.clear()
            id
        }
    }
}
