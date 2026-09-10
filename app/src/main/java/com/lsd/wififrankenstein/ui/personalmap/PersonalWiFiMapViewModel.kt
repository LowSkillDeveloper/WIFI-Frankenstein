package com.lsd.wififrankenstein.ui.personalmap

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.core.content.edit
import com.lsd.wififrankenstein.service.WiFiMapScanningService
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PersonalWiFiMapViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = LocalAppDbHelper(application)
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    
    private val _isScanning = MutableLiveData<Boolean>(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _networksCount = MutableLiveData<Int>(0)
    val networksCount: LiveData<Int> = _networksCount

    private val _gpsHealth = MutableLiveData<String>("UNKNOWN")
    val gpsHealth: LiveData<String> = _gpsHealth

    var scanInterval: Long
        get() = prefs.getLong("personal_map_scan_interval", 5000L)
        set(value) = prefs.edit { putLong("personal_map_scan_interval", value) }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WiFiMapScanningService.BROADCAST_STATE) {
                _isScanning.postValue(intent.getBooleanExtra(WiFiMapScanningService.EXTRA_SCANNING, false))
                _networksCount.postValue(intent.getIntExtra(WiFiMapScanningService.EXTRA_NETWORKS_COUNT, 0))
                _gpsHealth.postValue(intent.getStringExtra(WiFiMapScanningService.EXTRA_GPS_HEALTH) ?: "UNKNOWN")
            }
        }
    }

    init {
        LocalBroadcastManager.getInstance(application).registerReceiver(
            serviceReceiver,
            IntentFilter(WiFiMapScanningService.BROADCAST_STATE)
        )
    }

    fun startScanning(intervalMs: Long, saveToDb: Boolean) {
        val intent = Intent(getApplication(), WiFiMapScanningService::class.java).apply {
            action = WiFiMapScanningService.ACTION_START
            putExtra(WiFiMapScanningService.EXTRA_INTERVAL, intervalMs)
            putExtra(WiFiMapScanningService.EXTRA_SAVE, saveToDb)
        }
        getApplication<Application>().startService(intent)
    }

    fun stopScanning() {
        val intent = Intent(getApplication(), WiFiMapScanningService::class.java).apply {
            action = WiFiMapScanningService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }

    fun clearMap() {
        CoroutineScope(Dispatchers.IO).launch {
            dbHelper.clearPersonalMap()
            _networksCount.postValue(0)
        }
    }

    override fun onCleared() {
        super.onCleared()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(serviceReceiver)
    }
}
