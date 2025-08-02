package com.lsd.wififrankenstein.ui.iwscanner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class IwScannerViewModel(application: Application) : AndroidViewModel(application), IwScannerHelper.IwScannerCallbacks {

    private val iwHelper = IwScannerHelper(getApplication())

    private val _scanState = MutableLiveData<IwScanState>(IwScanState.Idle)
    val scanState: LiveData<IwScanState> = _scanState

    private val _networks = MutableLiveData<List<IwNetworkInfo>>(emptyList())
    val networks: LiveData<List<IwNetworkInfo>> = _networks

    private val _linkInfo = MutableLiveData<IwLinkInfo>()
    val linkInfo: LiveData<IwLinkInfo> = _linkInfo

    private val _deviceInfo = MutableLiveData<IwDeviceInfo>()
    val deviceInfo: LiveData<IwDeviceInfo> = _deviceInfo

    private val _rootAccessAvailable = MutableLiveData<Boolean>()
    val rootAccessAvailable: LiveData<Boolean> = _rootAccessAvailable

    private val _progressMessage = MutableLiveData<String>()
    val progressMessage: LiveData<String> = _progressMessage

    private val _availableInterfaces = MutableLiveData<List<IwInterface>>(emptyList())
    val availableInterfaces: LiveData<List<IwInterface>> = _availableInterfaces

    private val _selectedInterface = MutableLiveData<String>("wlan0")
    val selectedInterface: LiveData<String> = _selectedInterface

    init {
        checkSystemRequirements()
    }

    private fun checkSystemRequirements() {
        viewModelScope.launch(Dispatchers.IO) {
            val hasRoot = iwHelper.checkRootAccess()
            _rootAccessAvailable.postValue(hasRoot)

            if (hasRoot) {
                val binariesCopied = iwHelper.copyBinariesFromAssets()
                if (binariesCopied) {
                    _progressMessage.postValue(getApplication<Application>().getString(R.string.iw_ready))
                    loadInitialData()
                } else {
                    _progressMessage.postValue(getApplication<Application>().getString(R.string.iw_binary_copy_failed))
                }
            } else {
                _progressMessage.postValue(getApplication<Application>().getString(R.string.iw_root_required))
            }
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val interfaces = iwHelper.getAvailableInterfaces()
                _availableInterfaces.postValue(interfaces)

                val currentInterface = _selectedInterface.value ?: "wlan0"

                val linkInfo = iwHelper.getLinkInfo(currentInterface)
                _linkInfo.postValue(linkInfo)

                val deviceInfo = iwHelper.getDeviceInfo()
                _deviceInfo.postValue(deviceInfo)
            } catch (e: Exception) {
                _progressMessage.postValue(getApplication<Application>().getString(R.string.iw_initial_load_failed))
            }
        }
    }

    fun scanNetworks() {
        if (_scanState.value is IwScanState.Scanning) return

        _scanState.value = IwScanState.Scanning
        _progressMessage.value = getApplication<Application>().getString(R.string.iw_scanning)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentInterface = _selectedInterface.value ?: "wlan0"
                val networks = iwHelper.scanNetworks(currentInterface)
                _networks.postValue(networks)
                _scanState.postValue(IwScanState.Completed(networks))
                _progressMessage.postValue(
                    getApplication<Application>().getString(R.string.iw_scan_completed, networks.size)
                )
            } catch (e: Exception) {
                _scanState.postValue(IwScanState.Failed(e.message ?: "Unknown error"))
                _progressMessage.postValue(
                    getApplication<Application>().getString(R.string.iw_scan_failed, e.message)
                )
            }
        }
    }

    fun refreshLinkInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentInterface = _selectedInterface.value ?: "wlan0"
                val linkInfo = iwHelper.getLinkInfo(currentInterface)
                _linkInfo.postValue(linkInfo)
            } catch (e: Exception) {
                _progressMessage.postValue(
                    getApplication<Application>().getString(R.string.iw_link_refresh_failed)
                )
            }
        }
    }

    fun refreshDeviceInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _scanState.postValue(IwScanState.LoadingDeviceInfo)
                val deviceInfo = iwHelper.getDeviceInfo()
                _deviceInfo.postValue(deviceInfo)
                _scanState.postValue(IwScanState.Idle)
            } catch (e: Exception) {
                _progressMessage.postValue(
                    getApplication<Application>().getString(R.string.iw_device_info_failed)
                )
                _scanState.postValue(IwScanState.Idle)
            }
        }
    }

    fun setSelectedInterface(interfaceName: String) {
        _selectedInterface.value = interfaceName
        refreshLinkInfo()
    }

    fun refreshInterfaces() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val interfaces = iwHelper.getAvailableInterfaces()
                _availableInterfaces.postValue(interfaces)
            } catch (e: Exception) {
                _progressMessage.postValue(
                    getApplication<Application>().getString(R.string.iw_interface_refresh_failed)
                )
            }
        }
    }

    override fun onStateChanged(state: IwScanState) {
        viewModelScope.launch(Dispatchers.Main) {
            _scanState.value = state
        }
    }

    override fun onLinkInfoUpdated(linkInfo: IwLinkInfo) {
        viewModelScope.launch(Dispatchers.Main) {
            _linkInfo.value = linkInfo
        }
    }

    override fun onDeviceInfoUpdated(deviceInfo: IwDeviceInfo) {
        viewModelScope.launch(Dispatchers.Main) {
            _deviceInfo.value = deviceInfo
        }
    }

    override fun onInterfacesUpdated(interfaces: List<IwInterface>) {
        viewModelScope.launch(Dispatchers.Main) {
            _availableInterfaces.value = interfaces
        }
    }
}