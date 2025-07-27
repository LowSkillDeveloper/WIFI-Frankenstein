package com.lsd.wififrankenstein.ui.qrgenerator

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.utils.QrCodeHelper
import com.lsd.wififrankenstein.utils.SecurityType
import com.lsd.wififrankenstein.utils.WiFiNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QrCodeGeneratorViewModel(application: Application) : AndroidViewModel(application) {

    private val _qrCodeBitmap = MutableLiveData<Bitmap?>()
    val qrCodeBitmap: LiveData<Bitmap?> = _qrCodeBitmap

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _saveResult = MutableLiveData<Boolean>()
    val saveResult: LiveData<Boolean> = _saveResult

    private var currentNetwork: WiFiNetwork? = null

    fun generateQrCode(ssid: String, password: String, security: SecurityType, hidden: Boolean) {
        if (ssid.isBlank()) {
            _errorMessage.value = "Network name cannot be empty"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val network = WiFiNetwork(ssid, password, security, hidden)
                currentNetwork = network

                val qrString = QrCodeHelper.generateWiFiQrString(network)
                val bitmap = withContext(Dispatchers.Default) {
                    QrCodeHelper.generateQrCodeBitmap(qrString, 512)
                }

                if (bitmap != null) {
                    _qrCodeBitmap.value = bitmap
                } else {
                    _errorMessage.value = "Failed to generate QR code"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to generate QR code: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun saveQrCodeToGallery() {
        val bitmap = _qrCodeBitmap.value
        val network = currentNetwork

        if (bitmap == null || network == null) {
            _saveResult.value = false
            return
        }

        viewModelScope.launch {
            try {
                val result = QrCodeHelper.saveQrCodeToGallery(getApplication(), bitmap, network.ssid)
                _saveResult.value = result
            } catch (e: Exception) {
                _saveResult.value = false
            }
        }
    }

    fun shareQrCode() {
        val bitmap = _qrCodeBitmap.value
        val network = currentNetwork

        if (bitmap != null && network != null) {
            QrCodeHelper.shareQrCode(getApplication(), bitmap, network.ssid)
        }
    }

    fun setNetworkData(ssid: String, password: String = "", security: SecurityType = SecurityType.WPA) {
        currentNetwork = WiFiNetwork(ssid, password, security, false)
    }

    fun getCurrentNetwork(): WiFiNetwork? = currentNetwork

    fun clearError() {
        _errorMessage.value = null
    }
}