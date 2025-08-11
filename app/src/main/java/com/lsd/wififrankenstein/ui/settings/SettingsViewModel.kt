package com.lsd.wififrankenstein.ui.settings

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.API3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val api3WiFiPrefs = application.getSharedPreferences("API3WiFiSettings", Context.MODE_PRIVATE)

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

    private val _fullCleanup = MutableLiveData<Boolean>()
    val fullCleanup: LiveData<Boolean> = _fullCleanup

    private val _scanOnStartup = MutableLiveData<Boolean>()
    val scanOnStartup: LiveData<Boolean> = _scanOnStartup

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

    private val api3WiFiHelper = API3WiFiHelper(application, "your_server_url", "your_api_key")

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

    private val _clusterAggressiveness = MutableLiveData<Float>()
    val clusterAggressiveness: LiveData<Float> = _clusterAggressiveness

    private val _maxClusterSize = MutableLiveData<Int>()
    val maxClusterSize: LiveData<Int> = _maxClusterSize

    private val _markerVisibilityZoom = MutableLiveData<Float>()
    val markerVisibilityZoom: LiveData<Float> = _markerVisibilityZoom

    private val _maxMarkerDensity = MutableLiveData<Int>()

    private val _forcePointSeparation = MutableLiveData<Boolean>()
    val forcePointSeparation: LiveData<Boolean> = _forcePointSeparation

    private val _autoScrollToNetworksWithData = MutableLiveData<Boolean>()
    val autoScrollToNetworksWithData: LiveData<Boolean> = _autoScrollToNetworksWithData

    init {
        _currentTheme.value = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        _currentAppIcon.value = prefs.getString("app_icon", "default")
        _currentColorTheme.value = prefs.getString("color_theme", "purple") ?: "purple"
        _fullCleanup.value = prefs.getBoolean("full_cleanup", false)
        _checkUpdatesOnOpen.value = prefs.getBoolean("check_updates_on_open", true)
        _enableRoot.value = prefs.getBoolean("enable_root", false)
        _scanOnStartup.value = prefs.getBoolean("scan_on_startup", true)
        _alwaysExpandSettings.value = prefs.getBoolean("always_expand_settings", false)
        _dummyNetworkMode.value = prefs.getBoolean("dummy_network_mode", false)
        _mergeResults.value = prefs.getBoolean("merge_results", true)
        _usePostMethod.value = api3WiFiPrefs.getBoolean("usePostMethod", false)
        _prioritizeNetworksWithData.value = prefs.getBoolean("prioritize_networks_with_data", true)
        _autoScrollToNetworksWithData.value = prefs.getBoolean("auto_scroll_to_networks_with_data", true)

        _clusterAggressiveness.value = prefs.getFloat("map_cluster_aggressiveness", 0.4f)
        val savedValue = prefs.getInt("map_max_cluster_size", 5000)
        val roundedValue = ((savedValue + 500) / 1000) * 1000
        _maxClusterSize.value = if (roundedValue < 1000) 1000 else if (roundedValue > 30000) 30000 else roundedValue
        _markerVisibilityZoom.value = prefs.getFloat("map_marker_visibility_zoom", 11f)
        _maxMarkerDensity.value = prefs.getInt("map_max_marker_density", 3000)
        _forcePointSeparation.value = prefs.getBoolean("map_force_point_separation", true)

        _maxPointsPerRequest.value = api3WiFiPrefs.getInt("maxPointsPerRequest", 99)
        _requestDelay.value = api3WiFiPrefs.getLong("requestDelay", 1000)
        _connectTimeout.value = api3WiFiPrefs.getInt("connectTimeout", 5000)
        _readTimeout.value = api3WiFiPrefs.getInt("readTimeout", 10000)
        _cacheResults.value = api3WiFiPrefs.getBoolean("cacheResults", true)
        _tryAlternativeUrl.value = api3WiFiPrefs.getBoolean("tryAlternativeUrl", true)
        _ignoreSSLCertificate.value = api3WiFiPrefs.getBoolean("ignoreSSLCertificate", false)
        _includeAppIdentifier.value = api3WiFiPrefs.getBoolean("includeAppIdentifier", true)
        _showWipFeatures.value = prefs.getBoolean("show_wip_features", false)
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

    fun getForcePointSeparation() = _forcePointSeparation.value != false
    fun setForcePointSeparation(value: Boolean) {
        prefs.edit { putBoolean("map_force_point_separation", value) }
        _forcePointSeparation.value = value
        notifyMapSettingsChanged()
    }

    fun getClusterAggressiveness() = _clusterAggressiveness.value ?: 1.0f
    fun setClusterAggressiveness(value: Float) {
        prefs.edit { putFloat("map_cluster_aggressiveness", value) }
        _clusterAggressiveness.value = value
        notifyMapSettingsChanged()
    }

    private val _mapSettingsChanged = MutableLiveData<Boolean>()
    val mapSettingsChanged: LiveData<Boolean> = _mapSettingsChanged

    private fun notifyMapSettingsChanged() {
        _mapSettingsChanged.value = true
    }

    fun resetMapSettingsChangedFlag() {
        _mapSettingsChanged.value = false
    }

    fun getMaxClusterSize(): Int {
        val value = _maxClusterSize.value ?: 5000
        val roundedValue = ((value + 500) / 1000) * 1000
        return if (roundedValue < 1000) 1000 else if (roundedValue > 30000) 30000 else roundedValue
    }
    fun setMaxClusterSize(value: Int) {
        prefs.edit { putInt("map_max_cluster_size", value) }
        _maxClusterSize.value = value
        notifyMapSettingsChanged()
    }

    private val _clusterSettingsChanged = MutableLiveData<Boolean>()
    val clusterSettingsChanged: LiveData<Boolean> = _clusterSettingsChanged

    private fun notifyClusterSettingsChanged() {
        _clusterSettingsChanged.value = true
    }

    fun resetClusterSettingsChangedFlag() {
        _clusterSettingsChanged.value = false
    }

    fun getMarkerVisibilityZoom() = _markerVisibilityZoom.value ?: 11f
    fun setMarkerVisibilityZoom(value: Float) {
        prefs.edit { putFloat("map_marker_visibility_zoom", value) }
        _markerVisibilityZoom.value = value
    }

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

    fun setMergeResults(isMerged: Boolean) {
        prefs.edit { putBoolean("merge_results", isMerged) }
        _mergeResults.value = isMerged
    }

    fun setAppIcon(icon: String) {
        if (icon != _currentAppIcon.value) {
            prefs.edit { putString("app_icon", icon) }
            _currentAppIcon.value = icon
            updateAppIcon(icon)
        }
    }

    fun clearAllCachedDatabases() {
        val dbSetupViewModel = DbSetupViewModel(getApplication())
        dbSetupViewModel.clearAllCachedDatabases()
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

            listOf(".MainActivity_Default", ".MainActivity_3WiFi", ".MainActivity_Anti3WiFi", ".MainActivity_P3WiFi", ".MainActivity_P3WiFiPixel").forEach { alias ->
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
            android.util.Log.e("SettingsViewModel", "Error changing app icon", e)
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

    fun setFullCleanup(isChecked: Boolean) {
        prefs.edit { putBoolean("full_cleanup", isChecked) }
        _fullCleanup.value = isChecked
    }

    fun setCheckUpdatesOnOpen(isChecked: Boolean) {
        prefs.edit { putBoolean("check_updates_on_open", isChecked) }
        _checkUpdatesOnOpen.value = isChecked
    }

    fun setEnableRoot(isChecked: Boolean) {
        prefs.edit { putBoolean("enable_root", isChecked) }
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

    fun setAppIconDeferred(icon: String) {
        if (icon != _currentAppIcon.value) {
            prefs.edit { putString("app_icon", icon) }
            _currentAppIcon.value = icon
        }
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

    fun getMergeResults() = _mergeResults.value == true
}