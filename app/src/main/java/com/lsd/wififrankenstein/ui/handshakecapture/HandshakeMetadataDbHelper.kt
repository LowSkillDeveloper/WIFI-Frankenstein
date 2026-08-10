package com.lsd.wififrankenstein.ui.handshakecapture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class HandshakeMetadataDbHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "handshakes_meta.db"
        private const val DATABASE_VERSION = 9
        private const val TABLE_HANDSHAKES = "handshakes"

        private const val COL_FILE_NAME = "file_name"
        private const val COL_ESSID = "essid"
        private const val COL_BSSID = "bssid"
        private const val COL_FILE_SIZE = "file_size"
        private const val COL_LAST_MODIFIED = "last_modified"
        private const val COL_HASH_22000 = "hash_22000"
        private const val COL_HASH_PMKID = "hash_pmkid"
        private const val COL_IS_VALID = "is_valid"
        private const val COL_CRACKED_PASSWORD = "cracked_password"
        private const val COL_LATITUDE = "latitude"
        private const val COL_LONGITUDE = "longitude"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPLOADED_TO_WPASEC = "uploaded_to_wpasec"
        private const val COL_WPASEC_KEY = "wpasec_key"
        private const val COL_WPASEC_CHECKED = "wpasec_checked"
        private const val COL_WPASEC_PASSWORD_FOUND = "wpasec_password_found"
        private const val COL_WPASEC_PASSWORD = "wpasec_password"
        private const val COL_ORIGINAL_FORMAT = "original_format"
        private const val COL_HANDSHAKE_COUNT = "handshake_count"
        private const val COL_EAPOL_COUNT = "eapol_count"
        private const val COL_PMKID_COUNT = "pmkid_count"
        private const val COL_KEYVER = "keyver"
        private const val COL_NONCE_ERROR_CORRECTION = "nonce_error_correction"
        private const val COL_ENDIANNESS = "endianness"
        private const val COL_UPLOADED_TO_OHC = "uploaded_to_ohc"
        private const val COL_REQUEST_ID_OHC = "request_id_ohc"
        private const val COL_OHC_EMAIL = "ohc_email"
        private const val COL_HASH_DEDUP_MD5 = "hash_dedup_md5"
        private const val COL_CLIENTS = "clients"
        private const val COL_CHANNEL = "channel"
        private const val COL_BAND = "band"
        private const val COL_AKM = "akm"
        private const val COL_GROUP_CIPHER = "group_cipher"
        private const val COL_PAIRWISE_CIPHER = "pairwise_cipher"
        private const val COL_RSSI = "rssi"
        private const val COL_EAPOL_M1_COUNT = "eapol_m1_count"
        private const val COL_EAPOL_M2_COUNT = "eapol_m2_count"
        private const val COL_EAPOL_M3_COUNT = "eapol_m3_count"
        private const val COL_EAPOL_M4_COUNT = "eapol_m4_count"
        private const val COL_BEACON_COUNT = "beacon_count"
        private const val COL_ASSOC_REQ_COUNT = "assoc_req_count"
        private const val COL_AUTH_COUNT = "auth_count"
        private const val COL_PROBE_REQ_COUNT = "probe_req_count"
        private const val COL_HASH_16800 = "hash_16800"
        private const val COL_APS_IN_FILE = "aps_in_file"

        private const val IDX_ESSID =
            "CREATE INDEX IF NOT EXISTS idx_handshakes_essid ON $TABLE_HANDSHAKES ($COL_ESSID)"
        private const val IDX_BSSID =
            "CREATE INDEX IF NOT EXISTS idx_handshakes_bssid ON $TABLE_HANDSHAKES ($COL_BSSID)"
        private const val IDX_LAST_MODIFIED =
            "CREATE INDEX IF NOT EXISTS idx_handshakes_last_modified ON $TABLE_HANDSHAKES ($COL_LAST_MODIFIED)"

        private val lock = ReentrantLock()
        private fun getOptionalDouble(cursor: android.database.Cursor, column: String): Double? {
            val idx = cursor.getColumnIndexOrThrow(column)
            return if (cursor.isNull(idx)) null else cursor.getDouble(idx)
        }

        private fun getOptionalInt(cursor: android.database.Cursor, column: String): Int? {
            val idx = cursor.getColumnIndexOrThrow(column)
            return if (cursor.isNull(idx)) null else cursor.getInt(idx)
        }
    }

    private val CREATE_TABLE = """
        CREATE TABLE $TABLE_HANDSHAKES (
            $COL_FILE_NAME TEXT PRIMARY KEY,
            $COL_ESSID TEXT,
            $COL_BSSID TEXT,
            $COL_FILE_SIZE INTEGER DEFAULT 0,
            $COL_LAST_MODIFIED INTEGER DEFAULT 0,
            $COL_HASH_22000 TEXT,
            $COL_HASH_PMKID TEXT,
            $COL_IS_VALID INTEGER,
            $COL_CRACKED_PASSWORD TEXT,
            $COL_LATITUDE REAL,
            $COL_LONGITUDE REAL,
            $COL_UPLOADED_TO_WPASEC INTEGER DEFAULT 0,
            $COL_WPASEC_KEY TEXT,
            $COL_WPASEC_CHECKED INTEGER DEFAULT 0,
            $COL_WPASEC_PASSWORD_FOUND INTEGER DEFAULT 0,
            $COL_WPASEC_PASSWORD TEXT,
            $COL_ORIGINAL_FORMAT TEXT,
            $COL_HANDSHAKE_COUNT INTEGER DEFAULT 0,
            $COL_EAPOL_COUNT INTEGER DEFAULT 0,
            $COL_PMKID_COUNT INTEGER DEFAULT 0,
            $COL_KEYVER INTEGER,
            $COL_NONCE_ERROR_CORRECTION INTEGER,
            $COL_ENDIANNESS TEXT,
            $COL_UPLOADED_TO_OHC INTEGER DEFAULT 0,
            $COL_REQUEST_ID_OHC TEXT,
            $COL_OHC_EMAIL TEXT,
            $COL_HASH_DEDUP_MD5 TEXT,
            $COL_CLIENTS TEXT,
            $COL_CHANNEL INTEGER,
            $COL_BAND TEXT,
            $COL_AKM TEXT,
            $COL_GROUP_CIPHER TEXT,
            $COL_PAIRWISE_CIPHER TEXT,
            $COL_RSSI INTEGER,
            $COL_EAPOL_M1_COUNT INTEGER DEFAULT 0,
            $COL_EAPOL_M2_COUNT INTEGER DEFAULT 0,
            $COL_EAPOL_M3_COUNT INTEGER DEFAULT 0,
            $COL_EAPOL_M4_COUNT INTEGER DEFAULT 0,
            $COL_BEACON_COUNT INTEGER DEFAULT 0,
            $COL_ASSOC_REQ_COUNT INTEGER DEFAULT 0,
            $COL_AUTH_COUNT INTEGER DEFAULT 0,
            $COL_PROBE_REQ_COUNT INTEGER DEFAULT 0,
            $COL_HASH_16800 TEXT,
            $COL_APS_IN_FILE TEXT,
            $COL_CREATED_AT INTEGER DEFAULT (strftime('%s','now'))
        )
    """.trimIndent()

    init {
        lock.withLock { writableDatabase }
    }

    override fun onCreate(db: SQLiteDatabase?) {
        db?.execSQL(CREATE_TABLE)
        db?.execSQL(IDX_ESSID)
        db?.execSQL(IDX_BSSID)
        db?.execSQL(IDX_LAST_MODIFIED)
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_UPLOADED_TO_WPASEC INTEGER DEFAULT 0")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_WPASEC_KEY TEXT")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_WPASEC_CHECKED INTEGER DEFAULT 0")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_WPASEC_PASSWORD_FOUND INTEGER DEFAULT 0")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_WPASEC_PASSWORD TEXT")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_ORIGINAL_FORMAT TEXT")
        }
        if (oldVersion < 3) {
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_HANDSHAKE_COUNT INTEGER DEFAULT 0")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_EAPOL_COUNT INTEGER DEFAULT 0")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_PMKID_COUNT INTEGER DEFAULT 0")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_KEYVER INTEGER")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_NONCE_ERROR_CORRECTION INTEGER")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_ENDIANNESS TEXT")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_REQUEST_ID_OHC TEXT")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_HASH_DEDUP_MD5 TEXT")
        }
        if (oldVersion < 4) {
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_UPLOADED_TO_OHC INTEGER DEFAULT 0")
        }
        if (oldVersion < 5) {
            db?.execSQL(IDX_ESSID)
            db?.execSQL(IDX_BSSID)
            db?.execSQL(IDX_LAST_MODIFIED)
        }
        if (oldVersion < 6) {
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_CLIENTS TEXT")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_CHANNEL INTEGER")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_BAND TEXT")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_AKM TEXT")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_GROUP_CIPHER TEXT")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_PAIRWISE_CIPHER TEXT")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_RSSI INTEGER")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_EAPOL_M1_COUNT INTEGER DEFAULT 0")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_EAPOL_M2_COUNT INTEGER DEFAULT 0")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_EAPOL_M3_COUNT INTEGER DEFAULT 0")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_EAPOL_M4_COUNT INTEGER DEFAULT 0")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_BEACON_COUNT INTEGER DEFAULT 0")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_ASSOC_REQ_COUNT INTEGER DEFAULT 0")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_AUTH_COUNT INTEGER DEFAULT 0")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_PROBE_REQ_COUNT INTEGER DEFAULT 0")
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_HASH_16800 TEXT")
        }
        if (oldVersion < 7) {
            db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_OHC_EMAIL TEXT")
        }
        if (oldVersion < 8) {
            val v6Columns = listOf(
                "$COL_CLIENTS TEXT",
                "$COL_CHANNEL INTEGER",
                "$COL_BAND TEXT",
                "$COL_AKM TEXT",
                "$COL_GROUP_CIPHER TEXT",
                "$COL_PAIRWISE_CIPHER TEXT",
                "$COL_RSSI INTEGER",
                "$COL_EAPOL_M1_COUNT INTEGER DEFAULT 0",
                "$COL_EAPOL_M2_COUNT INTEGER DEFAULT 0",
                "$COL_EAPOL_M3_COUNT INTEGER DEFAULT 0",
                "$COL_EAPOL_M4_COUNT INTEGER DEFAULT 0",
                "$COL_BEACON_COUNT INTEGER DEFAULT 0",
                "$COL_ASSOC_REQ_COUNT INTEGER DEFAULT 0",
                "$COL_AUTH_COUNT INTEGER DEFAULT 0",
                "$COL_PROBE_REQ_COUNT INTEGER DEFAULT 0",
                "$COL_HASH_16800 TEXT"
            )
            for (colDef in v6Columns) {
                try {
                    db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $colDef")
                } catch (_: Exception) {
                }
            }
        }
        if (oldVersion < 9) {
            try {
                db?.execSQL("ALTER TABLE $TABLE_HANDSHAKES ADD COLUMN $COL_APS_IN_FILE TEXT")
            } catch (_: Exception) {
            }
        }
    }

    fun saveOrUpdate(item: HandshakeItem) {
        lock.withLock {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_FILE_NAME, item.fileName)
                put(COL_ESSID, item.essid)
                put(COL_BSSID, item.bssid)
                put(COL_FILE_SIZE, item.fileSize)
                put(COL_LAST_MODIFIED, item.lastModified)
                put(COL_HASH_22000, item.hash22000)
                put(COL_HASH_PMKID, item.hashPmkid)
                put(
                    COL_IS_VALID, when (item.isValid) {
                        true -> 1; false -> 0; else -> null as Int?
                    }
                )
                put(COL_CRACKED_PASSWORD, item.crackedPassword)
                put(COL_LATITUDE, item.latitude)
                put(COL_LONGITUDE, item.longitude)
                put(COL_UPLOADED_TO_WPASEC, if (item.uploadedToWpaSec) 1 else 0)
                put(COL_WPASEC_KEY, item.wpasecKey)
                put(COL_WPASEC_CHECKED, if (item.wpasecChecked) 1 else 0)
                put(COL_WPASEC_PASSWORD_FOUND, if (item.wpasecPasswordFound) 1 else 0)
                put(COL_WPASEC_PASSWORD, item.wpasecPassword)
                put(COL_ORIGINAL_FORMAT, item.originalFormat)
                put(COL_HANDSHAKE_COUNT, item.handshakeCount)
                put(COL_EAPOL_COUNT, item.eapolCount)
                put(COL_PMKID_COUNT, item.pmkidCount)
                put(COL_KEYVER, item.keyver)
                put(COL_NONCE_ERROR_CORRECTION, item.nonceErrorCorrection)
                put(COL_ENDIANNESS, item.endianness)
                put(COL_UPLOADED_TO_OHC, if (item.uploadedToOhc) 1 else 0)
                put(COL_REQUEST_ID_OHC, item.requestIdOhc)
                put(COL_OHC_EMAIL, item.ohcEmail)
                put(COL_HASH_DEDUP_MD5, item.hashDedupMd5)
                put(COL_CLIENTS, item.clients)
                put(COL_CHANNEL, item.channel)
                put(COL_BAND, item.band)
                put(COL_AKM, item.akm)
                put(COL_GROUP_CIPHER, item.groupCipher)
                put(COL_PAIRWISE_CIPHER, item.pairwiseCipher)
                put(COL_RSSI, item.rssi)
                put(COL_EAPOL_M1_COUNT, item.eapolM1Count)
                put(COL_EAPOL_M2_COUNT, item.eapolM2Count)
                put(COL_EAPOL_M3_COUNT, item.eapolM3Count)
                put(COL_EAPOL_M4_COUNT, item.eapolM4Count)
                put(COL_BEACON_COUNT, item.beaconCount)
                put(COL_ASSOC_REQ_COUNT, item.assocReqCount)
                put(COL_AUTH_COUNT, item.authCount)
                put(COL_PROBE_REQ_COUNT, item.probeReqCount)
                put(COL_HASH_16800, item.hash16800)
                put(COL_APS_IN_FILE, item.apsInFile)
            }
            db.insertWithOnConflict(TABLE_HANDSHAKES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun getAll(): List<HandshakeItem> {
        return lock.withLock {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT * FROM $TABLE_HANDSHAKES", null)
            val items = mutableListOf<HandshakeItem>()
            while (cursor.moveToNext()) {
                items.add(cursorToItem(cursor))
            }
            cursor.close()
            items
        }
    }

    fun getPointsInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limit: Int = Int.MAX_VALUE
    ): List<HandshakeItem> {
        val limitClause = if (limit != Int.MAX_VALUE) " LIMIT $limit" else ""
        return lock.withLock {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM $TABLE_HANDSHAKES " +
                        "WHERE $COL_LATITUDE IS NOT NULL AND $COL_LONGITUDE IS NOT NULL " +
                        "AND $COL_LATITUDE BETWEEN ? AND ? " +
                        "AND $COL_LONGITUDE BETWEEN ? AND ?$limitClause",
                arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString())
            )
            val items = mutableListOf<HandshakeItem>()
            while (cursor.moveToNext()) {
                items.add(cursorToItem(cursor))
            }
            cursor.close()
            items
        }
    }

    fun getAllSortedByDateDesc(): List<HandshakeItem> {
        return lock.withLock {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM $TABLE_HANDSHAKES ORDER BY $COL_LAST_MODIFIED DESC",
                null
            )
            val items = mutableListOf<HandshakeItem>()
            while (cursor.moveToNext()) {
                items.add(cursorToItem(cursor))
            }
            cursor.close()
            items
        }
    }

    fun getByBssid(bssid: String): List<HandshakeItem> {
        return lock.withLock {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM $TABLE_HANDSHAKES WHERE $COL_BSSID = ?",
                arrayOf(bssid)
            )
            val items = mutableListOf<HandshakeItem>()
            while (cursor.moveToNext()) {
                items.add(cursorToItem(cursor))
            }
            cursor.close()
            items
        }
    }

    fun get(fileName: String): HandshakeItem? {
        return lock.withLock {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM $TABLE_HANDSHAKES WHERE $COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
            val item = if (cursor.moveToFirst()) cursorToItem(cursor) else null
            cursor.close()
            item
        }
    }

    fun delete(fileName: String) {
        lock.withLock {
            writableDatabase.delete(TABLE_HANDSHAKES, "$COL_FILE_NAME = ?", arrayOf(fileName))
        }
    }

    fun updateHash22000(fileName: String, hash: String?) {
        lock.withLock {
            val values = ContentValues().apply { put(COL_HASH_22000, hash) }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun updateHashPmkid(fileName: String, hash: String?) {
        lock.withLock {
            val values = ContentValues().apply { put(COL_HASH_PMKID, hash) }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun updateValid(fileName: String, isValid: Boolean?) {
        lock.withLock {
            val values = ContentValues().apply {
                put(
                    COL_IS_VALID, when (isValid) {
                        true -> 1; false -> 0; else -> null as Int?
                    }
                )
            }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun updateEssid(fileName: String, essid: String?) {
        lock.withLock {
            val values = ContentValues().apply { put(COL_ESSID, essid) }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun updateBssid(fileName: String, bssid: String?) {
        lock.withLock {
            val values = ContentValues().apply { put(COL_BSSID, bssid) }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun updateCounts(fileName: String, eapolCount: Int, pmkidCount: Int, handshakeCount: Int) {
        lock.withLock {
            val values = ContentValues().apply {
                put(COL_EAPOL_COUNT, eapolCount)
                put(COL_PMKID_COUNT, pmkidCount)
                put(COL_HANDSHAKE_COUNT, handshakeCount)
            }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun updateCracked(fileName: String, password: String?) {
        lock.withLock {
            val values = ContentValues().apply { put(COL_CRACKED_PASSWORD, password) }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun updateKeyver(fileName: String, keyver: Int?) {
        lock.withLock {
            val values = ContentValues().apply { put(COL_KEYVER, keyver) }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun updateOriginalFormat(fileName: String, format: String?) {
        lock.withLock {
            val values = ContentValues().apply { put(COL_ORIGINAL_FORMAT, format) }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun updateApsInFile(fileName: String, apsInFile: String?) {
        lock.withLock {
            val values = ContentValues().apply { put(COL_APS_IN_FILE, apsInFile) }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun updateLocation(fileName: String, latitude: Double?, longitude: Double?) {
        lock.withLock {
            val values = ContentValues().apply {
                put(COL_LATITUDE, latitude)
                put(COL_LONGITUDE, longitude)
            }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun updateMetadata(
        fileName: String,
        clients: String? = null,
        channel: Int? = null,
        band: String? = null,
        akm: String? = null,
        groupCipher: String? = null,
        pairwiseCipher: String? = null,
        rssi: Int? = null,
        eapolM1Count: Int = 0,
        eapolM2Count: Int = 0,
        eapolM3Count: Int = 0,
        eapolM4Count: Int = 0,
        beaconCount: Int = 0,
        assocReqCount: Int = 0,
        authCount: Int = 0,
        probeReqCount: Int = 0,
        hash16800: String? = null
    ) {
        lock.withLock {
            val values = ContentValues().apply {
                put(COL_CLIENTS, clients)
                put(COL_CHANNEL, channel)
                put(COL_BAND, band)
                put(COL_AKM, akm)
                put(COL_GROUP_CIPHER, groupCipher)
                put(COL_PAIRWISE_CIPHER, pairwiseCipher)
                put(COL_RSSI, rssi)
                put(COL_EAPOL_M1_COUNT, eapolM1Count)
                put(COL_EAPOL_M2_COUNT, eapolM2Count)
                put(COL_EAPOL_M3_COUNT, eapolM3Count)
                put(COL_EAPOL_M4_COUNT, eapolM4Count)
                put(COL_BEACON_COUNT, beaconCount)
                put(COL_ASSOC_REQ_COUNT, assocReqCount)
                put(COL_AUTH_COUNT, authCount)
                put(COL_PROBE_REQ_COUNT, probeReqCount)
                put(COL_HASH_16800, hash16800)
            }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun updateOhcUploadStatus(
        fileName: String,
        uploaded: Boolean,
        requestId: String?,
        email: String? = null
    ) {
        lock.withLock {
            val values = ContentValues().apply {
                put(COL_UPLOADED_TO_OHC, if (uploaded) 1 else 0)
                put(COL_REQUEST_ID_OHC, requestId)
                if (email != null) put(COL_OHC_EMAIL, email)
            }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun updateWpaSecUploadStatus(fileName: String, uploaded: Boolean, key: String?) {
        lock.withLock {
            val values = ContentValues().apply {
                put(COL_UPLOADED_TO_WPASEC, if (uploaded) 1 else 0)
                put(COL_WPASEC_KEY, key)
            }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun updateWpaSecCheckResult(
        fileName: String,
        checked: Boolean,
        found: Boolean,
        password: String?
    ) {
        lock.withLock {
            val values = ContentValues().apply {
                put(COL_WPASEC_CHECKED, if (checked) 1 else 0)
                put(COL_WPASEC_PASSWORD_FOUND, if (found) 1 else 0)
                put(COL_WPASEC_PASSWORD, password)
            }
            writableDatabase.update(
                TABLE_HANDSHAKES,
                values,
                "$COL_FILE_NAME = ?",
                arrayOf(fileName)
            )
        }
    }

    fun getNotUploadedToWpaSec(): List<HandshakeItem> {
        return lock.withLock {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM $TABLE_HANDSHAKES WHERE $COL_UPLOADED_TO_WPASEC = 0 AND $COL_HASH_22000 IS NOT NULL",
                null
            )
            val items = mutableListOf<HandshakeItem>()
            while (cursor.moveToNext()) {
                items.add(cursorToItem(cursor))
            }
            cursor.close()
            items
        }
    }

    fun clearAll() {
        lock.withLock {
            writableDatabase.delete(TABLE_HANDSHAKES, null, null)
        }
    }

    private fun cursorToItem(cursor: android.database.Cursor): HandshakeItem {
        val fileName = cursor.getString(cursor.getColumnIndexOrThrow(COL_FILE_NAME))
        return HandshakeItem(
            filePath = fileName,
            fileName = fileName,
            essid = cursor.getString(cursor.getColumnIndexOrThrow(COL_ESSID)),
            bssid = cursor.getString(cursor.getColumnIndexOrThrow(COL_BSSID)),
            fileSize = cursor.getLong(cursor.getColumnIndexOrThrow(COL_FILE_SIZE)),
            lastModified = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_MODIFIED)),
            hash22000 = cursor.getString(cursor.getColumnIndexOrThrow(COL_HASH_22000)),
            hashPmkid = cursor.getString(cursor.getColumnIndexOrThrow(COL_HASH_PMKID)),
            isValid = let {
                val idx = cursor.getColumnIndexOrThrow(COL_IS_VALID)
                if (cursor.isNull(idx)) null else cursor.getInt(idx) == 1
            },
            crackedPassword = cursor.getString(cursor.getColumnIndexOrThrow(COL_CRACKED_PASSWORD)),
            latitude = getOptionalDouble(cursor, COL_LATITUDE),
            longitude = getOptionalDouble(cursor, COL_LONGITUDE),
            uploadedToWpaSec = getOptionalInt(cursor, COL_UPLOADED_TO_WPASEC) == 1,
            wpasecKey = cursor.getString(cursor.getColumnIndexOrThrow(COL_WPASEC_KEY)),
            wpasecChecked = getOptionalInt(cursor, COL_WPASEC_CHECKED) == 1,
            wpasecPasswordFound = getOptionalInt(cursor, COL_WPASEC_PASSWORD_FOUND) == 1,
            wpasecPassword = cursor.getString(cursor.getColumnIndexOrThrow(COL_WPASEC_PASSWORD)),
            originalFormat = cursor.getString(cursor.getColumnIndexOrThrow(COL_ORIGINAL_FORMAT)),
            handshakeCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_HANDSHAKE_COUNT)),
            eapolCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_EAPOL_COUNT)),
            pmkidCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_PMKID_COUNT)),
            keyver = getOptionalInt(cursor, COL_KEYVER),
            nonceErrorCorrection = getOptionalInt(cursor, COL_NONCE_ERROR_CORRECTION),
            endianness = cursor.getString(cursor.getColumnIndexOrThrow(COL_ENDIANNESS)),
            uploadedToOhc = getOptionalInt(cursor, COL_UPLOADED_TO_OHC) == 1,
            requestIdOhc = cursor.getString(cursor.getColumnIndexOrThrow(COL_REQUEST_ID_OHC)),
            ohcEmail = try {
                cursor.getString(cursor.getColumnIndexOrThrow(COL_OHC_EMAIL))
            } catch (_: Exception) {
                null
            },
            hashDedupMd5 = cursor.getString(cursor.getColumnIndexOrThrow(COL_HASH_DEDUP_MD5)),
            clients = try {
                cursor.getString(cursor.getColumnIndexOrThrow(COL_CLIENTS))
            } catch (_: Exception) {
                null
            },
            channel = try {
                getOptionalInt(cursor, COL_CHANNEL)
            } catch (_: Exception) {
                null
            },
            band = try {
                cursor.getString(cursor.getColumnIndexOrThrow(COL_BAND))
            } catch (_: Exception) {
                null
            },
            akm = try {
                cursor.getString(cursor.getColumnIndexOrThrow(COL_AKM))
            } catch (_: Exception) {
                null
            },
            groupCipher = try {
                cursor.getString(cursor.getColumnIndexOrThrow(COL_GROUP_CIPHER))
            } catch (_: Exception) {
                null
            },
            pairwiseCipher = try {
                cursor.getString(cursor.getColumnIndexOrThrow(COL_PAIRWISE_CIPHER))
            } catch (_: Exception) {
                null
            },
            rssi = try {
                getOptionalInt(cursor, COL_RSSI)
            } catch (_: Exception) {
                null
            },
            eapolM1Count = try {
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_EAPOL_M1_COUNT))
            } catch (_: Exception) {
                0
            },
            eapolM2Count = try {
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_EAPOL_M2_COUNT))
            } catch (_: Exception) {
                0
            },
            eapolM3Count = try {
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_EAPOL_M3_COUNT))
            } catch (_: Exception) {
                0
            },
            eapolM4Count = try {
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_EAPOL_M4_COUNT))
            } catch (_: Exception) {
                0
            },
            beaconCount = try {
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_BEACON_COUNT))
            } catch (_: Exception) {
                0
            },
            assocReqCount = try {
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_ASSOC_REQ_COUNT))
            } catch (_: Exception) {
                0
            },
            authCount = try {
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_AUTH_COUNT))
            } catch (_: Exception) {
                0
            },
            probeReqCount = try {
                cursor.getInt(cursor.getColumnIndexOrThrow(COL_PROBE_REQ_COUNT))
            } catch (_: Exception) {
                0
            },
            hash16800 = try {
                cursor.getString(cursor.getColumnIndexOrThrow(COL_HASH_16800))
            } catch (_: Exception) {
                null
            },
            apsInFile = try {
                cursor.getString(cursor.getColumnIndexOrThrow(COL_APS_IN_FILE))
            } catch (_: Exception) {
                null
            }
        )
    }
}
