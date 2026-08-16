package com.lsd.wififrankenstein.ui.handshakeconverter

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.ChrootCapabilities
import com.lsd.wififrankenstein.util.HandshakeFormat
import com.lsd.wififrankenstein.util.HandshakeHash
import com.lsd.wififrankenstein.util.HandshakeParser
import com.lsd.wififrankenstein.util.HandshakeType
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class HandshakeConverterViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "HandshakeConverterVM"
    private val engine = HandshakeConverterEngine(application)

    private val _files = MutableLiveData<List<ConvertFileItem>>(emptyList())
    val files: LiveData<List<ConvertFileItem>> = _files

    private val _isConverting = MutableLiveData(false)
    val isConverting: LiveData<Boolean> = _isConverting

    private val _results = MutableLiveData<List<ConvertResultItem>?>()
    val results: LiveData<List<ConvertResultItem>?> = _results

    fun loadFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            if (_files.value.orEmpty().isEmpty()) {
                withContext(Dispatchers.IO) { clearTempDir() }
            }
            val loaded = uris.mapNotNull { loadFile(it) }
            if (loaded.isNotEmpty()) {
                _files.value = _files.value.orEmpty() + loaded
            }
        }
    }

    private fun clearTempDir() {
        val dir = File(getApplication<Application>().cacheDir, "converter_temp")
        dir.listFiles()?.forEach { it.delete() }
    }

    private suspend fun loadFile(uri: Uri): ConvertFileItem? = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val tempDir = File(app.cacheDir, "converter_temp")
        tempDir.mkdirs()
        try {
            val fileName = getFileName(uri) ?: "file_${System.currentTimeMillis()}"
            val tempFile = File(tempDir, fileName)
            app.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { input.copyTo(it) }
            } ?: return@withContext null

            val id = UUID.randomUUID().toString()
            val format = HandshakeHash.detectFileFormat(tempFile)
            if (format == HandshakeFormat.UNKNOWN) {
                return@withContext ConvertFileItem(
                    id, tempFile.absolutePath, fileName, format,
                    emptyList(), false, false, emptyList(), TargetFormat.HASH_22000,
                    error = getApplication<Application>().getString(R.string.hc_unsupported_format)
                )
            }

            val hashes = HandshakeParser.parseFile(tempFile)
            if (hashes.isEmpty()) {
                return@withContext ConvertFileItem(
                    id, tempFile.absolutePath, fileName, format,
                    emptyList(), false, false, emptyList(), TargetFormat.HASH_22000,
                    error = getApplication<Application>().getString(R.string.hc_no_handshakes)
                )
            }

            val lines = hashes.map { it.to22000Line() }.distinct()
            val hasEapol = hashes.any { it.type == HandshakeType.EAPOL }
            val hasPmkid = hashes.any {
                it.type == HandshakeType.PMKID || it.type == HandshakeType.PMKID_EAPOL
            }
            val chrootAvailable = ChrootCapabilities.hasChrootTools(app)
            val targets = buildList {
                if (hasEapol || hasPmkid) add(TargetFormat.HASH_22000)
                if (hasEapol) {
                    add(TargetFormat.HCCAPX)
                    add(TargetFormat.HCCAP)
                }
                if (hasPmkid) {
                    add(TargetFormat.PMKID)
                    add(TargetFormat.HASH_16800)
                }
                if (hasEapol && chrootAvailable) add(TargetFormat.CAP)
            }

            ConvertFileItem(
                id, tempFile.absolutePath, fileName, format, lines,
                hasEapol, hasPmkid, targets,
                targets.firstOrNull() ?: TargetFormat.HASH_22000
            )
        } catch (e: Exception) {
            Log.w(tag, "loadFile failed", e)
            null
        }
    }

    fun setTargetFormat(id: String, target: TargetFormat) {
        _files.value = _files.value.orEmpty().map { item ->
            if (item.id == id) item.copy(selectedTarget = target) else item
        }
    }

    fun removeFile(id: String) {
        val current = _files.value.orEmpty()
        current.find { it.id == id }?.let { item ->
            runCatching { File(item.filePath).delete() }
        }
        _files.value = current.filterNot { it.id == id }
    }

    fun convertAll() {
        val items = _files.value.orEmpty().filter { it.isSupported }
        if (items.isEmpty() || _isConverting.value == true) return
        _isConverting.value = true
        viewModelScope.launch {
            val resultsList = items.map { engine.convert(it, it.selectedTarget) }
            _results.value = resultsList
            _isConverting.value = false
        }
    }

    fun clearResults() {
        _results.value = null
    }

    fun deleteOutput(path: String) {
        runCatching { File(path).delete() }
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            val resolver = getApplication<Application>().contentResolver
            val cursor = resolver.query(uri, null, null, null, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && it.moveToFirst()) it.getString(nameIndex) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
