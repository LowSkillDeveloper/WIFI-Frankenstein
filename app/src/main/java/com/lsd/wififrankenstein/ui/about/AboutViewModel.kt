package com.lsd.wififrankenstein.ui.about

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class AboutViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "WIFI Frankenstein about page"
    }
    val text: LiveData<String> = _text
}