package com.lsd.wififrankenstein.ui.settings

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class NotificationSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("notification_settings", Context.MODE_PRIVATE)

    private val _appUpdatesEnabled = MutableLiveData<Boolean>()
    val appUpdatesEnabled: LiveData<Boolean> = _appUpdatesEnabled

    private val _databaseUpdatesEnabled = MutableLiveData<Boolean>()
    val databaseUpdatesEnabled: LiveData<Boolean> = _databaseUpdatesEnabled

    private val _componentUpdatesEnabled = MutableLiveData<Boolean>()
    val componentUpdatesEnabled: LiveData<Boolean> = _componentUpdatesEnabled

    private val _recommendedDatabasesEnabled = MutableLiveData<Boolean>()
    val recommendedDatabasesEnabled: LiveData<Boolean> = _recommendedDatabasesEnabled

    private val _generalNotificationsEnabled = MutableLiveData<Boolean>()
    val generalNotificationsEnabled: LiveData<Boolean> = _generalNotificationsEnabled

    init {
        _appUpdatesEnabled.value = prefs.getBoolean("app_updates", true)
        _databaseUpdatesEnabled.value = prefs.getBoolean("database_updates", true)
        _componentUpdatesEnabled.value = prefs.getBoolean("component_updates", false)
        _recommendedDatabasesEnabled.value = prefs.getBoolean("recommended_databases", true)
        _generalNotificationsEnabled.value = prefs.getBoolean("general_notifications", true)
    }

    fun setAppUpdatesEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("app_updates", enabled) }
        _appUpdatesEnabled.value = enabled
    }

    fun setDatabaseUpdatesEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("database_updates", enabled) }
        _databaseUpdatesEnabled.value = enabled
    }

    fun setComponentUpdatesEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("component_updates", enabled) }
        _componentUpdatesEnabled.value = enabled
    }

    fun setRecommendedDatabasesEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("recommended_databases", enabled) }
        _recommendedDatabasesEnabled.value = enabled
    }

    fun setGeneralNotificationsEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("general_notifications", enabled) }
        _generalNotificationsEnabled.value = enabled
    }
}