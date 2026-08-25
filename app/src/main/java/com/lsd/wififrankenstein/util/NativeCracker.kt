package com.lsd.wififrankenstein.util

object NativeCracker {

    private const val TAG = "NativeCracker"

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
            if (runSelfTest()) {
                loaded = true
                Log.i(TAG, "NativeCracker self-test PASSED")
            } else {
                loadError = "native/JVM crypto mismatch detected"
                Log.e(
                    TAG,
                    "NativeCracker self-test FAILED — disabling native backend, " +
                            "JVM fallback will be used"
                )
            }
        } catch (e: UnsatisfiedLinkError) {
            loadError = "${e.message}"
        } catch (e: Exception) {
            loadError = "${e.message}"
        }
    }

    private fun runSelfTest(): Boolean {
        return try {
            val password = "wififrankenstein-selftest-pw"
            val ssid = "selftest-ssid"
            val apMac = "aabbccddeeff"
            val staMac = "112233445566"

            val pmk = WpaCrypto.pbkdf2Sha1(
                password.toByteArray(Charsets.UTF_8),
                ssid.toByteArray(Charsets.UTF_8),
                4096,
                32
            )
            val pmkidData = "PMK Name".toByteArray(Charsets.US_ASCII) +
                    WpaCrypto.hexToBytes(apMac) +
                    WpaCrypto.hexToBytes(staMac)
            val pmkidHex = WpaCrypto.bytesToHex(WpaCrypto.hmacSha1(pmk, pmkidData).copyOf(16))

            val positiveOk = tryPasswordHex(
                password, ssid, apMac, staMac,
                "", "", pmkidHex, 2, 1
            )
            val negativeOk = !tryPasswordHex(
                "$password-x", ssid, apMac, staMac,
                "", "", pmkidHex, 2, 1
            )

            if (!positiveOk || !negativeOk) {
                Log.e(
                    TAG,
                    "self-test mismatch: positive=$positiveOk negative=$negativeOk"
                )
            }
            positiveOk && negativeOk
        } catch (t: Throwable) {
            Log.e(TAG, "self-test error", t)
            false
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

    external fun crackBatchMultiHex(
        passwords: Array<String>,
        ssid: String,
        macApArr: Array<String>,
        macStaArr: Array<String>,
        anonceArr: Array<String>,
        eapolArr: Array<String>,
        micArr: Array<String>,
        keyvers: IntArray,
        types: IntArray
    ): Int

    external fun debugPbkdf2Hex(password: String, ssid: String): String
}
