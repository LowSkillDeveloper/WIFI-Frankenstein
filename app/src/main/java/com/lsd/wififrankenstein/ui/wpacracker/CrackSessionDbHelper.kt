package com.lsd.wififrankenstein.ui.wpacracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock

class CrackSessionDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "CrackSessionDb"
        const val DATABASE_NAME = "crack_sessions.db"
        const val DATABASE_VERSION = 1

        const val TABLE_NAME = "crack_sessions"
        const val COLUMN_ID = "id"
        const val COLUMN_SESSION_KEY = "session_key"
        const val COLUMN_WORDLIST_URI = "wordlist_uri"
        const val COLUMN_HANDSHAKE_LINE = "handshake_line"
        const val COLUMN_OFFSET = "offset"
        const val COLUMN_TOTAL_LINES = "total_lines"
        const val COLUMN_ENGINE_NAME = "engine_name"
        const val COLUMN_TIMESTAMP = "timestamp"
    }

    private val lock = ReentrantLock()

    init {
        lock.withLock { writableDatabase }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_NAME (" +
                    "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "$COLUMN_SESSION_KEY TEXT UNIQUE NOT NULL," +
                    "$COLUMN_WORDLIST_URI TEXT NOT NULL," +
                    "$COLUMN_HANDSHAKE_LINE TEXT NOT NULL," +
                    "$COLUMN_OFFSET INTEGER DEFAULT 0," +
                    "$COLUMN_TOTAL_LINES INTEGER DEFAULT 0," +
                    "$COLUMN_ENGINE_NAME TEXT," +
                    "$COLUMN_TIMESTAMP INTEGER" +
                    ")"
        )
        db.execSQL("CREATE INDEX idx_sessions_key ON $TABLE_NAME ($COLUMN_SESSION_KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun saveSession(data: CrackSessionData) {
        lock.withLock {
            val key = sessionKey(data.handshakeLine, data.wordlistUri)
            val values = ContentValues().apply {
                put(COLUMN_SESSION_KEY, key)
                put(COLUMN_WORDLIST_URI, data.wordlistUri)
                put(COLUMN_HANDSHAKE_LINE, data.handshakeLine)
                put(COLUMN_OFFSET, data.offset)
                put(COLUMN_TOTAL_LINES, data.totalLines)
                put(COLUMN_ENGINE_NAME, data.engineName)
                put(COLUMN_TIMESTAMP, data.timestamp)
            }
            writableDatabase.insertWithOnConflict(
                TABLE_NAME, null, values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    fun getSession(handshakeLine: String, wordlistUri: String): CrackSessionData? {
        return lock.withLock {
            val key = sessionKey(handshakeLine, wordlistUri)
            readableDatabase.rawQuery(
                "SELECT * FROM $TABLE_NAME WHERE $COLUMN_SESSION_KEY = ? LIMIT 1",
                arrayOf(key)
            ).use { cursor ->
                if (cursor.moveToFirst()) parseCursor(cursor)
                else null
            }
        }
    }

    fun hasSession(handshakeLine: String, wordlistUri: String): Boolean {
        return lock.withLock {
            val key = sessionKey(handshakeLine, wordlistUri)
            readableDatabase.rawQuery(
                "SELECT 1 FROM $TABLE_NAME WHERE $COLUMN_SESSION_KEY = ? LIMIT 1",
                arrayOf(key)
            ).use { cursor -> cursor.moveToFirst() }
        }
    }

    fun getLatestSession(): CrackSessionData? {
        return lock.withLock {
            readableDatabase.rawQuery(
                "SELECT * FROM $TABLE_NAME ORDER BY $COLUMN_TIMESTAMP DESC LIMIT 1",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) parseCursor(cursor)
                else null
            }
        }
    }

    fun getAllSessions(): List<CrackSessionData> {
        return lock.withLock {
            readableDatabase.rawQuery(
                "SELECT * FROM $TABLE_NAME ORDER BY $COLUMN_TIMESTAMP DESC",
                null
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(parseCursor(cursor))
                }
            }
        }
    }

    fun removeSession(handshakeLine: String, wordlistUri: String) {
        lock.withLock {
            val key = sessionKey(handshakeLine, wordlistUri)
            writableDatabase.delete(TABLE_NAME, "$COLUMN_SESSION_KEY = ?", arrayOf(key))
        }
    }

    fun clearAll() {
        lock.withLock {
            writableDatabase.delete(TABLE_NAME, null, null)
        }
    }

    private fun parseCursor(cursor: android.database.Cursor): CrackSessionData {
        val keyIndex = cursor.getColumnIndexOrThrow(COLUMN_SESSION_KEY)
        val wordlistIndex = cursor.getColumnIndexOrThrow(COLUMN_WORDLIST_URI)
        val handshakeIndex = cursor.getColumnIndexOrThrow(COLUMN_HANDSHAKE_LINE)
        val offsetIndex = cursor.getColumnIndexOrThrow(COLUMN_OFFSET)
        val totalIndex = cursor.getColumnIndexOrThrow(COLUMN_TOTAL_LINES)
        val engineIndex = cursor.getColumnIndexOrThrow(COLUMN_ENGINE_NAME)
        val timestampIndex = cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP)

        return CrackSessionData(
            wordlistUri = cursor.getString(wordlistIndex),
            handshakeLine = cursor.getString(handshakeIndex),
            offset = cursor.getLong(offsetIndex),
            totalLines = cursor.getLong(totalIndex),
            engineName = cursor.getString(engineIndex) ?: "NATIVE",
            timestamp = cursor.getLong(timestampIndex)
        )
    }

    private fun sessionKey(handshakeLine: String, wordlistUri: String): String {
        val raw = "$handshakeLine:$wordlistUri"
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private inline fun <reified T> ReentrantLock.withLock(block: () -> T): T {
        lock()
        return try {
            block()
        } finally {
            unlock()
        }
    }
}
