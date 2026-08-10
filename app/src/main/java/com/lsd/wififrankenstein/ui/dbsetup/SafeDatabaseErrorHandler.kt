package com.lsd.wififrankenstein.ui.dbsetup

import android.annotation.SuppressLint
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.lsd.wififrankenstein.util.Log
import java.io.File

class SafeDatabaseErrorHandler : DatabaseErrorHandler {
    companion object {
        private const val TAG = "SafeDatabaseErrorHandler"
        private val handlingCorruption = mutableSetOf<String>()
    }

    @SuppressLint("LongLogTag")
    override fun onCorruption(dbObj: SQLiteDatabase) {
        val path = dbObj.path
        Log.e(TAG, "Database corruption detected: $path")

        synchronized(handlingCorruption) {
            if (handlingCorruption.contains(path)) {
                Log.e(TAG, "Already handling corruption for $path, skipping")
                return
            }
            handlingCorruption.add(path)
        }

        try {
            try {
                dbObj.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing corrupted database", e)
            }

            Thread.sleep(100)

            try {
                val file = File(path)
                if (file.exists()) {
                    val backupPath = "$path.corrupted.${System.currentTimeMillis()}"
                    val success = file.renameTo(File(backupPath))
                    if (success) {
                        Log.d(TAG, "Corrupted database moved to: $backupPath")
                    } else {
                        Log.e(TAG, "Failed to rename corrupted database")
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling corrupted database file", e)
            }
        } finally {
            synchronized(handlingCorruption) {
                handlingCorruption.remove(path)
            }
        }
    }
}