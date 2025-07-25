package com.lsd.wififrankenstein.ui.wpagenerator

import android.content.Context
import android.util.Base64
import com.lsd.wififrankenstein.ui.wpagenerator.WpaResult.Companion.LIKELY_SUPPORTED
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import java.util.zip.ZipInputStream
import kotlin.collections.ArrayList

data class WpaResult(
    val keys: List<String>,
    val algorithm: String,
    val generationTime: Long,
    val supportState: Int = SUPPORTED
) {
    companion object {
        const val SUPPORTED = 2
        const val LIKELY_SUPPORTED = 2
        const val UNLIKELY_SUPPORTED = 1
        const val UNSUPPORTED = 0
    }
}

class WpaAlgorithmsHelper(private val context: Context) {

    companion object {
        const val SUPPORTED = 2
        const val UNLIKELY_SUPPORTED = 1
        const val UNSUPPORTED = 0
    }

    private var loadedAlice: List<AliceMagicInfo>? = null
    private var loadedCyta: List<CytaMagicInfo>? = null
    private var loadedCytaZTEs: Map<String, List<CytaMagicInfo>>? = null
    private var loadedNetfasters: List<NetfasterMagicInfo>? = null
    private var loadedOteHuawei: List<String>? = null
    private var loadedThomson: Map<String, List<String>>? = null
    private var loadedTele2: List<TeleTuMagicInfo>? = null

    init {
        loadResources()
    }

    fun generateKeys(ssid: String, bssid: String): List<WpaResult> {
        val results = mutableListOf<WpaResult>()
        val cleanSsid = ssid.trim()
        val cleanBssid = bssid.replace(":", "").uppercase()

        val algorithms = getAllAlgorithms()

        for (algorithm in algorithms) {
            val startTime = System.currentTimeMillis()

            try {
                val supportState = algorithm.getSupportState(cleanSsid, cleanBssid)
                if (supportState != UNSUPPORTED) {
                    val keys = algorithm.generateKeys(cleanSsid, cleanBssid)
                    val endTime = System.currentTimeMillis()

                    if (keys.isNotEmpty()) {
                        results.add(WpaResult(
                            keys = keys,
                            algorithm = algorithm.getName(),
                            generationTime = endTime - startTime,
                            supportState = supportState
                        ))
                    }
                }
            } catch (e: Exception) {
            }
        }

        return results.sortedByDescending { it.supportState }
    }

    private fun loadResources() {
        try {
            context.assets.open("magic_info.zip").use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val content = zip.readBytes()
                        when (entry.name) {
                            "alice.txt" -> loadedAlice = parseAliceMagicInfo(content)
                            "cyta.txt" -> loadedCyta = parseCytaMagicInfo(content)
                            "cyta_zte.txt" -> loadedCytaZTEs = parseCytaZTEMagicInfo(content)
                            "netfaster.txt" -> loadedNetfasters = parseNetfasterMagicInfo(content)
                            "ote_huawei.txt" -> loadedOteHuawei = parseOteHuaweiMagicValues(content)
                            "tele2.txt" -> loadedTele2 = parseTele2MagicInfo(content)
                        }
                        entry = zip.nextEntry
                    }
                }
            }

            val thomsonDict = mutableMapOf<String, List<String>>()
            context.assets.open("webdic.zip").use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name.endsWith(".txt")) {
                            val content = zip.readBytes()
                            parseDictionary(content, thomsonDict)
                        }
                        entry = zip.nextEntry
                    }
                }
            }

            context.assets.open("RouterKeygen.dic").use { input ->
                parseDictionary(input.readBytes(), thomsonDict)
            }

            if (thomsonDict.isNotEmpty()) {
                loadedThomson = thomsonDict
            }
        } catch (e: Exception) {
        }
    }

    private fun parseAliceMagicInfo(content: ByteArray): List<AliceMagicInfo> {
        return content.toString(Charsets.UTF_8).lines().mapNotNull {
            val parts = it.trim().split(",")
            if (parts.size == 3) {
                try {
                    AliceMagicInfo(parts[0], intArrayOf(parts[1].toInt(), parts[2].toInt()))
                } catch (e: NumberFormatException) {
                    null
                }
            } else null
        }.filter { it.serial.isNotEmpty() }
    }

    private fun parseCytaMagicInfo(content: ByteArray): List<CytaMagicInfo> {
        return content.toString(Charsets.UTF_8).lines().mapNotNull {
            val parts = it.trim().split(",")
            if (parts.size == 3) {
                try {
                    CytaMagicInfo(parts[0], parts[1].toLong(), parts[2].toLong())
                } catch (e: NumberFormatException) {
                    null
                }
            } else null
        }.filter { it.key.isNotEmpty() }
    }

    private fun parseCytaZTEMagicInfo(content: ByteArray): Map<String, List<CytaMagicInfo>> {
        val result = mutableMapOf<String, MutableList<CytaMagicInfo>>()
        content.toString(Charsets.UTF_8).lines().forEach {
            val parts = it.trim().split(",")
            if (parts.size == 4) {
                try {
                    val info = CytaMagicInfo(parts[1], parts[2].toLong(), parts[3].toLong())
                    result.getOrPut(parts[0]) { mutableListOf() }.add(info)
                } catch (e: NumberFormatException) {
                }
            }
        }
        return result
    }

    private fun parseNetfasterMagicInfo(content: ByteArray): List<NetfasterMagicInfo> {
        return content.toString(Charsets.UTF_8).lines().mapNotNull {
            val parts = it.trim().split(",")
            if (parts.size == 3) {
                try {
                    val divisors = parts[2].split(";").map { it.toInt() }.toIntArray()
                    NetfasterMagicInfo(parts[0], parts[1].toLong(), divisors)
                } catch (e: NumberFormatException) {
                    null
                }
            } else null
        }.filter { it.mac.isNotEmpty() }
    }

    private fun parseOteHuaweiMagicValues(content: ByteArray): List<String> {
        return content.toString(Charsets.UTF_8).lines().firstOrNull()?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
    }

    private fun parseTele2MagicInfo(content: ByteArray): List<TeleTuMagicInfo> {
        return content.toString(Charsets.UTF_8).lines().mapNotNull {
            val parts = it.trim().split(",")
            if (parts.size == 4) {
                try {
                    TeleTuMagicInfo(parts[0], parts[1], parts[2].toInt(), parts[3].toInt())
                } catch (e: NumberFormatException) {
                    null
                }
            } else null
        }.filter { it.serial.isNotEmpty() }
    }

    private fun parseDictionary(content: ByteArray, dict: MutableMap<String, List<String>>) {
        content.toString(Charsets.UTF_8).lines().forEach {
            val parts = it.trim().split(":")
            if (parts.size == 2) {
                val keys = parts[1].split(",").filter { key -> key.trim().isNotBlank() }
                if (keys.isNotEmpty()) {
                    dict[parts[0].uppercase().trim()] = keys.map { it.trim() }
                }
            }
        }
    }

    private fun getAllAlgorithms(): List<WpaAlgorithm> {
        return listOf(
            ArcadyanAlgorithm(),
            BelkinAlgorithm(),
            ArnetPirelliAlgorithm(),
            AliceGermanyAlgorithm(),
            CabovisaoSagemAlgorithm(),
            HuaweiAlgorithm(),
            InfostradaAlgorithm(),
            CytaZTEAlgorithm(loadedCytaZTEs),
            InterCableAlgorithm(),
            CytaAlgorithm(loadedCyta),
            HG824xAlgorithm(),
            ConnAlgorithm(),
            DiscusAlgorithm(),
            EircomAlgorithm(),
            DlinkAlgorithm(),
            OnoAlgorithm(),
            OteBAUDAlgorithm(),
            MeoPirelliAlgorithm(),
            NetFasterAlgorithm(loadedNetfasters),
            MegaredAlgorithm(),
            MaxcomAlgorithm(),
            OteHuaweiAlgorithm(loadedOteHuawei),
            ThomsonAlgorithm(loadedThomson),
            ComtrendAlgorithm(),
            AndaredAlgorithm(),
            AliceItalyAlgorithm(loadedAlice),
            AxtelAlgorithm(),
            TecomAlgorithm(),
            TplinkAlgorithm(),
            ZyxelAlgorithm(),
            SkyV1Algorithm(),
            PirelliAlgorithm(),
            TelseyAlgorithm(),
            VerizonAlgorithm(),
            WifimediaRAlgorithm(),
            PBSAlgorithm(),
            Wlan2Algorithm(),
            Wlan6Algorithm(),
            TeleTuAlgorithm(loadedTele2),
            Speedport500Algorithm(),
            SitecomX500Algorithm(),
            SitecomWLR341Algorithm(),
            Sitecom2100Algorithm(),
            UpcAlgorithm(),
            AlcatelLucentAlgorithm(),
            PtvAlgorithm(),
            NetgearAlgorithm(),
            LinksysAlgorithm(),
            AsusAlgorithm(),
            BuffaloAlgorithm(),
            LiveboxAlgorithm(),
            BboxAlgorithm(),
            TechnicolorAlgorithm(),
            SagemcomAlgorithm(),
            VodafoneAlgorithm(),
            BTHomeHubAlgorithm(),
            MovistarAlgorithm(),
            JazztelAlgorithm(),
            HuaweiE5Algorithm(),
            EEAlgorithm(),
            OteGenericAlgorithm(),
            FritzBoxAlgorithm(),
            ZTEAlgorithm(),
            TpLinkNewAlgorithm(),
            MikroTikAlgorithm(),
            UbiquitiAlgorithm(),
            AndroidHotspotAlgorithm(),
            IPhoneHotspotAlgorithm(),
            Huawei5GAlgorithm(),
            Speedport2Algorithm(),
            OperaAlgorithm(),
            Wind3Algorithm(),
            SercommAlgorithm(),
            ActiontecAlgorithm(),
            NetcommAlgorithm(),
            BillionAlgorithm(),
            SMCAlgorithm(),
            MotorolaAlgorithm(),
            ArrisAlgorithm(),
            GemtekAlgorithm(),
            BroadcomAlgorithm(),
            RealtekAlgorithm(),
            FreeboxAlgorithm(),
            SFRAlgorithm(),
            TelstraAlgorithm(),
            SkylinkAlgorithm()
        )
    }

    data class AliceMagicInfo(val serial: String, val magic: IntArray)
    data class CytaMagicInfo(val key: String, val base: Long, val divisor: Long)
    data class NetfasterMagicInfo(val mac: String, val base: Long, val divisors: IntArray)
    data class TeleTuMagicInfo(val serial: String, val mac: String, val base: Int, val divider: Int)

    abstract class WpaAlgorithm {
        abstract fun getName(): String
        abstract fun getSupportState(ssid: String, mac: String): Int
        abstract fun generateKeys(ssid: String, mac: String): List<String>

        protected fun incrementMac(mac: String, increment: Int): String {
            val macInt = mac.toLong(16)
            val newMac = macInt + increment
            return newMac.toString(16).padStart(12, '0').uppercase()
        }

        protected fun ByteArray.toHexString(): String {
            return joinToString("") { "%02x".format(it) }
        }
    }

    inner class ArcadyanAlgorithm : WpaAlgorithm() {
        override fun getName() = "Arcadyan"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("(Arcor|EasyBox|Vodafone|WLAN)(-| )[0-9a-fA-F]{6}")) ||
                        ssid.matches(Regex("Vodafone[0-9a-zA-Z]{4}")) -> SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()
            val c1 = Integer.parseInt(mac.substring(8), 16).toString().padStart(5, '0')
            val s7 = c1[1]
            val s8 = c1[2]
            val s9 = c1[3]
            val s10 = c1[4]
            val m9 = mac[8]
            val m10 = mac[9]
            val m11 = mac[10]
            val m12 = mac[11]

            val tmpK1 = (s7.digitToInt(16) + s8.digitToInt(16) + m11.digitToInt(16) + m12.digitToInt(16)).toString(16)
            val tmpK2 = (m9.digitToInt(16) + m10.digitToInt(16) + s9.digitToInt(16) + s10.digitToInt(16)).toString(16)

            val k1 = tmpK1.last()
            val k2 = tmpK2.last()

            val x1 = (k1.digitToInt(16) xor s10.digitToInt(16)).toString(16)
            val x2 = (k1.digitToInt(16) xor s9.digitToInt(16)).toString(16)
            val x3 = (k1.digitToInt(16) xor s8.digitToInt(16)).toString(16)
            val y1 = (k2.digitToInt(16) xor m10.digitToInt(16)).toString(16)
            val y2 = (k2.digitToInt(16) xor m11.digitToInt(16)).toString(16)
            val y3 = (k2.digitToInt(16) xor m12.digitToInt(16)).toString(16)
            val z1 = (m11.digitToInt(16) xor s10.digitToInt(16)).toString(16)
            val z2 = (m12.digitToInt(16) xor s9.digitToInt(16)).toString(16)
            val z3 = (k1.digitToInt(16) xor k2.digitToInt(16)).toString(16)

            val wpaKey = "$x1$y1$z1$x2$y2$z2$x3$y3$z3"
            results.add(wpaKey.uppercase())
            if (wpaKey.contains('0')) {
                results.add(wpaKey.replace("0", "1").uppercase())
            }
            return results
        }
    }

    inner class SercommAlgorithm : WpaAlgorithm() {
        override fun getName() = "Sercomm"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("SerComm[0-9a-fA-F]{6}")) -> SUPPORTED
                ssid.startsWith("SerComm") -> UNLIKELY_SUPPORTED
                mac.startsWith("00:13:33") || mac.startsWith("00:1C:A2") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("MD5")
                md.reset()
                md.update("SerComm".toByteArray(Charsets.UTF_8))
                md.update(mac.substring(6).toByteArray(Charsets.UTF_8))
                val hash = md.digest()

                results.add(hash.toHexString().substring(0, 16))

            } catch (e: NoSuchAlgorithmException) {
            }

            results.add(mac.substring(6).lowercase())
            results.add("sercomm${mac.substring(8)}")
            results.add("admin")

            return results
        }
    }

    inner class ActiontecAlgorithm : WpaAlgorithm() {
        override fun getName() = "Actiontec"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("Actiontec[0-9a-fA-F]{6}")) -> SUPPORTED
                ssid.startsWith("Actiontec") -> UNLIKELY_SUPPORTED
                mac.startsWith("00:26:62") || mac.startsWith("64:87:88") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            val macLast8 = mac.substring(4)
            results.add(macLast8.lowercase())
            results.add(macLast8.uppercase())
            results.add("actiontec")
            results.add("wireless")

            val macInt = mac.substring(6).toLong(16)
            val key = (macInt * 0x343FD + 0x269EC3).toString(16).take(8)
            results.add(key.padStart(8, '0'))

            return results
        }
    }

    inner class NetcommAlgorithm : WpaAlgorithm() {
        override fun getName() = "Netcomm"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("NetComm [0-9a-fA-F]{4}")) -> SUPPORTED
                ssid.startsWith("NetComm") -> UNLIKELY_SUPPORTED
                mac.startsWith("00:21:91") || mac.startsWith("A0:18:28") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            results.add(mac.substring(6).uppercase())
            results.add("NetComm${mac.substring(8)}")
            results.add("netcomm")
            results.add("admin")

            val charset = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            val macInt = mac.substring(6).toLong(16)
            var key = ""
            var value = macInt
            repeat(8) {
                key += charset[(value % 36).toInt()]
                value /= 36
            }
            results.add(key)

            return results
        }
    }

    inner class BillionAlgorithm : WpaAlgorithm() {
        override fun getName() = "Billion"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("Billion[0-9a-fA-F]{4}")) -> SUPPORTED
                ssid.startsWith("Billion") -> UNLIKELY_SUPPORTED
                mac.startsWith("00:08:A1") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            results.add(mac.substring(4).uppercase())
            results.add("billion")
            results.add("admin")

            val macInt = mac.substring(6).toLong(16)
            results.add((macInt + 0x1000).toString(16).padStart(6, '0'))
            results.add((macInt xor 0xAAAA).toString(16).padStart(6, '0'))

            return results
        }
    }

    inner class SMCAlgorithm : WpaAlgorithm() {
        override fun getName() = "SMC"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("SMC[0-9a-fA-F]{6}")) -> SUPPORTED
                ssid.startsWith("SMC") -> UNLIKELY_SUPPORTED
                mac.startsWith("00:13:F7") || mac.startsWith("00:30:4F") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("MD5")
                md.reset()
                md.update("SMC".toByteArray(Charsets.UTF_8))
                md.update(mac.toByteArray(Charsets.UTF_8))
                val hash = md.digest()

                results.add(hash.toHexString().substring(0, 10).uppercase())

            } catch (e: NoSuchAlgorithmException) {
            }

            results.add(mac.substring(6).uppercase())
            results.add("smcadmin")
            results.add("admin")

            return results
        }
    }

    inner class MotorolaAlgorithm : WpaAlgorithm() {
        override fun getName() = "Motorola"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("Motorola[0-9a-fA-F]{6}")) -> SUPPORTED
                ssid.startsWith("Motorola") -> UNLIKELY_SUPPORTED
                mac.startsWith("00:90:9C") || mac.startsWith("C8:FB:26") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            results.add(mac.substring(6).uppercase())
            results.add("motorola")
            results.add("password")

            val macBytes = ByteArray(6)
            for (i in 0 until 6) {
                macBytes[i] = mac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            var sum = 0
            for (byte in macBytes) {
                sum += (byte.toInt() and 0xFF)
            }

            results.add((sum % 100000000).toString().padStart(8, '0'))

            return results
        }
    }

    inner class ArrisAlgorithm : WpaAlgorithm() {
        override fun getName() = "Arris"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("ARRIS-[0-9a-fA-F]{4}")) -> SUPPORTED
                ssid.startsWith("ARRIS") -> UNLIKELY_SUPPORTED
                mac.startsWith("00:1D:D3") || mac.startsWith("2C:30:33") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            val macLast6 = mac.substring(6)
            results.add("ARRIS$macLast6")
            results.add("arris$macLast6")
            results.add(macLast6.lowercase())
            results.add("password")
            results.add("admin")

            val macInt = mac.substring(6).toLong(16)
            val key = (macInt + 0x41525249).toString(16)
            results.add(key.substring(0, minOf(8, key.length)).uppercase())

            return results
        }
    }

    inner class GemtekAlgorithm : WpaAlgorithm() {
        override fun getName() = "Gemtek"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                mac.startsWith("00:90:4B") || mac.startsWith("64:16:F0") -> LIKELY_SUPPORTED
                ssid.contains("Gemtek") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            results.add(mac.substring(6).lowercase())
            results.add(mac.substring(4).lowercase())
            results.add("gemtek")
            results.add("admin")

            val macInt = mac.substring(6).toLong(16)
            results.add((macInt xor 0x474D544B).toString(16).padStart(8, '0'))

            return results
        }
    }

    fun getSupportState(ssid: String, bssid: String): Int {
        val algorithms = getAllAlgorithms()
        var maxSupportState = 0

        for (algorithm in algorithms) {
            val supportState = algorithm.getSupportState(ssid, bssid)
            if (supportState > maxSupportState) {
                maxSupportState = supportState
            }
            if (maxSupportState == SUPPORTED) break
        }

        return maxSupportState
    }

    inner class BroadcomAlgorithm : WpaAlgorithm() {
        override fun getName() = "Broadcom"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("Broadcom[0-9a-fA-F]{6}")) -> SUPPORTED
                mac.startsWith("00:10:18") || mac.startsWith("B8:AE:6E") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("SHA1")
                md.reset()
                md.update("Broadcom".toByteArray(Charsets.UTF_8))
                md.update(mac.toByteArray(Charsets.UTF_8))
                val hash = md.digest()

                results.add(hash.toHexString().substring(0, 12))

            } catch (e: NoSuchAlgorithmException) {
            }

            results.add(mac.substring(6).lowercase())
            results.add("broadcom")
            results.add("admin")

            return results
        }
    }

    inner class RealtekAlgorithm : WpaAlgorithm() {
        override fun getName() = "Realtek"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                mac.startsWith("00:E0:4C") || mac.startsWith("1C:39:47") -> LIKELY_SUPPORTED
                ssid.contains("Realtek") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            results.add(mac.substring(6).uppercase())
            results.add("realtek")
            results.add("admin")

            val macInt = mac.substring(6).toLong(16)
            results.add((macInt + 0x52544C4B).toString(16).padStart(8, '0'))

            return results
        }
    }

    inner class FreeboxAlgorithm : WpaAlgorithm() {
        override fun getName() = "Freebox"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("Freebox-[0-9a-fA-F]{6}")) -> SUPPORTED
                ssid.startsWith("Freebox") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("SHA256")
                md.reset()
                md.update("Free".toByteArray(Charsets.UTF_8))
                md.update(mac.toByteArray(Charsets.UTF_8))
                val hash = md.digest()

                results.add(hash.toHexString().substring(0, 20))

            } catch (e: NoSuchAlgorithmException) {
            }

            val macLast6 = mac.substring(6)
            results.add("Free$macLast6")
            results.add("freebox$macLast6")
            results.add(macLast6.lowercase())

            return results
        }
    }

    inner class SFRAlgorithm : WpaAlgorithm() {
        override fun getName() = "SFR"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("SFR_[0-9a-fA-F]{4}")) -> SUPPORTED
                ssid.startsWith("SFR") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("MD5")
                md.reset()
                md.update("SFR9".toByteArray(Charsets.UTF_8))
                md.update(mac.toByteArray(Charsets.UTF_8))
                val hash = md.digest()

                results.add(hash.toHexString().substring(0, 20))

            } catch (e: NoSuchAlgorithmException) {
            }

            results.add("SFR${mac.substring(6)}")
            results.add("sfr${mac.substring(6)}")
            results.add(mac.substring(6).lowercase())

            return results
        }
    }

    inner class TelstraAlgorithm : WpaAlgorithm() {
        override fun getName() = "Telstra"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("Telstra[0-9a-fA-F]{6}")) -> SUPPORTED
                ssid.startsWith("Telstra") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            val macBytes = ByteArray(6)
            for (i in 0 until 6) {
                macBytes[i] = mac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            var checksum = 0
            for (byte in macBytes) {
                checksum += (byte.toInt() and 0xFF)
            }

            results.add((checksum % 100000000).toString().padStart(8, '0'))
            results.add("Telstra${mac.substring(8)}")
            results.add(mac.substring(6).uppercase())

            return results
        }
    }

    inner class SkylinkAlgorithm : WpaAlgorithm() {
        override fun getName() = "Skylink"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("Skylink_[0-9a-fA-F]{6}")) -> SUPPORTED
                ssid.startsWith("Skylink") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("MD5")
                md.reset()
                md.update("skylink".toByteArray(Charsets.UTF_8))
                md.update(mac.substring(6).toByteArray(Charsets.UTF_8))
                val hash = md.digest()

                results.add(hash.toHexString().substring(0, 16))

            } catch (e: NoSuchAlgorithmException) {
            }

            results.add("skylink${mac.substring(8)}")
            results.add(mac.substring(6).lowercase())

            return results
        }
    }

    inner class OteGenericAlgorithm : WpaAlgorithm() {
        override fun getName() = "OteGeneric"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.matches(Regex("OTE[0-9a-fA-F]{6}"))) SUPPORTED else UNLIKELY_SUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            val results = mutableListOf<String>()

            if (mac.length == 12) {
                results.add(mac.lowercase())
            } else {
                val ssidIdentifier = ssid.substring(ssid.length - 4)
                results.add("c87b5b$ssidIdentifier")
                results.add("fcc897$ssidIdentifier")
                results.add("681ab2$ssidIdentifier")
                results.add("b075d5$ssidIdentifier")
                results.add("384608$ssidIdentifier")
            }

            return results
        }
    }

    inner class FritzBoxAlgorithm : WpaAlgorithm() {
        override fun getName() = "FritzBox"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("FRITZ!Box [0-9]{4}")) -> SUPPORTED
                ssid.startsWith("FRITZ!Box") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("MD5")
                val macBytes = ByteArray(6)
                for (i in 0 until 6) {
                    macBytes[i] = mac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }

                md.reset()
                md.update("AVM".toByteArray(Charsets.UTF_8))
                md.update(macBytes)
                val hash = md.digest()

                val key = hash.toHexString().substring(0, 20)
                results.add(key)

            } catch (e: NoSuchAlgorithmException) {
                results.add(mac.substring(4).uppercase())
            }

            results.add(mac.substring(4).uppercase())
            results.add("${mac.substring(0, 2)}-${mac.substring(2, 4)}-${mac.substring(4, 6)}-${mac.substring(6, 8)}-${mac.substring(8, 10)}-${mac.substring(10, 12)}")

            return results
        }
    }

    inner class ZTEAlgorithm : WpaAlgorithm() {
        override fun getName() = "ZTE"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("ZTE[0-9a-fA-F]{6}")) -> SUPPORTED
                ssid.startsWith("ZTE") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            val macLast = mac.substring(6).toLong(16)
            val key1 = (macLast + 0x20100).toString(16).padStart(8, '0')
            val key2 = (macLast + 0x10100).toString(16).padStart(8, '0')
            val key3 = (macLast xor 0xF0F0F0).toString(16).padStart(8, '0')

            results.add(key1.uppercase())
            results.add(key2.uppercase())
            results.add(key3.uppercase())
            results.add(mac.substring(6))

            return results
        }
    }

    inner class TpLinkNewAlgorithm : WpaAlgorithm() {
        override fun getName() = "TpLinkNew"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("TP-LINK_[0-9A-F]{4}")) -> SUPPORTED
                ssid.matches(Regex("TP-Link_[0-9A-F]{4}")) -> SUPPORTED
                ssid.startsWith("TP-LINK") || ssid.startsWith("TP-Link") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("MD5")
                val seed = "TP-LINK"

                md.reset()
                md.update(seed.toByteArray(Charsets.UTF_8))
                md.update(mac.toByteArray(Charsets.UTF_8))
                val hash = md.digest()

                val key = hash.toHexString().substring(0, 16)
                results.add(key.uppercase())

            } catch (e: NoSuchAlgorithmException) {
                results.add(mac.substring(4).uppercase())
            }

            results.add(mac.substring(4).uppercase())
            results.add("0${mac.substring(5)}")

            return results
        }
    }

    inner class MikroTikAlgorithm : WpaAlgorithm() {
        override fun getName() = "MikroTik"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("MikroTik-[0-9A-F]{6}")) -> SUPPORTED
                ssid.startsWith("MikroTik") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            results.add(mac.substring(6).lowercase())
            results.add(mac.substring(6).uppercase())
            results.add("admin")
            results.add("")
            results.add("MikroTik${mac.substring(6)}")

            return results
        }
    }

    inner class UbiquitiAlgorithm : WpaAlgorithm() {
        override fun getName() = "Ubiquiti"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("ubnt[0-9a-fA-F]{6}")) -> SUPPORTED
                ssid.startsWith("Ubiquiti") -> UNLIKELY_SUPPORTED
                ssid.startsWith("UniFi") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            results.add(mac.substring(6).lowercase())
            results.add("ubnt${mac.substring(6)}")
            results.add("admin")
            results.add("ubnt")

            return results
        }
    }

    inner class AndroidHotspotAlgorithm : WpaAlgorithm() {
        override fun getName() = "AndroidHotspot"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("AndroidAP.*")) -> SUPPORTED
                ssid.matches(Regex("Android_[0-9A-F]{4}")) -> SUPPORTED
                ssid.contains("Android") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            val macLast4 = mac.substring(8)
            results.add("android$macLast4")
            results.add("Android$macLast4")
            results.add(macLast4.lowercase())
            results.add("12345678")
            results.add("password")

            return results
        }
    }

    inner class IPhoneHotspotAlgorithm : WpaAlgorithm() {
        override fun getName() = "iPhoneHotspot"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.contains("iPhone") -> SUPPORTED
                ssid.contains("iPad") -> SUPPORTED
                ssid.matches(Regex(".*'s iPhone")) -> SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            val results = mutableListOf<String>()

            if (mac.length == 12) {
                val macLast4 = mac.substring(8)
                results.add("iphone$macLast4")
                results.add("iPhone$macLast4")
                results.add(macLast4.lowercase())
            }

            val words = listOf("apple", "iphone", "ipad", "wifi", "password", "123456", "12345678")
            val numbers = listOf("123", "456", "789", "000", "111", "2020", "2021", "2022", "2023", "2024")

            for (word in words) {
                for (num in numbers) {
                    results.add("$word$num")
                    results.add("$word$num")
                }
            }

            return results.take(20)
        }
    }

    inner class Huawei5GAlgorithm : WpaAlgorithm() {
        override fun getName() = "Huawei5G"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("HUAWEI-[0-9A-F]{4}-5G")) -> SUPPORTED
                ssid.matches(Regex("HUAWEI_[0-9A-F]{4}_5G")) -> SUPPORTED
                ssid.contains("HUAWEI") && ssid.contains("5G") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("SHA1")
                val seed = "Huawei5G"

                md.reset()
                md.update(seed.toByteArray(Charsets.UTF_8))
                md.update(mac.toByteArray(Charsets.UTF_8))
                val hash = md.digest()

                val key = hash.toHexString().substring(0, 16)
                results.add(key.uppercase())

            } catch (e: NoSuchAlgorithmException) {
                results.add(mac.substring(4))
            }

            results.add("Huawei@${mac.substring(6)}")
            results.add("admin@${mac.substring(6)}")
            results.add(mac.substring(4))

            return results
        }
    }

    inner class Speedport2Algorithm : WpaAlgorithm() {
        override fun getName() = "Speedport2"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("Speedport_W [0-9]{3}[A-Z]")) -> SUPPORTED
                ssid.startsWith("Speedport") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("SHA256")
                val seed = "Telekom"

                md.reset()
                md.update(seed.toByteArray(Charsets.UTF_8))
                md.update(mac.toByteArray(Charsets.UTF_8))
                val hash = md.digest()

                val key = hash.toHexString().substring(0, 20)
                results.add(key.uppercase())

            } catch (e: NoSuchAlgorithmException) {
                results.add("SP-${mac.substring(6)}")
            }

            return results
        }
    }

    inner class OperaAlgorithm : WpaAlgorithm() {
        override fun getName() = "Opera"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("OPERA_[0-9A-F]{6}")) -> SUPPORTED
                ssid.startsWith("OPERA") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            val macLast6 = mac.substring(6)
            results.add("OPERA$macLast6")
            results.add("opera$macLast6")
            results.add(macLast6.lowercase())
            results.add("operawifi")

            return results
        }
    }

    inner class Wind3Algorithm : WpaAlgorithm() {
        override fun getName() = "Wind3"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("Wind3 HuaweiMobile-[0-9A-F]{4}")) -> SUPPORTED
                ssid.startsWith("Wind3") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            val macBytes = ByteArray(6)
            for (i in 0 until 6) {
                macBytes[i] = mac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            var checksum = 0
            for (byte in macBytes) {
                checksum += (byte.toInt() and 0xFF)
            }

            val key = (checksum % 100000000).toString().padStart(8, '0')
            results.add(key)
            results.add("wind${mac.substring(8)}")

            return results
        }
    }

    inner class BelkinAlgorithm : WpaAlgorithm() {
        override fun getName() = "Belkin"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.matches(Regex("^(B|b)elkin(\\.|_)[0-9a-fA-F]{3,6}\$"))) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()
            val orders = arrayOf(
                intArrayOf(6, 2, 3, 8, 5, 1, 7, 4),
                intArrayOf(1, 2, 3, 8, 5, 1, 7, 4),
                intArrayOf(1, 2, 3, 8, 5, 6, 7, 4),
                intArrayOf(6, 2, 3, 8, 5, 6, 7, 4)
            )
            val charsets = arrayOf("024613578ACE9BDF", "944626378ace9bdf")

            fun generateKey(macPart: String, charset: String, order: IntArray) {
                if (macPart.length != 8) return
                val key = buildString {
                    for (i in order.indices) {
                        val k = macPart[order[i] - 1].toString()
                        append(charset[Integer.parseInt(k, 16)])
                    }
                }
                results.add(key)
            }

            when {
                ssid.startsWith("B") -> generateKey(mac.substring(4), charsets[0], orders[0])
                ssid.startsWith("b") -> {
                    var newMac = incrementMac(mac, 1)
                    generateKey(newMac.substring(4), charsets[1], orders[0])
                    if (!newMac.startsWith("944452")) {
                        generateKey(newMac.substring(4), charsets[1], orders[2])
                        newMac = incrementMac(newMac, 1)
                        generateKey(newMac.substring(4), charsets[1], orders[0])
                    }
                }
                else -> {
                    var currentMac = mac
                    repeat(3) {
                        orders.forEach { order ->
                            generateKey(currentMac.substring(4), charsets[0], order)
                            generateKey(currentMac.substring(4), charsets[1], order)
                        }
                        currentMac = incrementMac(currentMac, 1)
                    }
                }
            }
            return results
        }
    }

    inner class ArnetPirelliAlgorithm : WpaAlgorithm() {
        override fun getName() = "ArnetPirelli"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.startsWith("WiFi-Arnet-")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            try {
                val md = MessageDigest.getInstance("SHA-256")
                val results = mutableListOf<String>()
                val lookup = "0123456789abcdefghijklmnopqrstuvwxyz"
                val aliceSeed = byteArrayOf(
                    0x64, 0xC6.toByte(), 0xDD.toByte(), 0xE3.toByte(), 0xE5.toByte(), 0x79,
                    0xB6.toByte(), 0xD9.toByte(), 0x86.toByte(), 0x96.toByte(), 0x8D.toByte(),
                    0x34, 0x45, 0xD2.toByte(), 0x3B, 0x15, 0xCA.toByte(), 0xAF.toByte(),
                    0x12, 0x84.toByte(), 0x02, 0xAC.toByte(), 0x56, 0x00, 0x05, 0xCE.toByte(),
                    0x20, 0x75, 0x91.toByte(), 0x3F, 0xDC.toByte(), 0xE8.toByte()
                )

                val newMac = incrementMac(mac, 1)
                val macBytes = ByteArray(6)
                for (i in 0 until 12 step 2) {
                    macBytes[i / 2] = ((newMac[i].digitToInt(16) shl 4) + newMac[i + 1].digitToInt(16)).toByte()
                }
                md.reset()
                md.update(aliceSeed)
                md.update("1236790".toByteArray(Charsets.UTF_8))
                md.update(macBytes)
                val hash = md.digest()
                val key = buildString {
                    repeat(10) {
                        append(lookup[(hash[it].toInt() and 0xFF) % lookup.length])
                    }
                }
                results.add(key)
                return results
            } catch (e: NoSuchAlgorithmException) {
                return emptyList()
            }
        }
    }

    inner class AliceGermanyAlgorithm : WpaAlgorithm() {
        override fun getName() = "AliceGermany"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.matches(Regex("ALICE-WLAN[0-9a-fA-F]{2}"))) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            try {
                val md = MessageDigest.getInstance("MD5")
                val results = mutableListOf<String>()

                var macEthInt = Integer.parseInt(mac.substring(6), 16) - 1
                if (macEthInt < 0) macEthInt = 0xFFFFFF
                var macEth = macEthInt.toString(16).padStart(6, '0')
                macEth = mac.substring(0, 6) + macEth
                md.reset()
                md.update(macEth.lowercase().toByteArray(Charsets.US_ASCII))
                val hash = md.digest().toHexString().substring(0, 12).toByteArray(Charsets.US_ASCII)
                results.add(Base64.encodeToString(hash, Base64.DEFAULT).trim())
                return results
            } catch (e: NoSuchAlgorithmException) {
                return emptyList()
            }
        }
    }

    inner class CabovisaoSagemAlgorithm : WpaAlgorithm() {
        override fun getName() = "CabovisaoSagem"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.startsWith("CBN-")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            val results = mutableListOf<String>()
            val keyBase = "2ce412e"
            val ssidIdentifier = ssid.substring(ssid.length - 4).lowercase()
            results.add("${keyBase}a$ssidIdentifier")
            results.add("${keyBase}b$ssidIdentifier")
            results.add("${keyBase}c$ssidIdentifier")
            results.add("${keyBase}d$ssidIdentifier")
            return results
        }
    }

    inner class HuaweiAlgorithm : WpaAlgorithm() {
        override fun getName() = "Huawei"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.matches(Regex("INFINITUM[0-9a-zA-Z]{4}"))) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()
            val macArray = IntArray(12) { Integer.parseInt(mac.substring(it, it + 1), 16) }
            val key = intArrayOf(30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 61, 62, 63, 64, 65, 66)

            val ya = (macArray[0] + macArray[1] + macArray[2]) % 16
            val yb = (macArray[3] + macArray[4] + macArray[5]) % 16
            val yc = (macArray[6] + macArray[7] + macArray[8]) % 16
            val yd = (macArray[9] + macArray[10] + macArray[11]) % 16
            val ye = (macArray[0] + macArray[6]) % 16

            results.add("${key[ya]}${key[yb]}${key[yc]}${key[yd]}${key[ye]}")
            return results
        }
    }

    inner class InfostradaAlgorithm : WpaAlgorithm() {
        override fun getName() = "Infostrada"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.startsWith("InfostradaWiFi-")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()
            return listOf("2$mac")
        }
    }

    inner class CytaZTEAlgorithm(private val cytaZTEs: Map<String, List<CytaMagicInfo>>?) : WpaAlgorithm() {
        override fun getName() = "CytaZTE"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.startsWith("CYTA")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12 || cytaZTEs.isNullOrEmpty()) return emptyList()

            val results = mutableListOf<String>()
            val macDec = mac.substring(6).toLong(16)

            for ((key, infos) in cytaZTEs) {
                for (info in infos) {
                    val basi = key.toLong(16) - info.base
                    val diff = macDec - basi
                    if (diff in 0..(9999 * info.divisor) && diff % info.divisor == 0L) {
                        val result = (diff / info.divisor).toString().padStart(5, '0')
                        results.add("${info.key}$result")
                    }
                }
            }
            return results
        }
    }

    inner class InterCableAlgorithm : WpaAlgorithm() {
        override fun getName() = "InterCable"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.startsWith("InterCable")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()
            val wep = "m${mac.substring(0, 10).lowercase()}"
            var intValue = mac.substring(10, 12).toInt(16)
            results.add(wep + (intValue + 1).toString(16).lowercase())
            results.add(wep + (intValue + 2).toString(16).lowercase())
            return results
        }
    }

    inner class CytaAlgorithm(private val cyta: List<CytaMagicInfo>?) : WpaAlgorithm() {
        override fun getName() = "Cyta"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.startsWith("CYTA") || ssid.matches(Regex("Discus--?[0-9a-fA-F]{6}"))) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12 || cyta.isNullOrEmpty()) return emptyList()

            val results = mutableListOf<String>()
            val macDec = mac.substring(6).toLong(16)

            for (info in cyta) {
                val diff = macDec - info.base
                if (diff in 0..9999999 && diff % info.divisor == 0L) {
                    val key = (diff / info.divisor).toString().padStart(7, '0')
                    results.add("${info.key}$key")
                }
            }
            return results
        }
    }

    inner class HG824xAlgorithm : WpaAlgorithm() {
        override fun getName() = "HG824x"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.startsWith("HG824")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()
            val wpaPassword = StringBuilder().apply {
                append(mac.substring(6, 8))
                val lastPair = mac.substring(10).toInt(16)
                if (lastPair <= 8) {
                    val fifthPair = (mac.substring(8, 10).toInt(16) - 1) and 0xFF
                    append(fifthPair.toString(16))
                } else {
                    append(mac.substring(8, 10))
                }
                val lastChar = mac.substring(11).toInt(16)
                if (lastChar <= 8) {
                    val nextPart = (mac.substring(10, 11).toInt(16) - 1) and 0xF
                    append(nextPart.toString(16))
                } else {
                    append(mac.substring(10, 11))
                }
                when (lastChar) {
                    8 -> append("F")
                    9 -> append("0")
                    0xA -> append("1")
                    0xB -> append("2")
                    0xC -> append("3")
                    0xD -> append("4")
                    0xE -> append("5")
                    0xF -> append("6")
                    0 -> append("7")
                    1 -> append("8")
                    2 -> append("9")
                    3 -> append("A")
                    4 -> append("B")
                    5 -> append("C")
                    6 -> append("D")
                    7 -> append("E")
                    else -> return emptyList()
                }
                when (mac.substring(0, 2)) {
                    "28" -> append("03")
                    "08" -> append("05")
                    "80" -> append("06")
                    "E0" -> append("0C")
                    "00" -> append("0D")
                    "10" -> append("0E")
                    "CC" -> append("12")
                    "D4" -> append("35")
                    "AC" -> append("1A")
                    "20" -> append("1F")
                    "70" -> append("20")
                    "F8" -> append("21")
                    "48" -> append("24")
                    else -> return emptyList()
                }
            }
            results.add(wpaPassword.toString().uppercase())
            return results
        }
    }

    inner class ConnAlgorithm : WpaAlgorithm() {
        override fun getName() = "Conn"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("OTE[0-9a-fA-F]{6}")) -> SUPPORTED
                ssid.matches(Regex("conn-x[0-9a-fA-F]{6}")) -> {
                    if (mac.length == 12) {
                        val macShort = mac.replace(":", "")
                        val ssidSubpart = ssid.substring(ssid.length - 6)
                        if (macShort.equals(ssidSubpart, ignoreCase = true)) SUPPORTED else UNLIKELY_SUPPORTED
                    } else UNSUPPORTED
                }
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            val results = mutableListOf<String>()
            if (mac.length == 12) {
                results.add(mac.lowercase())
            }
            results.add("1234567890123")
            return results
        }
    }

    inner class DiscusAlgorithm : WpaAlgorithm() {
        override fun getName() = "Discus"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.matches(Regex("Discus--?[0-9a-fA-F]{6}"))) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (!ssid.matches(Regex("Discus--?[0-9a-fA-F]{6}"))) return emptyList()

            val results = mutableListOf<String>()
            val routerEssId = ssid.substring(ssid.length - 6).toInt(16)
            val essidConst = 0xD0EC31
            val result = (routerEssId - essidConst) shr 2
            results.add("YW0$result")
            return results
        }
    }

    inner class EircomAlgorithm : WpaAlgorithm() {
        override fun getName() = "Eircom"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.matches(Regex("eircom[0-1]{4} [0-1]{4}"))) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length < 6) return emptyList()

            try {
                val md = MessageDigest.getInstance("SHA1")
                val results = mutableListOf<String>()
                val macPart = mac.substring(6)
                val routerMAC = ByteArray(4)
                routerMAC[0] = 1
                for (i in 0 until 6 step 2) {
                    routerMAC[i / 2 + 1] = ((macPart[i].digitToInt(16) shl 4) + macPart[i + 1].digitToInt(16)).toByte()
                }
                val macDec = ((0xFF and routerMAC[0].toInt()) shl 24) or
                        ((0xFF and routerMAC[1].toInt()) shl 16) or
                        ((0xFF and routerMAC[2].toInt()) shl 8) or
                        (0xFF and routerMAC[3].toInt())
                val input = "$macDec${"Although your world wonders me, "}"
                md.reset()
                md.update(input.toByteArray(Charsets.UTF_8))
                val hash = md.digest()
                results.add(hash.toHexString().substring(0, 26))
                return results
            } catch (e: NoSuchAlgorithmException) {
                return emptyList()
            }
        }
    }

    inner class DlinkAlgorithm : WpaAlgorithm() {
        override fun getName() = "Dlink"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.matches(Regex("(DL|dl)ink-[0-9a-fA-F]{6}"))) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.isEmpty()) return emptyList()

            val results = mutableListOf<String>()
            val hash = arrayOf('X', 'r', 'q', 'a', 'H', 'N', 'p', 'd', 'S', 'Y', 'w', '8', '6', '2', '1', '5')

            val key = CharArray(20).apply {
                this[0] = mac[11]
                this[1] = mac[0]
                this[2] = mac[10]
                this[3] = mac[1]
                this[4] = mac[9]
                this[5] = mac[2]
                this[6] = mac[8]
                this[7] = mac[3]
                this[8] = mac[7]
                this[9] = mac[4]
                this[10] = mac[6]
                this[11] = mac[5]
                this[12] = mac[1]
                this[13] = mac[6]
                this[14] = mac[8]
                this[15] = mac[9]
                this[16] = mac[11]
                this[17] = mac[2]
                this[18] = mac[4]
                this[19] = mac[10]
            }

            val newKey = CharArray(20)
            for (i in key.indices) {
                val t = key[i]
                val index = when {
                    t.isDigit() -> t.digitToInt()
                    t in 'A'..'F' || t in 'a'..'f' -> t.uppercaseChar().code - 'A'.code + 10
                    else -> return emptyList()
                }
                newKey[i] = hash[index]
            }
            results.add(newKey.concatToString())
            return results
        }
    }

    inner class OnoAlgorithm : WpaAlgorithm() {
        override fun getName() = "Ono"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.matches(Regex("[pP]1[0-9]{6}0000[0-9]"))) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (ssid.length != 13) return emptyList()

            try {
                val md = MessageDigest.getInstance("MD5")
                val results = mutableListOf<String>()

                val value = buildString {
                    append(ssid.substring(0, 11))
                    val lastDigit = ssid.substring(11).toInt() + 1
                    append(if (lastDigit < 10) "0$lastDigit" else lastDigit.toString())
                }

                val pseed = IntArray(4)
                for (i in value.indices) {
                    pseed[i % 4] = pseed[i % 4] xor value[i].code
                }
                var randNumber = pseed[0] or (pseed[1] shl 8) or (pseed[2] shl 16) or (pseed[3] shl 24)
                val key1 = buildString {
                    repeat(5) {
                        randNumber = (randNumber * 0x343fd + 0x269ec3)
                        val tmp = ((randNumber shr 16) and 0xff).toByte()
                        append(tmp.toInt().toString(16).padStart(2, '0').uppercase())
                    }
                }
                results.add(key1)

                md.reset()
                md.update(padTo64(value).toByteArray())
                val hash = md.digest()
                val key2 = buildString {
                    repeat(13) {
                        append((hash[it].toInt() and 0xFF).toString(16).padStart(2, '0'))
                    }
                }
                results.add(key2.uppercase())
                return results
            } catch (e: NoSuchAlgorithmException) {
                return emptyList()
            }
        }

        private fun padTo64(value: String): String {
            if (value.isEmpty()) return ""
            return buildString {
                repeat(1 + (64 / value.length)) { append(value) }
            }.substring(0, 64)
        }
    }

    inner class OteBAUDAlgorithm : WpaAlgorithm() {
        override fun getName() = "OteBAUD"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.matches(Regex("OTE[0-9a-fA-F]{6}")) && !ssid.startsWith("CYTA")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()
            return listOf("0${mac.lowercase()}")
        }
    }

    inner class MeoPirelliAlgorithm : WpaAlgorithm() {
        override fun getName() = "MeoPirelli"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.matches(Regex("ADSLPT-AB[0-9]{5}"))) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            try {
                val md = MessageDigest.getInstance("SHA-256")
                val results = mutableListOf<String>()
                val lookup = "0123456789abcdefghijklmnopqrstuvwxyz"
                val aliceSeed = byteArrayOf(
                    0x64, 0xC6.toByte(), 0xDD.toByte(), 0xE3.toByte(), 0xE5.toByte(), 0x79,
                    0xB6.toByte(), 0xD9.toByte(), 0x86.toByte(), 0x96.toByte(), 0x8D.toByte(),
                    0x34, 0x45, 0xD2.toByte(), 0x3B, 0x15, 0xCA.toByte(), 0xAF.toByte(),
                    0x12, 0x84.toByte(), 0x02, 0xAC.toByte(), 0x56, 0x00, 0x05, 0xCE.toByte(),
                    0x20, 0x75, 0x91.toByte(), 0x3F, 0xDC.toByte(), 0xE8.toByte()
                )

                val newMac = incrementMac(mac, -1)
                val macBytes = ByteArray(6)
                for (i in 0 until 12 step 2) {
                    macBytes[i / 2] = ((newMac[i].digitToInt(16) shl 4) + newMac[i + 1].digitToInt(16)).toByte()
                }
                md.reset()
                md.update(aliceSeed)
                md.update("1236790".toByteArray(Charsets.UTF_8))
                md.update(macBytes)
                val hash = md.digest()
                val key = buildString {
                    repeat(8) {
                        append(lookup[(hash[it].toInt() and 0xFF) % lookup.length])
                    }
                }
                results.add(key)
                return results
            } catch (e: NoSuchAlgorithmException) {
                return emptyList()
            }
        }
    }

    inner class NetFasterAlgorithm(private val netfasters: List<NetfasterMagicInfo>?) : WpaAlgorithm() {
        override fun getName() = "NetFaster"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.contains("NetFasteR") || ssid.contains("hol")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12 || netfasters.isNullOrEmpty()) return emptyList()

            val results = mutableListOf<String>()
            val macDec = mac.substring(6).toLong(16)

            for (info in netfasters) {
                for (div in info.divisors) {
                    val basi = info.mac.toLong(16) - info.base * div
                    val diff = macDec - basi
                    if (diff >= 0 && diff <= (9999 * div) && diff % div == 0L) {
                        val key = (diff / div).toString().padStart(4, '0')
                        val password = "${mac.uppercase()}-${key}$"
                        results.add(password)
                    }
                }
            }
            return results
        }
    }

    inner class TeleTuAlgorithm(private val tele2: List<TeleTuMagicInfo>?) : WpaAlgorithm() {
        override fun getName() = "TeleTu"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12 || tele2.isNullOrEmpty()) return emptyList()

            val results = mutableListOf<String>()
            for (magicInfo in tele2) {
                val serial = ((mac.substring(6).toInt(16) - magicInfo.base) / magicInfo.divider).toString().padStart(7, '0')
                results.add("${magicInfo.serial}Y$serial")
            }
            return results
        }
    }

    inner class Speedport500Algorithm : WpaAlgorithm() {
        override fun getName() = "Speedport500"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12 || ssid.length < 11) return emptyList()

            val results = mutableListOf<String>()
            val block = ssid[10].toString() + mac.substring(9)
            for (x in 0 until 10) {
                for (y in 0 until 10) {
                    for (z in 0 until 10) {
                        results.add("SP-${ssid[9]}$z$block$x$y$z")
                    }
                }
            }
            return results
        }
    }

    inner class SitecomX500Algorithm : WpaAlgorithm() {
        override fun getName() = "SitecomX500"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.lowercase().startsWith("sitecom")) SUPPORTED else UNLIKELY_SUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()
            val charset = "123456789abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ"

            fun generateKey(macInput: String) {
                val key = StringBuilder()
                val numericMac = "0${macInput.substring(6).split(Regex("[A-Fa-f]"))[0]}".toIntOrNull() ?: return

                key.append(charset[((numericMac + macInput[11].code + macInput[5].code) * (macInput[9].code + macInput[3].code + macInput[11].code)) % charset.length])
                key.append(charset[((numericMac + macInput[11].code + macInput[6].code) * (macInput[8].code + macInput[10].code + macInput[11].code)) % charset.length])
                key.append(charset[((numericMac + macInput[3].code + macInput[5].code) * (macInput[7].code + macInput[9].code + macInput[11].code)) % charset.length])
                key.append(charset[((numericMac + macInput[7].code + macInput[6].code) * (macInput[5].code + macInput[4].code + macInput[11].code)) % charset.length])
                key.append(charset[((numericMac + macInput[7].code + macInput[6].code) * (macInput[8].code + macInput[9].code + macInput[11].code)) % charset.length])
                key.append(charset[((numericMac + macInput[11].code + macInput[5].code) * (macInput[3].code + macInput[4].code + macInput[11].code)) % charset.length])
                key.append(charset[((numericMac + macInput[11].code + macInput[4].code) * (macInput[6].code + macInput[8].code + macInput[11].code)) % charset.length])
                key.append(charset[((numericMac + macInput[10].code + macInput[11].code) * (macInput[7].code + macInput[8].code + macInput[11].code)) % charset.length])
                results.add(key.toString())
            }

            generateKey(mac.lowercase())
            generateKey(mac.uppercase())
            generateKey(incrementMac(mac, 1).uppercase())
            generateKey(incrementMac(mac, 2).uppercase())
            return results
        }
    }

    inner class SitecomWLR341Algorithm : WpaAlgorithm() {
        override fun getName() = "SitecomWLR341"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.lowercase().equals("sitecom${mac.substring(6)}", ignoreCase = true)) SUPPORTED else UNLIKELY_SUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()
            val charsets341 = arrayOf("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", "W0X1CDYNJU8VOZA0BKL46PQ7RS9T2E5HI3MFG")
            val charsets4000 = arrayOf("23456789ABCDEFGHJKLMNPQRSTUVWXYZ38BZ", "WXCDYNJU8VZABKL46PQ7RS9T2E5H3MFGPWR2")
            val charsets4004 = arrayOf("JKLMNPQRST23456789ABCDEFGHUVWXYZ38BK", "E5MFJUWXCDKL46PQHAB3YNJ8VZ7RS9TR2GPW")
            val magic1 = 0x98124557L
            val magic2 = 0x0004321aL
            val magic3 = 0x80000000L

            fun generateKey(macInput: String, charsets: Array<String>) {
                var value = macInput.substring(4).toLong(16)
                val offsets = IntArray(12)
                for (i in 0 until 12) {
                    if ((value and 0x1) == 0L) {
                        value = value xor magic2
                        value = value shr 1
                    } else {
                        value = value xor magic1
                        value = value shr 1
                        value = value or magic3
                    }
                    val offset = value % charsets[0].length
                    offsets[i] = offset.toInt()
                }
                val wpakey = StringBuilder()
                wpakey.append(charsets[0][offsets[0]])
                for (i in 0 until 11) {
                    if (offsets[i] != offsets[i + 1]) {
                        wpakey.append(charsets[0][offsets[i + 1]])
                    } else {
                        val newOffset = (offsets[i] + i + 1) % charsets[0].length
                        wpakey.append(charsets[1][newOffset])
                    }
                }
                results.add(wpakey.toString())
            }

            generateKey(mac, charsets341)
            generateKey(mac, charsets4000)
            generateKey(mac, charsets4004)
            generateKey(incrementMac(mac, 1), charsets341)
            generateKey(incrementMac(mac, 1), charsets4000)
            generateKey(incrementMac(mac, 1), charsets4004)
            generateKey(incrementMac(mac, 4), charsets341)
            generateKey(incrementMac(mac, 4), charsets4000)
            generateKey(incrementMac(mac, 4), charsets4004)
            return results
        }
    }

    inner class Sitecom2100Algorithm : WpaAlgorithm() {
        override fun getName() = "Sitecom2100"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.lowercase().startsWith("sitecom")) SUPPORTED else UNLIKELY_SUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            try {
                val md = MessageDigest.getInstance("MD5")
                val results = mutableListOf<String>()
                val charset = "ABCDEFGHJKLMNPQRSTUVWXYZ"

                md.reset()
                md.update(mac.lowercase().toByteArray(Charsets.US_ASCII))
                val hash = md.digest()
                val hashStr = hash.toHexString().substring(hash.toHexString().length - 16)

                val key = StringBuilder()
                val divider = 24.toBigInteger()
                var magicNrBig = hashStr.toBigInteger(16)

                repeat(12) {
                    key.append(charset[magicNrBig.mod(divider).toInt()])
                    magicNrBig = magicNrBig.divide(divider)
                }

                results.add(key.toString())
                return results
            } catch (e: NoSuchAlgorithmException) {
                return emptyList()
            }
        }
    }

    inner class UpcAlgorithm : WpaAlgorithm() {
        override fun getName() = "Upc"

        override fun getSupportState(ssid: String, mac: String): Int {
            val trimmedSsid = ssid.trim()
            return when {
                trimmedSsid.matches(Regex("UPC[0-9]{7}")) -> SUPPORTED
                trimmedSsid.matches(Regex("UPC[0-9]{5,6}")) -> UNLIKELY_SUPPORTED
                trimmedSsid.matches(Regex("UPC[0-9]{8}")) -> UNLIKELY_SUPPORTED
                mac.startsWith("64:7C:34") || mac.uppercase().startsWith("647C34") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            val results = mutableListOf<String>()

            if (mac.uppercase().startsWith("647C34")) {
                val macInt = mac.toLong(16)
                val macStart = macInt - 4
                repeat(7) { i ->
                    val curMac = macStart + i
                    val curPass = generateUbeePass(curMac.toString(16).padStart(12, '0'))
                    if (!results.contains(curPass)) {
                        results.add(curPass)
                    }
                }
            }

            if (ssid.startsWith("UPC")) {
                results.addAll(generateUpcKeys(ssid))
            }

            return results
        }

        private fun generateUbeePass(mac: String): String {
            val macBytes = ByteArray(6)
            for (i in 0 until 6) {
                macBytes[i] = mac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return "WIFI${macBytes.joinToString("") { "%02X".format(it) }}"
        }

        private fun generateUpcKeys(ssid: String): List<String> {
            val results = mutableListOf<String>()
            val ssidNum = ssid.substring(3).toIntOrNull() ?: return results

            for (year in 4..9) {
                for (week in 1..53) {
                    for (a in 0..35) {
                        for (b in 0..35) {
                            for (c in 0..35) {
                                val serial = year * 36 * 36 * 36 * 52 + week * 36 * 36 * 36 + a * 36 * 36 + b * 36 + c
                                if (serial == ssidNum) {
                                    val key = "CP${year}${week.toString().padStart(2, '0')}${intToChar(a)}${intToChar(b)}${intToChar(c)}"
                                    results.add(key)
                                }
                            }
                        }
                    }
                }
            }
            return results
        }

        private fun intToChar(value: Int): String {
            return when {
                value < 10 -> value.toString()
                else -> ('A' + value - 10).toString()
            }
        }
    }

    inner class AlcatelLucentAlgorithm : WpaAlgorithm() {
        override fun getName() = "AlcatelLucent"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()
            try {
                val key = performDnsQuery(mac)
                if (key.isNotEmpty()) {
                    results.add(key)
                }
            } catch (e: Exception) {
            }
            return results
        }

        private fun performDnsQuery(mac: String): String {
            return ""
        }
    }

    inner class PtvAlgorithm : WpaAlgorithm() {
        override fun getName() = "Ptv"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()
            return listOf(mac.substring(2))
        }
    }

    inner class NetgearAlgorithm : WpaAlgorithm() {
        override fun getName() = "Netgear"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("NETGEAR[0-9]{2}")) -> SUPPORTED
                ssid.startsWith("NETGEAR") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            val results = mutableListOf<String>()

            if (ssid.matches(Regex("NETGEAR[0-9]{2}"))) {
                val suffix = ssid.substring(7)
                results.add("adjective$suffix")
                results.add("noun$suffix")
                results.add("pleasant$suffix")
            }

            if (mac.length == 12) {
                val adjectives = listOf("beautiful", "charming", "elegant", "fantastic", "gorgeous", "handsome", "incredible", "joyful", "kindly", "lovely")
                val nouns = listOf("angel", "butterfly", "cloud", "diamond", "eagle", "flower", "garden", "heart", "island", "jewel")

                val macLast = mac.substring(8).toIntOrNull(16) ?: return results
                val adjIndex = macLast % adjectives.size
                val nounIndex = (macLast / 16) % nouns.size
                val number = macLast % 1000

                results.add("${adjectives[adjIndex]}$number")
                results.add("${nouns[nounIndex]}$number")
            }

            return results
        }
    }

    inner class LinksysAlgorithm : WpaAlgorithm() {
        override fun getName() = "Linksys"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("Linksys[0-9a-fA-F]{5}")) -> SUPPORTED
                ssid.startsWith("Linksys") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            if (ssid.matches(Regex("Linksys[0-9a-fA-F]{5}"))) {
                val suffix = ssid.substring(7)
                val macBytes = ByteArray(6)
                for (i in 0 until 6) {
                    macBytes[i] = mac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }

                val key = StringBuilder()
                for (i in 0 until 10) {
                    val index = (macBytes[i % 6].toInt() and 0xFF + i) % 36
                    if (index < 10) {
                        key.append(index)
                    } else {
                        key.append(('A' + index - 10))
                    }
                }
                results.add(key.toString())
            }

            return results
        }
    }

    inner class AsusAlgorithm : WpaAlgorithm() {
        override fun getName() = "Asus"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("ASUS[_-][0-9a-fA-F]{2}")) -> SUPPORTED
                ssid.startsWith("ASUS") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("MD5")
                md.reset()
                md.update(mac.toByteArray(Charsets.UTF_8))
                val hash = md.digest()

                val key = StringBuilder()
                for (i in 0 until 8) {
                    val value = hash[i].toInt() and 0xFF
                    key.append(value.toString(16).padStart(2, '0'))
                }
                results.add(key.toString())

                results.add(mac.substring(6))
                results.add("0${mac.substring(6)}")

            } catch (e: NoSuchAlgorithmException) {
                results.add(mac.substring(6))
            }

            return results
        }
    }

    inner class BuffaloAlgorithm : WpaAlgorithm() {
        override fun getName() = "Buffalo"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("Buffalo-[AG]-[0-9a-fA-F]{4}")) -> SUPPORTED
                ssid.startsWith("Buffalo") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            val macInt = mac.substring(6).toLong(16)
            val key1 = (macInt + 0x2C0A).toString(16).padStart(8, '0')
            val key2 = (macInt + 0x1C0A).toString(16).padStart(8, '0')
            val key3 = (macInt + 0x0C0A).toString(16).padStart(8, '0')

            results.add(key1.uppercase())
            results.add(key2.uppercase())
            results.add(key3.uppercase())

            return results
        }
    }

    inner class LiveboxAlgorithm : WpaAlgorithm() {
        override fun getName() = "Livebox"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("Livebox-[0-9a-fA-F]{4}")) -> SUPPORTED
                ssid.startsWith("Livebox") -> UNLIKELY_SUPPORTED
                ssid.startsWith("Orange-") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("SHA1")
                val macBytes = ByteArray(6)
                for (i in 0 until 6) {
                    macBytes[i] = mac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }

                md.reset()
                md.update("$ssid.orange.fr".toByteArray(Charsets.UTF_8))
                md.update(macBytes)
                val hash = md.digest()

                val key = hash.toHexString().substring(0, 20)
                results.add(key)

            } catch (e: NoSuchAlgorithmException) {
                val key = mac.substring(6) + ssid.substring(ssid.length - 4)
                results.add(key)
            }

            return results
        }
    }

    inner class BboxAlgorithm : WpaAlgorithm() {
        override fun getName() = "Bbox"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("bbox[2-3]-[0-9a-fA-F]{4}")) -> SUPPORTED
                ssid.startsWith("bbox") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("MD5")
                val seed = "PrOxImUs"

                md.reset()
                md.update(seed.toByteArray(Charsets.UTF_8))
                md.update(mac.toByteArray(Charsets.UTF_8))
                val hash = md.digest()

                val key = hash.toHexString().substring(0, 20)
                results.add(key.uppercase())

            } catch (e: NoSuchAlgorithmException) {
                results.add(mac.substring(4))
            }

            return results
        }
    }

    inner class TechnicolorAlgorithm : WpaAlgorithm() {
        override fun getName() = "Technicolor"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("Technicolor[0-9a-fA-F]{4}")) -> SUPPORTED
                ssid.startsWith("Technicolor") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            val charset = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            val macInt = mac.substring(6).toLong(16)

            var key = ""
            var value = macInt
            repeat(8) {
                key = charset[(value % 36).toInt()] + key
                value /= 36
            }

            results.add(key)
            results.add(mac.substring(4).uppercase())

            return results
        }
    }

    inner class SagemcomAlgorithm : WpaAlgorithm() {
        override fun getName() = "Sagemcom"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("FAST[0-9]{4}")) -> SUPPORTED
                ssid.startsWith("Sagemcom") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("SHA256")

                md.reset()
                md.update(mac.toByteArray(Charsets.UTF_8))
                md.update(ssid.toByteArray(Charsets.UTF_8))
                val hash = md.digest()

                val key = hash.toHexString().substring(0, 16)
                results.add(key.uppercase())

            } catch (e: NoSuchAlgorithmException) {
                results.add(mac.substring(2))
            }

            return results
        }
    }

    inner class VodafoneAlgorithm : WpaAlgorithm() {
        override fun getName() = "VodafoneGeneric"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("VodafoneWiFi[0-9a-fA-F]{6}")) -> SUPPORTED
                ssid.startsWith("Vodafone") && !ssid.matches(Regex("Vodafone[0-9a-zA-Z]{4}")) -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            val macLast6 = mac.substring(6)
            results.add("VF$macLast6")
            results.add("vodafone$macLast6")
            results.add(macLast6.lowercase())

            if (ssid.length >= 6) {
                val ssidLast6 = ssid.substring(ssid.length - 6)
                results.add("VF$ssidLast6")
            }

            return results
        }
    }

    inner class BTHomeHubAlgorithm : WpaAlgorithm() {
        override fun getName() = "BTHomeHub"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("BTHomeHub[2-5]-[0-9A-Z]{4}")) -> SUPPORTED
                ssid.startsWith("BTHome") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("MD5")
                val salt = "BT_OpenReach_"

                md.reset()
                md.update(salt.toByteArray(Charsets.UTF_8))
                md.update(mac.toByteArray(Charsets.UTF_8))
                val hash = md.digest()

                val key = hash.toHexString().substring(0, 20)
                results.add(key)

            } catch (e: NoSuchAlgorithmException) {
                results.add(mac.substring(2).uppercase())
            }

            return results
        }
    }

    inner class MovistarAlgorithm : WpaAlgorithm() {
        override fun getName() = "Movistar"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("Movistar_[0-9a-fA-F]{4}")) -> SUPPORTED
                ssid.startsWith("Movistar") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            val macInt = mac.substring(6).toLong(16)
            val key1 = (macInt + 0x4D6F7669).toString(16).padStart(8, '0')
            val key2 = (macInt xor 0x4D6F7669).toString(16).padStart(8, '0')

            results.add(key1.uppercase())
            results.add(key2.uppercase())
            results.add(mac.substring(6).uppercase())

            return results
        }
    }

    inner class JazztelAlgorithm : WpaAlgorithm() {
        override fun getName() = "Jazztel"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("JAZZTEL_[0-9a-fA-F]{4}")) -> SUPPORTED
                ssid.startsWith("JAZZTEL") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            try {
                val md = MessageDigest.getInstance("MD5")
                val prefix = "JZTL"

                md.reset()
                md.update(prefix.toByteArray(Charsets.UTF_8))
                md.update(mac.toByteArray(Charsets.UTF_8))
                val hash = md.digest()

                val key = hash.toHexString().substring(0, 20)
                results.add(key.uppercase())

            } catch (e: NoSuchAlgorithmException) {
                results.add("JZTL${mac.substring(6)}")
            }

            return results
        }
    }

    inner class HuaweiE5Algorithm : WpaAlgorithm() {
        override fun getName() = "HuaweiE5"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("HUAWEI-E5[0-9]{2}-[0-9a-fA-F]{4}")) -> SUPPORTED
                ssid.matches(Regex("E5[0-9]{3}-[0-9a-fA-F]{4}")) -> SUPPORTED
                ssid.startsWith("HUAWEI-E5") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            val macBytes = ByteArray(6)
            for (i in 0 until 6) {
                macBytes[i] = mac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            var sum = 0
            for (byte in macBytes) {
                sum += byte.toInt() and 0xFF
            }

            val key = (sum % 100000000).toString().padStart(8, '0')
            results.add(key)

            results.add(mac.substring(4))
            results.add(mac.substring(6))

            return results
        }
    }

    inner class EEAlgorithm : WpaAlgorithm() {
        override fun getName() = "EE"

        override fun getSupportState(ssid: String, mac: String): Int {
            return when {
                ssid.matches(Regex("EE-[0-9a-zA-Z]{6}")) -> SUPPORTED
                ssid.startsWith("EE-") -> UNLIKELY_SUPPORTED
                else -> UNSUPPORTED
            }
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            val charset = "0123456789abcdefghijklmnopqrstuvwxyz"
            val macInt = mac.substring(6).toLong(16)

            var key = ""
            var value = macInt
            repeat(8) {
                key += charset[(value % 36).toInt()]
                value /= 36
            }

            results.add(key)
            results.add("EE${mac.substring(6)}")

            return results
        }
    }

    inner class MegaredAlgorithm : WpaAlgorithm() {
        override fun getName() = "Megared"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.startsWith("Megared")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()
            return listOf(mac.substring(2))
        }
    }

    inner class MaxcomAlgorithm : WpaAlgorithm() {
        override fun getName() = "Maxcom"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.startsWith("Maxcom")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()
            return listOf(mac.uppercase())
        }
    }

    inner class OteHuaweiAlgorithm(private val oteHuawei: List<String>?) : WpaAlgorithm() {
        override fun getName() = "OteHuawei"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.matches(Regex("OTE[0-9a-fA-F]{6}")) && !ssid.startsWith("CYTA")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12 || oteHuawei.isNullOrEmpty()) return emptyList()

            val results = mutableListOf<String>()
            val series = mac.substring(0, 2) + mac.substring(8)
            val point = when (series) {
                "E8FD" -> 0
                "E8F5" -> 1
                "E8F6" -> 2
                else -> return emptyList()
            }
            if (point < oteHuawei.size) {
                val pass = "000000${oteHuawei[point]}"
                results.add(pass.substring(pass.length - 8))
            }
            return results
        }
    }

    inner class ThomsonAlgorithm(private val thomson: Map<String, List<String>>?) : WpaAlgorithm() {
        override fun getName() = "Thomson"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.matches(Regex("(Thomson|SpeedTouch|Orange|INFINITUM|O2Wireless|Bbox|Alice-|DMAX|privat|TN_private|BigPond|CytaHome)[0-9a-fA-F]{6}"))) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()

            if (!thomson.isNullOrEmpty()) {
                val ssidKey = ssid.substring(ssid.length - 6).uppercase()
                val keys = thomson[ssidKey]
                if (!keys.isNullOrEmpty()) {
                    results.addAll(keys)
                    return results
                }
            }

            try {
                val md = MessageDigest.getInstance("MD5")
                val macBytes = ByteArray(6)
                for (i in 0 until 12 step 2) {
                    macBytes[i / 2] = ((mac[i].digitToInt(16) shl 4) + mac[i + 1].digitToInt(16)).toByte()
                }
                md.reset()
                md.update(macBytes)
                md.update(ssid.substring(ssid.length - 6).toByteArray(Charsets.UTF_8))
                val hash = md.digest()
                val key = buildString {
                    repeat(10) {
                        append((hash[it].toInt() and 0xFF).toString(16).padStart(2, '0'))
                    }
                }
                results.add(key.uppercase())
                return results
            } catch (e: NoSuchAlgorithmException) {
                return emptyList()
            }
        }
    }

    inner class ComtrendAlgorithm : WpaAlgorithm() {
        override fun getName() = "Comtrend"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.startsWith("COMTREND-")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            try {
                val md = MessageDigest.getInstance("MD5")
                val results = mutableListOf<String>()
                val ssidIdentifier = ssid.substring(ssid.length - 4)
                val magic = "bcgbghgg"
                val lowermagic = "64680C"
                val highermagic = "3872C0"
                val mac001a2b = "001A2B"

                if (mac.substring(0, 6).equals(mac001a2b, ignoreCase = true)) {
                    for (i in 0 until 512) {
                        md.reset()
                        md.update(magic.toByteArray(Charsets.US_ASCII))
                        val xx = if (i < 256) {
                            md.update(lowermagic.toByteArray(Charsets.US_ASCII))
                            i.toString(16).uppercase().padStart(2, '0')
                        } else {
                            md.update(highermagic.toByteArray(Charsets.US_ASCII))
                            (i - 256).toString(16).uppercase().padStart(2, '0')
                        }
                        md.update(xx.toByteArray(Charsets.US_ASCII))
                        md.update(ssidIdentifier.toByteArray(Charsets.US_ASCII))
                        md.update(mac.toByteArray(Charsets.US_ASCII))
                        val hash = md.digest()
                        results.add(hash.toHexString().substring(0, 20))
                    }
                } else {
                    val macMod = "${mac.substring(0, 8)}$ssidIdentifier"
                    md.reset()
                    md.update(magic.toByteArray(Charsets.US_ASCII))
                    md.update(macMod.uppercase().toByteArray(Charsets.US_ASCII))
                    md.update(mac.toByteArray(Charsets.US_ASCII))
                    val hash = md.digest()
                    results.add(hash.toHexString().substring(0, 20))
                }
                return results
            } catch (e: NoSuchAlgorithmException) {
                return emptyList()
            }
        }
    }

    inner class AndaredAlgorithm : WpaAlgorithm() {
        override fun getName() = "Andared"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.startsWith("ANDARED-")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            return listOf("6b629f4c299371737494c61b5a101693a2d4e9e1f3e1320f3ebf9ae379cecf32")
        }
    }

    inner class AliceItalyAlgorithm(private val alice: List<AliceMagicInfo>?) : WpaAlgorithm() {
        override fun getName() = "AliceItaly"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.startsWith("Alice-")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (alice.isNullOrEmpty() || mac.length != 12) return emptyList()

            try {
                val md = MessageDigest.getInstance("SHA-256")
                val results = mutableListOf<String>()
                val ssidIdentifier = ssid.substring(ssid.length - 8)
                val preInitCharset = "0123456789abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123"
                val aliceSeed = byteArrayOf(
                    0x64, 0xC6.toByte(), 0xDD.toByte(), 0xE3.toByte(), 0xE5.toByte(), 0x79,
                    0xB6.toByte(), 0xD9.toByte(), 0x86.toByte(), 0x96.toByte(), 0x8D.toByte(),
                    0x34, 0x45, 0xD2.toByte(), 0x3B, 0x15, 0xCA.toByte(), 0xAF.toByte(),
                    0x12, 0x84.toByte(), 0x02, 0xAC.toByte(), 0x56, 0x00, 0x05, 0xCE.toByte(),
                    0x20, 0x75, 0x91.toByte(), 0x3F, 0xDC.toByte(), 0xE8.toByte()
                )

                for (aliceInfo in alice) {
                    var serialStr = "${aliceInfo.serial}X"
                    val k = aliceInfo.magic[0]
                    val q = aliceInfo.magic[1]
                    val serial = (ssidIdentifier.toInt() - q) / k
                    val tmp = serial.toString()
                    serialStr += "0".repeat(7 - tmp.length) + tmp

                    val macBytes = ByteArray(6)
                    for (i in 0 until 12 step 2) {
                        macBytes[i / 2] = ((mac[i].digitToInt(16) shl 4) + mac[i + 1].digitToInt(16)).toByte()
                    }
                    md.reset()
                    md.update(aliceSeed)
                    md.update(serialStr.toByteArray(Charsets.UTF_8))
                    md.update(macBytes)
                    val hash = md.digest()
                    val key = buildString {
                        repeat(24) {
                            append(preInitCharset[(hash[it].toInt() and 0xFF) % preInitCharset.length])
                        }
                    }
                    results.add(key)

                    var macEth = mac.substring(0, 6)
                    for (extraNumber in 0 until 10) {
                        val calc = (extraNumber + ssidIdentifier.toInt()).toString(16).uppercase()
                        if (macEth[5].toString() == calc[0].toString()) {
                            macEth += calc.substring(1)
                            break
                        }
                    }
                    if (macEth != mac.substring(0, 6)) {
                        for (i in 0 until 12 step 2) {
                            macBytes[i / 2] = ((macEth[i].digitToInt(16) shl 4) + macEth[i + 1].digitToInt(16)).toByte()
                        }
                        md.reset()
                        md.update(aliceSeed)
                        md.update(serialStr.toByteArray(Charsets.UTF_8))
                        md.update(macBytes)
                        val hash = md.digest()
                        val key = buildString {
                            repeat(24) {
                                append(preInitCharset[(hash[it].toInt() and 0xFF) % preInitCharset.length])
                            }
                        }
                        results.add(key)
                    }
                }
                return results
            } catch (e: NoSuchAlgorithmException) {
                return emptyList()
            }
        }
    }

    inner class AxtelAlgorithm : WpaAlgorithm() {
        override fun getName() = "Axtel"

        override fun getSupportState(ssid: String, mac: String): Int {
            return if (ssid.startsWith("AXTEL-")) SUPPORTED else UNSUPPORTED
        }

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()
            return listOf(mac.substring(2).uppercase())
        }
    }

    inner class TecomAlgorithm : WpaAlgorithm() {
        override fun getName() = "Tecom"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            try {
                val md = MessageDigest.getInstance("SHA1")
                val results = mutableListOf<String>()
                md.reset()
                md.update(ssid.toByteArray())
                val hash = md.digest()
                results.add(hash.toHexString().substring(0, 26))
                return results
            } catch (e: NoSuchAlgorithmException) {
                return emptyList()
            }
        }
    }

    inner class TplinkAlgorithm : WpaAlgorithm() {
        override fun getName() = "Tplink"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()
            return listOf(mac.substring(4).uppercase())
        }
    }

    inner class ZyxelAlgorithm : WpaAlgorithm() {
        override fun getName() = "Zyxel"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            try {
                val md = MessageDigest.getInstance("MD5")
                val results = mutableListOf<String>()
                val ssidIdentifier = ssid.substring(ssid.length - 4)
                val macMod = mac.substring(0, 8) + ssidIdentifier
                md.reset()
                md.update(macMod.lowercase().toByteArray(Charsets.US_ASCII))
                val hash = md.digest()
                results.add(hash.toHexString().substring(0, 20).uppercase())
                return results
            } catch (e: NoSuchAlgorithmException) {
                return emptyList()
            }
        }
    }

    inner class SkyV1Algorithm : WpaAlgorithm() {
        override fun getName() = "SkyV1"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            try {
                val md = MessageDigest.getInstance("MD5")
                val results = mutableListOf<String>()
                val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                md.reset()
                md.update(mac.toByteArray())
                val hash = md.digest()
                val key = buildString {
                    for (i in 1..15 step 2) {
                        val index = hash[i].toInt() and 0xFF
                        append(alphabet[index % 26])
                    }
                }
                results.add(key)
                return results
            } catch (e: NoSuchAlgorithmException) {
                return emptyList()
            }
        }
    }

    inner class PirelliAlgorithm : WpaAlgorithm() {
        override fun getName() = "Pirelli"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (ssid.length < 12) return emptyList()

            try {
                val md = MessageDigest.getInstance("MD5")
                val results = mutableListOf<String>()
                val ssidIdentifier = ssid.substring(ssid.length - 12)
                val saltMD5 = byteArrayOf(
                    0x22, 0x33, 0x11, 0x34, 0x02,
                    0x81.toByte(), 0xFA.toByte(), 0x22, 0x11, 0x41,
                    0x68, 0x11, 0x12, 0x01, 0x05,
                    0x22, 0x71, 0x42, 0x10, 0x66
                )

                val routerESSID = ByteArray(6)
                for (i in 0 until 12 step 2) {
                    routerESSID[i / 2] = ((ssidIdentifier[i].digitToInt(16) shl 4) + ssidIdentifier[i + 1].digitToInt(16)).toByte()
                }

                md.reset()
                md.update(routerESSID)
                md.update(saltMD5)
                val hash = md.digest()
                val key = ShortArray(5)

                key[0] = ((hash[0].toInt() and 0xF8) shr 3).toShort()
                key[1] = (((hash[0].toInt() and 0x07) shl 2) or ((hash[1].toInt() and 0xC0) shr 6)).toShort()
                key[2] = ((hash[1].toInt() and 0x3E) shr 1).toShort()
                key[3] = (((hash[1].toInt() and 0x01) shl 4) or ((hash[2].toInt() and 0xF0) shr 4)).toShort()
                key[4] = (((hash[2].toInt() and 0x0F) shl 1) or ((hash[3].toInt() and 0x80) shr 7)).toShort()

                for (i in 0 until 5) {
                    if (key[i] >= 0x0A) {
                        key[i] = (key[i] + 0x57).toShort()
                    }
                }

                val keyString = buildString {
                    for (k in key) {
                        append((k.toInt() and 0xFF).toChar())
                    }
                }
                results.add(keyString)
                return results
            } catch (e: NoSuchAlgorithmException) {
                return emptyList()
            }
        }
    }

    inner class TelseyAlgorithm : WpaAlgorithm() {
        override fun getName() = "Telsey"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length < 12) return emptyList()

            val results = mutableListOf<String>()
            val macValue = ByteArray(6)
            for (i in 0 until 12 step 2) {
                macValue[i / 2] = ((mac[i].digitToInt(16) shl 4) + mac[i + 1].digitToInt(16)).toByte()
            }

            val vector = LongArray(64)
            vector[0] = 0xFFFFFFFFL and ((macValue[5].toLong() and 0xFF shl 24) or
                    (macValue[1].toLong() and 0xFF shl 16) or
                    (macValue[0].toLong() and 0xFF shl 8) or
                    (macValue[5].toLong() and 0xFF))

            val hash = JenkinsHash()
            var seed = 0L
            for (x in 0 until 64) {
                seed = hash.hashword(vector, x, seed)
            }
            val s1 = seed.toString(16).padStart(8, '0')

            for (x in 0 until 64) {
                when {
                    x < 8 -> vector[x] = vector[x] shl 3
                    x < 16 -> vector[x] = vector[x] ushr 5
                    x < 32 -> vector[x] = vector[x] ushr 2
                    else -> vector[x] = vector[x] shl 7
                }
            }

            seed = 0L
            for (x in 0 until 64) {
                seed = hash.hashword(vector, x, seed)
            }
            val s2 = seed.toString(16).padStart(8, '0')

            results.add(s1.substring(s1.length - 5) + s2.substring(0, 5))
            return results
        }
    }

    inner class VerizonAlgorithm : WpaAlgorithm() {
        override fun getName() = "Verizon"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (ssid.length != 5) return emptyList()

            val results = mutableListOf<String>()
            val inverse = CharArray(5)
            inverse[0] = ssid[4]
            inverse[1] = ssid[3]
            inverse[2] = ssid[2]
            inverse[3] = ssid[1]
            inverse[4] = ssid[0]

            try {
                val result = Integer.valueOf(String(inverse), 36)
                var ssidKey = Integer.toHexString(result).uppercase()
                while (ssidKey.length < 6) {
                    ssidKey = "0$ssidKey"
                }

                if (mac.isNotEmpty()) {
                    results.add("${mac.substring(3, 5)}${mac.substring(6, 8)}$ssidKey")
                } else {
                    results.add("1801$ssidKey")
                    results.add("1F90$ssidKey")
                }
                return results
            } catch (e: NumberFormatException) {
                return emptyList()
            }
        }
    }

    inner class WifimediaRAlgorithm : WpaAlgorithm() {
        override fun getName() = "WifimediaR"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()
            val possibleKey = mac.substring(0, 11).lowercase() + "0"
            results.add(possibleKey)
            results.add(possibleKey.uppercase())
            return results
        }
    }

    inner class PBSAlgorithm : WpaAlgorithm() {
        override fun getName() = "PBS"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            try {
                val md = MessageDigest.getInstance("SHA-256")
                val results = mutableListOf<String>()
                val saltSHA256 = byteArrayOf(
                    0x54, 0x45, 0x4F, 0x74, 0x65, 0x6C,
                    0xB6.toByte(), 0xD9.toByte(), 0x86.toByte(), 0x96.toByte(), 0x8D.toByte(),
                    0x34, 0x45, 0xD2.toByte(), 0x3B, 0x15, 0xCA.toByte(), 0xAF.toByte(),
                    0x12, 0x84.toByte(), 0x02, 0xAC.toByte(), 0x56, 0x00, 0x05,
                    0xCE.toByte(), 0x20, 0x75, 0x94.toByte(), 0x3F, 0xDC.toByte(),
                    0xE8.toByte()
                )
                val lookup = "0123456789ABCDEFGHIKJLMNOPQRSTUVWXYZabcdefghikjlmnopqrstuvwxyz"

                val macHex = ByteArray(6)
                for (i in 0 until 12 step 2) {
                    macHex[i / 2] = ((mac[i].digitToInt(16) shl 4) + mac[i + 1].digitToInt(16)).toByte()
                }
                macHex[5] = (macHex[5] - 5).toByte()

                md.reset()
                md.update(saltSHA256)
                md.update(macHex)
                val hash = md.digest()
                val key = buildString {
                    repeat(13) {
                        val index = if (hash[it] >= 0) hash[it].toInt() else 256 + hash[it].toInt()
                        append(lookup[index % lookup.length])
                    }
                }
                results.add(key)
                return results
            } catch (e: NoSuchAlgorithmException) {
                return emptyList()
            }
        }
    }

    inner class Wlan2Algorithm : WpaAlgorithm() {
        override fun getName() = "Wlan2"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()
            val ssidIdentifier = ssid.substring(ssid.length - 2)
            val key = CharArray(26)

            key[0] = mac[10]
            key[1] = mac[11]
            key[2] = mac[0]
            key[3] = mac[1]
            key[4] = mac[8]
            key[5] = mac[9]
            key[6] = mac[2]
            key[7] = mac[3]
            key[8] = mac[4]
            key[9] = mac[5]
            key[10] = mac[6]
            key[11] = mac[7]
            key[12] = mac[10]
            key[13] = mac[11]
            key[14] = mac[8]
            key[15] = mac[9]
            key[16] = mac[2]
            key[17] = mac[3]
            key[18] = mac[4]
            key[19] = mac[5]
            key[20] = mac[6]
            key[21] = mac[7]
            key[22] = mac[0]
            key[23] = mac[1]
            key[24] = mac[4]
            key[25] = mac[5]

            val max = 9
            val begin = ssidIdentifier.substring(0, 1)
            val primerN = Integer.parseInt(begin, 16)
            if (primerN > max) {
                val cadena = String(key, 0, 2)
                var value = Integer.parseInt(cadena, 16)
                value -= 1
                val cadena2 = Integer.toHexString(value).padStart(2, '0')
                key[0] = cadena2[0]
                key[1] = cadena2[1]
            }

            results.add(String(key, 0, 26))
            return results
        }
    }

    inner class Wlan6Algorithm : WpaAlgorithm() {
        override fun getName() = "Wlan6"

        override fun getSupportState(ssid: String, mac: String): Int = UNLIKELY_SUPPORTED

        override fun generateKeys(ssid: String, mac: String): List<String> {
            if (mac.length != 12) return emptyList()

            val results = mutableListOf<String>()
            val ssidIdentifier = ssid.substring(ssid.length - 6)
            val ssidSubPart = CharArray(6)
            val bssidLastByte = CharArray(2)

            for (i in 0 until 6) {
                ssidSubPart[i] = ssidIdentifier[i]
            }
            bssidLastByte[0] = mac[10]
            bssidLastByte[1] = mac[11]

            for (k in 0 until 6) {
                if (ssidSubPart[k] >= 'A') {
                    ssidSubPart[k] = (ssidSubPart[k].code - 55).toChar()
                }
            }

            if (bssidLastByte[0] >= 'A') {
                bssidLastByte[0] = (bssidLastByte[0].code - 55).toChar()
            }
            if (bssidLastByte[1] >= 'A') {
                bssidLastByte[1] = (bssidLastByte[1].code - 55).toChar()
            }

            for (i in 0 until 10) {
                val aux = i + (ssidSubPart[3].code and 0xf) + (bssidLastByte[0].code and 0xf) + (bssidLastByte[1].code and 0xf)
                val aux1 = (ssidSubPart[1].code and 0xf) + (ssidSubPart[2].code and 0xf) + (ssidSubPart[4].code and 0xf) + (ssidSubPart[5].code and 0xf)
                val second = aux xor (ssidSubPart[5].code and 0xf)
                val sixth = aux xor (ssidSubPart[4].code and 0xf)
                val tenth = aux xor (ssidSubPart[3].code and 0xf)
                val third = aux1 xor (ssidSubPart[2].code and 0xf)
                val seventh = aux1 xor (bssidLastByte[0].code and 0xf)
                val eleventh = aux1 xor (bssidLastByte[1].code and 0xf)
                val fourth = (bssidLastByte[0].code and 0xf) xor (ssidSubPart[5].code and 0xf)
                val eighth = (bssidLastByte[1].code and 0xf) xor (ssidSubPart[4].code and 0xf)
                val twelfth = aux xor aux1
                val fifth = second xor eighth
                val ninth = seventh xor eleventh
                val thirteenth = third xor tenth
                val first = twelfth xor sixth

                val key = Integer.toHexString(first and 0xf) + Integer.toHexString(second and 0xf) +
                        Integer.toHexString(third and 0xf) + Integer.toHexString(fourth and 0xf) +
                        Integer.toHexString(fifth and 0xf) + Integer.toHexString(sixth and 0xf) +
                        Integer.toHexString(seventh and 0xf) + Integer.toHexString(eighth and 0xf) +
                        Integer.toHexString(ninth and 0xf) + Integer.toHexString(tenth and 0xf) +
                        Integer.toHexString(eleventh and 0xf) + Integer.toHexString(twelfth and 0xf) +
                        Integer.toHexString(thirteenth and 0xf)

                results.add(key.uppercase())
            }
            return results
        }
    }

    inner class JenkinsHash {
        private val maxValue = 0xFFFFFFFFL
        private var a: Long = 0
        private var b: Long = 0
        private var c: Long = 0

        private fun add(val1: Long, add: Long): Long = (val1 + add) and maxValue
        private fun subtract(val1: Long, subtract: Long): Long = (val1 - subtract) and maxValue
        private fun xor(val1: Long, x: Long): Long = (val1 xor x) and maxValue
        private fun leftShift(val1: Long, shift: Int): Long = (val1 shl shift) and maxValue
        private fun rot(val1: Long, shift: Int): Long = (leftShift(val1, shift) or (val1 ushr (32 - shift))) and maxValue

        private fun hashMix() {
            a = subtract(a, c)
            a = xor(a, rot(c, 4))
            c = add(c, b)
            b = subtract(b, a)
            b = xor(b, rot(a, 6))
            a = add(a, c)
            c = subtract(c, b)
            c = xor(c, rot(b, 8))
            b = add(b, a)
            a = subtract(a, c)
            a = xor(a, rot(c, 16))
            c = add(c, b)
            b = subtract(b, a)
            b = xor(b, rot(a, 19))
            a = add(a, c)
            c = subtract(c, b)
            c = xor(c, rot(b, 4))
            b = add(b, a)
        }

        fun hashword(k: LongArray, length: Int, initval: Long): Long {
            a = 0xdeadbeef + (length shl 2) + (initval and maxValue)
            b = a
            c = a

            var i = 0
            var len = length
            while (len > 3) {
                a = add(a, k[i])
                b = add(b, k[i + 1])
                c = add(c, k[i + 2])
                hashMix()

                len -= 3
                i += 3
            }

            when (len) {
                3 -> {
                    c = add(c, k[i + 2])
                    b = add(b, k[i + 1])
                    a = add(a, k[i])
                    finalHash()
                }
                2 -> {
                    b = add(b, k[i + 1])
                    a = add(a, k[i])
                    finalHash()
                }
                1 -> {
                    a = add(a, k[i])
                    finalHash()
                }
            }
            return c
        }

        private fun finalHash() {
            c = xor(c, b)
            c = subtract(c, rot(b, 14))
            a = xor(a, c)
            a = subtract(a, rot(c, 11))
            b = xor(b, a)
            b = subtract(b, rot(a, 25))
            c = xor(c, b)
            c = subtract(c, rot(b, 16))
            a = xor(a, c)
            a = subtract(a, rot(c, 4))
            b = xor(b, a)
            b = subtract(b, rot(a, 14))
            c = xor(c, b)
            c = subtract(c, rot(b, 24))
        }
    }
}