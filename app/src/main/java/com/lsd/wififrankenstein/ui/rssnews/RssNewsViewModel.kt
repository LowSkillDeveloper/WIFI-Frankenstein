package com.lsd.wififrankenstein.ui.rssnews

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class RssNewsViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "This is RSS News Fragment"
    }
    val text: LiveData<String> = _text
}