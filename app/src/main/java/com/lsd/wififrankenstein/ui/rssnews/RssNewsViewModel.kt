package com.lsd.wififrankenstein.ui.rssnews

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.lsd.wififrankenstein.R

class RssNewsViewModel(application: Application) : AndroidViewModel(application) {

    private val _text = MutableLiveData<String>().apply {
        value = getApplication<Application>().getString(R.string.rss_fragment_placeholder)
    }
    val text: LiveData<String> = _text
}