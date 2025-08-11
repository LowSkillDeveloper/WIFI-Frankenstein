package com.lsd.wififrankenstein

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WelcomeViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPreferences =
        application.getSharedPreferences("com.lsd.wififrankenstein", Context.MODE_PRIVATE)

    private val _selectedTheme = MutableLiveData<Int>()

    private val _selectedColorTheme = MutableLiveData<String>()

    private val _selectedAppIcon = MutableLiveData<String>()

    private val _selectedDatabases = MutableLiveData<List<DbItem>>(emptyList())

    private val _locationPermissionGranted = MutableLiveData<Boolean>()
    val locationPermissionGranted: LiveData<Boolean> = _locationPermissionGranted

    private val _storagePermissionGranted = MutableLiveData<Boolean>()
    val storagePermissionGranted: LiveData<Boolean> = _storagePermissionGranted

    private val _rootEnabled = MutableLiveData<Boolean>()
    val rootEnabled: LiveData<Boolean> = _rootEnabled

    init {
        viewModelScope.launch(Dispatchers.Main) {
            val locationPermission = withContext(Dispatchers.IO) {
                sharedPreferences.getBoolean("location_permission_granted", false)
            }
            val storagePermission = withContext(Dispatchers.IO) {
                sharedPreferences.getBoolean("storage_permission_granted", false)
            }
            val rootEnabled = withContext(Dispatchers.IO) {
                sharedPreferences.getBoolean("enable_root", false)
            }

            _locationPermissionGranted.value = locationPermission
            _storagePermissionGranted.value = storagePermission
            _rootEnabled.value = rootEnabled
        }
    }

    suspend fun isFirstLaunch(): Boolean = withContext(Dispatchers.IO) {
        sharedPreferences.getBoolean("isFirstLaunch", true)
    }

    fun setFirstLaunch(isFirst: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            sharedPreferences.edit { putBoolean("isFirstLaunch", isFirst) }
        }
    }

    private val _notificationPermissionGranted = MutableLiveData<Boolean>()
    val notificationPermissionGranted: LiveData<Boolean> = _notificationPermissionGranted

    fun setNotificationPermissionGranted(granted: Boolean) {
        _notificationPermissionGranted.value = granted
    }

    fun setSelectedTheme(theme: Int) {
        _selectedTheme.postValue(theme)
    }

    fun setSelectedColorTheme(colorTheme: String) {
        _selectedColorTheme.postValue(colorTheme)
    }

    fun setSelectedAppIcon(icon: String) {
        _selectedAppIcon.postValue(icon)
    }

    fun addSelectedDatabase(database: DbItem) {
        val currentList = _selectedDatabases.value.orEmpty().toMutableList()
        if (!currentList.any { it.id == database.id }) {
            currentList.add(database)
            _selectedDatabases.postValue(currentList)
        }
    }

    fun setLocationPermissionGranted(granted: Boolean) {
        _locationPermissionGranted.postValue(granted)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPreferences.edit {
                putBoolean("location_permission_granted", granted)
            }
        }
    }

    fun setStoragePermissionGranted(granted: Boolean) {
        _storagePermissionGranted.postValue(granted)
        viewModelScope.launch(Dispatchers.IO) {
            sharedPreferences.edit {
                putBoolean("storage_permission_granted", granted)
            }
        }
    }

    fun setRootEnabled(enabled: Boolean) {
        _rootEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            sharedPreferences.edit {
                putBoolean("enable_root", enabled)
            }
        }
    }
}