package com.lsd.wififrankenstein.ui.wifianalysis

import android.app.Application
import android.content.Context
import android.net.wifi.ScanResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WiFiAnalysisViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPrefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _selectedBand = MutableLiveData<FrequencyBand>()
    val selectedBand: LiveData<FrequencyBand> = _selectedBand

    private val _environmentAnalysis = MutableLiveData<WiFiEnvironmentAnalysis?>()
    val analysisData: LiveData<WiFiEnvironmentAnalysis?> = _environmentAnalysis

    private val _isProcessing = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isProcessing

    private val _errorMessage = MutableLiveData<String?>()
    val error: LiveData<String?> = _errorMessage

    private val _channelData = MutableLiveData<List<ChannelAnalysisData>>()
    val channelAnalysis: LiveData<List<ChannelAnalysisData>> = _channelData

    private val _suggestionData = MutableLiveData<List<ChannelRecommendation>>()
    val recommendations: LiveData<List<ChannelRecommendation>> = _suggestionData

    private val _excludedNetworks = MutableLiveData<Set<String>>()
    val excludedBssids: LiveData<Set<String>> = _excludedNetworks

    private val bandObserver: (FrequencyBand) -> Unit = { band ->
        _environmentAnalysis.value?.let { analysis ->
            updateBandSpecificData(analysis, band)
        }
    }

    private val excludedObserver: (Set<String>) -> Unit = {
        _environmentAnalysis.value?.let { analysis ->
            updateBandSpecificData(analysis, _selectedBand.value ?: FrequencyBand.GHZ_2_4)
        }
    }

    init {
        _selectedBand.value = FrequencyBand.GHZ_2_4
        _excludedNetworks.value = emptySet()

        _selectedBand.observeForever(bandObserver)
        _excludedNetworks.observeForever(excludedObserver)
    }

    override fun onCleared() {
        super.onCleared()
        _selectedBand.removeObserver(bandObserver)
        _excludedNetworks.removeObserver(excludedObserver)
    }

    fun setBand(band: FrequencyBand) {
        _selectedBand.value = band
    }

    fun toggleBssidExclusion(bssid: String) {
        val currentSet = _excludedNetworks.value ?: emptySet()
        _excludedNetworks.value = if (currentSet.contains(bssid)) {
            currentSet - bssid
        } else {
            currentSet + bssid
        }
    }

    fun refreshAnalysisFromExistingData(scanResults: List<ScanResult>) {
        if (scanResults.isEmpty()) {
            _environmentAnalysis.value = null
            _channelData.value = emptyList()
            _suggestionData.value = emptyList()
            return
        }

        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val excludedSet = _excludedNetworks.value ?: emptySet()
                val filteredResults = scanResults.filter { !excludedSet.contains(it.BSSID) }

                val summary = withContext(Dispatchers.Default) {
                    NetworkFrequencyAnalyzer.analyzeNetworkEnvironment(filteredResults)
                }

                val analysis = WiFiEnvironmentAnalysis.fromNetworkSummary(summary)
                _environmentAnalysis.value = analysis
                updateBandSpecificData(analysis, _selectedBand.value ?: FrequencyBand.GHZ_2_4)

            } catch (e: Exception) {
                _errorMessage.value = withContext(Dispatchers.Main) {
                    getApplication<Application>().getString(R.string.channel_analysis_error)
                }
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private fun updateBandSpecificData(analysis: WiFiEnvironmentAnalysis, band: FrequencyBand) {
        val channelInfo = analysis.channelAnalysis[band] ?: emptyList()
        val suggestionInfo = analysis.recommendations[band] ?: emptyList()

        _channelData.value = channelInfo
        _suggestionData.value = suggestionInfo
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
