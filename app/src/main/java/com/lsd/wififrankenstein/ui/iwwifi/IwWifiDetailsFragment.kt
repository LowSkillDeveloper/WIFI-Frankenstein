package com.lsd.wififrankenstein.ui.iwwifi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentIwWifiDetailsBinding
import com.lsd.wififrankenstein.databinding.ItemDetailRowBinding
import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.NativeWifiHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter

class IwWifiDetailsFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentIwWifiDetailsBinding? = null
    private val binding get() = _binding!!

    private lateinit var network: IwWifiNetwork
    private var rawInterfaceName: String = ""

    private val iwWifiManager by lazy { IwWifiManager(requireContext()) }
    private val nativeWifiHelper by lazy { NativeWifiHelper(requireContext()) }
    private var saveLauncher: ActivityResultLauncher<String>? = null

    companion object {
        private const val TAG = "IwWifiDetails"
        private const val NETWORK_DATA = "network_data"
        private const val GROUP_DIVIDER = "\n───────────────\n"

        fun newInstance(network: IwWifiNetwork, interfaceName: String): IwWifiDetailsFragment {
            val fragment = IwWifiDetailsFragment()
            val args = Bundle().apply {
                putString("ssid", network.ssid)
                putString("bssid", network.bssid)
                putString("frequency", network.frequency)
                putString("channel", network.channel)
                putString("signal", network.signal)
                putBoolean("wpsEnabled", network.wpsEnabled)
                putBoolean("wpsLocked", network.wpsLocked)
                putString("capabilities", network.capabilities)
                putString("lastSeen", network.lastSeen)
                putString("beaconInterval", network.beaconInterval)
                putString("dtimPeriod", network.dtimPeriod)
                putString("dtimCount", network.dtimCount)
                putBoolean("associated", network.associated)
                putBoolean("probeResponse", network.probeResponse)
                putString("securityType", network.securityType)
                putString("band", network.band)

                putString("groupCipher", network.groupCipher)
                putString("pairwiseCipher", network.pairwiseCipher)
                putString("authSuite", network.authSuite)
                putString("wpaVersion", network.wpaVersion)
                putString("rsnVersion", network.rsnVersion)
                putString("wpsVersion", network.wpsVersion)
                putString("wpsDeviceType", network.wpsDeviceType)
                putString("wpsDeviceName", network.wpsDeviceName)
                putString("wpsManufacturer", network.wpsManufacturer)
                putString("wpsModel", network.wpsModel)
                putString("wpsModelNumber", network.wpsModelNumber)
                putString("wpsSerialNumber", network.wpsSerialNumber)
                putString("wpsUuid", network.wpsUuid)
                putString("wpsConfigMethods", network.wpsConfigMethods)
                putString("wpsRfBands", network.wpsRfBands)
                putString("wpsResponseTypes", network.wpsResponseTypes)
                putString("supportedRates", network.supportedRates)
                putString("extendedRates", network.extendedRates)
                putString("country", network.country)
                putString("countryEnv", network.countryEnv)
                putString("channelsAvailable", network.channelsAvailable)
                putString("powerConstraint", network.powerConstraint)
                putString("txPower", network.txPower)
                putString("stationCount", network.stationCount)
                putString("channelUtilisation", network.channelUtilisation)
                putString("admissionCapacity", network.admissionCapacity)
                putString("htCapabilities", network.htCapabilities)
                putString("htCapabilitiesCapab", network.htCapabilitiesCapab)
                putString("htChannelWidth", network.htChannelWidth)
                putString("htSecondaryChannel", network.htSecondaryChannel)
                putString("htProtection", network.htProtection)
                putString("htMcs", network.htMcs)
                putString("heCapabilities", network.heCapabilities)
                putBoolean("wmmPresent", network.wmmPresent)
                putString("wmmParams", network.wmmParams)
                putString("extCapabilities", network.extCapabilities)
                putString("rawData", network.rawData)

                putString("tsf", network.tsf)
                putString("rmCapabilities", network.rmCapabilities)
                putString("rmCapabilitiesHex", network.rmCapabilitiesHex)
                putBoolean("rmLinkMeasurement", network.rmLinkMeasurement)
                putBoolean("rmNeighborReport", network.rmNeighborReport)
                putBoolean("rmBeaconPassive", network.rmBeaconPassive)
                putBoolean("rmBeaconActive", network.rmBeaconActive)
                putBoolean("rmBeaconTable", network.rmBeaconTable)
                putBoolean("rmChannelLoad", network.rmChannelLoad)
                putBoolean("rmStatistics", network.rmStatistics)
                putBoolean("rmFrameMeasurement", network.rmFrameMeasurement)
                putBoolean("rmLCI", network.rmLCI)
                putBoolean("rmTransmitStream", network.rmTransmitStream)
                putBoolean("ftmRangeReport", network.ftmRangeReport)
                putBoolean("rmCivicLocation", network.rmCivicLocation)
                putString("rmMeasurementPilotCap", network.rmMeasurementPilotCap)
                putString("rmNonOpChannelMaxDur", network.rmNonOpChannelMaxDur)
                putString("operatingClass", network.operatingClass)
                putString("txPowerEnvelope", network.txPowerEnvelope)
                putString("networkOptions", network.networkOptions)
                putString("networkType", network.networkType)
                putBoolean("anqpAvailable", network.anqpAvailable)
                putString("queryResponseLength", network.queryResponseLength)
                putString("heMacCapabilities", network.heMacCapabilities)
                putString("hePhyCapabilities", network.hePhyCapabilities)
                putString("heRcMcs", network.heRcMcs)
                putString("heTcMcs", network.heTcMcs)
                putString("hePpeThreshold", network.hePpeThreshold)
                putString("htAmpduMaxLen", network.htAmpduMaxLen)
                putString("htAmpduMinSpacing", network.htAmpduMinSpacing)
                putString("htTxMcs", network.htTxMcs)
                putString("obssPassiveDwell", network.obssPassiveDwell)
                putString("obssActiveDwell", network.obssActiveDwell)
                putString("obssScanInterval", network.obssScanInterval)
                putString("obssPassiveTotal", network.obssPassiveTotal)
                putString("obssActiveTotal", network.obssActiveTotal)
                putString("obssChannelDelayFactor", network.obssChannelDelayFactor)
                putString("obssScanThreshold", network.obssScanThreshold)
                putString("erpProtection", network.erpProtection)
                putString("staChannelWidth", network.staChannelWidth)
                putString("rifs", network.rifs)
                putString("nonGfPresent", network.nonGfPresent)
                putString("obssNonGfPresent", network.obssNonGfPresent)
                putString("dualBeacon", network.dualBeacon)
                putString("dualCtsProtection", network.dualCtsProtection)
                putString("stbcBeacon", network.stbcBeacon)
                putString("lsigTxopProtect", network.lsigTxopProtect)
                putString("pcoActive", network.pcoActive)
                putString("pcoPhase", network.pcoPhase)
                putBoolean("extHtInfoExchange", network.extHtInfoExchange)
                putBoolean("extTfs", network.extTfs)
                putBoolean("extWnmSleep", network.extWnmSleep)
                putBoolean("extTimBroadcast", network.extTimBroadcast)
                putBoolean("extBssTransition", network.extBssTransition)
                putBoolean("extOpModeNotification", network.extOpModeNotification)
                putBoolean("extTwtResponder", network.extTwtResponder)
                putBoolean("extEcs", network.extEcs)

                putString("tpcTxPower", network.tpcTxPower)
                putString("environment", network.environment)

                putBoolean("hePhySuBeamformer", network.hePhySuBeamformer)
                putBoolean("hePhySuBeamformee", network.hePhySuBeamformee)
                putBoolean("hePhyMuBeamformer", network.hePhyMuBeamformer)
                putString("hePhyBeamformeeSts80", network.hePhyBeamformeeSts80)
                putString("hePhyBeamformeeSts80Plus", network.hePhyBeamformeeSts80Plus)
                putString("hePhySoundingDims80", network.hePhySoundingDims80)
                putString("hePhySoundingDims80Plus", network.hePhySoundingDims80Plus)
                putString("hePhyNg", network.hePhyNg)
                putBoolean("hePhyCodebookSu", network.hePhyCodebookSu)
                putBoolean("hePhyTriggeredSuBf", network.hePhyTriggeredSuBf)
                putBoolean("hePhyTriggeredCqi", network.hePhyTriggeredCqi)
                putBoolean("hePhyPpePresent", network.hePhyPpePresent)
                putString("hePhyMaxNc", network.hePhyMaxNc)
                putBoolean("hePhyTx1024Qam", network.hePhyTx1024Qam)
                putBoolean("hePhyRx1024Qam", network.hePhyRx1024Qam)
                putBoolean("hePhyLdpcPayload", network.hePhyLdpcPayload)

                putBoolean("heMacHtcHe", network.heMacHtcHe)
                putBoolean("heMacBsr", network.heMacBsr)
                putBoolean("heMacOmControl", network.heMacOmControl)
                putString("heMacMaxAmpduExp", network.heMacMaxAmpduExp)
                putBoolean("heMacAmsduInAmpdu", network.heMacAmsduInAmpdu)
                putBoolean("heMacOmUlMuDataDisableRx", network.heMacOmUlMuDataDisableRx)

                putString("vhtCapabilities", network.vhtCapabilities)
                putString("vhtFeaturesRaw", network.vhtFeaturesRaw)
                putString("vhtMaxMpdu", network.vhtMaxMpdu)
                putString("vhtSupportedChannelWidth", network.vhtSupportedChannelWidth)
                putString("vhtRxMcs", network.vhtRxMcs)
                putString("vhtTxMcs", network.vhtTxMcs)
                putString("vhtRxHighestSupported", network.vhtRxHighestSupported)
                putString("vhtTxHighestSupported", network.vhtTxHighestSupported)
                putString("vhtExtendedNss", network.vhtExtendedNss)
                putString("vhtOpChannelWidth", network.vhtOpChannelWidth)
                putString("vhtOpCenterFreq1", network.vhtOpCenterFreq1)
                putString("vhtOpCenterFreq2", network.vhtOpCenterFreq2)
                putString("vhtOpBasicMcs", network.vhtOpBasicMcs)

                putString("heOpParameters", network.heOpParameters)
                putString("heOpDefaultPeDuration", network.heOpDefaultPeDuration)
                putString("heOpTxopDurationRts", network.heOpTxopDurationRts)
                putBoolean("heOpCoHostedBss", network.heOpCoHostedBss)
                putBoolean("heOpErSuDisable", network.heOpErSuDisable)
                putString("heOpBssColor", network.heOpBssColor)
                putString("heOpBasicMcsSet", network.heOpBasicMcsSet)
                putString("heOpMaxCoHostedBssid", network.heOpMaxCoHostedBssid)
                putBoolean("heOpVhtInfoPresent", network.heOpVhtInfoPresent)
                putString("heOpVhtInfo", network.heOpVhtInfo)

                putBoolean("heMacAckEnabledAggregation", network.heMacAckEnabledAggregation)
                putBoolean("heMacTwtResponder", network.heMacTwtResponder)
                putString("heMacDynamicBaFragmentation", network.heMacDynamicBaFragmentation)
                putBoolean("heMacMinPayload128", network.heMacMinPayload128)
                putBoolean("heMacRxControlFrameMultiBss", network.heMacRxControlFrameMultiBss)
                putBoolean("heMacBqr", network.heMacBqr)

                putString("hePhyDeviceClass", network.hePhyDeviceClass)
                putBoolean("hePhyStbcTx80", network.hePhyStbcTx80)
                putBoolean("hePhyStbcRx80", network.hePhyStbcRx80)
                putString("hePhyDcmMaxConstellation", network.hePhyDcmMaxConstellation)
                putBoolean("hePhyFullBwUlMuMimo", network.hePhyFullBwUlMuMimo)
                putBoolean("hePhyPartialBwUlMuMimo", network.hePhyPartialBwUlMuMimo)
                putBoolean("hePhyPartialBwExtendedRange", network.hePhyPartialBwExtendedRange)
                putBoolean("hePhy20In40Mhz", network.hePhy20In40Mhz)
                putBoolean("hePhyHe40He80", network.hePhyHe40He80)
                putBoolean("hePhyErSuPpdu4Ltf", network.hePhyErSuPpdu4Ltf)
                putBoolean("hePhyErSuPpdu1Ltf", network.hePhyErSuPpdu1Ltf)

                putString("operatingClasses", network.operatingClasses)
                putString("apChannelReportClass", network.apChannelReportClass)
                putString("apChannelReportChannels", network.apChannelReportChannels)
                putString("txPowerEnvelope20", network.txPowerEnvelope20)
                putString("txPowerEnvelope40", network.txPowerEnvelope40)
                putString("txPowerEnvelope80", network.txPowerEnvelope80)
                putString("txPowerEnvelope160", network.txPowerEnvelope160)
            }
            args.putString("interface", interfaceName)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentIwWifiDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        saveLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                uri?.let {
                    try {
                        val rawText = binding.textRawData.text.toString()
                        requireContext().contentResolver.openOutputStream(it)?.use { stream ->
                            OutputStreamWriter(stream).use { writer ->
                                writer.write(rawText)
                                writer.flush()
                            }
                        }
                        Log.d(TAG, "File saved successfully: $uri")
                    } catch (e: Exception) {
                        Log.e(TAG, "Save error", e)
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.iw_save_failed, e.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

        parseArguments()
        setupViews()
        displayNetworkInfo()
    }

    private fun parseArguments() {
        arguments?.let { args ->
            network = IwWifiNetwork(
                ssid = args.getString("ssid", ""),
                bssid = args.getString("bssid", ""),
                frequency = args.getString("frequency", ""),
                channel = args.getString("channel", ""),
                signal = args.getString("signal", ""),
                wpsEnabled = args.getBoolean("wpsEnabled", false),
                wpsLocked = args.getBoolean("wpsLocked", false),
                capabilities = args.getString("capabilities", ""),
                lastSeen = args.getString("lastSeen", ""),
                beaconInterval = args.getString("beaconInterval", ""),
                dtimPeriod = args.getString("dtimPeriod", ""),
                dtimCount = args.getString("dtimCount", ""),
                associated = args.getBoolean("associated", false),
                probeResponse = args.getBoolean("probeResponse", false),
                rawData = args.getString("rawData", ""),
                securityType = args.getString("securityType", getString(R.string.unknown)),
                band = args.getString("band", ""),
                groupCipher = args.getString("groupCipher", ""),
                pairwiseCipher = args.getString("pairwiseCipher", ""),
                authSuite = args.getString("authSuite", ""),
                wpaVersion = args.getString("wpaVersion", ""),
                rsnVersion = args.getString("rsnVersion", ""),
                wpsVersion = args.getString("wpsVersion", ""),
                wpsDeviceType = args.getString("wpsDeviceType", ""),
                wpsDeviceName = args.getString("wpsDeviceName", ""),
                wpsManufacturer = args.getString("wpsManufacturer", ""),
                wpsModel = args.getString("wpsModel", ""),
                wpsModelNumber = args.getString("wpsModelNumber", ""),
                wpsSerialNumber = args.getString("wpsSerialNumber", ""),
                wpsUuid = args.getString("wpsUuid", ""),
                wpsConfigMethods = args.getString("wpsConfigMethods", ""),
                wpsRfBands = args.getString("wpsRfBands", ""),
                wpsResponseTypes = args.getString("wpsResponseTypes", ""),
                supportedRates = args.getString("supportedRates", ""),
                extendedRates = args.getString("extendedRates", ""),
                country = args.getString("country", ""),
                countryEnv = args.getString("countryEnv", ""),
                channelsAvailable = args.getString("channelsAvailable", ""),
                powerConstraint = args.getString("powerConstraint", ""),
                txPower = args.getString("txPower", ""),
                stationCount = args.getString("stationCount", ""),
                channelUtilisation = args.getString("channelUtilisation", ""),
                admissionCapacity = args.getString("admissionCapacity", ""),
                htCapabilities = args.getString("htCapabilities", ""),
                htCapabilitiesCapab = args.getString("htCapabilitiesCapab", ""),
                htChannelWidth = args.getString("htChannelWidth", ""),
                htSecondaryChannel = args.getString("htSecondaryChannel", ""),
                htProtection = args.getString("htProtection", ""),
                htMcs = args.getString("htMcs", ""),
                heCapabilities = args.getString("heCapabilities", ""),
                wmmPresent = args.getBoolean("wmmPresent", false),
                wmmParams = args.getString("wmmParams", ""),
                extCapabilities = args.getString("extCapabilities", ""),

                tsf = args.getString("tsf", ""),
                rmCapabilities = args.getString("rmCapabilities", ""),
                rmCapabilitiesHex = args.getString("rmCapabilitiesHex", ""),
                rmLinkMeasurement = args.getBoolean("rmLinkMeasurement", false),
                rmNeighborReport = args.getBoolean("rmNeighborReport", false),
                rmBeaconPassive = args.getBoolean("rmBeaconPassive", false),
                rmBeaconActive = args.getBoolean("rmBeaconActive", false),
                rmBeaconTable = args.getBoolean("rmBeaconTable", false),
                rmChannelLoad = args.getBoolean("rmChannelLoad", false),
                rmStatistics = args.getBoolean("rmStatistics", false),
                rmFrameMeasurement = args.getBoolean("rmFrameMeasurement", false),
                rmLCI = args.getBoolean("rmLCI", false),
                rmTransmitStream = args.getBoolean("rmTransmitStream", false),
                ftmRangeReport = args.getBoolean("ftmRangeReport", false),
                rmCivicLocation = args.getBoolean("rmCivicLocation", false),
                rmMeasurementPilotCap = args.getString("rmMeasurementPilotCap", ""),
                rmNonOpChannelMaxDur = args.getString("rmNonOpChannelMaxDur", ""),
                operatingClass = args.getString("operatingClass", ""),
                txPowerEnvelope = args.getString("txPowerEnvelope", ""),
                networkOptions = args.getString("networkOptions", ""),
                networkType = args.getString("networkType", ""),
                anqpAvailable = args.getBoolean("anqpAvailable", false),
                queryResponseLength = args.getString("queryResponseLength", ""),
                heMacCapabilities = args.getString("heMacCapabilities", ""),
                hePhyCapabilities = args.getString("hePhyCapabilities", ""),
                heRcMcs = args.getString("heRcMcs", ""),
                heTcMcs = args.getString("heTcMcs", ""),
                hePpeThreshold = args.getString("hePpeThreshold", ""),
                htAmpduMaxLen = args.getString("htAmpduMaxLen", ""),
                htAmpduMinSpacing = args.getString("htAmpduMinSpacing", ""),
                htTxMcs = args.getString("htTxMcs", ""),
                obssPassiveDwell = args.getString("obssPassiveDwell", ""),
                obssActiveDwell = args.getString("obssActiveDwell", ""),
                obssScanInterval = args.getString("obssScanInterval", ""),
                obssPassiveTotal = args.getString("obssPassiveTotal", ""),
                obssActiveTotal = args.getString("obssActiveTotal", ""),
                obssChannelDelayFactor = args.getString("obssChannelDelayFactor", ""),
                obssScanThreshold = args.getString("obssScanThreshold", ""),
                erpProtection = args.getString("erpProtection", ""),
                staChannelWidth = args.getString("staChannelWidth", ""),
                rifs = args.getString("rifs", ""),
                nonGfPresent = args.getString("nonGfPresent", ""),
                obssNonGfPresent = args.getString("obssNonGfPresent", ""),
                dualBeacon = args.getString("dualBeacon", ""),
                dualCtsProtection = args.getString("dualCtsProtection", ""),
                stbcBeacon = args.getString("stbcBeacon", ""),
                lsigTxopProtect = args.getString("lsigTxopProtect", ""),
                pcoActive = args.getString("pcoActive", ""),
                pcoPhase = args.getString("pcoPhase", ""),
                extHtInfoExchange = args.getBoolean("extHtInfoExchange", false),
                extTfs = args.getBoolean("extTfs", false),
                extWnmSleep = args.getBoolean("extWnmSleep", false),
                extTimBroadcast = args.getBoolean("extTimBroadcast", false),
                extBssTransition = args.getBoolean("extBssTransition", false),
                extOpModeNotification = args.getBoolean("extOpModeNotification", false),
                extTwtResponder = args.getBoolean("extTwtResponder", false),
                extEcs = args.getBoolean("extEcs", false),

                tpcTxPower = args.getString("tpcTxPower", ""),
                environment = args.getString("environment", ""),

                hePhySuBeamformer = args.getBoolean("hePhySuBeamformer", false),
                hePhySuBeamformee = args.getBoolean("hePhySuBeamformee", false),
                hePhyMuBeamformer = args.getBoolean("hePhyMuBeamformer", false),
                hePhyBeamformeeSts80 = args.getString("hePhyBeamformeeSts80", ""),
                hePhyBeamformeeSts80Plus = args.getString("hePhyBeamformeeSts80Plus", ""),
                hePhySoundingDims80 = args.getString("hePhySoundingDims80", ""),
                hePhySoundingDims80Plus = args.getString("hePhySoundingDims80Plus", ""),
                hePhyNg = args.getString("hePhyNg", ""),
                hePhyCodebookSu = args.getBoolean("hePhyCodebookSu", false),
                hePhyTriggeredSuBf = args.getBoolean("hePhyTriggeredSuBf", false),
                hePhyTriggeredCqi = args.getBoolean("hePhyTriggeredCqi", false),
                hePhyPpePresent = args.getBoolean("hePhyPpePresent", false),
                hePhyMaxNc = args.getString("hePhyMaxNc", ""),
                hePhyTx1024Qam = args.getBoolean("hePhyTx1024Qam", false),
                hePhyRx1024Qam = args.getBoolean("hePhyRx1024Qam", false),
                hePhyLdpcPayload = args.getBoolean("hePhyLdpcPayload", false),

                heMacHtcHe = args.getBoolean("heMacHtcHe", false),
                heMacBsr = args.getBoolean("heMacBsr", false),
                heMacOmControl = args.getBoolean("heMacOmControl", false),
                heMacMaxAmpduExp = args.getString("heMacMaxAmpduExp", ""),
                heMacAmsduInAmpdu = args.getBoolean("heMacAmsduInAmpdu", false),
                heMacOmUlMuDataDisableRx = args.getBoolean("heMacOmUlMuDataDisableRx", false),

                vhtCapabilities = args.getString("vhtCapabilities", ""),
                vhtFeaturesRaw = args.getString("vhtFeaturesRaw", ""),
                vhtMaxMpdu = args.getString("vhtMaxMpdu", ""),
                vhtSupportedChannelWidth = args.getString("vhtSupportedChannelWidth", ""),
                vhtRxMcs = args.getString("vhtRxMcs", ""),
                vhtTxMcs = args.getString("vhtTxMcs", ""),
                vhtRxHighestSupported = args.getString("vhtRxHighestSupported", ""),
                vhtTxHighestSupported = args.getString("vhtTxHighestSupported", ""),
                vhtExtendedNss = args.getString("vhtExtendedNss", ""),
                vhtOpChannelWidth = args.getString("vhtOpChannelWidth", ""),
                vhtOpCenterFreq1 = args.getString("vhtOpCenterFreq1", ""),
                vhtOpCenterFreq2 = args.getString("vhtOpCenterFreq2", ""),
                vhtOpBasicMcs = args.getString("vhtOpBasicMcs", ""),

                heOpParameters = args.getString("heOpParameters", ""),
                heOpDefaultPeDuration = args.getString("heOpDefaultPeDuration", ""),
                heOpTxopDurationRts = args.getString("heOpTxopDurationRts", ""),
                heOpCoHostedBss = args.getBoolean("heOpCoHostedBss", false),
                heOpErSuDisable = args.getBoolean("heOpErSuDisable", false),
                heOpBssColor = args.getString("heOpBssColor", ""),
                heOpBasicMcsSet = args.getString("heOpBasicMcsSet", ""),
                heOpMaxCoHostedBssid = args.getString("heOpMaxCoHostedBssid", ""),
                heOpVhtInfoPresent = args.getBoolean("heOpVhtInfoPresent", false),
                heOpVhtInfo = args.getString("heOpVhtInfo", ""),

                heMacAckEnabledAggregation = args.getBoolean("heMacAckEnabledAggregation", false),
                heMacTwtResponder = args.getBoolean("heMacTwtResponder", false),
                heMacDynamicBaFragmentation = args.getString("heMacDynamicBaFragmentation", ""),
                heMacMinPayload128 = args.getBoolean("heMacMinPayload128", false),
                heMacRxControlFrameMultiBss = args.getBoolean("heMacRxControlFrameMultiBss", false),
                heMacBqr = args.getBoolean("heMacBqr", false),

                hePhyDeviceClass = args.getString("hePhyDeviceClass", ""),
                hePhyStbcTx80 = args.getBoolean("hePhyStbcTx80", false),
                hePhyStbcRx80 = args.getBoolean("hePhyStbcRx80", false),
                hePhyDcmMaxConstellation = args.getString("hePhyDcmMaxConstellation", ""),
                hePhyFullBwUlMuMimo = args.getBoolean("hePhyFullBwUlMuMimo", false),
                hePhyPartialBwUlMuMimo = args.getBoolean("hePhyPartialBwUlMuMimo", false),
                hePhyPartialBwExtendedRange = args.getBoolean("hePhyPartialBwExtendedRange", false),
                hePhy20In40Mhz = args.getBoolean("hePhy20In40Mhz", false),
                hePhyHe40He80 = args.getBoolean("hePhyHe40He80", false),
                hePhyErSuPpdu4Ltf = args.getBoolean("hePhyErSuPpdu4Ltf", false),
                hePhyErSuPpdu1Ltf = args.getBoolean("hePhyErSuPpdu1Ltf", false),

                operatingClasses = args.getString("operatingClasses", ""),
                apChannelReportClass = args.getString("apChannelReportClass", ""),
                apChannelReportChannels = args.getString("apChannelReportChannels", ""),
                txPowerEnvelope20 = args.getString("txPowerEnvelope20", ""),
                txPowerEnvelope40 = args.getString("txPowerEnvelope40", ""),
                txPowerEnvelope80 = args.getString("txPowerEnvelope80", ""),
                txPowerEnvelope160 = args.getString("txPowerEnvelope160", "")
            )
            rawInterfaceName = args.getString("interface", "wlan0")
        }
    }

    private fun setupViews() {
        binding.buttonCopyBssid.setOnClickListener {
            copyToClipboard(getString(R.string.iw_wifi_bssid), network.bssid)
        }

        binding.buttonShowRawData.setOnClickListener {
            val isHidden = binding.textRawData.visibility == View.GONE
            if (isHidden) {
                binding.buttonShowRawData.text = getString(R.string.iw_wifi_hide_raw_data)
                binding.textRawData.visibility = View.VISIBLE
                binding.rowRawButtons.visibility = View.VISIBLE

                if (binding.textRawData.text.isEmpty()) {
                    fetchRawData()
                }
            } else {
                binding.buttonShowRawData.text = getString(R.string.iw_wifi_show_raw_data)
                binding.textRawData.text = ""
                binding.textRawData.visibility = View.GONE
                binding.rowRawButtons.visibility = View.GONE
            }
        }

        binding.buttonCopyRawData.setOnClickListener {
            copyToClipboard(
                getString(R.string.iw_wifi_raw_data),
                binding.textRawData.text.toString()
            )
        }

        binding.buttonSaveRawData.setOnClickListener {
            saveRawDataToFile()
        }

        binding.buttonClose.setOnClickListener {
            dismiss()
        }
    }

    private fun displayNetworkInfo() {
        binding.apply {
            textSsid.text = if (network.isHidden) {
                getString(R.string.iw_wifi_hidden_network)
            } else {
                network.ssid
            }

            textBssid.text = network.bssid


            if (network.frequency.isNotEmpty()) {
                textFrequency.text = network.frequency
                textFrequency.visibility = View.VISIBLE
            } else {
                textFrequency.visibility = View.GONE
            }


            if (network.channel.isNotEmpty()) {
                textChannel.text = network.channel
                rowChannel.visibility = View.VISIBLE
            } else {
                rowChannel.visibility = View.GONE
            }

            if (network.band.isNotEmpty()) {
                textBand.text = network.band
                rowBand.visibility = View.VISIBLE
            } else {
                rowBand.visibility = View.GONE
            }

            textSecurityType.text = network.securityType
            textSignal.text = network.signal


            updateSignalIndicator(network.signalStrength)


            setRowText(binding.rowGroupCipher, R.string.iw_wifi_group_cipher, network.groupCipher)
            setRowText(
                binding.rowPairwiseCipher,
                R.string.iw_wifi_pairwise_cipher,
                network.pairwiseCipher
            )
            setRowText(binding.rowAuthSuite, R.string.iw_wifi_auth_suite, network.authSuite)


            textWpsStatus.text = when {
                network.wpsLocked -> getString(R.string.iw_wifi_wps_locked)
                network.wpsEnabled -> getString(R.string.iw_wifi_wps_available)
                else -> getString(R.string.iw_wifi_wps_not_supported)
            }


            setRowText(
                binding.rowDeviceInfo,
                R.string.iw_wifi_manufacturer,
                network.wpsManufacturer
            )
            setRowText(
                binding.rowModelInfo,
                R.string.iw_wifi_model,
                "${network.wpsModel.ifEmpty { network.wpsModelNumber }}"
            )
            setRowText(
                binding.rowSerialInfo,
                R.string.iw_wifi_serial_number,
                network.wpsSerialNumber
            )
            setRowText(binding.rowUuidInfo, R.string.iw_wifi_uuid, network.wpsUuid)
            setRowText(
                binding.rowDeviceNameInfo,
                R.string.iw_wifi_device_name,
                network.wpsDeviceName
            )
            setRowText(
                binding.rowConfigMethodsInfo,
                R.string.iw_wifi_config_methods,
                network.wpsConfigMethods
            )


            binding.cardDevice.visibility =
                if (network.wpsManufacturer.isEmpty() && network.wpsModel.isEmpty()) View.GONE else View.VISIBLE


            val countryText =
                if (network.country.isNotEmpty() && network.environment.isNotEmpty()) {
                    "${network.country} (${network.environment})"
                } else if (network.country.isNotEmpty()) {
                    network.country
                } else {
                    ""
                }

            setRowText(binding.rowCountryInfo, R.string.iw_wifi_country, countryText)
            setRowText(
                binding.rowRatesInfo,
                R.string.iw_wifi_supported_rates,
                network.supportedRates
            )
            setRowText(
                binding.rowExtRatesInfo,
                R.string.iw_wifi_extended_rates,
                network.extendedRates
            )
            setRowText(
                binding.rowChannelsInfo,
                R.string.iw_wifi_available_channels,
                network.channelsAvailable
            )
            setRowText(
                binding.rowPowerInfo,
                R.string.iw_wifi_power_constraint,
                network.powerConstraint
            )
            setRowText(binding.rowTxPowerInfo, R.string.iw_wifi_tx_power, network.txPower)

            setRowText(binding.rowTpcTxPower, R.string.iw_wifi_tpc_tx_power, network.tpcTxPower)
            setRowText(
                binding.rowBeaconInfo,
                R.string.iw_wifi_beacon_interval,
                network.beaconInterval
            )
            setRowText(binding.rowDtimInfo, R.string.iw_wifi_dtim_period, network.dtimPeriod)
            setRowText(binding.rowDtimCount, R.string.iw_wifi_dtim_count, network.dtimCount)
            setRowText(
                binding.rowAssociated,
                R.string.iw_wifi_associated,
                if (network.associated) getString(R.string.yes) else ""
            )
            setRowText(
                binding.rowProbeResponse,
                R.string.iw_wifi_probe_response,
                if (network.probeResponse) getString(R.string.iw_probe_response) else ""
            )


            binding.cardNetwork.visibility = if (
                countryText.isNotEmpty() || network.supportedRates.isNotEmpty() ||
                network.extendedRates.isNotEmpty() || network.channelsAvailable.isNotEmpty() ||
                network.powerConstraint.isNotEmpty() || network.txPower.isNotEmpty() ||
                network.tpcTxPower.isNotEmpty() || network.beaconInterval.isNotEmpty() ||
                network.dtimPeriod.isNotEmpty() || network.dtimCount.isNotEmpty()
            ) View.VISIBLE else View.GONE


            if (network.stationCount.isNotEmpty()) {
                binding.textStationCount.text = network.stationCount
                binding.textStationCount.visibility = View.VISIBLE
            } else {
                binding.textStationCount.visibility = View.GONE
            }
            if (network.channelUtilisation.isNotEmpty()) {
                binding.textChannelUtil.text = network.channelUtilisation
                binding.textChannelUtil.visibility = View.VISIBLE
            } else {
                binding.textChannelUtil.visibility = View.GONE
            }
            if (network.admissionCapacity.isNotEmpty()) {
                binding.textAdmissionCap.text = network.admissionCapacity
                binding.textAdmissionCap.visibility = View.VISIBLE
            } else {
                binding.textAdmissionCap.visibility = View.GONE
            }

            binding.cardBssLoad.visibility =
                if (network.stationCount.isEmpty() && network.channelUtilisation.isEmpty() && network.admissionCapacity.isEmpty()) View.GONE else View.VISIBLE


            setRowText(
                binding.rowHtCaps,
                R.string.iw_wifi_ht_caps,
                network.htFeaturesRaw.ifEmpty { network.htCapabilities.ifEmpty { network.htCapabilitiesCapab } })
            setRowText(
                binding.rowHtChannelInfo,
                R.string.iw_wifi_ht_channel_width,
                network.htChannelWidth
            )
            setRowText(
                binding.rowHtProtectionInfo,
                R.string.iw_wifi_ht_protection,
                network.htProtection
            )
            setRowText(binding.rowMcsInfo, R.string.iw_wifi_mcs, network.htMcs)
            setRowText(binding.rowHeCaps, R.string.iw_wifi_he_caps, network.heCapabilities)
            setRowText(
                binding.rowWmmInfo,
                R.string.iw_wifi_wmm,
                if (network.wmmPresent) getString(R.string.iw_wmm_enabled) else ""
            )
            setRowText(
                binding.rowExtCaps,
                R.string.iw_wifi_ext_caps,
                network.extCapabilities.trim()
            )
            setRowText(binding.rowOrigCaps, R.string.iw_wifi_orig_caps, network.capabilities)


            setRowText(binding.rowTsf, R.string.iw_wifi_tsf, network.tsf)
            binding.cardTsf.visibility = if (network.tsf.isNotEmpty()) View.VISIBLE else View.GONE


            if (network.rmCapabilities.isNotEmpty()) {
                binding.rowRmCapabilities.root.visibility = View.VISIBLE
                binding.rowRmCapabilities.rowValue.text = network.rmCapabilities
                binding.rowRmCapabilities.rowLabel.text =
                    getString(R.string.iw_wifi_rm_capabilities)
                binding.rowRmCapabilities.rowCopyButton.setOnClickListener {
                    copyToClipboard(
                        getString(R.string.iw_wifi_rm_capabilities),
                        network.rmCapabilities
                    )
                }
            } else {
                binding.rowRmCapabilities.root.visibility = View.GONE
            }
            setRowText(binding.rowRmHex, R.string.iw_wifi_rm_hex, network.rmCapabilitiesHex)
            setRowText(
                binding.rowRmMeasurementPilot,
                R.string.iw_wifi_rm_pilot,
                network.rmMeasurementPilotCap
            )
            setRowText(
                binding.rowRmNonOpChannel,
                R.string.iw_wifi_rm_non_op_channel,
                network.rmNonOpChannelMaxDur
            )

            binding.textRmLink.text = if (network.rmLinkMeasurement) getString(R.string.yes) else getString(R.string.no)
            binding.rowRmLink.visibility =
                if (network.rmLinkMeasurement) View.VISIBLE else View.GONE

            binding.textRmNeighbor.text = if (network.rmNeighborReport) getString(R.string.yes) else getString(R.string.no)
            binding.rowRmNeighbor.visibility =
                if (network.rmNeighborReport) View.VISIBLE else View.GONE

            binding.textRmFtm.text = if (network.ftmRangeReport) getString(R.string.yes) else getString(R.string.no)
            binding.rowRmFtm.visibility = if (network.ftmRangeReport) View.VISIBLE else View.GONE

            binding.cardRm.visibility =
                if (network.rmCapabilities.isNotEmpty() || network.rmCapabilitiesHex.isNotEmpty()) View.VISIBLE else View.GONE


            setRowText(
                binding.rowNetworkOptions,
                R.string.iw_wifi_network_options,
                network.networkOptions
            )
            setRowText(binding.rowNetworkType, R.string.iw_wifi_network_type, network.networkType)
            setRowText(
                binding.rowAnqp,
                R.string.iw_wifi_anqp,
                if (network.anqpAvailable) getString(R.string.iw_available) else ""
            )
            setRowText(
                binding.rowQueryResponse,
                R.string.iw_wifi_query_response,
                network.queryResponseLength
            )

            binding.card11u.visibility = if (network.interworking) View.VISIBLE else View.GONE


            setRowText(binding.rowOpClass, R.string.iw_wifi_operating_class, network.operatingClass)
            setRowText(
                binding.rowOperatingClasses,
                R.string.iw_wifi_operating_classes,
                network.operatingClasses
            )
            setRowText(
                binding.rowApChannelReportClass,
                R.string.iw_wifi_ap_channel_report_class,
                network.apChannelReportClass
            )
            setRowText(
                binding.rowApChannelReportChannels,
                R.string.iw_wifi_ap_channel_report_channels,
                network.apChannelReportChannels
            )
            setRowText(
                binding.rowTxPowerEnvelope,
                R.string.iw_wifi_tx_power_envelope,
                network.txPowerEnvelope
            )
            setRowText(
                binding.rowTxPowerEnvelope20,
                R.string.iw_wifi_tx_power_envelope20,
                network.txPowerEnvelope20
            )
            setRowText(
                binding.rowTxPowerEnvelope40,
                R.string.iw_wifi_tx_power_envelope40,
                network.txPowerEnvelope40
            )
            setRowText(
                binding.rowTxPowerEnvelope80,
                R.string.iw_wifi_tx_power_envelope80,
                network.txPowerEnvelope80
            )
            setRowText(
                binding.rowTxPowerEnvelope160,
                R.string.iw_wifi_tx_power_envelope160,
                network.txPowerEnvelope160
            )
            setRowText(
                binding.rowPowerConstraint,
                R.string.iw_wifi_power_constraint,
                network.powerConstraint
            )
            setRowText(binding.rowTxPower, R.string.iw_wifi_tx_power, network.txPower)

            binding.cardOpClass.visibility = if (
                network.operatingClass.isNotEmpty() || network.operatingClasses.isNotEmpty() ||
                network.apChannelReportClass.isNotEmpty() ||
                network.apChannelReportChannels.isNotEmpty() ||
                network.txPowerEnvelope.isNotEmpty() || network.powerConstraint.isNotEmpty() ||
                network.txPower.isNotEmpty() || network.txPowerEnvelope20.isNotEmpty() ||
                network.txPowerEnvelope40.isNotEmpty() || network.txPowerEnvelope80.isNotEmpty() ||
                network.txPowerEnvelope160.isNotEmpty()
            ) View.VISIBLE else View.GONE


            setRowText(
                binding.rowHeMacCaps,
                R.string.iw_wifi_he_mac_caps,
                network.heMacCapabilities
            )
            setRowText(
                binding.rowHePhyCaps,
                R.string.iw_wifi_he_phy_caps,
                network.hePhyCapabilities
            )
            setRowText(binding.rowHeRxMcs, R.string.iw_wifi_he_rx_mcs, network.heRcMcs)
            setRowText(binding.rowHeTxMcs, R.string.iw_wifi_he_tx_mcs, network.heTcMcs)
            setRowText(binding.rowHePpe, R.string.iw_wifi_he_ppe, network.hePpeThreshold)


            val hePhyFeatures = buildHePhyFeaturesString(network)
            if (hePhyFeatures.isNotEmpty()) {
                setRowText(
                    binding.rowHePhyFeatures,
                    R.string.iw_wifi_he_phy_features,
                    hePhyFeatures
                )
            }


            val heMacFeatures = buildHeMacFeaturesString(network)
            if (heMacFeatures.isNotEmpty()) {
                setRowText(
                    binding.rowHeMacFeatures,
                    R.string.iw_wifi_he_mac_features,
                    heMacFeatures
                )
            }


            setRowText(
                binding.rowHeOpParameters,
                R.string.iw_wifi_he_op_parameters,
                network.heOpParameters
            )
            setRowText(
                binding.rowHeOpDefaultPe,
                R.string.iw_wifi_he_op_pe_duration,
                network.heOpDefaultPeDuration
            )
            setRowText(
                binding.rowHeOpTxopRts,
                R.string.iw_wifi_he_op_txop_rts,
                network.heOpTxopDurationRts
            )
            setRowText(
                binding.rowHeOpCoHosted,
                R.string.iw_wifi_he_op_co_hosted,
                if (network.heOpCoHostedBss) getString(R.string.yes) else ""
            )
            setRowText(
                binding.rowHeOpErSuDisable,
                R.string.iw_wifi_he_op_er_su_disable,
                if (network.heOpErSuDisable) getString(R.string.yes) else ""
            )
            setRowText(
                binding.rowHeOpBssColor,
                R.string.iw_wifi_he_op_bss_color,
                network.heOpBssColor
            )
            setRowText(
                binding.rowHeOpBasicMcs,
                R.string.iw_wifi_he_op_basic_mcs,
                network.heOpBasicMcsSet
            )
            setRowText(
                binding.rowHeOpMaxCoHosted,
                R.string.iw_wifi_he_op_max_co_hosted,
                network.heOpMaxCoHostedBssid
            )
            setRowText(
                binding.rowHeOpVhtInfo,
                R.string.iw_wifi_he_op_vht_info,
                if (network.heOpVhtInfoPresent) {
                    network.heOpVhtInfo.ifEmpty { getString(R.string.iw_present) }
                } else {
                    network.heOpVhtInfo
                }
            )

            binding.cardHeDetailed.visibility = if (
                network.heMacCapabilities.isNotEmpty() || network.hePhyCapabilities.isNotEmpty() ||
                network.hePhySuBeamformer || network.hePhySuBeamformee || network.hePhyMuBeamformer ||
                network.heOpParameters.isNotEmpty() || network.heOpBssColor.isNotEmpty() ||
                network.heOpBasicMcsSet.isNotEmpty()
            ) View.VISIBLE else View.GONE


            setRowText(
                binding.rowVhtCaps,
                R.string.iw_wifi_vht_caps,
                network.vhtCapabilities
            )
            val vhtFeatures = buildVhtFeaturesString(network)
            setRowText(
                binding.rowVhtFeatures,
                R.string.iw_wifi_vht_features,
                vhtFeatures
            )
            setRowText(
                binding.rowVhtRxMcs,
                R.string.iw_wifi_vht_rx_mcs,
                network.vhtRxMcs
            )
            setRowText(
                binding.rowVhtTxMcs,
                R.string.iw_wifi_vht_tx_mcs,
                network.vhtTxMcs
            )
            setRowText(
                binding.rowVhtOpChannelWidth,
                R.string.iw_wifi_vht_op_channel_width,
                network.vhtOpChannelWidth
            )
            setRowText(
                binding.rowVhtOpCenterFreq1,
                R.string.iw_wifi_vht_op_center_freq1,
                network.vhtOpCenterFreq1
            )
            setRowText(
                binding.rowVhtOpCenterFreq2,
                R.string.iw_wifi_vht_op_center_freq2,
                network.vhtOpCenterFreq2
            )
            setRowText(
                binding.rowVhtOpBasicMcs,
                R.string.iw_wifi_vht_op_basic_mcs,
                network.vhtOpBasicMcs
            )

            binding.cardVht.visibility = if (
                network.vhtCapabilities.isNotEmpty() || network.vhtFeaturesRaw.isNotEmpty() ||
                network.vhtRxMcs.isNotEmpty() || network.vhtTxMcs.isNotEmpty() ||
                network.vhtOpChannelWidth.isNotEmpty()
            ) View.VISIBLE else View.GONE


            setRowText(binding.rowHtAmpduMax, R.string.iw_wifi_ht_ampdu_max, network.htAmpduMaxLen)
            setRowText(
                binding.rowHtAmpduSpacing,
                R.string.iw_wifi_ht_ampdu_spacing,
                network.htAmpduMinSpacing
            )
            setRowText(binding.rowHtTxMcs, R.string.iw_wifi_ht_tx_mcs, network.htTxMcs)

            binding.cardHtAmpdu.visibility = if (
                network.htAmpduMaxLen.isNotEmpty() || network.htAmpduMinSpacing.isNotEmpty() || network.htTxMcs.isNotEmpty()
            ) View.VISIBLE else View.GONE


            setRowText(
                binding.rowObssPassiveDwell,
                R.string.iw_wifi_obss_passive_dwell,
                network.obssPassiveDwell
            )
            setRowText(
                binding.rowObssActiveDwell,
                R.string.iw_wifi_obss_active_dwell,
                network.obssActiveDwell
            )
            setRowText(
                binding.rowObssScanInterval,
                R.string.iw_wifi_obss_scan_interval,
                network.obssScanInterval
            )
            setRowText(
                binding.rowObssDelayFactor,
                R.string.iw_wifi_obss_delay_factor,
                network.obssChannelDelayFactor
            )
            setRowText(
                binding.rowObssThreshold,
                R.string.iw_wifi_obss_threshold,
                network.obssScanThreshold
            )

            binding.cardObss.visibility = if (
                network.obssPassiveDwell.isNotEmpty() || network.obssActiveDwell.isNotEmpty() ||
                network.obssScanInterval.isNotEmpty() || network.obssChannelDelayFactor.isNotEmpty() ||
                network.obssScanThreshold.isNotEmpty()
            ) View.VISIBLE else View.GONE


            setRowText(binding.rowRifs, R.string.iw_wifi_rifs, network.rifs)
            setRowText(binding.rowNonGf, R.string.iw_wifi_non_gf, network.nonGfPresent)
            setRowText(binding.rowObssNonGf, R.string.iw_wifi_obss_non_gf, network.obssNonGfPresent)
            setRowText(binding.rowLsigTxop, R.string.iw_wifi_lsig_txop, network.lsigTxopProtect)
            setRowText(
                binding.rowErpProtection,
                R.string.iw_wifi_erp_protection,
                network.erpProtection
            )

            binding.cardHtOp.visibility = if (
                network.rifs.isNotEmpty() || network.nonGfPresent.isNotEmpty() ||
                network.obssNonGfPresent.isNotEmpty() || network.lsigTxopProtect.isNotEmpty() ||
                network.erpProtection.isNotEmpty()
            ) View.VISIBLE else View.GONE


            val hasCaps =
                network.htCapabilities.isNotEmpty() || network.heCapabilities.isNotEmpty() || network.capabilities.isNotEmpty()
            binding.cardCaps.visibility = if (hasCaps) View.VISIBLE else View.GONE
        }
    }

    private fun updateSignalIndicator(signalDbm: Int) {
        binding.apply {
            val progress = computeSignalProgress(signalDbm)
            val (label, drawableRes) = computeSignalLabel(signalDbm)

            progressSignal.progress = progress
            textSignalStrength.text = label

            signalBarIndicator.setBackgroundResource(drawableRes)


            val tintColor = when {
                signalDbm >= -50 -> R.color.green_500
                signalDbm >= -60 -> R.color.green_500
                signalDbm >= -70 -> R.color.blue_500
                signalDbm >= -80 -> R.color.orange_500
                else -> R.color.red_500
            }
            progressSignal.progressTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), tintColor)
            )
        }
    }

    private fun computeSignalProgress(dbm: Int): Int {
        return when {
            dbm >= -50 -> 100
            dbm >= -60 -> 75
            dbm >= -70 -> 50
            dbm >= -80 -> 25
            else -> 10
        }
    }

    private fun computeSignalLabel(dbm: Int): Pair<String, Int> {
        return when {
            dbm >= -50 -> getString(R.string.iw_wifi_signal_excellent) to R.drawable.bg_signal_excellent
            dbm >= -60 -> getString(R.string.iw_wifi_signal_good) to R.drawable.bg_signal_good
            dbm >= -70 -> getString(R.string.iw_wifi_signal_fair) to R.drawable.bg_signal_fair
            else -> getString(R.string.iw_wifi_signal_poor) to R.drawable.bg_signal_poor
        }
    }

    private fun setRowText(
        binding: ItemDetailRowBinding,
        labelRes: Int,
        value: String
    ) {
        if (value.isEmpty()) {
            binding.root.visibility = View.GONE
            return
        }
        binding.root.visibility = View.VISIBLE
        binding.rowLabel.text = getString(labelRes)
        binding.rowValue.text = value
        binding.rowCopyButton.setOnClickListener {
            copyToClipboard(getString(labelRes), value)
        }
    }

    private fun fetchRawData() {
        val bssid = network.bssid
        val interfaceName = rawInterfaceName.takeIf { it.isNotBlank() } ?: "wlan0"

        if (bssid.isBlank()) {
            Log.w(TAG, "No BSSID to fetch raw data for")
            return
        }

        lifecycleScope.launch {
            binding.textRawData.text = getString(R.string.iw_wifi_fetching_details)

            val rawOutput = withContext(Dispatchers.IO) {
                if (isNativeEnabled()) {
                    nativeWifiHelper.getRawScanForBssid(interfaceName, bssid)
                } else {
                    iwWifiManager.getRawScanForBssid(interfaceName, bssid)
                }
            }

            activity?.runOnUiThread {
                if (!rawOutput.isNullOrBlank()) {
                    binding.textRawData.text = rawOutput
                } else {
                    binding.textRawData.text = getString(R.string.iw_wifi_raw_data_unavailable)
                }
            }
        }
    }

    private fun isNativeEnabled(): Boolean {
        return requireContext()
            .getSharedPreferences("iw_scanner_prefs", Context.MODE_PRIVATE)
            .getBoolean("use_native_iw", false)
    }

    private fun saveRawDataToFile() {
        val rawText = binding.textRawData.text.toString()
        if (rawText.isEmpty()) {
            Toast.makeText(
                requireContext(),
                R.string.iw_wifi_raw_data_unavailable,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val bssid = network.bssid.takeIf { it.isNotEmpty() } ?: "unknown"
        val safeBssid = bssid.replace(":", "_")
        val fileName = "iw_scan_${safeBssid}_${System.currentTimeMillis()}.txt"

        saveLauncher?.launch(fileName)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(
            requireContext(),
            getString(R.string.iw_wifi_copied_to_clipboard),
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun buildHePhyFeaturesString(network: IwWifiNetwork): String {
        val band = mutableListOf<String>()
        if (network.hePhyHe40) band.add("HE40/2.4GHz")
        if (network.hePhyHe40He80) band.add("HE40/HE80/5GHz")
        if (network.hePhy242ToneRu) band.add("242 tone RUs/2.4GHz")
        if (network.hePhyDeviceClass.isNotEmpty()) band.add("Device Class: ${network.hePhyDeviceClass}")

        val phy = mutableListOf<String>()
        if (network.hePhyLdpcPayload) phy.add("LDPC Coding in Payload")
        if (network.hePhyNd4Ltf32Gi) phy.add("NDP with 4x HE-LTF and 3.2us GI")
        if (network.hePhyRxMuPpduNonAp) phy.add("Rx HE MU PPDU from Non-AP STA")
        if (network.hePhyStbcTx80) phy.add("STBC Tx <= 80MHz")
        if (network.hePhyStbcRx80) phy.add("STBC Rx <= 80MHz")
        if (network.hePhyFullBwUlMuMimo) phy.add("Full Bandwidth UL MU-MIMO")
        if (network.hePhyPartialBwUlMuMimo) phy.add("Partial Bandwidth UL MU-MIMO")
        if (network.hePhyPartialBwExtendedRange) phy.add("Partial Bandwidth Extended Range")
        if (network.hePhy20In40Mhz) phy.add("20MHz in 40MHz HE PPDU 2.4GHz")
        if (network.hePhyDcmMaxConstellation.isNotEmpty()) phy.add("DCM Max Constellation: ${network.hePhyDcmMaxConstellation}")
        if (network.hePhyErSuPpdu4Ltf) phy.add("HE ER SU PPDU 4x HE-LTF 0.8us GI")
        if (network.hePhyErSuPpdu1Ltf) phy.add("HE ER SU PPDU 1x HE-LTF 0.8us GI")
        if (network.hePhyTxPpdu4Ltf08Gi) phy.add("HE SU PPDU & HE PPDU 4x HE-LTF 0.8us GI")

        val beamforming = mutableListOf<String>()
        if (network.hePhySuBeamformer) beamforming.add("SU Beamformer")
        if (network.hePhySuBeamformee) beamforming.add("SU Beamformee")
        if (network.hePhyMuBeamformer) beamforming.add("MU Beamformer")
        if (network.hePhyBeamformeeSts80.isNotEmpty()) beamforming.add("Beamformee STS <= 80MHz: ${network.hePhyBeamformeeSts80}")
        if (network.hePhyBeamformeeSts80Plus.isNotEmpty()) beamforming.add("Beamformee STS > 80MHz: ${network.hePhyBeamformeeSts80Plus}")
        if (network.hePhySoundingDims80.isNotEmpty()) beamforming.add("Sounding Dimensions <= 80MHz: ${network.hePhySoundingDims80}")
        if (network.hePhySoundingDims80Plus.isNotEmpty()) beamforming.add("Sounding Dimensions > 80MHz: ${network.hePhySoundingDims80Plus}")
        if (network.hePhyNg.isNotEmpty()) beamforming.add("Ng = ${network.hePhyNg} SU Feedback")
        if (network.hePhyCodebookSu) beamforming.add("Codebook Size SU Feedback")
        if (network.hePhyTriggeredSuBf) beamforming.add("Triggered SU Beamforming Feedback")
        if (network.hePhyTriggeredCqi) beamforming.add("Triggered CQI Feedback")
        if (network.hePhyPpePresent) beamforming.add("PPE Threshold Present")
        if (network.hePhyMaxNc.isNotEmpty()) beamforming.add("Max NC: ${network.hePhyMaxNc}")

        val modulation = mutableListOf<String>()
        if (network.hePhyTx1024Qam) modulation.add("TX 1024-QAM")
        if (network.hePhyRx1024Qam) modulation.add("RX 1024-QAM")

        return joinFeatureGroups(listOf(band, phy, beamforming, modulation))
    }

    private fun buildHeMacFeaturesString(network: IwWifiNetwork): String {
        val core = mutableListOf<String>()
        if (network.heMacHtcHe) core.add("+HTC HE Supported")
        if (network.heMacBsr) core.add("BSR")
        if (network.heMacBqr) core.add("BQR")
        if (network.heMacOmControl) core.add("OM Control")
        if (network.heMacMaxAmpduExp.isNotEmpty()) core.add("Maximum A-MPDU Length Exponent: ${network.heMacMaxAmpduExp}")
        if (network.heMacAmsduInAmpdu) core.add("A-MSDU in A-MPDU")
        if (network.heMacOmUlMuDataDisableRx) core.add("OM Control UL MU Data Disable RX")

        val aggregation = mutableListOf<String>()
        if (network.heMacAckEnabledAggregation) aggregation.add("Ack-Enabled Aggregation")
        if (network.heMacTwtResponder) aggregation.add("TWT Responder")
        if (network.heMacDynamicBaFragmentation.isNotEmpty()) aggregation.add("Dynamic BA Fragmentation Level: ${network.heMacDynamicBaFragmentation}")
        if (network.heMacMinPayload128) aggregation.add("Minimum Payload size of 128 bytes")
        if (network.heMacRxControlFrameMultiBss) aggregation.add("RX Control Frame to MultiBSS")

        return joinFeatureGroups(listOf(core, aggregation))
    }

    private fun buildVhtFeaturesString(network: IwWifiNetwork): String {
        val caps = mutableListOf<String>()
        if (network.vhtMaxMpdu.isNotEmpty()) caps.add("Max MPDU length: ${network.vhtMaxMpdu}")
        if (network.vhtSupportedChannelWidth.isNotEmpty()) caps.add("Supported Channel Width: ${network.vhtSupportedChannelWidth}")

        val flags =
            if (network.vhtFeaturesRaw.isNotEmpty()) listOf(network.vhtFeaturesRaw) else emptyList()

        val rates = mutableListOf<String>()
        if (network.vhtRxHighestSupported.isNotEmpty()) rates.add("VHT RX highest supported: ${network.vhtRxHighestSupported}")
        if (network.vhtTxHighestSupported.isNotEmpty()) rates.add("VHT TX highest supported: ${network.vhtTxHighestSupported}")
        if (network.vhtExtendedNss.isNotEmpty()) rates.add("VHT extended NSS: ${network.vhtExtendedNss}")

        return joinFeatureGroups(listOf(caps, flags, rates))
    }

    private fun joinFeatureGroups(groups: List<List<String>>): String {
        val nonEmpty = groups.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return ""
        return nonEmpty.joinToString(GROUP_DIVIDER) { group -> group.joinToString("\n") }
    }
}
