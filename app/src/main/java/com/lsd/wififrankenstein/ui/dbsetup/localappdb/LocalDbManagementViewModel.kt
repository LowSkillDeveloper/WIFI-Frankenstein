package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@SuppressLint("LongLogTag")
class LocalDbManagementViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = LocalAppDbHelper(application)

    private val indexLevel: String = dbHelper.getIndexLevel()

    init {
        Log.d("LocalDbManagementViewModel", "Local DB index level: $indexLevel")
    }

    var localDbItems = Pager(
        config = PagingConfig(pageSize = 50, enablePlaceholders = false, prefetchDistance = 3),
        pagingSourceFactory = { LocalDbPagingSource(dbHelper) }
    ).flow.cachedIn(viewModelScope)

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    fun deleteRecords(records: List<WifiNetwork>) {
        viewModelScope.launch(Dispatchers.IO) {
            records.forEach { record ->
                dbHelper.deleteRecord(record.id)
            }
            invalidateData()
        }
    }

    private fun invalidateData() {
        localDbItems = Pager(
            config = PagingConfig(pageSize = 50, enablePlaceholders = false, prefetchDistance = 3),
            pagingSourceFactory = { LocalDbPagingSource(dbHelper) }
        ).flow.cachedIn(viewModelScope)
    }

    fun updateRecord(wifiNetwork: WifiNetwork) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.updateRecord(wifiNetwork)
            invalidateData()
        }
    }

    fun deleteRecord(item: WifiNetwork) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.deleteRecord(item.id)
            invalidateData()
        }
    }

    fun addRecord(wifiNetwork: WifiNetwork) {
        viewModelScope.launch(Dispatchers.IO) {
            val newId = dbHelper.addRecord(wifiNetwork)
            if (newId > 0) {
                invalidateData()
            }
        }
    }
}