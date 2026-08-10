package com.lsd.wififrankenstein.ui.wpacracker

import android.content.Context

data class CrackSessionData(
    val wordlistUri: String,
    val handshakeLine: String,
    val offset: Long,
    val totalLines: Long,
    val engineName: String,
    val timestamp: Long
)

class CrackSessionManager(context: Context) {

    private val dbHelper = CrackSessionDbHelper(context.applicationContext)

    fun saveSession(data: CrackSessionData) {
        dbHelper.saveSession(data)
    }

    fun getSession(handshakeLine: String, wordlistUri: String): CrackSessionData? {
        return dbHelper.getSession(handshakeLine, wordlistUri)
    }

    fun hasSession(handshakeLine: String, wordlistUri: String): Boolean {
        return dbHelper.hasSession(handshakeLine, wordlistUri)
    }

    fun getLatestSession(): CrackSessionData? {
        return dbHelper.getLatestSession()
    }

    fun getAllSessions(): List<CrackSessionData> {
        return dbHelper.getAllSessions()
    }

    fun removeSession(handshakeLine: String, wordlistUri: String) {
        dbHelper.removeSession(handshakeLine, wordlistUri)
    }

    fun clearAll() {
        dbHelper.clearAll()
    }
}
