package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.content.edit

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

    fun getIndexingLevel(): String {
        val dbHelper = LocalAppDbHelper(getApplication())
        return dbHelper.getIndexLevel()
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

    fun toggleIndexing(enable: Boolean, level: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enable) {
                val indexLevel = level ?: getSavedIndexLevel()
                dbHelper.enableIndexing(indexLevel)
                saveIndexLevel(indexLevel)
            } else {
                dbHelper.disableIndexing()
                clearSavedIndexLevel()
            }
            _isIndexingEnabled.postValue(enable)
        }
    }

    private fun getSavedIndexLevel(): String {
        return getApplication<Application>().getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
            .getString("local_db_index_level", "BASIC") ?: "BASIC"
    }

    private fun saveIndexLevel(level: String) {
        getApplication<Application>().getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
            .edit {
                putString("local_db_index_level", level)
            }
    }

    private fun clearSavedIndexLevel() {
        getApplication<Application>().getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
            .edit {
                remove("local_db_index_level")
            }
    }

    fun isIndexingConfigured(): Boolean {
        return getApplication<Application>().getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
            .contains("local_db_index_level")
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