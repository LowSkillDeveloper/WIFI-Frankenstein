package com.lsd.wififrankenstein

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MainViewModel(application: Application) : AndroidViewModel(application) {

    fun checkAndCopyFiles(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val filesToCheck = listOf("vendor.db", "vendor_data.txt", "wps_pin.db", "wpspin.html")

            filesToCheck.forEach { fileName ->
                if (!File(context.filesDir, fileName).exists()) {
                    try {
                        context.assets.open(fileName).use { input ->
                            context.openFileOutput(fileName, Context.MODE_PRIVATE).use { output ->
                                input.copyTo(output)
                            }
                        }
                        createVersionFile(context, fileName, "1.0")
                    } catch (e: IOException) {
                        Log.e("MainViewModel", "Error copying file: $fileName", e)
                    }
                }
            }
        }
    }

    private fun createVersionFile(context: Context, fileName: String, version: String) {
        val versionFileName = "${fileName.substringBeforeLast(".")}_version.json"
        try {
            val versionJson = JSONObject().put("version", version)
            context.openFileOutput(versionFileName, Context.MODE_PRIVATE).use { output ->
                output.write(versionJson.toString().toByteArray())
            }
        } catch (e: IOException) {
            Log.e("MainViewModel", "Error creating version file: $versionFileName", e)
        }
    }
}