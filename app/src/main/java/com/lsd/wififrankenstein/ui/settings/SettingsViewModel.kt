package com.lsd.wififrankenstein.ui.settings

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.API3WiFiHelper
import com.lsd.wififrankenstein.util.ChrootManagerSingleton
import com.lsd.wififrankenstein.util.ChrootType
import com.lsd.wififrankenstein.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import com.lsd.wififrankenstein.util.Log as AppLog

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val rootPrefs =
        application.getSharedPreferences("com.lsd.wififrankenstein", Context.MODE_PRIVATE)
    private val api3WiFiPrefs =
        application.getSharedPreferences("API3WiFiSettings", Context.MODE_PRIVATE)

    private val _currentTheme = MutableLiveData<Int>()
    val currentTheme: LiveData<Int> = _currentTheme

    private val _usePostMethod = MutableLiveData<Boolean>()
    val usePostMethod: LiveData<Boolean> = _usePostMethod

    private val _currentAppIcon = MutableLiveData<String>()
    val currentAppIcon: LiveData<String> = _currentAppIcon

    private val _currentColorTheme = MutableLiveData<String>()
    val currentColorTheme: LiveData<String> = _currentColorTheme

    private val _themeChanged = MutableLiveData<Boolean>()
    val themeChanged: LiveData<Boolean> = _themeChanged

    private val _scanOnStartup = MutableLiveData<Boolean>()
    val scanOnStartup: LiveData<Boolean> = _scanOnStartup

    private val _showAdvancedUploadOptions = MutableLiveData<Boolean>()
    val showAdvancedUploadOptions: LiveData<Boolean> = _showAdvancedUploadOptions

    private val _showRootWithoutRoot = MutableLiveData<Boolean>()
    val showRootWithoutRoot: LiveData<Boolean> = _showRootWithoutRoot

    private val _ignoreChrootCheck = MutableLiveData<Boolean>()
    val ignoreChrootCheck: LiveData<Boolean> = _ignoreChrootCheck

    private val _checkUpdatesOnOpen = MutableLiveData<Boolean>()
    val checkUpdatesOnOpen: LiveData<Boolean> = _checkUpdatesOnOpen

    private val _enableRoot = MutableLiveData<Boolean>()
    val enableRoot: LiveData<Boolean> = _enableRoot

    private val _alwaysExpandSettings = MutableLiveData<Boolean>()
    val alwaysExpandSettings: LiveData<Boolean> = _alwaysExpandSettings

    private val _mergeResults = MutableLiveData<Boolean>()
    val mergeResults: LiveData<Boolean> = _mergeResults

    private val _showWipFeatures = MutableLiveData<Boolean>()
    val showWipFeatures: LiveData<Boolean> = _showWipFeatures

    private val _includeAppIdentifier = MutableLiveData<Boolean>()
    val includeAppIdentifier: LiveData<Boolean> = _includeAppIdentifier

    private val _prioritizeNetworksWithData = MutableLiveData<Boolean>()
    val prioritizeNetworksWithData: LiveData<Boolean> = _prioritizeNetworksWithData

    private val _maxPointsPerRequest = MutableLiveData<Int>()
    val maxPointsPerRequest: LiveData<Int> = _maxPointsPerRequest

    private val api3WiFiHelper = API3WiFiHelper(application, "", "")

    private val _requestDelay = MutableLiveData<Long>()
    val requestDelay: LiveData<Long> = _requestDelay

    private val _connectTimeout = MutableLiveData<Int>()
    val connectTimeout: LiveData<Int> = _connectTimeout

    private val _readTimeout = MutableLiveData<Int>()
    val readTimeout: LiveData<Int> = _readTimeout

    private val _cacheResults = MutableLiveData<Boolean>()
    val cacheResults: LiveData<Boolean> = _cacheResults

    private val _tryAlternativeUrl = MutableLiveData<Boolean>()
    val tryAlternativeUrl: LiveData<Boolean> = _tryAlternativeUrl

    private val _ignoreSSLCertificate = MutableLiveData<Boolean>()
    val ignoreSSLCertificate: LiveData<Boolean> = _ignoreSSLCertificate

    private val _dummyNetworkMode = MutableLiveData<Boolean>()
    val dummyNetworkMode: LiveData<Boolean> = _dummyNetworkMode

    private val _autoScrollToNetworksWithData = MutableLiveData<Boolean>()
    val autoScrollToNetworksWithData: LiveData<Boolean> = _autoScrollToNetworksWithData

    private val _enableLogging = MutableLiveData<Boolean>()
    val enableLogging: LiveData<Boolean> = _enableLogging

    private val _suppressInterfacePollingLogs = MutableLiveData<Boolean>()
    val suppressInterfacePollingLogs: LiveData<Boolean> = _suppressInterfacePollingLogs

    private val _autoScanInterfaces = MutableLiveData<Boolean>()
    val autoScanInterfaces: LiveData<Boolean> = _autoScanInterfaces

    private val _extendedPollInterval = MutableLiveData<Boolean>()
    val extendedPollInterval: LiveData<Boolean> = _extendedPollInterval

    private val _hasChroot = MutableLiveData(false)
    val hasChroot: LiveData<Boolean> = _hasChroot

    private val _hasProot = MutableLiveData(false)
    val hasProot: LiveData<Boolean> = _hasProot

    init {
        _currentTheme.value = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        _currentAppIcon.value = prefs.getString("app_icon", "default")
        _currentColorTheme.value = prefs.getString("color_theme", "green") ?: "green"
        _checkUpdatesOnOpen.value = prefs.getBoolean("check_updates_on_open", true)
        _enableRoot.value = rootPrefs.getBoolean("enable_root", false)
        _scanOnStartup.value = prefs.getBoolean("scan_on_startup", true)
        _alwaysExpandSettings.value = prefs.getBoolean("always_expand_settings", false)
        _dummyNetworkMode.value = prefs.getBoolean("dummy_network_mode", false)
        _usePostMethod.value = api3WiFiPrefs.getBoolean("usePostMethod", false)
        _prioritizeNetworksWithData.value = prefs.getBoolean("prioritize_networks_with_data", true)
        _autoScrollToNetworksWithData.value =
            prefs.getBoolean("auto_scroll_to_networks_with_data", true)

        _showAdvancedUploadOptions.value = prefs.getBoolean("show_advanced_upload_options", false)
        _showRootWithoutRoot.value = prefs.getBoolean("show_root_without_root", false)
        _ignoreChrootCheck.value = prefs.getBoolean("ignore_chroot_check", false)

        _maxPointsPerRequest.value = api3WiFiPrefs.getInt("maxPointsPerRequest", 99)
        _requestDelay.value = api3WiFiPrefs.getLong("requestDelay", 1000)
        _connectTimeout.value = api3WiFiPrefs.getInt("connectTimeout", 5000)
        _readTimeout.value = api3WiFiPrefs.getInt("readTimeout", 10000)
        _cacheResults.value = api3WiFiPrefs.getBoolean("cacheResults", true)
        _tryAlternativeUrl.value = api3WiFiPrefs.getBoolean("tryAlternativeUrl", true)
        _ignoreSSLCertificate.value = api3WiFiPrefs.getBoolean("ignoreSSLCertificate", false)
        _includeAppIdentifier.value = api3WiFiPrefs.getBoolean("includeAppIdentifier", true)
        _showWipFeatures.value = prefs.getBoolean("show_wip_features", false)
        _enableLogging.value = FileLogger.isLoggingEnabled()

        val suppress = prefs.getBoolean("suppress_interface_polling_logs", false)
        _suppressInterfacePollingLogs.value = suppress
        AppLog.suppressedTags = if (suppress) POLLING_TAGS else emptySet()

        _autoScanInterfaces.value = prefs.getBoolean("auto_scan_interfaces", true)
        _extendedPollInterval.value = prefs.getBoolean("extended_poll_interval", false)

        viewModelScope.launch(Dispatchers.IO) {
            val cm = ChrootManagerSingleton.get(getApplication())
            val ct = cm.getChrootType()
            _hasChroot.postValue(ct is ChrootType.Root)
            _hasProot.postValue(ct is ChrootType.Rootless || ct is ChrootType.RootWithoutChroot)
        }
    }

    companion object {
        private val POLLING_TAGS = setOf(
            "ChrootManager", "WlanInterfaceMgrVM", "HandshakeCaptureVM",
            "HandshakeCaptureFrag", "HandshakeCaptureRunner"
        )
    }

    fun setEnableLogging(enabled: Boolean) {
        _enableLogging.value = enabled
        if (enabled) {
            FileLogger.enableLogging(getApplication())
        } else {
            FileLogger.disableLogging()
        }
    }

    fun deleteLogFolder(): Boolean {
        return FileLogger.deleteLogFolder()
    }

    fun getLastLogFile(): File? {
        return FileLogger.getLastLogFile()
    }

    fun getEnableLogging(): Boolean = _enableLogging.value == true

    fun setShowAdvancedUploadOptions(isEnabled: Boolean) {
        prefs.edit { putBoolean("show_advanced_upload_options", isEnabled) }
        _showAdvancedUploadOptions.value = isEnabled
    }

    fun setShowRootWithoutRoot(isEnabled: Boolean) {
        prefs.edit { putBoolean("show_root_without_root", isEnabled) }
        _showRootWithoutRoot.value = isEnabled
    }

    fun setIgnoreChrootCheck(isEnabled: Boolean) {
        prefs.edit { putBoolean("ignore_chroot_check", isEnabled) }
        _ignoreChrootCheck.value = isEnabled
    }

    fun getShowAdvancedUploadOptions() = _showAdvancedUploadOptions.value == true

    fun getSuppressInterfacePollingLogs() = _suppressInterfacePollingLogs.value == true

    fun setSuppressInterfacePollingLogs(suppress: Boolean) {
        prefs.edit { putBoolean("suppress_interface_polling_logs", suppress) }
        _suppressInterfacePollingLogs.value = suppress
        AppLog.suppressedTags = if (suppress) POLLING_TAGS else emptySet()
    }

    fun getAutoScanInterfaces() = _autoScanInterfaces.value != false

    fun setAutoScanInterfaces(enabled: Boolean) {
        prefs.edit { putBoolean("auto_scan_interfaces", enabled) }
        _autoScanInterfaces.value = enabled
    }

    fun getExtendedPollInterval() = _extendedPollInterval.value == true

    fun setExtendedPollInterval(enabled: Boolean) {
        prefs.edit { putBoolean("extended_poll_interval", enabled) }
        _extendedPollInterval.value = enabled
    }

    fun setPrioritizeNetworksWithData(isPrioritized: Boolean) {
        prefs.edit { putBoolean("prioritize_networks_with_data", isPrioritized) }
        _prioritizeNetworksWithData.value = isPrioritized
    }

    fun setAutoScrollToNetworksWithData(isEnabled: Boolean) {
        prefs.edit { putBoolean("auto_scroll_to_networks_with_data", isEnabled) }
        _autoScrollToNetworksWithData.value = isEnabled
    }

    fun getAutoScrollToNetworksWithData() = _autoScrollToNetworksWithData.value != false

    fun getPrioritizeNetworksWithData() = _prioritizeNetworksWithData.value != false

    fun getIncludeAppIdentifier() = _includeAppIdentifier.value != false
    fun setIncludeAppIdentifier(value: Boolean) {
        api3WiFiPrefs.edit { putBoolean("includeAppIdentifier", value) }
        _includeAppIdentifier.value = value
    }

    fun setShowWipFeatures(isEnabled: Boolean) {
        prefs.edit { putBoolean("show_wip_features", isEnabled) }
        _showWipFeatures.value = isEnabled
    }

    fun setDummyNetworkMode(isEnabled: Boolean) {
        prefs.edit { putBoolean("dummy_network_mode", isEnabled) }
        _dummyNetworkMode.value = isEnabled
    }

    fun setScanOnStartup(isChecked: Boolean) {
        prefs.edit { putBoolean("scan_on_startup", isChecked) }
        _scanOnStartup.value = isChecked
    }


    fun setAppIcon(icon: String) {
        if (icon != _currentAppIcon.value) {
            prefs.edit { putString("app_icon", icon) }
            _currentAppIcon.value = icon
            updateAppIcon(icon)
        }
    }

    private fun updateAppIcon(icon: String) {
        val context = getApplication<Application>().applicationContext
        val pm = context.packageManager

        try {
            val aliasToEnable = when (icon) {
                "default" -> ".MainActivity_Default"
                "3wifi" -> ".MainActivity_3WiFi"
                "anti3wifi" -> ".MainActivity_Anti3WiFi"
                "p3wifi" -> ".MainActivity_P3WiFi"
                "p3wifi_pixel" -> ".MainActivity_P3WiFiPixel"
                else -> ".MainActivity_Default"
            }

            val targetAlias = ComponentName(context, context.packageName + aliasToEnable)
            val currentState = pm.getComponentEnabledSetting(targetAlias)

            if (currentState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return
            }

            listOf(
                ".MainActivity_Default",
                ".MainActivity_3WiFi",
                ".MainActivity_Anti3WiFi",
                ".MainActivity_P3WiFi",
                ".MainActivity_P3WiFiPixel"
            ).forEach { alias ->
                val component = ComponentName(context, context.packageName + alias)
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }

            pm.setComponentEnabledSetting(
                targetAlias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            Toast.makeText(context, R.string.icon_changed, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("SettingsViewModel", "Error changing app icon", e)
            Toast.makeText(context, R.string.icon_change_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun setTheme(theme: Int) {
        if (theme != _currentTheme.value) {
            prefs.edit { putInt("night_mode", theme) }
            _currentTheme.value = theme
            _themeChanged.value = true
        }
    }

    fun setColorTheme(colorTheme: String) {
        if (colorTheme != _currentColorTheme.value) {
            prefs.edit { putString("color_theme", colorTheme) }
            _currentColorTheme.value = colorTheme
            _themeChanged.value = true
        }
    }

    fun setCheckUpdatesOnOpen(isChecked: Boolean) {
        prefs.edit { putBoolean("check_updates_on_open", isChecked) }
        _checkUpdatesOnOpen.value = isChecked
    }

    fun setEnableRoot(isChecked: Boolean) {
        rootPrefs.edit { putBoolean("enable_root", isChecked) }
        _enableRoot.value = isChecked
    }

    fun setAlwaysExpandSettings(isChecked: Boolean) {
        prefs.edit { putBoolean("always_expand_settings", isChecked) }
        _alwaysExpandSettings.value = isChecked
    }

    fun resetThemeChangedFlag() {
        _themeChanged.value = false
    }

    fun getUsePostMethod(): Boolean {
        return api3WiFiPrefs.getBoolean("usePostMethod", false)
    }

    fun setUsePostMethod(value: Boolean) {
        api3WiFiPrefs.edit { putBoolean("usePostMethod", value) }
        _usePostMethod.value = value
    }

    fun getMaxPointsPerRequest() = _maxPointsPerRequest.value ?: 99
    fun setMaxPointsPerRequest(value: Int) {
        api3WiFiPrefs.edit { putInt("maxPointsPerRequest", value) }
        _maxPointsPerRequest.value = value
    }

    fun getRequestDelay() = _requestDelay.value ?: 1000L
    fun setRequestDelay(value: Long) {
        api3WiFiPrefs.edit { putLong("requestDelay", value) }
        _requestDelay.value = value
    }

    fun getConnectTimeout() = _connectTimeout.value ?: 5000
    fun setConnectTimeout(value: Int) {
        api3WiFiPrefs.edit { putInt("connectTimeout", value) }
        _connectTimeout.value = value
    }

    fun getReadTimeout() = _readTimeout.value ?: 10000
    fun setReadTimeout(value: Int) {
        api3WiFiPrefs.edit { putInt("readTimeout", value) }
        _readTimeout.value = value
    }

    fun getCacheResults() = _cacheResults.value != false
    fun setCacheResults(value: Boolean) {
        api3WiFiPrefs.edit { putBoolean("cacheResults", value) }
        _cacheResults.value = value
    }

    fun getTryAlternativeUrl() = _tryAlternativeUrl.value != false
    fun setTryAlternativeUrl(value: Boolean) {
        api3WiFiPrefs.edit { putBoolean("tryAlternativeUrl", value) }
        _tryAlternativeUrl.value = value
    }

    fun getIgnoreSSLCertificate() = _ignoreSSLCertificate.value == true

    @SuppressLint("UseKtx")
    fun setIgnoreSSLCertificate(value: Boolean) {
        api3WiFiPrefs.edit { putBoolean("ignoreSSLCertificate", value) }
        _ignoreSSLCertificate.value = value
    }

    fun clearAPI3WiFiCache() {
        api3WiFiHelper.clearCache()
    }

    fun setStartPage(value: String) {
        prefs.edit { putString("start_page", value) }
    }

}