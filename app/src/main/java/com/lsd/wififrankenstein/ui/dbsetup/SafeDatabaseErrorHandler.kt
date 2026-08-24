package com.lsd.wififrankenstein.ui.dbsetup

import android.annotation.SuppressLint
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.lsd.wififrankenstein.util.Log
import java.io.File

class SafeDatabaseErrorHandler(private val appContext: Context? = null) : DatabaseErrorHandler {
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
                    if (!isAppManagedFile(file)) {
                        Log.e(
                            TAG,
                            "Refusing to rename/delete user file outside app storage: $path"
                        )
                        return
                    }
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

    private fun isAppManagedFile(file: File): Boolean {
        val context = appContext ?: return false
        return try {
            val canonical = file.canonicalPath
            listOf(context.cacheDir, context.filesDir).any { root ->
                canonical.startsWith(root.canonicalPath + File.separator)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve app-managed paths", e)
            false
        }
    }
}
