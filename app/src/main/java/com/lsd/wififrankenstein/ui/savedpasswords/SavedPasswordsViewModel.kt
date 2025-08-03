package com.lsd.wififrankenstein.ui.savedpasswords

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.data.SavedPasswordsRepository
import com.lsd.wififrankenstein.data.SavedWifiPassword
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileWriter

class SavedPasswordsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SavedPasswordsRepository(application)
    private val context = application.applicationContext

    private val _passwords = MutableLiveData<List<SavedWifiPassword>>()
    val passwords: LiveData<List<SavedWifiPassword>> = _passwords

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _isRootAvailable = MutableLiveData<Boolean>()
    val isRootAvailable: LiveData<Boolean> = _isRootAvailable

    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage

    private val _rootStatus = MutableLiveData<RootStatus>()
    val rootStatus: LiveData<RootStatus> = _rootStatus

    enum class RootStatus {
        CHECKING,
        AVAILABLE,
        NOT_AVAILABLE,
        ERROR
    }

    init {
        checkRootAccess()
    }

    private fun checkRootAccess() {
        viewModelScope.launch {
            _isRootAvailable.value = Shell.isAppGrantedRoot() ?: false
        }
    }

    fun loadPasswords() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                if (Shell.isAppGrantedRoot() != true) {
                    _error.value = context.getString(R.string.root_required_passwords)
                    _passwords.value = emptyList()
                    _isLoading.value = false
                    return@launch
                }

                val passwordList = repository.getSavedPasswords(useRoot = true)
                _passwords.value = passwordList

                if (passwordList.isEmpty()) {
                    _error.value = context.getString(R.string.no_saved_passwords)
                }
            } catch (e: Exception) {
                Log.e("SavedPasswordsViewModel", "Error loading passwords", e)
                _error.value = context.getString(R.string.error_loading_passwords, e.message ?: "Unknown error")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun retryWithRoot() {
        if (_rootStatus.value != RootStatus.AVAILABLE) {
            checkRootAccess()
        }
        loadPasswords()
    }

    fun copyToClipboard(text: String, label: String) {
        if (text.isEmpty()) {
            _toastMessage.value = context.getString(R.string.no_data_to_copy)
            return
        }

        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboardManager.setPrimaryClip(clip)

            val message = when (label) {
                "password" -> context.getString(R.string.password_copied)
                "ssid" -> context.getString(R.string.ssid_copied)
                else -> context.getString(R.string.copied_to_clipboard, label)
            }
            _toastMessage.value = message
        } catch (e: Exception) {
            Log.e("SavedPasswordsViewModel", "Error copying to clipboard", e)
            _toastMessage.value = context.getString(R.string.error_general, e.message)
        }
    }

    fun exportPasswords(format: ExportFormat) {
        viewModelScope.launch {
            try {
                val passwordList = _passwords.value ?: emptyList()
                if (passwordList.isEmpty()) {
                    _toastMessage.value = context.getString(R.string.no_saved_passwords)
                    return@launch
                }

                val timestamp = System.currentTimeMillis()
                val fileName = "wifi_passwords_$timestamp"
                val content = when (format) {
                    ExportFormat.JSON -> exportAsJson(passwordList)
                    ExportFormat.CSV -> exportAsCsv(passwordList)
                    ExportFormat.TXT -> exportAsTxt(passwordList)
                }

                val externalDir = context.getExternalFilesDir(null)
                    ?: context.filesDir

                val file = File(externalDir, "$fileName.${format.extension}")
                FileWriter(file).use { writer ->
                    writer.write(content)
                }

                _toastMessage.value = context.getString(R.string.passwords_exported_to, file.absolutePath)
            } catch (e: Exception) {
                Log.e("SavedPasswordsViewModel", "Error exporting passwords", e)
                _toastMessage.value = context.getString(R.string.export_failed_passwords, e.message)
            }
        }
    }

    private fun exportAsJson(passwords: List<SavedWifiPassword>): String {
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
        return json.encodeToString(passwords)
    }

    private fun exportAsCsv(passwords: List<SavedWifiPassword>): String {
        val header = "SSID,Password,Security Type,BSSID\n"
        val rows = passwords.joinToString("\n") { password ->
            val escapedSsid = password.ssid.replace(",", "\\,").replace("\"", "\\\"")
            val escapedPassword = password.password.replace(",", "\\,").replace("\"", "\\\"")
            val escapedSecurity = password.securityType.replace(",", "\\,")
            val bssid = password.bssid ?: ""
            "\"$escapedSsid\",\"$escapedPassword\",\"$escapedSecurity\",\"$bssid\""
        }
        return header + rows
    }

    private fun exportAsTxt(passwords: List<SavedWifiPassword>): String {
        return passwords.joinToString("\n" + "=".repeat(50) + "\n") { password ->
            buildString {
                append("SSID: ${password.ssid}\n")
                append("Password: ${password.password.ifEmpty { context.getString(R.string.not_available) }}\n")
                append("Security: ${password.securityType}\n")
                if (password.bssid != null) {
                    append("BSSID: ${password.bssid}\n")
                }
                if (password.configKey != null) {
                    append("Config Key: ${password.configKey}\n")
                }
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearToastMessage() {
        _toastMessage.value = ""
    }

    fun getPasswordsWithPasswords(): List<SavedWifiPassword> {
        return _passwords.value?.filter { it.password.isNotEmpty() } ?: emptyList()
    }

    fun getStatistics(): PasswordStatistics {
        val allPasswords = _passwords.value ?: emptyList()
        val withPasswords = allPasswords.filter { it.password.isNotEmpty() }
        val openNetworks = allPasswords.filter { it.isOpenNetwork }

        return PasswordStatistics(
            total = allPasswords.size,
            withPasswords = withPasswords.size,
            openNetworks = openNetworks.size,
            securityTypes = allPasswords.groupBy { it.securityType }.mapValues { it.value.size }
        )
    }

    data class PasswordStatistics(
        val total: Int,
        val withPasswords: Int,
        val openNetworks: Int,
        val securityTypes: Map<String, Int>
    )

    enum class ExportFormat(val extension: String) {
        JSON("json"),
        CSV("csv"),
        TXT("txt")
    }
}