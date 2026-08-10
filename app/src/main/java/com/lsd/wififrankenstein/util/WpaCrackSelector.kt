package com.lsd.wififrankenstein.util

import android.os.Build

enum class CrackBackend(val priority: Int, val label: String) {
    JNI_NEON(100, "JNI+NEON"),
    JNI_SCALAR(50, "JNI+Scalar"),
    KOTLIN(10, "Kotlin"),
    NONE(0, "None")
}

object WpaCrackSelector {

    private val TAG = "WpaCrackSelector"
    private var _backend: CrackBackend = detect()
    val backend: CrackBackend get() = _backend

    val info: String
        get() {
            val cpu = when {
                isArm64() -> "ARM64"
                Build.SUPPORTED_32_BIT_ABIS.any { it.startsWith("arm") } -> "ARM32"
                else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
            }
            return "CPU: $cpu (${Runtime.getRuntime().availableProcessors()} cores)\n" +
                    "Backend: ${backend.label}\n" +
                    "Native: ${if (NativeCracker.isAvailable) "loaded" else "unavailable"}"
        }

    private fun detect(): CrackBackend {
        if (NativeCracker.isAvailable) {
            if (isArm64()) {
                Log.i(TAG, "ARM64 + native library — JNI+NEON backend")
                return CrackBackend.JNI_NEON
            }
            Log.i(TAG, "Native library loaded — JNI+Scalar backend")
            return CrackBackend.JNI_SCALAR
        }
        Log.i(TAG, "Native library unavailable — Kotlin fallback")
        return CrackBackend.KOTLIN
    }

    private fun isArm64(): Boolean =
        Build.SUPPORTED_64_BIT_ABIS.any { it.startsWith("arm64") }


    fun tryPassword(password: String, hash: HandshakeHash): Boolean {
        if (backend.priority >= CrackBackend.JNI_SCALAR.priority) {
            try {
                return nativeTryPassword(password, hash)
            } catch (e: Exception) {
                Log.e(TAG, "Native call failed: ${e.message}")
            }
        }
        return WpaCracker.tryPasswordAny(password, hash)
    }


    private fun nativeTryPassword(password: String, hash: HandshakeHash): Boolean {
        val macApHex = hash.macAp.replace(":", "")
        val macStaHex = hash.macSta.replace(":", "")
        val eapolHex = hash.eapol ?: ""
        val micHex = hash.pmkidOrMic
        val anonceHex = hash.anonce ?: ""
        val keyver = hash.keyver ?: 2

        val typeCode = when (hash.type) {
            HandshakeType.PMKID -> 1
            HandshakeType.EAPOL -> 2
            HandshakeType.PMKID_EAPOL -> 3
        }

        return NativeCracker.tryPasswordHex(
            password, hash.essid,
            macApHex, macStaHex, anonceHex,
            eapolHex, micHex, keyver, typeCode
        )
    }


    fun crackBatch(passwords: List<String>, hash: HandshakeHash): Int {
        if (backend.priority < CrackBackend.JNI_SCALAR.priority) {
            for ((i, pw) in passwords.withIndex()) {
                if (WpaCracker.tryPasswordAny(pw, hash)) return i
            }
            return -1
        }
        try {
            val macApHex = hash.macAp.replace(":", "")
            val macStaHex = hash.macSta.replace(":", "")
            val eapolHex = hash.eapol ?: ""
            val micHex = hash.pmkidOrMic
            val anonceHex = hash.anonce ?: ""
            val keyver = hash.keyver ?: 2
            val typeCode = when (hash.type) {
                HandshakeType.PMKID -> 1
                HandshakeType.EAPOL -> 2
                HandshakeType.PMKID_EAPOL -> 3
            }
            return NativeCracker.crackBatchHex(
                passwords.toTypedArray(), hash.essid,
                macApHex, macStaHex, anonceHex,
                eapolHex, micHex, keyver, typeCode
            )
        } catch (e: Exception) {
            Log.e(TAG, "Batch native error, falling back", e)
            for ((i, pw) in passwords.withIndex()) {
                if (WpaCracker.tryPasswordAny(pw, hash)) return i
            }
            return -1
        }
    }
}
