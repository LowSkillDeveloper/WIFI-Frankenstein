package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LocalDbViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = LocalAppDbHelper(application)

    private val _searchResults = MutableLiveData<List<WifiNetwork>>()
    val searchResults: LiveData<List<WifiNetwork>> = _searchResults

    fun searchRecords(query: String, filterByName: Boolean, filterByMac: Boolean, filterByPassword: Boolean, filterByWps: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val results = dbHelper.searchRecordsWithFilters(query, filterByName, filterByMac, filterByPassword, filterByWps)
            _searchResults.postValue(results)
        }
    }
}