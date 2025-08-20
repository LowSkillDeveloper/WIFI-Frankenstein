package com.lsd.wififrankenstein.ui.about

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class AboutViewModel(application: Application) : AndroidViewModel(application) {

    private val _text = MutableLiveData<String>().apply {
        value = getAppNameWithVersion()
    }
    val text: LiveData<String> = _text

    private fun getAppNameWithVersion(): String {
        return try {
            val context = getApplication<Application>()
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = packageInfo.versionName
            "WIFI Frankenstein $versionName"
        } catch (e: Exception) {
            "WIFI Frankenstein"
        }
    }
}