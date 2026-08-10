package com.lsd.wififrankenstein.util

object NativeCracker {


    const val BATCH_SIZE = 10


    @JvmStatic
    external fun benchmarkPbkdf2(iterations: Int): Long

    private var loaded = false
    private var loadError: String? = null

    val isAvailable: Boolean get() = loaded

    val availabilityInfo: String
        get() = when {
            loaded -> "Native C backend active"
            loadError != null -> "Native backend unavailable: $loadError"
            else -> "Native backend not loaded"
        }

    init {
        try {
            System.loadLibrary("cracker")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            loadError = "${e.message}"
        } catch (e: Exception) {
            loadError = "${e.message}"
        }
    }



    external fun tryPasswordHex(
        password: String,
        ssid: String,
        macApHex: String,
        macStaHex: String,
        anonceHex: String,
        eapolHex: String,
        micHex: String,
        keyver: Int,
        typeCode: Int
    ): Boolean


    external fun crackBatchHex(
        passwords: Array<String>,
        ssid: String,
        macApHex: String,
        macStaHex: String,
        anonceHex: String,
        eapolHex: String,
        micHex: String,
        keyver: Int,
        typeCode: Int
    ): Int
}
