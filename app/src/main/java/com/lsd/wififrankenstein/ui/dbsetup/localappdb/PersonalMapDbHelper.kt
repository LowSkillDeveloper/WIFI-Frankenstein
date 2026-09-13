package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.lsd.wififrankenstein.ui.wifimap.ClusteredMapPoint
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.MacAddressUtils
import com.lsd.wififrankenstein.util.QuadkeyUtils
import com.opencsv.CSVReader
import java.io.Reader
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class PersonalMapDbHelper(
    private val context: Context,
    databaseName: String = DATABASE_NAME
) :
    SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    init {

        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        try {
            db.execSQL("PRAGMA temp_store=MEMORY")
        } catch (e: Exception) {
            Log.w(TAG, "PRAGMA temp_store failed: ${e.message}")
        }
        try {
            db.execSQL("PRAGMA count_changes = false")
        } catch (e: Exception) {
            Log.w(TAG, "PRAGMA count_changes failed: ${e.message}")
        }
    }

    companion object {
        const val DATABASE_NAME = "personal_wifi_map.db"
        const val DATABASE_VERSION = 8

        const val TABLE_PERSONAL_MAP = "personal_wifi_map"
        const val COLUMN_ID = "id"
        const val COLUMN_WIFI_NAME = "wifiname"
        const val COLUMN_MAC_ADDRESS = "macaddress"
        const val COLUMN_LATITUDE = "latitude"
        const val COLUMN_LONGITUDE = "longitude"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_LEVEL = "level"
        const val COLUMN_ACCURACY = "accuracy"
        const val COLUMN_RELIABLE = "is_reliable"
        const val COLUMN_COUNT = "measure_count"
        const val COLUMN_WEIGHT_SUM = "weight_sum"
        const val COLUMN_LAT_SUM = "lat_sum"
        const val COLUMN_LON_SUM = "lon_sum"
        const val COLUMN_FIRST_SEEN = "first_seen"
        const val COLUMN_QUADKEY = "quadkey"
        const val COLUMN_SECURITY = "security"
        const val COLUMN_SECURITY_TYPE = "security_type"
        const val COLUMN_FREQUENCY = "frequency"
        const val COLUMN_CHANNEL = "channel"
        const val COLUMN_SOURCE = "source"
        const val COLUMN_OTHER_DB_STATE = "other_db_state"
        const val COLUMN_WPASEC_STATE = "wpasec_state"
        const val COLUMN_CROSS_CHECKED_AT = "cross_checked_at"
        const val COLUMN_WPASEC_CHECKED_AT = "wpasec_checked_at"
        // OPT-SEARCH: normalized prefix-search keys (index-backed)
        const val COLUMN_WIFI_NAME_NORM = "wifiname_norm"
        const val COLUMN_MAC_ADDRESS_NORM = "macaddress_norm"
        const val COLUMN_WPS = "wps"
        const val COLUMN_ALT = "alt"
        const val COLUMN_PASSPOINT = "passpoint"
        const val COLUMN_HIDDEN = "hidden"
        const val COLUMN_BAND = "band"
        const val COLUMN_VENDOR = "vendor"
        const val TABLE_NOTES = "network_notes"
        const val TABLE_TAGS = "network_tags"

        const val STATE_UNKNOWN = 0
        const val STATE_ABSENT = 1
        const val STATE_PRESENT = 2

        const val SOURCE_SCAN = "scan"
        const val SOURCE_IMPORT_WIGLE = "import_wigle"
        const val SOURCE_IMPORT_WIFILOC = "import_wifiloc"
        const val SOURCE_EXTERNAL = "external"
        const val SOURCE_RESTORE = "restore"

        const val SECURITY_OPEN = "open"
        const val SECURITY_WEP = "wep"
        const val SECURITY_WPA = "wpa"
        const val SECURITY_WPA2 = "wpa2"
        const val SECURITY_WPA2_ENT = "wpa2-ent"
        const val SECURITY_WPA3 = "wpa3"
        const val SECURITY_WPA3_ENT = "wpa3-ent"
        const val SECURITY_OWE = "owe"
        const val SECURITY_UNKNOWN = "unknown"

        private const val RSSI_REF = -45.0
        private const val PATH_LOSS_N = 2.7
        private const val WEIGHT_HALF_LIFE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_OUTLIER_FACTOR = 3.0
        private const val TAG = "PersonalMapDbHelper"
        private const val BACKFILL_PAGE_SIZE = 2000
        private val ALL_COLUMNS = listOf(
            COLUMN_ID, COLUMN_WIFI_NAME, COLUMN_MAC_ADDRESS, COLUMN_LATITUDE, COLUMN_LONGITUDE,
            COLUMN_TIMESTAMP, COLUMN_LEVEL, COLUMN_ACCURACY, COLUMN_RELIABLE, COLUMN_COUNT,
            COLUMN_WEIGHT_SUM, COLUMN_LAT_SUM, COLUMN_LON_SUM, COLUMN_FIRST_SEEN, COLUMN_QUADKEY,
            COLUMN_SECURITY, COLUMN_SECURITY_TYPE, COLUMN_FREQUENCY, COLUMN_CHANNEL, COLUMN_SOURCE,
            COLUMN_OTHER_DB_STATE, COLUMN_WPASEC_STATE, COLUMN_CROSS_CHECKED_AT,
            COLUMN_WPASEC_CHECKED_AT, COLUMN_WIFI_NAME_NORM, COLUMN_MAC_ADDRESS_NORM, COLUMN_WPS,
            COLUMN_ALT, COLUMN_PASSPOINT, COLUMN_HIDDEN, COLUMN_BAND, COLUMN_VENDOR
        )

        fun classifySecurity(security: String?): String {
            if (security.isNullOrBlank()) return SECURITY_UNKNOWN
            val s = security.uppercase()
            return when {
                s.contains("WEP") -> SECURITY_WEP
                s.contains("WPA3") || s.contains("SAE") ->
                    if (s.contains("EAP")) SECURITY_WPA3_ENT else SECURITY_WPA3
                s.contains("WPA2") || s.contains("RSN") ->
                    if (s.contains("EAP")) SECURITY_WPA2_ENT else SECURITY_WPA2
                s.contains("WPA") -> SECURITY_WPA
                s.contains("OWE") -> SECURITY_OWE
                s.contains("ESS") || s.contains("OPEN") -> SECURITY_OPEN
                else -> SECURITY_UNKNOWN
            }
        }

        fun channelForFrequency(frequency: Int): Int {
            if (frequency <= 0) return 0
            return when {
                frequency == 2484 -> 14
                frequency in 2412..2472 -> (frequency - 2407) / 5
                frequency in 5035..5895 -> (frequency - 5000) / 5
                frequency in 5955..7115 -> (frequency - 5950) / 5
                else -> 0
            }
        }

        fun bandForFrequency(frequency: Int): String = when {
            frequency in 2400..2500 -> "2.4G"
            frequency in 4900..5900 -> "5G"
            frequency in 5925..7125 -> "6G"
            else -> ""
        }

        fun normalizeMacKey(mac: String): String =
            MacAddressUtils.formatToColonSeparated(mac) ?: mac.trim().uppercase()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_PERSONAL_MAP (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_WIFI_NAME TEXT,
                $COLUMN_MAC_ADDRESS TEXT,
                $COLUMN_LATITUDE REAL,
                $COLUMN_LONGITUDE REAL,
                $COLUMN_TIMESTAMP INTEGER,
                $COLUMN_LEVEL INTEGER,
                $COLUMN_ACCURACY REAL,
                $COLUMN_RELIABLE INTEGER,
                $COLUMN_COUNT INTEGER DEFAULT 1,
                $COLUMN_WEIGHT_SUM REAL DEFAULT 0,
                $COLUMN_LAT_SUM REAL DEFAULT 0,
                $COLUMN_LON_SUM REAL DEFAULT 0,
                $COLUMN_FIRST_SEEN INTEGER,
                $COLUMN_QUADKEY INTEGER,
                $COLUMN_SECURITY TEXT,
                $COLUMN_SECURITY_TYPE TEXT,
                $COLUMN_FREQUENCY INTEGER,
                $COLUMN_CHANNEL INTEGER,
                $COLUMN_SOURCE TEXT DEFAULT '$SOURCE_SCAN',
                $COLUMN_OTHER_DB_STATE INTEGER NOT NULL DEFAULT 0,
                $COLUMN_WPASEC_STATE INTEGER NOT NULL DEFAULT 0,
                $COLUMN_CROSS_CHECKED_AT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_WPASEC_CHECKED_AT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_WIFI_NAME_NORM TEXT,
                $COLUMN_MAC_ADDRESS_NORM TEXT,
                $COLUMN_WPS INTEGER NOT NULL DEFAULT 0,
                $COLUMN_ALT REAL NOT NULL DEFAULT 0,
                $COLUMN_PASSPOINT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_HIDDEN INTEGER NOT NULL DEFAULT 0,
                $COLUMN_BAND TEXT DEFAULT '',
                $COLUMN_VENDOR TEXT DEFAULT ''
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_NOTES (
                bssid TEXT PRIMARY KEY NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_TAGS (
                bssid TEXT NOT NULL,
                tag TEXT NOT NULL,
                color INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (bssid, tag)
            )
            """.trimIndent()
        )

        db.execSQL("CREATE UNIQUE INDEX idx_personal_map_mac ON $TABLE_PERSONAL_MAP ($COLUMN_MAC_ADDRESS)")
        db.execSQL("CREATE INDEX idx_personal_map_quadkey ON $TABLE_PERSONAL_MAP ($COLUMN_QUADKEY)")
        db.execSQL("CREATE INDEX idx_personal_map_coords ON $TABLE_PERSONAL_MAP ($COLUMN_LATITUDE, $COLUMN_LONGITUDE)")
        db.execSQL("CREATE INDEX idx_personal_map_time ON $TABLE_PERSONAL_MAP ($COLUMN_TIMESTAMP)")
        db.execSQL("CREATE INDEX idx_personal_map_time_id ON $TABLE_PERSONAL_MAP ($COLUMN_TIMESTAMP DESC, $COLUMN_ID DESC)")
        db.execSQL("CREATE INDEX idx_personal_map_security_type ON $TABLE_PERSONAL_MAP ($COLUMN_SECURITY_TYPE)")
        db.execSQL("CREATE INDEX idx_personal_map_channel ON $TABLE_PERSONAL_MAP ($COLUMN_CHANNEL)")
        db.execSQL("CREATE INDEX idx_personal_map_otherdb ON $TABLE_PERSONAL_MAP ($COLUMN_OTHER_DB_STATE)")
        db.execSQL("CREATE INDEX idx_personal_map_wpasec ON $TABLE_PERSONAL_MAP ($COLUMN_WPASEC_STATE)")
        db.execSQL("CREATE INDEX idx_personal_map_cross ON $TABLE_PERSONAL_MAP ($COLUMN_OTHER_DB_STATE, $COLUMN_CROSS_CHECKED_AT, $COLUMN_TIMESTAMP DESC)")
        db.execSQL("CREATE INDEX idx_personal_map_wpasec_check ON $TABLE_PERSONAL_MAP ($COLUMN_WPASEC_STATE, $COLUMN_WPASEC_CHECKED_AT, $COLUMN_TIMESTAMP DESC)")
        db.execSQL("CREATE INDEX idx_personal_map_cover ON $TABLE_PERSONAL_MAP ($COLUMN_LATITUDE, $COLUMN_LONGITUDE, $COLUMN_QUADKEY, $COLUMN_MAC_ADDRESS)")
        db.execSQL("CREATE INDEX idx_personal_map_ssid_norm ON $TABLE_PERSONAL_MAP ($COLUMN_WIFI_NAME_NORM)")
        db.execSQL("CREATE INDEX idx_personal_map_mac_norm ON $TABLE_PERSONAL_MAP ($COLUMN_MAC_ADDRESS_NORM)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {

            try {
                db.execSQL(
                    "DELETE FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_ID NOT IN " +
                            "(SELECT MAX($COLUMN_ID) FROM $TABLE_PERSONAL_MAP GROUP BY $COLUMN_MAC_ADDRESS)"
                )
                db.execSQL("DROP INDEX IF EXISTS idx_personal_map_mac")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_personal_map_mac ON $TABLE_PERSONAL_MAP ($COLUMN_MAC_ADDRESS)")
            } catch (e: Exception) {
                Log.w(TAG, "v2 unique mac index migration failed: ${e.message}")
            }
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_personal_map_cross ON $TABLE_PERSONAL_MAP ($COLUMN_OTHER_DB_STATE, $COLUMN_CROSS_CHECKED_AT, $COLUMN_TIMESTAMP DESC)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_personal_map_wpasec_check ON $TABLE_PERSONAL_MAP ($COLUMN_WPASEC_STATE, $COLUMN_WPASEC_CHECKED_AT, $COLUMN_TIMESTAMP DESC)")
            } catch (e: Exception) {
                Log.w(TAG, "v3 cross-check index migration failed: ${e.message}")
            }
        }
        if (oldVersion < 4) {
            try {

                data class MigRow(val id: Long, val raw: String, val norm: String)
                val rows = mutableListOf<MigRow>()
                db.rawQuery("SELECT $COLUMN_ID, $COLUMN_MAC_ADDRESS FROM $TABLE_PERSONAL_MAP", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val raw = cursor.getString(1).orEmpty()
                        rows.add(MigRow(id, raw, normalizeMac(raw)))
                    }
                }
                db.transaction {
                    rows.groupBy({ it.norm }, { it }).forEach { (norm, group) ->
                        if (group.size == 1) {
                            val row = group.single()
                            if (row.norm != row.raw) {
                                db.execSQL(
                                    "UPDATE $TABLE_PERSONAL_MAP SET $COLUMN_MAC_ADDRESS = ? WHERE $COLUMN_ID = ?",
                                    arrayOf(norm, row.id.toString())
                                )
                            }
                        } else {

                            var keeperId = group.first().id
                            var keeperCount = -1
                            var totalCount = 0
                            var maxTs = 0L
                            group.forEach { row ->
                                var count = 0
                                var ts = 0L
                                db.rawQuery(
                                    "SELECT $COLUMN_COUNT, $COLUMN_TIMESTAMP FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_ID = ?",
                                    arrayOf(row.id.toString())
                                ).use { c ->
                                    if (c.moveToFirst()) {
                                        count = c.getInt(0)
                                        ts = c.getLong(1)
                                    }
                                }
                                totalCount += count
                                if (ts > maxTs) maxTs = ts
                                if (count > keeperCount || (count == keeperCount && row.id > keeperId)) {
                                    keeperCount = count
                                    keeperId = row.id
                                }
                            }
                            db.execSQL(
                                "UPDATE $TABLE_PERSONAL_MAP SET $COLUMN_MAC_ADDRESS = ?, " +
                                        "$COLUMN_COUNT = ?, $COLUMN_TIMESTAMP = ? WHERE $COLUMN_ID = ?",
                                arrayOf(norm, totalCount.toString(), maxTs.toString(), keeperId.toString())
                            )
                            group.forEach { row ->
                                if (row.id != keeperId) {
                                    db.execSQL(
                                        "DELETE FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_ID = ?",
                                        arrayOf(row.id.toString())
                                    )
                                }
                            }
                        }
                    }
                }
                db.execSQL("DROP INDEX IF EXISTS idx_personal_map_last_seen")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_personal_map_cover ON $TABLE_PERSONAL_MAP ($COLUMN_LATITUDE, $COLUMN_LONGITUDE, $COLUMN_QUADKEY, $COLUMN_MAC_ADDRESS)")
            } catch (e: Exception) {
                Log.w(TAG, "v4 mac normalize migration failed: ${e.message}")
            }
        }
        if (oldVersion < 5) {
            try {
                addColumnIfMissing(db, COLUMN_WIFI_NAME_NORM, "TEXT")
                addColumnIfMissing(db, COLUMN_MAC_ADDRESS_NORM, "TEXT")

                // Backfill normalized prefix-search keys.
                backfillSearchKeys(db)

                db.execSQL("CREATE INDEX IF NOT EXISTS idx_personal_map_ssid_norm ON $TABLE_PERSONAL_MAP ($COLUMN_WIFI_NAME_NORM)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_personal_map_mac_norm ON $TABLE_PERSONAL_MAP ($COLUMN_MAC_ADDRESS_NORM)")
            } catch (e: Exception) {
                Log.w(TAG, "v5 search-key migration failed: ${e.message}")
            }
        }
        if (oldVersion < 6) {
            try {
                addColumnIfMissing(db, COLUMN_WPS, "INTEGER NOT NULL DEFAULT 0")
            } catch (e: Exception) {
                Log.w(TAG, "v6 wps migration failed: ${e.message}")
            }
        }
        if (oldVersion < 7) {
            try {
                addColumnIfMissing(db, COLUMN_ALT, "REAL NOT NULL DEFAULT 0")
                addColumnIfMissing(db, COLUMN_PASSPOINT, "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, COLUMN_HIDDEN, "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, COLUMN_BAND, "TEXT DEFAULT ''")
                db.execSQL(
                    "UPDATE $TABLE_PERSONAL_MAP SET $COLUMN_BAND = " +
                            "CASE " +
                            "  WHEN $COLUMN_FREQUENCY BETWEEN 2400 AND 2500 THEN '2.4G' " +
                            "  WHEN $COLUMN_FREQUENCY BETWEEN 4900 AND 5900 THEN '5G' " +
                            "  WHEN $COLUMN_FREQUENCY BETWEEN 5925 AND 7125 THEN '6G' " +
                            "  ELSE '' " +
                            "END"
                )
            } catch (e: Exception) {
                Log.w(TAG, "v7 alt/passpoint/hidden/band migration failed: ${e.message}")
            }
        }
        if (oldVersion < 8) {
            try {
                addColumnIfMissing(db, COLUMN_VENDOR, "TEXT DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS $TABLE_NOTES (" +
                            "bssid TEXT PRIMARY KEY NOT NULL, " +
                            "note TEXT NOT NULL DEFAULT '', " +
                            "updated_at INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS $TABLE_TAGS (" +
                            "bssid TEXT NOT NULL, " +
                            "tag TEXT NOT NULL, " +
                            "color INTEGER NOT NULL DEFAULT 0, " +
                            "PRIMARY KEY (bssid, tag))"
                )
                backfillVendor(db)
            } catch (e: Exception) {
                Log.w(TAG, "v8 vendor/notes/tags migration failed: ${e.message}")
            }
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_personal_map_time_id ON $TABLE_PERSONAL_MAP ($COLUMN_TIMESTAMP DESC, $COLUMN_ID DESC)")
        } catch (e: Exception) {
            Log.w(TAG, "ensure time/id index failed: ${e.message}")
        }
        try {
            db.rawQuery("PRAGMA journal_mode=PERSIST", null).use { }
        } catch (e: Exception) {
            Log.w(TAG, "PRAGMA journal_mode failed: ${e.message}")
        }
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, column: String, type: String) {
        var exists = false
        db.rawQuery("PRAGMA table_info($TABLE_PERSONAL_MAP)", null).use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIdx >= 0 && cursor.getString(nameIdx) == column) {
                    exists = true
                    break
                }
            }
        }
        if (!exists) db.execSQL("ALTER TABLE $TABLE_PERSONAL_MAP ADD COLUMN $column $type")
    }

    /**
     * Recomputes the normalized prefix-search keys for every row in bounded
     * pages. SSID uses Unicode-aware Kotlin lowercase (SQLite LOWER is
     * ASCII-only), and the cursor is closed before updating to avoid mutating
     * a table while scanning it on the same connection.
     */
    private fun backfillSearchKeys(db: SQLiteDatabase) {
        val stmt = db.compileStatement(
            "UPDATE $TABLE_PERSONAL_MAP SET $COLUMN_WIFI_NAME_NORM = ?, " +
                    "$COLUMN_MAC_ADDRESS_NORM = ? WHERE $COLUMN_ID = ?"
        )
        try {
            var lastId = Long.MIN_VALUE
            while (true) {
                val page = ArrayList<Triple<Long, String, String>>(BACKFILL_PAGE_SIZE)
                db.rawQuery(
                    "SELECT $COLUMN_ID, $COLUMN_WIFI_NAME, $COLUMN_MAC_ADDRESS " +
                            "FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_ID > ? " +
                            "ORDER BY $COLUMN_ID LIMIT $BACKFILL_PAGE_SIZE",
                    arrayOf(lastId.toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        page.add(
                            Triple(
                                cursor.getLong(0),
                                cursor.getString(1).orEmpty(),
                                cursor.getString(2).orEmpty()
                            )
                        )
                    }
                }
                if (page.isEmpty()) break
                page.forEach { (id, name, mac) ->
                    stmt.bindString(1, PersonalSearchKeys.normalizeSsid(name))
                    stmt.bindString(2, PersonalSearchKeys.normalizeMac(mac))
                    stmt.bindLong(3, id)
                    stmt.executeUpdateDelete()
                    stmt.clearBindings()
                }
                lastId = page.last().first
                if (page.size < BACKFILL_PAGE_SIZE) break
            }
        } finally {
            stmt.close()
        }
    }

    private fun backfillVendor(db: SQLiteDatabase) {
        var lastId = 0L
        while (true) {
            val page = ArrayList<Pair<Long, String>>(BACKFILL_PAGE_SIZE)
            db.rawQuery(
                "SELECT $COLUMN_ID, $COLUMN_MAC_ADDRESS FROM $TABLE_PERSONAL_MAP " +
                "WHERE $COLUMN_ID > ? AND ($COLUMN_VENDOR IS NULL OR $COLUMN_VENDOR = '') " +
                "ORDER BY $COLUMN_ID LIMIT $BACKFILL_PAGE_SIZE",
                arrayOf(lastId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    page.add(cursor.getLong(0) to cursor.getString(1).orEmpty())
                }
            }
            if (page.isEmpty()) break
            page.forEach { (id, mac) ->
                val vendor = try { com.lsd.wififrankenstein.ui.localnetwork.OuiDatabase.lookupByMac(mac).orEmpty() } catch (_: Exception) { "" }
                if (vendor.isNotBlank()) {
                    db.execSQL("UPDATE $TABLE_PERSONAL_MAP SET $COLUMN_VENDOR = ? WHERE $COLUMN_ID = ?", arrayOf(vendor, id.toString()))
                }
            }
            lastId = page.last().first
            if (page.size < BACKFILL_PAGE_SIZE) break
        }
    }

    fun lookupVendor(mac: String): String {
        return try {
            com.lsd.wififrankenstein.util.VendorChecker.lookupLocalSync(
                context.applicationContext, mac
            )
                ?: com.lsd.wififrankenstein.ui.localnetwork.OuiDatabase.lookupByMac(mac).orEmpty()
        } catch (_: Exception) { "" }
    }

    fun backfillMissingVendors(): Int {
        val pending = ArrayList<Pair<Long, String>>()
        readableDatabase.rawQuery(
            "SELECT $COLUMN_ID, $COLUMN_MAC_ADDRESS FROM $TABLE_PERSONAL_MAP " +
                "WHERE $COLUMN_VENDOR IS NULL OR $COLUMN_VENDOR = ''",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                pending.add(cursor.getLong(0) to cursor.getString(1).orEmpty())
            }
        }
        var updated = 0
        pending.forEach { (id, mac) ->
            val vendor = lookupVendor(mac)
            if (vendor.isNotBlank()) {
                persistVendor(id, vendor)
                updated++
            }
        }
        return updated
    }

    private fun persistVendor(id: Long, vendor: String) {
        try {
            val values = ContentValues().apply { put(COLUMN_VENDOR, vendor) }
            writableDatabase.update(
                TABLE_PERSONAL_MAP,
                values,
                "$COLUMN_ID = ?",
                arrayOf(id.toString())
            )
        } catch (_: Exception) {
        }
    }

    fun getNote(bssid: String): String {
        return try {
            readableDatabase.rawQuery(
                "SELECT note FROM $TABLE_NOTES WHERE bssid = ?",
                arrayOf(normalizeMac(bssid))
            ).use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
        } catch (_: Exception) { "" }
    }

    fun setNote(bssid: String, note: String) {
        val normalized = normalizeMac(bssid)
        val values = ContentValues().apply {
            put("bssid", normalized)
            put("note", note)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(TABLE_NOTES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getTags(bssid: String): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        try {
            readableDatabase.rawQuery(
                "SELECT tag, color FROM $TABLE_TAGS WHERE bssid = ?",
                arrayOf(normalizeMac(bssid))
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(cursor.getString(0) to cursor.getInt(1))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    fun addTag(bssid: String, tag: String, color: Int) {
        val values = ContentValues().apply {
            put("bssid", normalizeMac(bssid))
            put("tag", tag)
            put("color", color)
        }
        writableDatabase.insertWithOnConflict(TABLE_TAGS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removeTag(bssid: String, tag: String) {
        writableDatabase.delete(
            TABLE_TAGS,
            "bssid = ? AND tag = ?",
            arrayOf(normalizeMac(bssid), tag)
        )
    }

    fun getAllTags(): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        try {
            readableDatabase.rawQuery(
                "SELECT DISTINCT tag, color FROM $TABLE_TAGS ORDER BY tag",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(cursor.getString(0) to cursor.getInt(1))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    fun searchByTag(tag: String): List<String> {
        val result = mutableListOf<String>()
        try {
            readableDatabase.rawQuery(
                "SELECT bssid FROM $TABLE_TAGS WHERE tag = ?",
                arrayOf(tag)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(cursor.getString(0))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    fun getTagsForBssids(bssids: Set<String>): Map<String, List<String>> {
        if (bssids.isEmpty()) return emptyMap()
        val result = HashMap<String, MutableList<String>>()
        val normalized = bssids.map { normalizeMac(it) }.distinct()
        normalized.chunked(400).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            try {
                readableDatabase.rawQuery(
                    "SELECT bssid, tag FROM $TABLE_TAGS WHERE bssid IN ($placeholders)",
                    chunk.toTypedArray()
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val bssid = cursor.getString(0)
                        val tag = cursor.getString(1)
                        result.getOrPut(bssid) { mutableListOf() }.add(tag)
                    }
                }
            } catch (_: Exception) {}
        }
        return result
    }

    fun getBandBreakdown(): List<Pair<String, Long>> {
        val query = "SELECT $COLUMN_BAND, COUNT(*) FROM $TABLE_PERSONAL_MAP " +
                "WHERE $COLUMN_BAND != '' GROUP BY $COLUMN_BAND ORDER BY 2 DESC"
        return readableDatabase.rawQuery(query, null).use { cursor ->
            val list = mutableListOf<Pair<String, Long>>()
            while (cursor.moveToNext()) list.add(cursor.getString(0) to cursor.getLong(1))
            list
        }
    }

    fun getVendorTop10(): List<Pair<String, Long>> {
        val query = "SELECT $COLUMN_VENDOR, COUNT(*) FROM $TABLE_PERSONAL_MAP " +
                "WHERE $COLUMN_VENDOR != '' GROUP BY $COLUMN_VENDOR ORDER BY 2 DESC LIMIT 10"
        return readableDatabase.rawQuery(query, null).use { cursor ->
            val list = mutableListOf<Pair<String, Long>>()
            while (cursor.moveToNext()) list.add(cursor.getString(0) to cursor.getLong(1))
            list
        }
    }

    fun getSsidPatternBreakdown(): List<Pair<String, Long>> {
        val query = "SELECT $COLUMN_WIFI_NAME, $COLUMN_MAC_ADDRESS FROM $TABLE_PERSONAL_MAP " +
                "WHERE $COLUMN_WIFI_NAME IS NOT NULL AND $COLUMN_WIFI_NAME != ''"
        val patterns = mutableMapOf<String, Long>()
        readableDatabase.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                val ssid = cursor.getString(0).orEmpty()
                val pattern = classifySsidPattern(ssid)
                patterns[pattern] = (patterns[pattern] ?: 0L) + 1
            }
        }
        return patterns.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    private fun classifySsidPattern(ssid: String): String {
        val s = ssid.uppercase()
        return when {
            s.startsWith("TP-LINK_") || s.startsWith("TP-LINK-") -> "TP-Link"
            s.startsWith("NETGEAR") -> "Netgear"
            s.startsWith("FRITZ!") -> "FRITZ!Box"
            s.startsWith("ASUS_") || s.startsWith("ASUS-") -> "ASUS"
            s.startsWith("LINKSYS") -> "Linksys"
            s.startsWith("HUAWEI-") || s.startsWith("HG") -> "Huawei"
            s.startsWith("TENDA") -> "Tenda"
            s.startsWith("COMTREND-") -> "Comtrend"
            s.startsWith("ZTE-") || s.startsWith("ZTE_") -> "ZTE"
            s.startsWith("D-LINK") || s.startsWith("DLINK") -> "D-Link"
            s.startsWith("ACTIONTEC") -> "Actiontec"
            s.startsWith("ARRIS") -> "Arris"
            s.startsWith("UBNT") || s.startsWith("UBIQUITI") -> "Ubiquiti"
            s.startsWith("MIKROTIK") -> "MikroTik"
            s.matches(Regex("^[A-Z]{2}-[A-F0-9]{4}$")) -> "ISP-assigned"
            s.isBlank() -> "Hidden"
            else -> "Custom"
        }
    }

    private fun attachedTableExists(db: SQLiteDatabase, schema: String, table: String): Boolean =
        try {
            db.rawQuery(
                "SELECT name FROM $schema.sqlite_master WHERE type='table' AND name=?",
                arrayOf(table)
            ).use { it.moveToFirst() }
        } catch (_: Exception) {
            false
        }

    private fun columnSet(db: SQLiteDatabase, schema: String, table: String): Set<String> {
        val result = mutableSetOf<String>()
        db.rawQuery("PRAGMA $schema.table_info($table)", null).use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIdx >= 0) cursor.getString(nameIdx)?.let { result.add(it) }
            }
        }
        return result
    }

    /**
     * Replaces the whole personal map with the contents of [sourcePath] using
     * ATTACH + INSERT instead of swapping the database file underneath other
     * open connections. Runs in a transaction, so a failure leaves the existing
     * data untouched. Returns the resulting row count, or -1 on failure.
     */
    fun replaceAllFromDatabaseFile(sourcePath: String): Int {
        val db = writableDatabase
        var imported = -1
        try {
            db.execSQL("ATTACH DATABASE ? AS restored", arrayOf(sourcePath))
            try {
                if (!attachedTableExists(db, "restored", TABLE_PERSONAL_MAP)) return -1
                val sourceCols = columnSet(db, "restored", TABLE_PERSONAL_MAP)
                if (!sourceCols.contains(COLUMN_MAC_ADDRESS)) return -1

                val targetCols = ALL_COLUMNS.filter {
                    it != COLUMN_ID &&
                            it != COLUMN_WIFI_NAME_NORM &&
                            it != COLUMN_MAC_ADDRESS_NORM &&
                            sourceCols.contains(it)
                }
                if (targetCols.isEmpty()) return -1
                val cols = targetCols.joinToString(", ")

                db.transaction {
                    db.execSQL("DELETE FROM $TABLE_PERSONAL_MAP")
                    db.execSQL(
                        "INSERT INTO $TABLE_PERSONAL_MAP ($cols) " +
                                "SELECT $cols FROM restored.$TABLE_PERSONAL_MAP"
                    )
                    backfillSearchKeys(db)
                }

                db.rawQuery("SELECT COUNT(*) FROM $TABLE_PERSONAL_MAP", null).use { cursor ->
                    if (cursor.moveToFirst()) imported = cursor.getInt(0)
                }
            } finally {
                try {
                    db.execSQL("DETACH DATABASE restored")
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "replaceAllFromDatabaseFile failed", e)
            imported = -1
        }
        return imported
    }

    fun addPersonalRecord(network: PersonalWifiNetwork): Long =
        writePersonalRecord(network, 1).id

    fun addPersonalRecord(network: PersonalWifiNetwork, increment: Int): Long =
        writePersonalRecord(network, increment).id

    fun addPersonalRecords(networks: List<PersonalWifiNetwork>): PersonalWriteResult {
        if (networks.isEmpty()) return PersonalWriteResult()
        var inserted = 0
        var updated = 0
        val newMacs = mutableSetOf<String>()

        // OPT-N+1: Load all existing rows for the batch in one (chunked) query
        // inside the transaction instead of one SELECT per MAC.
        writableDatabase.transaction {
            val normalizedMacs = networks.map { normalizeMac(it.macAddress) }
            val existingRows = loadExistingRows(normalizedMacs, writableDatabase)
            networks.forEach { network ->
                val mac = normalizeMac(network.macAddress)
                val existing = existingRows[mac]
                var outcome = if (existing != null) {
                    writePersonalRecord(network, network.measureCount, existing)
                } else {
                    insertPersonalRecord(network, network.measureCount)
                }

                if (outcome.id == -1L) {
                    // Conflict (e.g. duplicate MAC within the same batch): fall back
                    // to a per-row read/update.
                    outcome = writePersonalRecord(network, network.measureCount)
                }
                if (outcome.id != -1L) {
                    if (outcome.isNew) {
                        inserted++
                        newMacs.add(mac)
                    } else {
                        updated++
                    }
                }
            }
        }
        return PersonalWriteResult(inserted, updated, newMacs)
    }

    private class ExistingPersonalRow(
        val id: Long,
        val latitude: Double,
        val longitude: Double,
        val count: Int,
        val level: Int,
        val weightSum: Double,
        val latSum: Double,
        val lonSum: Double,
        val accuracy: Float,
        val reliable: Boolean,
        val timestamp: Long,
        val security: String?,
        val securityType: String?,
        val frequency: Int,
        val channel: Int,
        val source: String?,
        val firstSeen: Long,
        val alt: Double,
        val isPasspoint: Boolean,
        val isHidden: Boolean,
        val band: String,
        val vendor: String
    )

    /** Loads existing rows for [macs] in batched IN queries, keyed by normalized MAC. */
    private fun loadExistingRows(
        macs: List<String>,
        db: SQLiteDatabase = readableDatabase
    ): Map<String, ExistingPersonalRow> {
        val distinct = macs.map { normalizeMac(it) }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val result = HashMap<String, ExistingPersonalRow>(distinct.size)
        val query = "SELECT $COLUMN_MAC_ADDRESS, $COLUMN_ID, $COLUMN_LATITUDE, $COLUMN_LONGITUDE, " +
                "$COLUMN_COUNT, $COLUMN_LEVEL, $COLUMN_WEIGHT_SUM, $COLUMN_LAT_SUM, " +
                "$COLUMN_LON_SUM, $COLUMN_ACCURACY, $COLUMN_RELIABLE, $COLUMN_TIMESTAMP, " +
                "$COLUMN_SECURITY, $COLUMN_SECURITY_TYPE, $COLUMN_FREQUENCY, $COLUMN_CHANNEL, " +
                "$COLUMN_SOURCE, $COLUMN_FIRST_SEEN, " +
                "$COLUMN_ALT, $COLUMN_PASSPOINT, $COLUMN_HIDDEN, $COLUMN_BAND, $COLUMN_VENDOR " +
                "FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_MAC_ADDRESS IN "
        distinct.chunked(400).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(query + "($placeholders)", chunk.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    val mac = cursor.getString(0) ?: continue
                    result[normalizeMac(mac)] = ExistingPersonalRow(
                        id = cursor.getLong(1),
                        latitude = cursor.getDouble(2),
                        longitude = cursor.getDouble(3),
                        count = cursor.getInt(4),
                        level = cursor.getInt(5),
                        weightSum = cursor.getDouble(6),
                        latSum = cursor.getDouble(7),
                        lonSum = cursor.getDouble(8),
                        accuracy = cursor.getFloat(9),
                        reliable = cursor.getInt(10) == 1,
                        timestamp = cursor.getLong(11),
                        security = cursor.getString(12),
                        securityType = cursor.getString(13),
                        frequency = cursor.getInt(14),
                        channel = cursor.getInt(15),
                        source = cursor.getString(16),
                        firstSeen = cursor.getLong(17),
                        // v7
                        alt = cursor.getDouble(18),
                        isPasspoint = cursor.getInt(19) == 1,
                        isHidden = cursor.getInt(20) == 1,
                        band = cursor.getString(21).orEmpty(),
                        vendor = cursor.getString(22).orEmpty()
                    )
                }
            }
        }
        return result
    }

    private fun insertPersonalRecord(
        network: PersonalWifiNetwork,
        increment: Int
    ): PersonalWriteOutcome {
        val normalizedMac = normalizeMac(network.macAddress)
        val incrementBy = increment.coerceAtLeast(1)
        val initialValues = createPersonalValues(network, normalizedMac, incrementBy)
        val initialWeight = if (network.isReliable) {
            calculateRssiWeight(network.level, network.accuracy, 0L) * incrementBy
        } else {
            0.0
        }
        initialValues.put(COLUMN_WEIGHT_SUM, initialWeight)
        initialValues.put(COLUMN_LAT_SUM, network.latitude * initialWeight)
        initialValues.put(COLUMN_LON_SUM, network.longitude * initialWeight)
        initialValues.put(
            COLUMN_SECURITY_TYPE,
            network.securityType?.takeIf { it.isNotBlank() } ?: classifySecurity(network.security)
        )
        initialValues.put(
            COLUMN_CHANNEL,
            network.channel.takeIf { it > 0 } ?: channelForFrequency(network.frequency)
        )
        return PersonalWriteOutcome(writableDatabase.insert(TABLE_PERSONAL_MAP, null, initialValues), isNew = true)
    }

    private class PersonalWriteOutcome(val id: Long, val isNew: Boolean)

    private fun writePersonalRecord(
        network: PersonalWifiNetwork,
        increment: Int,
        existing: ExistingPersonalRow? = null
    ): PersonalWriteOutcome {
        val db = writableDatabase
        val normalizedMac = normalizeMac(network.macAddress)
        val incrementBy = increment.coerceAtLeast(1)

        val existingRow = existing ?: loadExistingRows(listOf(normalizedMac), db)[normalizedMac]
        if (existingRow != null) {
            run {
                val id = existingRow.id
                val oldLat = existingRow.latitude
                val oldLon = existingRow.longitude
                val count = existingRow.count
                val existingLevel = existingRow.level
                val oldWeightSum = existingRow.weightSum
                val storedLatSum = existingRow.latSum
                val storedLonSum = existingRow.lonSum
                val existingAccuracy = existingRow.accuracy
                val existingReliable = existingRow.reliable
                val lastTimestamp = existingRow.timestamp
                val existingSecurity = existingRow.security
                val existingSecurityType = existingRow.securityType
                val existingFrequency = existingRow.frequency
                val existingChannel = existingRow.channel
                val existingSource = existingRow.source
                val existingFirstSeen = existingRow.firstSeen

                val dtMs = (network.timestamp - lastTimestamp).coerceAtLeast(0L)

                val decayOld = recencyFactorFull(dtMs)
                val decayNew = recencyFactor(dtMs)
                val weight = calculateSpatialWeight(network.level, network.accuracy) * decayNew

                val firstSeenUpdate = if (network.firstSeen > 0L &&
                    (existingFirstSeen <= 0L || network.firstSeen < existingFirstSeen)
                ) network.firstSeen else null

                val values = ContentValues().apply {
                    put(COLUMN_MAC_ADDRESS, normalizedMac)
                    put(COLUMN_RELIABLE, if (network.isReliable || existingReliable) 1 else 0)
                    if (network.isWps) put(COLUMN_WPS, 1)
                    if (network.accuracy > 0f && (existingAccuracy <= 0f || network.accuracy < existingAccuracy)) {
                        put(COLUMN_ACCURACY, network.accuracy)
                    }

                    val security = network.security?.takeIf { it.isNotBlank() }
                    if (security != null && (existingSecurity.isNullOrBlank())) {
                        put(COLUMN_SECURITY, security)
                    }
                    if (existingSecurityType.isNullOrBlank()) {
                        val st = network.securityType?.takeIf { it.isNotBlank() }
                            ?: classifySecurity(security)
                        if (!st.isNullOrBlank()) put(COLUMN_SECURITY_TYPE, st)
                    }
                    if (network.frequency > 0) {
                        put(COLUMN_FREQUENCY, network.frequency)
                        val ch = network.channel.takeIf { it > 0 }
                            ?: channelForFrequency(network.frequency)
                        if (ch > 0) put(COLUMN_CHANNEL, ch)
                    } else if (existingFrequency == 0 && existingChannel == 0) {
                        val ch = network.channel.takeIf { it > 0 }
                            ?: channelForFrequency(network.frequency)
                        if (ch > 0) put(COLUMN_CHANNEL, ch)
                    }
                    if (existingSource.isNullOrBlank()) {
                        put(COLUMN_SOURCE, network.source)
                    }
                    // v7: altitude — keep the observation with best (lowest) accuracy
                    if (network.alt != 0.0 && (existingAccuracy <= 0f || network.accuracy < existingAccuracy)) {
                        put(COLUMN_ALT, network.alt)
                    }
                    if (network.isPasspoint) put(COLUMN_PASSPOINT, 1)
                    // v7: hidden — once seen, always true
                    if (network.isHidden) put(COLUMN_HIDDEN, 1)
                    val newBand = network.band.ifBlank { bandForFrequency(network.frequency) }
                    if (newBand.isNotBlank()) put(COLUMN_BAND, newBand)
                    if (network.vendor.isNotBlank()) put(COLUMN_VENDOR, network.vendor)
                    else if (existingRow.vendor.isBlank()) {
                        val v = lookupVendor(network.macAddress)
                        if (v.isNotBlank()) put(COLUMN_VENDOR, v)
                    }
                }

                var rejectedAsOutlier = false
                if (network.isReliable) {

                    val oldLatSum = if (storedLatSum != 0.0 || oldWeightSum == 0.0) {
                        storedLatSum
                    } else {
                        oldLat * oldWeightSum
                    }
                    val oldLonSum = if (storedLonSum != 0.0 || oldWeightSum == 0.0) {
                        storedLonSum
                    } else {
                        oldLon * oldWeightSum
                    }

                    val decayed = oldWeightSum * decayOld
                    val decayedLatSum = oldLatSum * decayOld
                    val decayedLonSum = oldLonSum * decayOld
                    val newWeightSum = decayed + weight

                    val hasAccuracy = network.accuracy > 0f && existingAccuracy > 0f
                    val jump = if (hasAccuracy) distanceMeters(oldLat, oldLon, network.latitude, network.longitude) else 0.0
                    val allowedJump = MAX_OUTLIER_FACTOR *
                            maxOf(network.accuracy, existingAccuracy).toDouble().coerceAtLeast(5.0)
                    val isOutlier = hasAccuracy && newWeightSum > 0.0 && oldWeightSum > 0.0 && jump > allowedJump

                    if (isOutlier) {

                        rejectedAsOutlier = true
                        values.put(COLUMN_WEIGHT_SUM, decayed)
                        values.put(COLUMN_LAT_SUM, decayedLatSum)
                        values.put(COLUMN_LON_SUM, decayedLonSum)
                    } else {
                        val newLatSum = decayedLatSum + network.latitude * weight
                        val newLonSum = decayedLonSum + network.longitude * weight
                        val newLat = if (newWeightSum > 0) newLatSum / newWeightSum else oldLat
                        val newLon = if (newWeightSum > 0) newLonSum / newWeightSum else oldLon
                        values.put(COLUMN_LATITUDE, newLat)
                        values.put(COLUMN_LONGITUDE, newLon)
                        values.put(COLUMN_LAT_SUM, newLatSum)
                        values.put(COLUMN_LON_SUM, newLonSum)
                        values.put(COLUMN_WEIGHT_SUM, newWeightSum)
                        values.put(COLUMN_QUADKEY, QuadkeyUtils.latLonToQuadkey(newLat, newLon))
                        values.put(COLUMN_COUNT, count + incrementBy)
                    }
                }

                if (!rejectedAsOutlier) {
                    if (network.timestamp > lastTimestamp) {
                        values.put(COLUMN_TIMESTAMP, network.timestamp)
                    }
                    val resetPeak = dtMs > WEIGHT_HALF_LIFE_MS &&
                            dtMs <= 30L * 24 * 60 * 60 * 1000
                    values.put(
                        COLUMN_LEVEL,
                        if (resetPeak) network.level else maxOf(existingLevel, network.level)
                    )
                    if (firstSeenUpdate != null) {
                        values.put(COLUMN_FIRST_SEEN, firstSeenUpdate)
                    }
                }

                db.update(TABLE_PERSONAL_MAP, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
            }
            return PersonalWriteOutcome(existingRow.id, isNew = false)
        }

        val initialValues = createPersonalValues(network, normalizedMac, incrementBy)
        val initialWeight = if (network.isReliable) {
            calculateRssiWeight(network.level, network.accuracy, 0L) * incrementBy
        } else {
            0.0
        }
        initialValues.put(COLUMN_WEIGHT_SUM, initialWeight)
        initialValues.put(COLUMN_LAT_SUM, network.latitude * initialWeight)
        initialValues.put(COLUMN_LON_SUM, network.longitude * initialWeight)
        initialValues.put(
            COLUMN_SECURITY_TYPE,
            network.securityType?.takeIf { it.isNotBlank() } ?: classifySecurity(network.security)
        )
        val fallbackChannel = network.channel.takeIf { it > 0 } ?: channelForFrequency(network.frequency)
        initialValues.put(COLUMN_CHANNEL, fallbackChannel)
        return PersonalWriteOutcome(db.insert(TABLE_PERSONAL_MAP, null, initialValues), isNew = true)
    }

    private fun createPersonalValues(
        network: PersonalWifiNetwork,
        normalizedMac: String,
        measureCount: Int = 1
    ): ContentValues {
        return ContentValues().apply {
            put(COLUMN_WIFI_NAME, network.wifiName)
            put(COLUMN_MAC_ADDRESS, normalizedMac)
            put(COLUMN_WIFI_NAME_NORM, PersonalSearchKeys.normalizeSsid(network.wifiName))
            put(COLUMN_MAC_ADDRESS_NORM, PersonalSearchKeys.normalizeMac(normalizedMac))
            put(COLUMN_WPS, if (network.isWps) 1 else 0)
            put(COLUMN_LATITUDE, network.latitude)
            put(COLUMN_LONGITUDE, network.longitude)
            put(COLUMN_TIMESTAMP, network.timestamp)
            put(COLUMN_LEVEL, network.level)
            put(COLUMN_ACCURACY, network.accuracy)
            put(COLUMN_RELIABLE, if (network.isReliable) 1 else 0)
            put(COLUMN_COUNT, measureCount.coerceAtLeast(1))
            put(
                COLUMN_FIRST_SEEN,
                if (network.firstSeen > 0) network.firstSeen else network.timestamp
            )
            put(COLUMN_SECURITY, network.security?.takeIf { it.isNotBlank() })
            put(COLUMN_FREQUENCY, network.frequency.takeIf { it > 0 })
            put(COLUMN_SOURCE, network.source.ifBlank { SOURCE_SCAN })
            put(
                COLUMN_QUADKEY,
                if (network.latitude != 0.0 && network.longitude != 0.0) {
                    QuadkeyUtils.latLonToQuadkey(network.latitude, network.longitude)
                } else null
            )
            put(COLUMN_ALT, network.alt)
            put(COLUMN_PASSPOINT, if (network.isPasspoint) 1 else 0)
            put(COLUMN_HIDDEN, if (network.isHidden) 1 else 0)
            put(COLUMN_BAND, network.band.ifBlank { bandForFrequency(network.frequency) })
            put(COLUMN_VENDOR, network.vendor.ifBlank { lookupVendor(network.macAddress) })
        }
    }

    fun getPersonalMapStats(): PersonalMapStats {
        val query = "SELECT COUNT(*), COALESCE(SUM($COLUMN_RELIABLE), 0), " +
                "COALESCE(MIN($COLUMN_FIRST_SEEN), 0), COALESCE(MAX($COLUMN_TIMESTAMP), 0), " +
                "COALESCE(AVG(NULLIF($COLUMN_ACCURACY, 0)), 0), " +
                "COALESCE(SUM(CASE WHEN $COLUMN_SECURITY_TYPE = '$SECURITY_OPEN' THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN $COLUMN_OTHER_DB_STATE = $STATE_PRESENT THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN $COLUMN_WPASEC_STATE = $STATE_PRESENT THEN 1 ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN $COLUMN_WPS = 1 THEN 1 ELSE 0 END), 0) " +
                "FROM $TABLE_PERSONAL_MAP"
        return readableDatabase.rawQuery(query, null).use { cursor ->
            if (cursor.moveToFirst()) {
                PersonalMapStats(
                    total = cursor.getLong(0),
                    reliable = cursor.getLong(1),
                    firstSeen = cursor.getLong(2),
                    lastSeen = cursor.getLong(3),
                    avgAccuracy = cursor.getFloat(4),
                    openCount = cursor.getLong(5),
                    crossFound = cursor.getLong(6),
                    wpasecFound = cursor.getLong(7),
                    wpsCount = cursor.getLong(8)
                )
            } else {
                PersonalMapStats()
            }
        }
    }

    fun getPersonalPointsInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limit: Int = 1000
    ): List<PersonalWifiNetwork> {
        val query = "SELECT * FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_LATITUDE BETWEEN ? AND ? AND $COLUMN_LONGITUDE BETWEEN ? AND ? AND $COLUMN_LATITUDE != 0 AND $COLUMN_LONGITUDE != 0 LIMIT ?"
        return readableDatabase.rawQuery(
            query,
            arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString(), limit.toString())
        ).use { cursor -> buildPersonalList(cursor) }
    }

    suspend fun getClusteredPersonalPointsByTileRange(
        tileX1: Int,
        tileY1: Int,
        tileX2: Int,
        tileY2: Int,
        zoom: Int,
        scatterMode: Boolean = false
    ): List<ClusteredMapPoint> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val maxZoom = 23.0
        val isHighZoom = zoom >= maxZoom - 1
        val effectiveScatterMode = scatterMode || isHighZoom
        val groupLevel = if (effectiveScatterMode) maxZoom else zoom + 2
        val mask = (2 * (maxZoom.toInt() - groupLevel.toInt())).coerceAtLeast(0)

        val latNorth = QuadkeyUtils.tileXYToLat(tileY1, zoom)
        val latSouth = QuadkeyUtils.tileXYToLat(tileY2 + 1, zoom)
        val lonWest = QuadkeyUtils.tileXYToLon(tileX1, zoom)
        val lonEast = QuadkeyUtils.tileXYToLon(tileX2 + 1, zoom)

        val db = readableDatabase
        val points = mutableListOf<ClusteredMapPoint>()

        if (effectiveScatterMode) {
            val scatterLimit = getZoomBasedLimit(zoom.toDouble())
            val query = "SELECT $COLUMN_MAC_ADDRESS, $COLUMN_LATITUDE, $COLUMN_LONGITUDE, $COLUMN_WIFI_NAME, $COLUMN_OTHER_DB_STATE, $COLUMN_WPASEC_STATE, $COLUMN_SECURITY_TYPE FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_LATITUDE >= ? AND $COLUMN_LATITUDE <= ? AND $COLUMN_LONGITUDE >= ? AND $COLUMN_LONGITUDE <= ? AND $COLUMN_LATITUDE != 0 AND $COLUMN_LONGITUDE != 0 LIMIT ?"
            db.rawQuery(
                query,
                arrayOf(latSouth.toString(), latNorth.toString(), lonWest.toString(), lonEast.toString(), scatterLimit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val macStr = cursor.getString(0)
                    val mac = macToDecimal(macStr) ?: continue
                    points.add(
                        ClusteredMapPoint(
                            mac, cursor.getDouble(1), cursor.getDouble(2), 1, false, cursor.getString(3),
                            hasCrossData = cursor.getInt(4) == STATE_PRESENT,
                            wpasecKnown = cursor.getInt(5) == STATE_PRESENT,
                            isOpen = cursor.getString(6) == SECURITY_OPEN
                        )
                    )
                }
            }
        } else {
            val divisor = 1L shl mask
            val clusterLimit = getZoomBasedLimit(zoom.toDouble())
            val query = "SELECT MIN($COLUMN_MAC_ADDRESS), AVG($COLUMN_LATITUDE), AVG($COLUMN_LONGITUDE), COUNT(*), MIN($COLUMN_WIFI_NAME), MAX($COLUMN_OTHER_DB_STATE), MAX($COLUMN_WPASEC_STATE), MAX(CASE WHEN $COLUMN_SECURITY_TYPE = '$SECURITY_OPEN' THEN 1 ELSE 0 END) FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_LATITUDE >= ? AND $COLUMN_LATITUDE <= ? AND $COLUMN_LONGITUDE >= ? AND $COLUMN_LONGITUDE <= ? AND $COLUMN_LATITUDE != 0 AND $COLUMN_LONGITUDE != 0 GROUP BY COALESCE(CAST($COLUMN_QUADKEY / $divisor AS INTEGER), -1) LIMIT $clusterLimit"
            db.rawQuery(
                query,
                arrayOf(latSouth.toString(), latNorth.toString(), lonWest.toString(), lonEast.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val macStr = cursor.getString(0)
                    val mac = macToDecimal(macStr) ?: continue
                    val count = cursor.getInt(3)
                    points.add(
                        ClusteredMapPoint(
                            mac, cursor.getDouble(1), cursor.getDouble(2), count, count > 1, cursor.getString(4),
                            hasCrossData = cursor.getInt(5) == STATE_PRESENT,
                            wpasecKnown = cursor.getInt(6) == STATE_PRESENT,
                            isOpen = cursor.getInt(7) == 1
                        )
                    )
                }
            }
        }
        points
    }

    fun getPersonalRecords(): List<PersonalWifiNetwork> {
        val records = mutableListOf<PersonalWifiNetwork>()
        readableDatabase.query(
            TABLE_PERSONAL_MAP, null, null, null, null, null, "$COLUMN_TIMESTAMP DESC"
        ).use { cursor ->
            records.addAll(buildPersonalList(cursor))
        }
        return records
    }

    fun getPersonalRecordsPage(beforeTimestamp: Long, limit: Int): List<PersonalWifiNetwork> =
        getPersonalRecordsPage(beforeTimestamp, Long.MAX_VALUE, limit)

    fun getPersonalRecordsPage(
        beforeTimestamp: Long,
        beforeId: Long,
        limit: Int
    ): List<PersonalWifiNetwork> {

        val cols = "$COLUMN_ID, $COLUMN_WIFI_NAME, $COLUMN_MAC_ADDRESS, $COLUMN_LATITUDE, " +
                "$COLUMN_LONGITUDE, $COLUMN_TIMESTAMP, $COLUMN_LEVEL, $COLUMN_ACCURACY, " +
                "$COLUMN_RELIABLE, $COLUMN_COUNT, $COLUMN_FIRST_SEEN, $COLUMN_SECURITY, " +
                "$COLUMN_SECURITY_TYPE, $COLUMN_FREQUENCY, $COLUMN_CHANNEL, $COLUMN_SOURCE, " +
                "$COLUMN_OTHER_DB_STATE, $COLUMN_WPASEC_STATE, $COLUMN_WPS, $COLUMN_VENDOR, " +
                "$COLUMN_ALT, $COLUMN_PASSPOINT, $COLUMN_HIDDEN, $COLUMN_BAND"
        val query: String
        val args: Array<String>
        if (beforeTimestamp > 0) {
            query = "SELECT $cols FROM $TABLE_PERSONAL_MAP " +
                    "WHERE ($COLUMN_TIMESTAMP < ?) OR ($COLUMN_TIMESTAMP = ? AND $COLUMN_ID < ?) " +
                    "ORDER BY $COLUMN_TIMESTAMP DESC, $COLUMN_ID DESC LIMIT ?"
            args = arrayOf(
                beforeTimestamp.toString(),
                beforeTimestamp.toString(),
                beforeId.toString(),
                limit.toString()
            )
        } else {
            query = "SELECT $cols FROM $TABLE_PERSONAL_MAP " +
                    "ORDER BY $COLUMN_TIMESTAMP DESC, $COLUMN_ID DESC LIMIT ?"
            args = arrayOf(limit.toString())
        }
        return readableDatabase.rawQuery(query, args).use { buildPersonalList(it) }
    }

    fun searchPersonalRecordsByBssid(bssid: String): List<PersonalWifiNetwork> {
        val normalized = normalizeMac(bssid)
        val query = "SELECT * FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_MAC_ADDRESS = ?"
        return readableDatabase.rawQuery(query, arrayOf(normalized)).use { cursor ->
            buildPersonalList(cursor)
        }
    }

    fun getPersonalRecordById(id: Long): PersonalWifiNetwork? {
        val cols = "$COLUMN_ID, $COLUMN_WIFI_NAME, $COLUMN_MAC_ADDRESS, $COLUMN_LATITUDE, " +
                "$COLUMN_LONGITUDE, $COLUMN_TIMESTAMP, $COLUMN_LEVEL, $COLUMN_ACCURACY, " +
                "$COLUMN_RELIABLE, $COLUMN_COUNT, $COLUMN_FIRST_SEEN, $COLUMN_SECURITY, " +
                "$COLUMN_SECURITY_TYPE, $COLUMN_FREQUENCY, $COLUMN_CHANNEL, $COLUMN_SOURCE, " +
                "$COLUMN_OTHER_DB_STATE, $COLUMN_WPASEC_STATE, $COLUMN_WPS, " +
                "$COLUMN_ALT, $COLUMN_PASSPOINT, $COLUMN_HIDDEN, $COLUMN_BAND, $COLUMN_VENDOR"
        return readableDatabase.rawQuery(
            "SELECT $cols FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_ID = ?",
            arrayOf(id.toString())
        ).use { buildPersonalList(it).firstOrNull() }
    }

    fun getPersonalPointDetail(id: Long): PersonalPointDetail? {
        val query = "SELECT $COLUMN_ID, $COLUMN_WIFI_NAME, $COLUMN_MAC_ADDRESS, $COLUMN_LATITUDE, " +
                "$COLUMN_LONGITUDE, $COLUMN_LEVEL, $COLUMN_ACCURACY, $COLUMN_RELIABLE, " +
                "$COLUMN_COUNT, $COLUMN_FIRST_SEEN, $COLUMN_TIMESTAMP, $COLUMN_SECURITY, " +
                "$COLUMN_SECURITY_TYPE, $COLUMN_FREQUENCY, $COLUMN_CHANNEL, $COLUMN_SOURCE, " +
                "$COLUMN_OTHER_DB_STATE, $COLUMN_WPASEC_STATE, $COLUMN_CROSS_CHECKED_AT, " +
                "$COLUMN_WPASEC_CHECKED_AT, $COLUMN_WPS, " +
                "$COLUMN_ALT, $COLUMN_PASSPOINT, $COLUMN_HIDDEN, $COLUMN_BAND, $COLUMN_VENDOR " +
                "FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_ID = ?"
        var pendingPersistId = -1L
        var pendingVendor = ""
        val detail = readableDatabase.rawQuery(query, arrayOf(id.toString())).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val bssid = cursor.getString(2).orEmpty()
                val storedVendor = cursor.getString(25).orEmpty()
                val resolvedVendor = storedVendor.ifBlank { lookupVendor(bssid) }
                if (storedVendor.isBlank() && resolvedVendor.isNotBlank()) {
                    pendingPersistId = cursor.getLong(0)
                    pendingVendor = resolvedVendor
                }
                PersonalPointDetail(
                    id = cursor.getLong(0),
                    ssid = cursor.getString(1).orEmpty(),
                    bssid = bssid,
                    latitude = cursor.getDouble(3),
                    longitude = cursor.getDouble(4),
                    level = cursor.getInt(5),
                    accuracy = cursor.getFloat(6),
                    isReliable = cursor.getInt(7) == 1,
                    measureCount = cursor.getInt(8),
                    firstSeen = cursor.getLong(9),
                    lastSeen = cursor.getLong(10),
                    security = cursor.getString(11),
                    securityType = cursor.getString(12),
                    frequency = cursor.getInt(13),
                    channel = cursor.getInt(14),
                    source = cursor.getString(15) ?: SOURCE_SCAN,
                    otherDbState = cursor.getInt(16),
                    wpasecState = cursor.getInt(17),
                    crossCheckedAt = cursor.getLong(18),
                    wpasecCheckedAt = cursor.getLong(19),
                    isWps = cursor.getInt(20) == 1,
                    alt = cursor.getDouble(21),
                    isPasspoint = cursor.getInt(22) == 1,
                    isHidden = cursor.getInt(23) == 1,
                    band = cursor.getString(24).orEmpty(),
                    vendor = resolvedVendor
                )
            }
        }
        if (pendingPersistId >= 0L) {
            persistVendor(pendingPersistId, pendingVendor)
        }
        return detail
    }

    fun searchPersonalRecords(query: String, limit: Int = 200): List<PersonalWifiNetwork> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val cols = "$COLUMN_ID, $COLUMN_WIFI_NAME, $COLUMN_MAC_ADDRESS, $COLUMN_LATITUDE, " +
                "$COLUMN_LONGITUDE, $COLUMN_TIMESTAMP, $COLUMN_LEVEL, $COLUMN_ACCURACY, " +
                "$COLUMN_RELIABLE, $COLUMN_COUNT, $COLUMN_FIRST_SEEN, $COLUMN_SECURITY, " +
                "$COLUMN_SECURITY_TYPE, $COLUMN_FREQUENCY, $COLUMN_CHANNEL, $COLUMN_SOURCE, " +
                "$COLUMN_OTHER_DB_STATE, $COLUMN_WPASEC_STATE, $COLUMN_WPS, " +
                "$COLUMN_ALT, $COLUMN_PASSPOINT, $COLUMN_HIDDEN, $COLUMN_BAND, $COLUMN_VENDOR"

        val branches = mutableListOf<String>()
        val args = mutableListOf<String>()

        fun addPrefixBranch(column: String, prefix: String) {
            val bounds = PersonalSearchKeys.prefixBounds(prefix) ?: return
            val hi = bounds.second
            if (hi == null) {
                branches += "SELECT $cols FROM $TABLE_PERSONAL_MAP WHERE $column >= ?"
                args += bounds.first
            } else {
                branches += "SELECT $cols FROM $TABLE_PERSONAL_MAP " +
                        "WHERE $column >= ? AND $column < ?"
                args += bounds.first
                args += hi
            }
        }

        // Both norm columns are already lowercase/hex, so range comparison is exact
        // and can use the plain BINARY indexes (no LIKE, no collation tricks).
        addPrefixBranch(COLUMN_WIFI_NAME_NORM, PersonalSearchKeys.normalizeSsid(q))
        val macNorm = PersonalSearchKeys.normalizeMac(q)
        if (PersonalSearchKeys.looksLikeMacQuery(q, macNorm)) addPrefixBranch(COLUMN_MAC_ADDRESS_NORM, macNorm)

        if (branches.isEmpty()) return emptyList()

        // UNION (not OR) so each branch is an independent index range scan; the
        // unique id in the projection makes UNION de-duplicate correctly.
        val sql = "SELECT * FROM (${branches.joinToString(" UNION ")}) AS matches " +
                "ORDER BY $COLUMN_TIMESTAMP DESC, $COLUMN_ID DESC LIMIT ?"
        args += limit.toString()
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { buildPersonalList(it) }
    }

    private fun buildPersonalList(cursor: Cursor): List<PersonalWifiNetwork> {
        val list = mutableListOf<PersonalWifiNetwork>()
        val idIdx = cursor.getColumnIndex(COLUMN_ID)
        val nameIdx = cursor.getColumnIndex(COLUMN_WIFI_NAME)
        val macIdx = cursor.getColumnIndex(COLUMN_MAC_ADDRESS)
        val latIdx = cursor.getColumnIndex(COLUMN_LATITUDE)
        val lonIdx = cursor.getColumnIndex(COLUMN_LONGITUDE)
        val timeIdx = cursor.getColumnIndex(COLUMN_TIMESTAMP)
        val levelIdx = cursor.getColumnIndex(COLUMN_LEVEL)
        val accIdx = cursor.getColumnIndex(COLUMN_ACCURACY)
        val relIdx = cursor.getColumnIndex(COLUMN_RELIABLE)
        val countIdx = cursor.getColumnIndex(COLUMN_COUNT)
        val firstSeenIdx = cursor.getColumnIndex(COLUMN_FIRST_SEEN)
        val securityIdx = cursor.getColumnIndex(COLUMN_SECURITY)
        val securityTypeIdx = cursor.getColumnIndex(COLUMN_SECURITY_TYPE)
        val freqIdx = cursor.getColumnIndex(COLUMN_FREQUENCY)
        val channelIdx = cursor.getColumnIndex(COLUMN_CHANNEL)
        val sourceIdx = cursor.getColumnIndex(COLUMN_SOURCE)
        val otherDbIdx = cursor.getColumnIndex(COLUMN_OTHER_DB_STATE)
        val wpasecIdx = cursor.getColumnIndex(COLUMN_WPASEC_STATE)
        val wpsIdx = cursor.getColumnIndex(COLUMN_WPS)
        val altIdx = cursor.getColumnIndex(COLUMN_ALT)
        val passpointIdx = cursor.getColumnIndex(COLUMN_PASSPOINT)
        val hiddenIdx = cursor.getColumnIndex(COLUMN_HIDDEN)
        val bandIdx = cursor.getColumnIndex(COLUMN_BAND)
        val vendorIdx = cursor.getColumnIndex(COLUMN_VENDOR)

        while (cursor.moveToNext()) {
            val mac = if (macIdx >= 0) cursor.getString(macIdx).orEmpty() else ""
            val storedVendor = if (vendorIdx >= 0) cursor.getString(vendorIdx).orEmpty() else ""
            list.add(
                PersonalWifiNetwork(
                    id = if (idIdx >= 0) cursor.getLong(idIdx) else 0,
                    wifiName = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "" else "",
                    macAddress = mac,
                    latitude = if (latIdx >= 0) cursor.getDouble(latIdx) else 0.0,
                    longitude = if (lonIdx >= 0) cursor.getDouble(lonIdx) else 0.0,
                    timestamp = if (timeIdx >= 0) cursor.getLong(timeIdx) else 0L,
                    level = if (levelIdx >= 0) cursor.getInt(levelIdx) else -100,
                    accuracy = if (accIdx >= 0) cursor.getFloat(accIdx) else 0f,
                    isReliable = if (relIdx >= 0) cursor.getInt(relIdx) == 1 else false,
                    measureCount = if (countIdx >= 0) cursor.getInt(countIdx) else 1,
                    firstSeen = if (firstSeenIdx >= 0) cursor.getLong(firstSeenIdx) else 0L,
                    security = if (securityIdx >= 0) cursor.getString(securityIdx) else null,
                    securityType = if (securityTypeIdx >= 0) cursor.getString(securityTypeIdx) else null,
                    frequency = if (freqIdx >= 0) cursor.getInt(freqIdx) else 0,
                    channel = if (channelIdx >= 0) cursor.getInt(channelIdx) else 0,
                    source = if (sourceIdx >= 0) cursor.getString(sourceIdx) ?: SOURCE_SCAN else SOURCE_SCAN,
                    otherDbState = if (otherDbIdx >= 0) cursor.getInt(otherDbIdx) else STATE_UNKNOWN,
                    wpasecState = if (wpasecIdx >= 0) cursor.getInt(wpasecIdx) else STATE_UNKNOWN,
                    isWps = if (wpsIdx >= 0) cursor.getInt(wpsIdx) == 1 else false,
                    // v7
                    alt = if (altIdx >= 0) cursor.getDouble(altIdx) else 0.0,
                    isPasspoint = if (passpointIdx >= 0) cursor.getInt(passpointIdx) == 1 else false,
                    isHidden = if (hiddenIdx >= 0) cursor.getInt(hiddenIdx) == 1 else false,
                    band = if (bandIdx >= 0) cursor.getString(bandIdx).orEmpty() else "",
                    vendor = storedVendor.ifBlank { lookupVendor(mac) }
                )
            )
        }
        return list
    }

    fun checkpointWal() {
        try {
            readableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "wal_checkpoint failed: ${e.message}")
        }
    }

    fun clearPersonalMap() {
        writableDatabase.delete(TABLE_PERSONAL_MAP, null, null)
    }

    fun updatePersonalNetworkInfo(id: Long, newName: String): Boolean {
        val name = newName.trim()
        if (name.isEmpty()) return false
        val db = writableDatabase
        val values = ContentValues().apply {

            put(COLUMN_WIFI_NAME, name)
            put(COLUMN_WIFI_NAME_NORM, PersonalSearchKeys.normalizeSsid(name))
        }
        return db.update(TABLE_PERSONAL_MAP, values, "$COLUMN_ID = ?", arrayOf(id.toString())) > 0
    }

    fun deletePersonalRecord(id: Long): Boolean {
        return writableDatabase.delete(
            TABLE_PERSONAL_MAP,
            "$COLUMN_ID = ?",
            arrayOf(id.toString())
        ) > 0
    }

    data class CrossState(val otherDbState: Int, val wpasecState: Int)

    fun getCrossStates(bssids: Set<String>): Map<String, CrossState> {
        if (bssids.isEmpty()) return emptyMap()
        val normalized = bssids.map { normalizeMac(it).uppercase() }.distinct()
        val result = HashMap<String, CrossState>(normalized.size)
        normalized.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val query = "SELECT $COLUMN_MAC_ADDRESS, $COLUMN_OTHER_DB_STATE, $COLUMN_WPASEC_STATE " +
                    "FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_MAC_ADDRESS IN ($placeholders)"
            readableDatabase.rawQuery(query, chunk.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    result[cursor.getString(0)] = CrossState(cursor.getInt(1), cursor.getInt(2))
                }
            }
        }
        return result
    }

    fun setOtherDbState(bssid: String, state: Int, checkedAt: Long) {
        val values = ContentValues().apply {
            put(COLUMN_OTHER_DB_STATE, state)
            put(COLUMN_CROSS_CHECKED_AT, checkedAt)
        }
        writableDatabase.update(
            TABLE_PERSONAL_MAP, values, "$COLUMN_MAC_ADDRESS = ?", arrayOf(normalizeMac(bssid))
        )
    }

    fun setOtherDbStates(states: Map<String, Int>, checkedAt: Long) {
        if (states.isEmpty()) return

        val normalized = states.mapKeys { normalizeMac(it.key) }
        val db = writableDatabase
        db.transaction {
            val stmt = db.compileStatement(
                "UPDATE $TABLE_PERSONAL_MAP SET $COLUMN_OTHER_DB_STATE = ?, $COLUMN_CROSS_CHECKED_AT = ? " +
                        "WHERE $COLUMN_MAC_ADDRESS = ?"
            )
            try {
                normalized.forEach { (mac, state) ->
                    stmt.bindLong(1, state.toLong())
                    stmt.bindLong(2, checkedAt)
                    stmt.bindString(3, mac)
                    stmt.executeUpdateDelete()
                    stmt.clearBindings()
                }
            } finally {
                stmt.close()
            }
        }
    }

    fun setWpaSecState(bssid: String, state: Int, checkedAt: Long) {
        val values = ContentValues().apply {
            put(COLUMN_WPASEC_STATE, state)
            put(COLUMN_WPASEC_CHECKED_AT, checkedAt)
        }
        writableDatabase.update(
            TABLE_PERSONAL_MAP, values, "$COLUMN_MAC_ADDRESS = ?", arrayOf(normalizeMac(bssid))
        )
    }

    fun setWpaSecStates(states: Map<String, Int>, checkedAt: Long) {
        if (states.isEmpty()) return
        val normalized = states.mapKeys { normalizeMac(it.key) }
        val db = writableDatabase
        db.transaction {
            val stmt = db.compileStatement(
                "UPDATE $TABLE_PERSONAL_MAP SET $COLUMN_WPASEC_STATE = ?, $COLUMN_WPASEC_CHECKED_AT = ? " +
                        "WHERE $COLUMN_MAC_ADDRESS = ?"
            )
            try {
                normalized.forEach { (mac, state) ->
                    stmt.bindLong(1, state.toLong())
                    stmt.bindLong(2, checkedAt)
                    stmt.bindString(3, mac)
                    stmt.executeUpdateDelete()
                    stmt.clearBindings()
                }
            } finally {
                stmt.close()
            }
        }
    }

    fun resetOtherDbStates() {
        writableDatabase.execSQL(
            "UPDATE $TABLE_PERSONAL_MAP SET $COLUMN_OTHER_DB_STATE = $STATE_UNKNOWN, $COLUMN_CROSS_CHECKED_AT = 0"
        )
    }

    fun resetWpaSecStates() {
        writableDatabase.execSQL(
            "UPDATE $TABLE_PERSONAL_MAP SET $COLUMN_WPASEC_STATE = $STATE_UNKNOWN, $COLUMN_WPASEC_CHECKED_AT = 0"
        )
    }

    fun getBssidsNeedingCrossCheck(ttlMs: Long, limit: Int): List<String> {
        val threshold = System.currentTimeMillis() - ttlMs
        val query = "SELECT $COLUMN_MAC_ADDRESS FROM $TABLE_PERSONAL_MAP " +
                "WHERE $COLUMN_OTHER_DB_STATE = $STATE_UNKNOWN OR $COLUMN_CROSS_CHECKED_AT < ? " +
                "ORDER BY $COLUMN_TIMESTAMP DESC LIMIT ?"
        return readableDatabase.rawQuery(query, arrayOf(threshold.toString(), limit.toString()))
            .use { cursor ->
                val list = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { list.add(it) }
                }
                list
            }
    }

    fun getBssidsNeedingWpaSecCheck(ttlMs: Long, limit: Int): List<String> {
        val threshold = System.currentTimeMillis() - ttlMs
        val query = "SELECT $COLUMN_MAC_ADDRESS FROM $TABLE_PERSONAL_MAP " +
                "WHERE $COLUMN_WPASEC_STATE = $STATE_UNKNOWN OR $COLUMN_WPASEC_CHECKED_AT < ? " +
                "ORDER BY $COLUMN_TIMESTAMP DESC LIMIT ?"
        return readableDatabase.rawQuery(query, arrayOf(threshold.toString(), limit.toString()))
            .use { cursor ->
                val list = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { list.add(it) }
                }
                list
            }
    }

    fun getBssidsNeedingCrossCheckFor(visible: Set<String>, ttlMs: Long): List<String> {
        return filterVisibleNeeding(visible, COLUMN_OTHER_DB_STATE, COLUMN_CROSS_CHECKED_AT, ttlMs)
    }

    fun getBssidsNeedingWpaSecCheckFor(visible: Set<String>, ttlMs: Long): List<String> {
        return getWpaSecCandidates(visible, ttlMs).keys.toList()
    }

    fun getWpaSecCandidates(visible: Set<String>, ttlMs: Long): Map<String, String> {
        if (visible.isEmpty()) return emptyMap()
        val threshold = System.currentTimeMillis() - ttlMs
        val normalized = visible.map { normalizeMac(it).uppercase() }.distinct()
        val result = HashMap<String, String>()
        normalized.chunked(400).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val query = "SELECT $COLUMN_MAC_ADDRESS, $COLUMN_WIFI_NAME FROM $TABLE_PERSONAL_MAP " +
                    "WHERE $COLUMN_MAC_ADDRESS IN ($placeholders) " +
                    "AND $COLUMN_WIFI_NAME IS NOT NULL AND TRIM($COLUMN_WIFI_NAME) != '' " +
                    "AND ($COLUMN_WPASEC_STATE = $STATE_UNKNOWN OR $COLUMN_WPASEC_CHECKED_AT <= ?)"
            val args = chunk.toTypedArray() + threshold.toString()
            readableDatabase.rawQuery(query, args).use { cursor ->
                while (cursor.moveToNext()) {
                    val mac = cursor.getString(0) ?: continue
                    result[mac] = cursor.getString(1).orEmpty()
                }
            }
        }
        return result
    }

    private fun filterVisibleNeeding(
        visible: Set<String>,
        stateColumn: String,
        checkedColumn: String,
        ttlMs: Long
    ): List<String> {
        if (visible.isEmpty()) return emptyList()
        val threshold = System.currentTimeMillis() - ttlMs
        val normalized = visible.map { normalizeMac(it).uppercase() }.distinct()
        val result = mutableListOf<String>()
        normalized.chunked(400).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val query = "SELECT $COLUMN_MAC_ADDRESS FROM $TABLE_PERSONAL_MAP " +
                    "WHERE $COLUMN_MAC_ADDRESS IN ($placeholders) " +
                    "AND ($stateColumn = $STATE_UNKNOWN OR $checkedColumn <= ?)"
            val args = chunk.toTypedArray() + threshold.toString()
            readableDatabase.rawQuery(query, args).use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let { result.add(it) }
            }
        }
        return result
    }

    fun getEssidsForBssids(bssids: Set<String>): Map<String, String> {
        if (bssids.isEmpty()) return emptyMap()
        val result = HashMap<String, String>()
        bssids.map { normalizeMac(it).uppercase() }.distinct().chunked(400).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val query = "SELECT $COLUMN_MAC_ADDRESS, $COLUMN_WIFI_NAME FROM $TABLE_PERSONAL_MAP " +
                    "WHERE $COLUMN_MAC_ADDRESS IN ($placeholders)"
            readableDatabase.rawQuery(query, chunk.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { result[it] = cursor.getString(1).orEmpty() }
                }
            }
        }
        return result
    }

    fun getSecurityBreakdown(): List<Pair<String, Long>> {
        val query = "SELECT COALESCE($COLUMN_SECURITY_TYPE, '$SECURITY_UNKNOWN'), COUNT(*) " +
                "FROM $TABLE_PERSONAL_MAP GROUP BY 1 ORDER BY 2 DESC"
        return readableDatabase.rawQuery(query, null).use { cursor ->
            val list = mutableListOf<Pair<String, Long>>()
            while (cursor.moveToNext()) list.add(cursor.getString(0) to cursor.getLong(1))
            list
        }
    }

    fun getChannelBreakdown(limit: Int = 40): List<Pair<Int, Long>> {
        val query = "SELECT $COLUMN_CHANNEL, COUNT(*) FROM $TABLE_PERSONAL_MAP " +
                "WHERE $COLUMN_CHANNEL > 0 GROUP BY $COLUMN_CHANNEL ORDER BY 2 DESC LIMIT ?"
        return readableDatabase.rawQuery(query, arrayOf(limit.toString())).use { cursor ->
            val list = mutableListOf<Pair<Int, Long>>()
            while (cursor.moveToNext()) list.add(cursor.getInt(0) to cursor.getLong(1))
            list
        }
    }

    private val wigleTimeFormatLocal = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }
    private val wigleTimeFormat: SimpleDateFormat
        get() = wigleTimeFormatLocal.get()!!

    fun exportWigleCsv(writer: Writer) {
        fun escape(value: String?): String {
            val v = value ?: ""
            return if (v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')) {
                "\"" + v.replace("\"", "\"\"") + "\""
            } else {
                v
            }
        }

        fun writeRow(vararg fields: String?) {
            writer.write(fields.joinToString(",") { escape(it) })
            writer.write("\n")
        }

        val formatter = wigleTimeFormat

        writeRow(
            "WigleWifi-1.6",
            "appRelease=WIFI-Frankenstein",
            "model=${android.os.Build.MODEL}",
            "release=${android.os.Build.VERSION.RELEASE}",
            "device=${android.os.Build.DEVICE}",
            "display=${android.os.Build.DISPLAY}",
            "board=${android.os.Build.BOARD}",
            "brand=${android.os.Build.BRAND}",
            "star=Sol",
            "body=3",
            "subBody=0"
        )
        writeRow(
            "MAC", "SSID", "AuthMode", "FirstSeen", "Channel", "Frequency", "RSSI",
            "CurrentLatitude", "CurrentLongitude", "AltitudeMeters", "AccuracyMeters",
            "RCOIs", "MfgrId", "Type"
        )

        val query = "SELECT $COLUMN_MAC_ADDRESS, $COLUMN_WIFI_NAME, $COLUMN_SECURITY, " +
                "$COLUMN_FIRST_SEEN, $COLUMN_TIMESTAMP, $COLUMN_CHANNEL, $COLUMN_FREQUENCY, " +
                "$COLUMN_LEVEL, $COLUMN_LATITUDE, $COLUMN_LONGITUDE, $COLUMN_ACCURACY, " +
                "$COLUMN_ALT " +
                "FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_LATITUDE != 0 AND $COLUMN_LONGITUDE != 0"
        readableDatabase.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                val mac = cursor.getString(0) ?: continue
                val ssid = cursor.getString(1).orEmpty()
                val auth = cursor.getString(2)?.takeIf { it.isNotBlank() } ?: "[ESSID]"
                val firstSeen = cursor.getLong(3).let { if (it > 0) it else cursor.getLong(4) }
                val channel = cursor.getInt(5)
                val frequency = cursor.getInt(6)
                val rssi = cursor.getInt(7)
                val lat = cursor.getDouble(8)
                val lon = cursor.getDouble(9)
                val accuracy = cursor.getFloat(10)
                val alt = cursor.getDouble(11)

                writeRow(
                    mac,
                    ssid,
                    auth,
                    formatter.format(java.util.Date(firstSeen)),
                    if (channel > 0) channel.toString() else "",
                    if (frequency > 0) frequency.toString() else "",
                    rssi.toString(),
                    lat.toString(),
                    lon.toString(),
                    alt.toString(),
                    accuracy.toString(),
                    "",
                    "",
                    "WIFI"
                )
            }
        }
        writer.flush()
    }

    fun importWigleCsv(reader: Reader, source: String = SOURCE_IMPORT_WIGLE): PersonalWriteResult {
        var inserted = 0
        var updated = 0
        CSVReader(reader).use { csv ->
            var index: Map<String, Int>? = null
            val batch = mutableListOf<PersonalWifiNetwork>()

            fun flush() {
                if (batch.isEmpty()) return
                val r = addPersonalRecords(batch)
                inserted += r.inserted
                updated += r.updated
                batch.clear()
            }

            while (true) {
                val row = csv.readNext() ?: break
                if (row.isEmpty() || row.all { it.isNullOrBlank() }) continue
                if (index == null) {
                    if (row[0].trim().startsWith("WigleWifi", ignoreCase = true)) continue
                    index = row.withIndex()
                        .filter { it.value.isNotBlank() }
                        .associate { (i, name) -> name.trim().lowercase() to i }
                    continue
                }
                val idx = index ?: continue

                fun value(name: String): String? {
                    val i = idx[name.lowercase()] ?: return null
                    return row.getOrNull(i)?.trim()
                }

                val mac = value("MAC") ?: continue
                if (mac.isBlank()) continue
                val lat = value("CurrentLatitude")?.toDoubleOrNull() ?: continue
                val lon = value("CurrentLongitude")?.toDoubleOrNull() ?: continue
                if (lat == 0.0 && lon == 0.0) continue
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

                val security = value("AuthMode")?.takeIf { it.isNotBlank() }
                val frequency = value("Frequency")?.toIntOrNull() ?: 0
                val channel = value("Channel")?.toIntOrNull()
                    ?: channelForFrequency(frequency)
                val timestamp = parseWigleTime(value("FirstSeen"))
                val accuracy = value("AccuracyMeters")?.toFloatOrNull() ?: 0f
                val alt = value("AltitudeMeters")?.toDoubleOrNull() ?: 0.0

                batch.add(
                    PersonalWifiNetwork(
                        wifiName = value("SSID").orEmpty(),
                        macAddress = mac,
                        latitude = lat,
                        longitude = lon,

                        timestamp = System.currentTimeMillis(),
                        firstSeen = timestamp,
                        level = value("RSSI")?.toDoubleOrNull()?.toInt() ?: -100,
                        accuracy = accuracy,
                        isReliable = accuracy > 0f && accuracy < 100f,
                        security = security,
                        securityType = classifySecurity(security),
                        frequency = frequency,
                        channel = channel,
                        source = source,
                        alt = alt
                    )
                )
                if (batch.size >= 500) flush()
            }
            flush()
        }
        return PersonalWriteResult(inserted, updated)
    }

    private fun parseWigleTime(value: String?): Long {
        if (value.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            wigleTimeFormat.parse(value)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            value.toLongOrNull() ?: System.currentTimeMillis()
        }
    }

    fun findReliableLocation(
        scans: List<Pair<String, Int>>,
        fallback: LocalAppDbHelper? = null
    ): Pair<Double, Double>? {
        if (scans.isEmpty()) return null

        val normalizedScans = LinkedHashMap<String, Int>()
        scans.forEach { (mac, level) -> normalizedScans[normalizeMac(mac)] = level }
        if (normalizedScans.isEmpty()) return null

        val db = readableDatabase
        val args = normalizedScans.keys.toTypedArray()
        val placeholders = args.joinToString(",") { "?" }

        val knownAps = mutableMapOf<String, Pair<Double, Double>>()
        val query = "SELECT $COLUMN_MAC_ADDRESS, $COLUMN_LATITUDE, $COLUMN_LONGITUDE FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_MAC_ADDRESS IN ($placeholders) AND $COLUMN_RELIABLE = 1 AND $COLUMN_LATITUDE != 0 AND $COLUMN_LONGITUDE != 0"
        db.rawQuery(query, args).use { cursor ->
            while (cursor.moveToNext()) {
                knownAps[cursor.getString(0)] = Pair(cursor.getDouble(1), cursor.getDouble(2))
            }
        }

        if (knownAps.size < 3 && fallback != null) {
            fallback.findKnownLocations(normalizedScans.keys).forEach { (mac, coords) ->
                if (!knownAps.containsKey(mac)) knownAps[mac] = coords
            }
        }

        if (knownAps.size < 3) return null

        var sumWeightLat = 0.0
        var sumWeightLon = 0.0
        var sumWeight = 0.0
        normalizedScans.forEach { (mac, level) ->
            val coords = knownAps[mac] ?: return@forEach
            val weight = calculateRssiWeight(level, 0f, 0L)
            sumWeightLat += coords.first * weight
            sumWeightLon += coords.second * weight
            sumWeight += weight
        }

        return if (sumWeight > 0) {
            Pair(sumWeightLat / sumWeight, sumWeightLon / sumWeight)
        } else null
    }

    fun importFromLegacy(source: SQLiteDatabase) {
        if (!tableExists(source, TABLE_PERSONAL_MAP)) return

        val columns = mutableSetOf<String>()
        source.rawQuery("PRAGMA table_info($TABLE_PERSONAL_MAP)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val nameIdx = cursor.getColumnIndex("name")
                if (nameIdx >= 0) cursor.getString(nameIdx)?.let { columns.add(it) }
            }
        }
        if (columns.isEmpty()) return

        fun col(name: String): String = if (columns.contains(name)) name else "NULL"
        val query = "SELECT ${col(COLUMN_WIFI_NAME)}, ${col(COLUMN_MAC_ADDRESS)}, " +
                "${col(COLUMN_LATITUDE)}, ${col(COLUMN_LONGITUDE)}, ${col(COLUMN_TIMESTAMP)}, " +
                "${col(COLUMN_LEVEL)}, ${col(COLUMN_ACCURACY)}, ${col(COLUMN_RELIABLE)}, " +
                "${col(COLUMN_COUNT)}, ${col(COLUMN_WEIGHT_SUM)}, ${col(COLUMN_FIRST_SEEN)} " +
                "FROM $TABLE_PERSONAL_MAP"

        val aggregates = LinkedHashMap<String, LegacyAggregate>()
        source.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                val rawMac = cursor.getString(1) ?: continue
                if (rawMac.isBlank()) continue
                val mac = normalizeMac(rawMac)
                val lat = cursor.getDouble(2)
                val lon = cursor.getDouble(3)
                if (lat == 0.0 && lon == 0.0) continue
                val timestamp = cursor.getLong(4)
                val level = if (cursor.isNull(5)) -100 else cursor.getInt(5)
                val accuracy = cursor.getFloat(6)
                val reliable = cursor.getInt(7) == 1
                val count = cursor.getInt(8).coerceAtLeast(1)
                val weightSum = cursor.getDouble(9)
                val firstSeen = if (columns.contains(COLUMN_FIRST_SEEN)) cursor.getLong(10) else timestamp

                val agg = aggregates.getOrPut(mac) {
                    LegacyAggregate(cursor.getString(0).orEmpty(), if (firstSeen > 0) firstSeen else timestamp)
                }
                val w = if (weightSum > 0) weightSum else count.toDouble()
                agg.latSum += lat * w
                agg.lonSum += lon * w
                agg.weightSum += w
                agg.measureCount += count
                agg.lastSeen = maxOf(agg.lastSeen, timestamp)
                if (agg.firstSeen == 0L) agg.firstSeen = timestamp else agg.firstSeen = minOf(agg.firstSeen, timestamp)
                agg.bestLevel = if (agg.bestLevel == Int.MIN_VALUE) level else maxOf(agg.bestLevel, level)
                if (accuracy > 0f && (agg.bestAccuracy <= 0f || accuracy < agg.bestAccuracy)) agg.bestAccuracy = accuracy
                agg.reliable = agg.reliable || reliable
                if (agg.wifiName.isBlank()) agg.wifiName = cursor.getString(0).orEmpty()
            }
        }

        writableDatabase.transaction {
            aggregates.forEach { (mac, agg) ->
                val lat = if (agg.weightSum > 0) agg.latSum / agg.weightSum else 0.0
                val lon = if (agg.weightSum > 0) agg.lonSum / agg.weightSum else 0.0
                val values = ContentValues().apply {
                    put(COLUMN_WIFI_NAME, agg.wifiName)
                    put(COLUMN_MAC_ADDRESS, mac)
                    put(COLUMN_WIFI_NAME_NORM, PersonalSearchKeys.normalizeSsid(agg.wifiName))
                    put(COLUMN_MAC_ADDRESS_NORM, PersonalSearchKeys.normalizeMac(mac))
                    put(COLUMN_LATITUDE, lat)
                    put(COLUMN_LONGITUDE, lon)
                    put(COLUMN_TIMESTAMP, agg.lastSeen)
                    put(COLUMN_LEVEL, agg.bestLevel)
                    put(COLUMN_ACCURACY, agg.bestAccuracy)
                    put(COLUMN_RELIABLE, if (agg.reliable) 1 else 0)
                    put(COLUMN_COUNT, agg.measureCount)
                    put(COLUMN_WEIGHT_SUM, agg.weightSum)
                    put(COLUMN_LAT_SUM, agg.latSum)
                    put(COLUMN_LON_SUM, agg.lonSum)
                    put(COLUMN_FIRST_SEEN, agg.firstSeen)
                    put(COLUMN_SOURCE, SOURCE_SCAN)
                    put(
                        COLUMN_QUADKEY,
                        if (lat != 0.0 && lon != 0.0) QuadkeyUtils.latLonToQuadkey(lat, lon) else null
                    )
                }
                writableDatabase.insertWithOnConflict(
                    TABLE_PERSONAL_MAP, null, values, SQLiteDatabase.CONFLICT_IGNORE
                )
            }
        }
    }

    private class LegacyAggregate(var wifiName: String, var firstSeen: Long) {
        var latSum: Double = 0.0
        var lonSum: Double = 0.0
        var weightSum: Double = 0.0
        var measureCount: Int = 0
        var lastSeen: Long = 0L
        var bestLevel: Int = Int.MIN_VALUE
        var bestAccuracy: Float = 0f
        var reliable: Boolean = false
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        return try {
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table)
            ).use { it.moveToFirst() }
        } catch (_: Exception) {
            false
        }
    }

    private fun calculateRssiWeight(rssi: Int, accuracyMeters: Float, dtMs: Long): Double {
        return calculateSpatialWeight(rssi, accuracyMeters) * recencyFactor(dtMs)
    }

    private fun calculateSpatialWeight(rssi: Int, accuracyMeters: Float): Double {
        val distance = 10.0.pow((RSSI_REF - rssi) / (10.0 * PATH_LOSS_N)).coerceIn(1.0, 500.0)
        val sigmaPath = distance / 2.0
        val sigmaGps = accuracyMeters.toDouble().coerceAtLeast(3.0)
        val sigma = sqrt(sigmaPath * sigmaPath + sigmaGps * sigmaGps)
        return 1.0 / (sigma * sigma)
    }

    private fun recencyFactor(dtMs: Long): Double {

        val capped = dtMs.coerceAtLeast(0L).coerceAtMost(24L * 60 * 60 * 1000)
        return 0.5.pow(capped.toDouble() / WEIGHT_HALF_LIFE_MS)
    }

    private fun recencyFactorFull(dtMs: Long): Double =
        0.5.pow(dtMs.coerceAtLeast(0L).toDouble() / WEIGHT_HALF_LIFE_MS)

    private fun normalizeMac(mac: String): String =
        MacAddressUtils.formatToColonSeparated(mac) ?: mac.trim().uppercase()

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * earthRadius * asin(sqrt(a).coerceIn(0.0, 1.0))
    }

    private fun getZoomBasedLimit(zoom: Double): Int {
        return when {
            zoom < 10 -> 2000
            zoom < 11 -> 4000
            zoom < 12 -> 8000
            zoom < 13 -> 15000
            zoom < 14 -> 25000
            zoom < 15 -> 40000
            zoom < 16 -> 60000
            zoom < 18 -> 80000
            else -> 100000
        }
    }

    private fun macToDecimal(mac: String?): Long? {
        if (mac == null || mac.isBlank()) return null

        return MacAddressUtils.convertToDecimal(mac)
    }
}
