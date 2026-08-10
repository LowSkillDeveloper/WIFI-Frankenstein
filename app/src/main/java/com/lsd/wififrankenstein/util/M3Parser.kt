package com.lsd.wififrankenstein.util

import com.lsd.wififrankenstein.util.M3Parser.M5M7_DWELL_MS
import java.io.BufferedReader





















object M3Parser {

    data class M3Data(
        val enroleeNonce: String,
        val ownPublicKey: String,
        val peerPublicKey: String,
        val authKey: String,
        val eHash1: String,
        val eHash2: String
    ) {
        fun toPixiewpsArgs(): String =
            "--pke $peerPublicKey --pkr $ownPublicKey --e-hash1 $eHash1 " +
                    "--e-hash2 $eHash2 --authkey $authKey --e-nonce $enroleeNonce"
    }





    sealed class M3Capture {
        data class Complete(val data: M3Data) : M3Capture()
        data class Incomplete(val capturedCount: Int, val missing: List<String>) : M3Capture()
    }






    data class M5M7Capture(
        val bssid: String = "",
        val m5Enc: String? = null,
        val m7Enc: String? = null
    ) {





        fun toPixiewpsMode3Args(m3: M3Data): String =
            "--mode 3 ${m3.toPixiewpsArgs()}" +
                    (m5Enc?.let { " -5 $it" } ?: "") +
                    (m7Enc?.let { " -7 $it" } ?: "")

        fun hasBoth(): Boolean = m5Enc != null && m7Enc != null
    }

    private const val TAG = "M3Parser"

    private const val FIELD_ENROL_NONCE = 0
    private const val FIELD_OWN_PUBLIC = 1
    private const val FIELD_PEER_PUBLIC = 2
    private const val FIELD_AUTH_KEY = 3
    private const val FIELD_EHASH1 = 4
    private const val FIELD_EHASH2 = 5

    private val FIELD_NAMES = arrayOf(
        "Enrollee Nonce",
        "DH own Public Key",
        "DH peer Public Key",
        "AuthKey",
        "E-Hash1",
        "E-Hash2"
    )



    private const val MIN_HEX_LENGTH = 32




    private const val M5M7_DWELL_MS = 4_000L



    private val KEY_VALUE = Regex("""([A-Z0-9]+)=([0-9a-fA-F]+)""")
    private val BSSID_VALUE = Regex("""BSSID=([0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5})""")
    private val ENCR_VALUE = Regex("""ENCR=([0-9a-fA-F]+)""")

    private val WPS_M3_PREFIX = "WPS-M3: "
    private const val M5_PREFIX = "WPS-M5:"
    private const val M7_PREFIX = "WPS-M7:"















    fun parse(
        reader: BufferedReader,
        timeoutMs: Long = 25_000L,
        cancelRequested: () -> Boolean = { false },
        onLine: ((String) -> Unit)? = null,
        onM5M7: ((M5M7Capture) -> Unit)? = null
    ): M3Capture {
        val fields = arrayOfNulls<String>(6)
        val deadline = System.currentTimeMillis() + timeoutMs
        var counted = 0
        var m5: String? = null
        var m7: String? = null
        var bssid = ""
        var m5m7Sent = false
        var completeReturnAt = Long.MAX_VALUE

        while (System.currentTimeMillis() < deadline &&
            System.currentTimeMillis() < completeReturnAt
        ) {
            if (cancelRequested()) {
                Log.d(TAG, "M3 capture cancelled by caller")
                return buildIncomplete(counted, fields)
            }
            try {
                if (!reader.ready()) {
                    Thread.sleep(50)
                    continue
                }
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                onLine?.invoke(line)


                if (line.startsWith(WPS_M3_PREFIX)) {
                    bssid = BSSID_VALUE.find(line)?.groupValues?.get(1) ?: bssid
                    parseM3Line(line, fields) { counted++ }
                    if (counted == 6) {
                        Log.d(TAG, "All 6 M3 fields captured (WPS-M3: line)")
                        completeReturnAt = armDwell(completeReturnAt)
                    }
                    continue
                }
                if (line.startsWith(M5_PREFIX)) {
                    m5 = extractEncr(line)
                    bssid = BSSID_VALUE.find(line)?.groupValues?.get(1) ?: bssid
                    Log.d(TAG, "WPS-M5: encr captured (len=${m5?.length ?: 0})")
                    m5m7Sent = maybeNotifyM5M7(onM5M7, m5m7Sent, bssid, m5, m7)
                    continue
                }
                if (line.startsWith(M7_PREFIX)) {
                    m7 = extractEncr(line)
                    bssid = BSSID_VALUE.find(line)?.groupValues?.get(1) ?: bssid
                    Log.d(TAG, "WPS-M7: encr captured (len=${m7?.length ?: 0})")
                    m5m7Sent = maybeNotifyM5M7(onM5M7, m5m7Sent, bssid, m5, m7)
                    continue
                }

                if (!line.contains("hexdump")) continue

                val field = when {
                    line.contains("Enrollee Nonce") -> FIELD_ENROL_NONCE
                    line.contains("DH own Public Key") -> FIELD_OWN_PUBLIC
                    line.contains("DH peer Public Key") -> FIELD_PEER_PUBLIC
                    line.contains("AuthKey") -> FIELD_AUTH_KEY
                    line.contains("E-Hash1") -> FIELD_EHASH1
                    line.contains("E-Hash2") -> FIELD_EHASH2
                    else -> -1
                }
                if (field == -1) continue
                if (fields[field] != null) continue

                val hex = cleanHex(line)
                if (hex == null || hex.length < MIN_HEX_LENGTH) {
                    Log.w(
                        TAG,
                        "M3 field $field captured but hex looks invalid (len=${hex?.length ?: 0})"
                    )
                    continue
                }
                fields[field] = hex
                counted++
                Log.d(TAG, "M3 field #$field captured (hex len=${hex.length})")
                if (counted == 6) {
                    Log.d(TAG, "All 6 M3 fields captured")
                    completeReturnAt = armDwell(completeReturnAt)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reading M3 fields", e)
                return buildIncomplete(counted, fields)
            }
        }

        Log.d(TAG, "M3 capture ended: $counted/6")
        return if (counted == 6) {
            M3Capture.Complete(buildResult(fields))
        } else {
            buildIncomplete(counted, fields)
        }
    }






    private fun armDwell(completeReturnAt: Long): Long {
        if (completeReturnAt != Long.MAX_VALUE) return completeReturnAt
        val updated = System.currentTimeMillis() + M5M7_DWELL_MS
        Log.d(TAG, "Dwelling ${M5M7_DWELL_MS}ms for WPS-M5/M7 (P4) after M3...")
        return updated
    }





    private fun maybeNotifyM5M7(
        onM5M7: ((M5M7Capture) -> Unit)?,
        m5m7Sent: Boolean,
        bssid: String,
        m5: String?,
        m7: String?
    ): Boolean {
        if (onM5M7 == null || m5m7Sent || m5 == null || m7 == null) return m5m7Sent
        onM5M7(M5M7Capture(bssid = bssid, m5Enc = m5, m7Enc = m7))
        return true
    }






    private fun parseM3Line(line: String, fields: Array<String?>, onCaptured: () -> Unit) {
        for (m in KEY_VALUE.findAll(line)) {
            val key = m.groupValues[1]
            val value = m.groupValues[2]
            val field = when (key) {
                "ENONCE" -> FIELD_ENROL_NONCE
                "PKE" -> FIELD_PEER_PUBLIC
                "PKR" -> FIELD_OWN_PUBLIC
                "AUTHKEY" -> FIELD_AUTH_KEY
                "EHASH1" -> FIELD_EHASH1
                "EHASH2" -> FIELD_EHASH2
                else -> -1
            }
            if (field == -1) continue
            if (value.length < MIN_HEX_LENGTH) {
                Log.w(TAG, "WPS-M3: field $key hex too short (len=${value.length})")
                continue
            }
            if (fields[field] != null) continue
            fields[field] = value
            onCaptured()
        }
    }






    private fun extractEncr(line: String): String? {
        return ENCR_VALUE.find(line)?.groupValues?.get(1)?.ifEmpty { null }
    }

    private fun buildIncomplete(counted: Int, fields: Array<String?>): M3Capture.Incomplete {
        val missing = mutableListOf<String>()
        for (i in fields.indices) {
            if (fields[i] == null) missing.add(FIELD_NAMES[i])
        }
        return M3Capture.Incomplete(capturedCount = counted, missing = missing)
    }

    private fun buildResult(fields: Array<String?>): M3Data = M3Data(
        enroleeNonce = fields[FIELD_ENROL_NONCE]!!,
        ownPublicKey = fields[FIELD_OWN_PUBLIC]!!,
        peerPublicKey = fields[FIELD_PEER_PUBLIC]!!,
        authKey = fields[FIELD_AUTH_KEY]!!,
        eHash1 = fields[FIELD_EHASH1]!!,
        eHash2 = fields[FIELD_EHASH2]!!
    )






    fun cleanHex(line: String): String? {
        val idx = line.indexOf("):")
        if (idx < 0) return null
        val hex = line.substring(idx + 3).replace("\\s".toRegex(), "")
        return hex.ifEmpty { null }
    }
}
