package com.lsd.wififrankenstein.ui.uploadrouterscan

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class UploadRouterScanViewModel(application: Application) : AndroidViewModel(application) {

    private val _servers = MutableLiveData<List<DbItem>>()
    val servers: LiveData<List<DbItem>> = _servers

    private val _uploadProgress = MutableLiveData<Int>()
    val uploadProgress: LiveData<Int> = _uploadProgress

    private val _uploadResult = MutableLiveData<UploadResult>()
    val uploadResult: LiveData<UploadResult> = _uploadResult

    private val _isUploading = MutableLiveData<Boolean>()
    val isUploading: LiveData<Boolean> = _isUploading

    private val _selectedFile = MutableLiveData<SelectedFile?>()
    val selectedFile: LiveData<SelectedFile?> = _selectedFile

    private val dbSetupViewModel = DbSetupViewModel(application)

    data class SelectedFile(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mimeType: String?
    )

    data class UploadResult(
        val success: Boolean,
        val message: String,
        val taskId: String? = null
    )

    init {
        loadServers()
    }

    private fun loadServers() {
        viewModelScope.launch {
            dbSetupViewModel.loadDbList()
            val wifiApiServers = dbSetupViewModel.getWifiApiDatabases()
            _servers.value = wifiApiServers
        }
    }

    fun setSelectedFile(uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cursor = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex("_display_name")
                        val sizeIndex = it.getColumnIndex("_size")

                        val name = if (nameIndex >= 0) {
                            it.getString(nameIndex)
                        } else {
                            uri.lastPathSegment ?: "unknown_file"
                        }

                        val size = if (sizeIndex >= 0) {
                            it.getLong(sizeIndex)
                        } else {
                            0L
                        }

                        val mimeType = contentResolver.getType(uri)

                        val file = SelectedFile(uri, name, size, mimeType)
                        withContext(Dispatchers.Main) {
                            _selectedFile.value = file
                        }
                    }
                }
            } catch (e: Exception) {
                val name = uri.lastPathSegment ?: "unknown_file"
                val file = SelectedFile(uri, name, 0L, null)
                withContext(Dispatchers.Main) {
                    _selectedFile.value = file
                }
            }
        }
    }

    fun uploadFile(
        server: DbItem,
        comment: String,
        checkExisting: Boolean,
        noWait: Boolean
    ) {
        val file = _selectedFile.value ?: return

        viewModelScope.launch {
            _isUploading.value = true
            _uploadProgress.value = 0

            try {
                val result = performUpload(server, file, comment, checkExisting, noWait)
                _uploadResult.value = result
            } catch (e: Exception) {
                _uploadResult.value = UploadResult(
                    success = false,
                    message = e.message ?: "Upload failed"
                )
            } finally {
                _isUploading.value = false
            }
        }
    }

    private suspend fun performUpload(
        server: DbItem,
        file: SelectedFile,
        comment: String,
        checkExisting: Boolean,
        noWait: Boolean
    ): UploadResult = withContext(Dispatchers.IO) {

        val contentResolver = getApplication<Application>().contentResolver
        val inputStream = contentResolver.openInputStream(file.uri)
            ?: throw Exception("Cannot open file")

        val fileContent = inputStream.bufferedReader().use { it.readText() }

        val mimeType = when {
            file.name.endsWith(".csv", true) -> "text/csv"
            file.name.endsWith(".txt", true) -> "text/plain"
            else -> "text/plain"
        }

        val serverUrl = server.path.removeSuffix("/")
        val uploadUrl = "$serverUrl/3wifi.php?a=upload"

        val url = buildString {
            append(uploadUrl)
            if (comment.isNotEmpty()) {
                append("&comment=${java.net.URLEncoder.encode(comment, "UTF-8")}")
            }
            if (checkExisting) {
                append("&checkexist=1")
            }
            if (noWait) {
                append("&nowait=1")
            }
            append("&done=1")

            server.apiWriteKey?.let { key ->
                append("&key=${java.net.URLEncoder.encode(key, "UTF-8")}")
            }
        }

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", mimeType)
        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 60000

        val outputStream = connection.outputStream
        val bytes = fileContent.toByteArray()
        val totalBytes = bytes.size
        var sentBytes = 0
        val chunkSize = 8192

        try {
            while (sentBytes < totalBytes) {
                val remainingBytes = totalBytes - sentBytes
                val currentChunkSize = minOf(chunkSize, remainingBytes)

                outputStream.write(bytes, sentBytes, currentChunkSize)
                sentBytes += currentChunkSize

                val progress = (sentBytes * 100 / totalBytes)
                withContext(Dispatchers.Main) {
                    _uploadProgress.value = progress
                }
            }
            outputStream.flush()
            outputStream.close()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)

                if (jsonResponse.getBoolean("result")) {
                    val uploadInfo = jsonResponse.getJSONObject("upload")
                    if (uploadInfo.getBoolean("state")) {
                        val taskId = uploadInfo.optString("tid", null)
                        UploadResult(
                            success = true,
                            message = "Upload successful",
                            taskId = taskId
                        )
                    } else {
                        val errors = uploadInfo.optJSONArray("error")
                        val errorMessage = if (errors != null && errors.length() > 0) {
                            "Upload failed with error code: ${errors.getInt(0)}"
                        } else {
                            "Upload failed"
                        }
                        UploadResult(success = false, message = errorMessage)
                    }
                } else {
                    val error = jsonResponse.optString("error", "Unknown error")
                    UploadResult(success = false, message = "Server error: $error")
                }
            } else {
                UploadResult(success = false, message = "HTTP error: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun clearSelectedFile() {
        _selectedFile.value = null
    }

    fun clearUploadResult() {
        _uploadResult.value = null
    }
}