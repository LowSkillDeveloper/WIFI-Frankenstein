package com.lsd.wififrankenstein.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class AppleWLoc @OptIn(ExperimentalSerializationApi::class) constructor(
    @ProtoNumber(1) val unknown_value0: Long? = null,
    @ProtoNumber(2) val wifi_devices: List<WifiDevice> = emptyList(),
    @ProtoNumber(3) val unknown_value1: Int? = null,
    @ProtoNumber(4) val return_single_result: Int? = null,
    @ProtoNumber(5) val APIName: String? = null
)

@Serializable
data class WifiDevice @OptIn(ExperimentalSerializationApi::class) constructor(
    @ProtoNumber(1) val bssid: String,
    @ProtoNumber(2) val location: Location? = null
)

@Serializable
data class Location @OptIn(ExperimentalSerializationApi::class) constructor(
    @ProtoNumber(1) val latitude: Long? = null,
    @ProtoNumber(2) val longitude: Long? = null,
    @ProtoNumber(3) val unknown_value3: Long? = null,
    @ProtoNumber(4) val unknown_value4: Long? = null,
    @ProtoNumber(5) val unknown_value5: Long? = null,
    @ProtoNumber(6) val unknown_value6: Long? = null,
    @ProtoNumber(7) val unknown_value7: Long? = null,
    @ProtoNumber(8) val unknown_value8: Long? = null,
    @ProtoNumber(9) val unknown_value9: Long? = null,
    @ProtoNumber(10) val unknown_value10: Long? = null,
    @ProtoNumber(11) val unknown_value11: Long? = null,
    @ProtoNumber(12) val unknown_value12: Long? = null,
    @ProtoNumber(21) val unknown_value21: Long? = null
)