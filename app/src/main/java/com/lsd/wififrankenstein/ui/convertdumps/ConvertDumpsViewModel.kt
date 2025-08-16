package com.lsd.wififrankenstein.ui.convertdumps

import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val type: DumpFileType
)

enum class DumpFileType {
    ROUTERSCAN_TXT,
    WIFI_3_SQL,
    P3WIFI_SQL,
    UNKNOWN
}

enum class ConversionType {
    ROUTERSCAN_TXT,
    WIFI_3_SQL,
    P3WIFI_SQL
}

enum class ConversionMode {
    PERFORMANCE,
    ECONOMY
}

enum class IndexingOption {
    FULL,
    BASIC,
    NONE
}

class ConvertDumpsViewModel : ViewModel() {

    private val _conversionType = MutableLiveData<ConversionType?>()
    val conversionType: LiveData<ConversionType?> = _conversionType

    private val _routerScanFiles = MutableLiveData<List<SelectedFile>>(emptyList())
    val routerScanFiles: LiveData<List<SelectedFile>> = _routerScanFiles

    private val _baseFile = MutableLiveData<SelectedFile?>()
    val baseFile: LiveData<SelectedFile?> = _baseFile

    private val _geoFile = MutableLiveData<SelectedFile?>()
    val geoFile: LiveData<SelectedFile?> = _geoFile

    private val _inputFile = MutableLiveData<SelectedFile?>()
    val inputFile: LiveData<SelectedFile?> = _inputFile

    private val _isConverting = MutableLiveData(false)
    val isConverting: LiveData<Boolean> = _isConverting

    private val _estimatedTime = MutableLiveData("Неизвестно")
    val estimatedTime: LiveData<String> = _estimatedTime

    private val _conversionProgress = MutableLiveData<Map<String, Int>>(emptyMap())
    val conversionProgress: LiveData<Map<String, Int>> = _conversionProgress

    private val _outputLocation = MutableLiveData<Uri?>()
    val outputLocation: LiveData<Uri?> = _outputLocation

    private val _outputLocationText = MutableLiveData<String>("")
    val outputLocationText: LiveData<String> = _outputLocationText

    private val _outputFileName = MutableLiveData<String>("")
    val outputFileName: LiveData<String> = _outputFileName

    private val _canStartConversion = MutableLiveData(false)
    val canStartConversion: LiveData<Boolean> = _canStartConversion

    fun setConversionType(type: ConversionType) {
        _conversionType.value = type
        clearAllFiles()
        updateCanStartConversion()
        updateEstimatedTime()
    }

    fun addRouterScanFiles(uris: List<Uri>) {
        if (_conversionType.value != ConversionType.ROUTERSCAN_TXT) return

        val currentFiles = _routerScanFiles.value ?: emptyList()
        val newFiles = uris.mapNotNull { uri ->
            if (currentFiles.any { it.uri == uri }) return@mapNotNull null

            val fileName = uri.lastPathSegment ?: "unknown"
            if (fileName.endsWith(".txt", ignoreCase = true)) {
                SelectedFile(uri, fileName, 0L, DumpFileType.ROUTERSCAN_TXT)
            } else null
        }

        _routerScanFiles.value = currentFiles + newFiles
        updateCanStartConversion()
        updateEstimatedTime()
    }

    fun removeRouterScanFile(uri: Uri) {
        val currentFiles = _routerScanFiles.value ?: emptyList()
        _routerScanFiles.value = currentFiles.filter { it.uri != uri }
        updateCanStartConversion()
        updateEstimatedTime()
    }

    fun setBaseFile(uri: Uri) {
        if (_conversionType.value != ConversionType.WIFI_3_SQL) return

        val fileName = uri.lastPathSegment ?: "unknown"
        _baseFile.value = SelectedFile(uri, fileName, 0L, DumpFileType.WIFI_3_SQL)
        updateCanStartConversion()
        updateEstimatedTime()
    }

    fun setGeoFile(uri: Uri) {
        if (_conversionType.value != ConversionType.WIFI_3_SQL) return

        val fileName = uri.lastPathSegment ?: "unknown"
        _geoFile.value = SelectedFile(uri, fileName, 0L, DumpFileType.WIFI_3_SQL)
        updateCanStartConversion()
        updateEstimatedTime()
    }

    fun setInputFile(uri: Uri) {
        if (_conversionType.value != ConversionType.P3WIFI_SQL) return

        val fileName = uri.lastPathSegment ?: "unknown"
        _inputFile.value = SelectedFile(uri, fileName, 0L, DumpFileType.P3WIFI_SQL)
        updateCanStartConversion()
        updateEstimatedTime()
    }

    fun getAllSelectedFiles(): List<SelectedFile> {
        return when (_conversionType.value) {
            ConversionType.ROUTERSCAN_TXT -> _routerScanFiles.value ?: emptyList()
            ConversionType.WIFI_3_SQL -> listOfNotNull(_baseFile.value, _geoFile.value)
            ConversionType.P3WIFI_SQL -> listOfNotNull(_inputFile.value)
            null -> emptyList()
        }
    }

    private fun clearAllFiles() {
        _routerScanFiles.value = emptyList()
        _baseFile.value = null
        _geoFile.value = null
        _inputFile.value = null
    }

    private fun updateCanStartConversion() {
        val canStart = when (_conversionType.value) {
            ConversionType.ROUTERSCAN_TXT -> (_routerScanFiles.value?.isNotEmpty() == true)
            ConversionType.WIFI_3_SQL -> (_baseFile.value != null && _geoFile.value != null)
            ConversionType.P3WIFI_SQL -> (_inputFile.value != null)
            null -> false
        } && !(_isConverting.value ?: false)

        _canStartConversion.value = canStart
    }

    fun startConversion() {
        _isConverting.value = true
        _conversionProgress.value = emptyMap()
        updateCanStartConversion()
    }

    fun updateProgress(fileName: String, progress: Int) {
        val currentProgress = _conversionProgress.value ?: emptyMap()
        _conversionProgress.value = currentProgress + (fileName to progress)
    }

    fun setOutputLocation(uri: Uri?) {
        _outputLocation.value = uri
        if (uri != null) {
            val path = uri.path?.substringAfterLast(':') ?: "Unknown"
            _outputLocationText.value = "Выбрана папка: $path"
            val timestamp = System.currentTimeMillis()
            _outputFileName.value = "Имя файла: converted_$timestamp.db"
        } else {
            _outputLocationText.value = ""
            _outputFileName.value = ""
        }
        updateCanStartConversion()
    }

    fun conversionComplete(outputFile: String) {
        _isConverting.value = false
        updateCanStartConversion()
    }

    fun conversionError(error: String) {
        _isConverting.value = false
        updateCanStartConversion()
    }

    fun conversionCancelled() {
        _isConverting.value = false
        updateCanStartConversion()
    }

    fun updateEstimatedTime() {
    }
}