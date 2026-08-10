package com.lsd.wififrankenstein.ui.iwwifi.models

data class IwInterface(
    val name: String,
    val type: String = "",
    val addr: String = ""
)





data class IwWifiNetwork(

    val ssid: String,
    val bssid: String,
    val frequency: String,
    val channel: String,
    val signal: String,
    val lastSeen: String,
    val beaconInterval: String,

    val associated: Boolean = false,

    val probeResponse: Boolean = false,

    val tsf: String = "",

    val capabilities: String,
    val securityType: String = "Unknown",
    val groupCipher: String = "",
    val pairwiseCipher: String = "",
    val authSuite: String = "",
    val wpaVersion: String = "",
    val rsnVersion: String = "",
    val wpsEnabled: Boolean,
    val wpsLocked: Boolean,

    val wpsVersion: String = "",
    val wpsDeviceType: String = "",
    val wpsDeviceName: String = "",
    val wpsManufacturer: String = "",
    val wpsModel: String = "",
    val wpsModelNumber: String = "",
    val wpsSerialNumber: String = "",
    val wpsUuid: String = "",
    val wpsConfigMethods: String = "",
    val wpsRfBands: String = "",
    val wpsResponseTypes: String = "",

    val band: String = "",
    val dtimPeriod: String = "",
    val dtimCount: String = "",
    val supportedRates: String = "",
    val extendedRates: String = "",
    val country: String = "",
    val countryEnv: String = "",
    val channelsAvailable: String = "",
    val powerConstraint: String = "",
    val txPower: String = "",

    val stationCount: String = "",
    val channelUtilisation: String = "",
    val admissionCapacity: String = "",

    val htCapabilities: String = "",
    val htCapabilitiesCapab: String = "",
    val htChannelWidth: String = "",
    val htSecondaryChannel: String = "",
    val htProtection: String = "",
    val htMcs: String = "",
    val htTxMcs: String = "",
    val htAmpduMaxLen: String = "",
    val htAmpduMinSpacing: String = "",

    val htRxLdpc: Boolean = false,
    val htHt20Ht40: Boolean = false,
    val htSmPowerSaveDisabled: Boolean = false,
    val htRxHt20Sgi: Boolean = false,
    val htRxHt40Sgi: Boolean = false,
    val htTxStbc: Boolean = false,
    val htRxStbc1Stream: Boolean = false,
    val htNoRxStbc: Boolean = false,
    val htMaxAmsduLen: String = "",
    val htDssCckHt40: Boolean = false,
    val htNoDssCckHt40: Boolean = false,

    val htFeaturesRaw: String = "",

    val vhtCapabilities: String = "",
    val vhtFeaturesRaw: String = "",
    val vhtMaxMpdu: String = "",
    val vhtSupportedChannelWidth: String = "",
    val vhtRxMcs: String = "",
    val vhtTxMcs: String = "",
    val vhtRxHighestSupported: String = "",
    val vhtTxHighestSupported: String = "",
    val vhtExtendedNss: String = "",

    val vhtOpChannelWidth: String = "",
    val vhtOpCenterFreq1: String = "",
    val vhtOpCenterFreq2: String = "",
    val vhtOpBasicMcs: String = "",

    val heOpParameters: String = "",
    val heOpDefaultPeDuration: String = "",
    val heOpTxopDurationRts: String = "",
    val heOpCoHostedBss: Boolean = false,
    val heOpErSuDisable: Boolean = false,
    val heOpBssColor: String = "",
    val heOpBasicMcsSet: String = "",
    val heOpMaxCoHostedBssid: String = "",
    val heOpVhtInfoPresent: Boolean = false,
    val heOpVhtInfo: String = "",

    val tpcTxPower: String = "",

    val environment: String = "",

    val hePhyHe40: Boolean = false,
    val hePhy242ToneRu: Boolean = false,
    val hePhyLdpcPayload: Boolean = false,
    val hePhyNd4Ltf32Gi: Boolean = false,
    val hePhyRxMuPpduNonAp: Boolean = false,
    val hePhySuBeamformer: Boolean = false,
    val hePhySuBeamformee: Boolean = false,
    val hePhyMuBeamformer: Boolean = false,
    val hePhyBeamformeeSts80: String = "",
    val hePhyBeamformeeSts80Plus: String = "",
    val hePhySoundingDims80: String = "",
    val hePhySoundingDims80Plus: String = "",
    val hePhyNg: String = "",
    val hePhyCodebookSu: Boolean = false,
    val hePhyTriggeredSuBf: Boolean = false,
    val hePhyTriggeredCqi: Boolean = false,
    val hePhyPpePresent: Boolean = false,
    val hePhyMaxNc: String = "",
    val hePhyTx1024Qam: Boolean = false,
    val hePhyRx1024Qam: Boolean = false,
    val hePhyTxPpdu4Ltf08Gi: Boolean = false,
    val hePhyDeviceClass: String = "",
    val hePhyStbcTx80: Boolean = false,
    val hePhyStbcRx80: Boolean = false,
    val hePhyDcmMaxConstellation: String = "",
    val hePhyFullBwUlMuMimo: Boolean = false,
    val hePhyPartialBwUlMuMimo: Boolean = false,
    val hePhyPartialBwExtendedRange: Boolean = false,
    val hePhy20In40Mhz: Boolean = false,
    val hePhyHe40He80: Boolean = false,
    val hePhyErSuPpdu4Ltf: Boolean = false,
    val hePhyErSuPpdu1Ltf: Boolean = false,

    val heMacHtcHe: Boolean = false,
    val heMacBsr: Boolean = false,
    val heMacOmControl: Boolean = false,
    val heMacMaxAmpduExp: String = "",
    val heMacAmsduInAmpdu: Boolean = false,
    val heMacOmUlMuDataDisableRx: Boolean = false,
    val heMacAckEnabledAggregation: Boolean = false,
    val heMacTwtResponder: Boolean = false,
    val heMacDynamicBaFragmentation: String = "",
    val heMacMinPayload128: Boolean = false,
    val heMacRxControlFrameMultiBss: Boolean = false,
    val heMacBqr: Boolean = false,

    val heCapabilities: String = "",
    val heMacCapabilities: String = "",
    val hePhyCapabilities: String = "",
    val heRcMcs: String = "",
    val heTcMcs: String = "",
    val hePpeThreshold: String = "",

    val rmCapabilities: String = "",
    val rmCapabilitiesHex: String = "",
    val rmLinkMeasurement: Boolean = false,
    val rmNeighborReport: Boolean = false,
    val rmBeaconPassive: Boolean = false,
    val rmBeaconActive: Boolean = false,
    val rmBeaconTable: Boolean = false,
    val rmChannelLoad: Boolean = false,
    val rmStatistics: Boolean = false,
    val rmFrameMeasurement: Boolean = false,
    val rmLCI: Boolean = false,
    val rmTransmitStream: Boolean = false,
    val ftmRangeReport: Boolean = false,
    val rmCivicLocation: Boolean = false,
    val rmMeasurementPilotCap: String = "",
    val rmNonOpChannelMaxDur: String = "",

    val operatingClass: String = "",
    val operatingClasses: String = "",

    val apChannelReportClass: String = "",
    val apChannelReportChannels: String = "",

    val extCapabilities: String = "",
    val extHtInfoExchange: Boolean = false,
    val extTfs: Boolean = false,
    val extWnmSleep: Boolean = false,
    val extTimBroadcast: Boolean = false,
    val extBssTransition: Boolean = false,
    val extOpModeNotification: Boolean = false,
    val extTwtResponder: Boolean = false,
    val extEcs: Boolean = false,

    val interworking: Boolean = false,
    val networkOptions: String = "",
    val networkType: String = "",
    val anqpAvailable: Boolean = false,
    val queryResponseLength: String = "",

    val txPowerEnvelope: String = "",
    val txPowerEnvelope20: String = "",
    val txPowerEnvelope40: String = "",
    val txPowerEnvelope80: String = "",
    val txPowerEnvelope160: String = "",

    val obssPassiveDwell: String = "",
    val obssActiveDwell: String = "",
    val obssScanInterval: String = "",
    val obssPassiveTotal: String = "",
    val obssActiveTotal: String = "",
    val obssChannelDelayFactor: String = "",
    val obssScanThreshold: String = "",

    val erpProtection: String = "",

    val staChannelWidth: String = "",
    val rifs: String = "",
    val nonGfPresent: String = "",
    val obssNonGfPresent: String = "",
    val dualBeacon: String = "",
    val dualCtsProtection: String = "",
    val stbcBeacon: String = "",
    val lsigTxopProtect: String = "",
    val pcoActive: String = "",
    val pcoPhase: String = "",

    val wmmPresent: Boolean = false,
    val wmmParams: String = "",

    val rawData: String
) {
    val isHidden: Boolean
        get() = ssid.isBlank() || ssid == "Hidden network"

    val signalStrength: Int
        get() = try {
            signal.trim().replace("dBm", "").trim().toDoubleOrNull()?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }

    val wpsStatus: String
        get() = when {
            wpsLocked -> "Locked"
            wpsEnabled -> "Available"
            else -> "Not supported"
        }
}

data class IwLinkInfo(
    val connected: Boolean = false,
    val ssid: String = "",
    val bssid: String = "",
    val frequency: String = "",
    val txBitrate: String = "",
    val rxBitrate: String = ""
)

data class IwDeviceInfo(
    val wiphy: String = "",
    val bands: List<String> = emptyList(),
    val supportedCiphers: List<String> = emptyList(),
    val maxScanSSIDs: String = ""
)
