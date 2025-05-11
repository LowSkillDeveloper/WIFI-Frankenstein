package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LocalAppDbViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = LocalAppDbHelper(application)

    private val _isIndexingEnabled = MutableLiveData<Boolean>()
    val isIndexingEnabled: LiveData<Boolean> = _isIndexingEnabled

    init {
        checkIndexingStatus()
    }

    private fun checkIndexingStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val indexesExist = dbHelper.hasIndexes()
            _isIndexingEnabled.postValue(indexesExist)
        }
    }

    fun getAllRecords(): List<WifiNetwork> {
        return dbHelper.getAllRecords()
    }

    fun clearDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.clearDatabase()
        }
    }

    fun importRecords(records: List<WifiNetwork>) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.importRecords(records)
        }
    }

    fun toggleIndexing(enable: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enable) {
                dbHelper.enableIndexing()
            } else {
                dbHelper.disableIndexing()
            }
            _isIndexingEnabled.postValue(enable)
        }
    }

    fun optimizeDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.optimizeDatabase()
        }
    }

    fun removeDuplicates() {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.removeDuplicates()
        }
    }

    fun restoreDatabaseFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.restoreDatabaseFromUri(uri)
            checkIndexingStatus()
        }
    }

}