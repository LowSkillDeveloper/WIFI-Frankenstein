package com.lsd.wififrankenstein.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream

object VendorChecker {

    private fun getFileFromInternalStorageOrAssets(context: Context, fileName: String): File {
        val file = File(context.filesDir, fileName)
        if (!file.exists()) {
            context.assets.open(fileName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            createVersionFile(context, fileName, "1.0")
        }
        return file
    }

    private fun createVersionFile(context: Context, fileName: String, version: String) {
        val versionFileName = "${fileName.substringBeforeLast(".")}_version.json"
        try {
            val versionJson = JSONObject().put("version", version)
            context.openFileOutput(versionFileName, Context.MODE_PRIVATE).use { output ->
                output.write(versionJson.toString().toByteArray())
            }
            Log.d("VendorChecker", "Created version file for $fileName: version $version")
        } catch (e: Exception) {
            Log.e("VendorChecker", "Error creating version file: $versionFileName", e)
        }
    }

    private fun getFileVersion(context: Context, fileName: String): String {
        val versionFileName = "${fileName.substringBeforeLast(".")}_version.json"
        return try {
            val file = File(context.filesDir, versionFileName)
            if (file.exists()) {
                val json = JSONObject(file.readText())
                json.getString("version")
            } else {
                "1.0"
            }
        } catch (e: Exception) {
            Log.e("VendorChecker", "Error reading version file: $versionFileName", e)
            "1.0"
        }
    }

    suspend fun checkVendorLocalSource1(context: Context, bssid: String): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val dbFile = getFileFromInternalStorageOrAssets(context, "vendor.db")
                val version = getFileVersion(context, "vendor.db")
                Log.d("VendorChecker", "Using vendor.db version: $version")

                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { localDB ->
                    localDB.rawQuery("SELECT vendor FROM oui WHERE mac = ?", arrayOf(bssid)).use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getString(cursor.getColumnIndexOrThrow("vendor"))
                        } else {
                            "unknown vendor"
                        }
                    }
                }
            }.getOrElse { e ->
                e.printStackTrace()
                context.getString(R.string.error_accessing_database)
            }
        }

    suspend fun checkVendorLocalSource2(context: Context, bssid: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val file = getFileFromInternalStorageOrAssets(context, "vendor_data.txt")
                val version = getFileVersion(context, "vendor_data.txt")
                Log.d("VendorChecker", "Using vendor_data.txt version: $version")

                file.bufferedReader().useLines { lines ->
                    lines.firstOrNull { it.contains(bssid, ignoreCase = true) }?.split("|")
                        ?.firstOrNull() ?: "unknown vendor"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                context.getString(R.string.error_accessing_text_file)
            }
        }
    }

    suspend fun checkVendorOnlineSource1(bssid: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://wpsfinder.com/ethernet-wifi-brand-lookup/MAC:$bssid"
                val doc = Jsoup.connect(url).get()
                doc.select("h4.text-muted > center").first()?.text() ?: "unknown vendor"
            } catch (e: Exception) {
                e.printStackTrace()
                "unknown vendor"
            }
        }
    }

    suspend fun checkVendorOnlineSource2(context: Context, bssid: String, dbList: List<DbItem>): String {
        return withContext(Dispatchers.IO) {
            Log.d("VendorChecker", "Starting vendor check for BSSID: $bssid")
            for (db in dbList.filter { it.dbType == DbType.WIFI_API }) {
                val apiKey = db.apiKey ?: "000000000000"
                val url = "${db.path}/api/apidev?key=$apiKey&bssid=$bssid"
                val alternativeUrl = "${db.path}/3wifi.php?a=apidev?key=$apiKey&bssid=$bssid"
                Log.d("VendorChecker", "Constructed URL: $url")

                try {
                    Log.d("VendorChecker", "Sending GET request to URL: $url")
                    val response = Jsoup.connect(url).ignoreContentType(true).execute().body()
                    Log.d("VendorChecker", "Received response: $response")

                    val json = JSONObject(response)
                    Log.d("VendorChecker", "Parsed JSON response: $json")

                    if (json.getBoolean("result")) {
                        val data = json.getJSONObject("data")
                        val bssidData = data.optJSONArray(bssid) ?: continue
                        Log.d("VendorChecker", "BSSID data array length: ${bssidData.length()}")

                        if (bssidData.length() > 0) {
                            val vendor = bssidData.getJSONObject(0).getString("name")
                            Log.d("VendorChecker", "Vendor found: $vendor")

                            if (vendor != "unknown vendor") {
                                return@withContext vendor
                            }
                        } else {
                            Log.d("VendorChecker", "No data found for BSSID: $bssid")
                        }
                    } else {
                        Log.d("VendorChecker", "Result false for BSSID: $bssid")
                    }
                } catch (e: Exception) {
                    Log.e(
                        "VendorChecker",
                        "Error checking vendor for BSSID $bssid with API ${db.path}, trying alternative URL",
                        e
                    )

                    if (isAlternativeUrlEnabled(context)) {
                        try {
                            Log.d("VendorChecker", "Sending GET request to alternative URL: $alternativeUrl")
                            val altResponse = Jsoup.connect(alternativeUrl).ignoreContentType(true).execute().body()
                            Log.d("VendorChecker", "Received alternative response: $altResponse")

                            val altJson = JSONObject(altResponse)
                            Log.d("VendorChecker", "Parsed alternative JSON response: $altJson")

                            if (altJson.getBoolean("result")) {
                                val data = altJson.getJSONObject("data")
                                val bssidData = data.optJSONArray(bssid) ?: continue
                                Log.d("VendorChecker", "BSSID data array length: ${bssidData.length()}")

                                if (bssidData.length() > 0) {
                                    val vendor = bssidData.getJSONObject(0).getString("name")
                                    Log.d("VendorChecker", "Vendor found: $vendor")

                                    if (vendor != "unknown vendor") {
                                        return@withContext vendor
                                    }
                                } else {
                                    Log.d("VendorChecker", "No data found for BSSID: $bssid")
                                }
                            } else {
                                Log.d("VendorChecker", "Result false for BSSID: $bssid")
                            }
                        } catch (altException: Exception) {
                            Log.e("VendorChecker", "Error checking vendor for BSSID $bssid with alternative URL", altException)
                        }
                    }
                }
            }
            Log.d("VendorChecker", "Vendor not found, returning 'unknown vendor'")
            return@withContext "unknown vendor"
        }
    }

    private fun isAlternativeUrlEnabled(context: Context): Boolean {
        val sharedPreferences = context.getSharedPreferences("API3WiFiSettings", Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean("tryAlternativeUrl", true)
    }
}