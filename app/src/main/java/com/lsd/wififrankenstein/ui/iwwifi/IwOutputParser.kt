package com.lsd.wififrankenstein.ui.iwwifi

import com.lsd.wififrankenstein.ui.iwwifi.models.IwInterface

object IwOutputParser {

    const val MODE_MANAGED = "managed"
    const val MODE_MONITOR = "monitor"
    const val MODE_UNKNOWN = "unknown"

    fun extractInterfaceBlock(output: String, ifaceName: String): String? {
        val lines = output.lines()
        var inBlock = false
        val sb = StringBuilder()
        for (line in lines) {
            if (!inBlock) {
                val trimmed = line.trim()
                val name = trimmed.removePrefix("Interface ").trim()
                if (trimmed.startsWith("Interface ") && name == ifaceName) {
                    inBlock = true
                    sb.append(line).append('\n')
                }
                continue
            }
            val trimmed = line.trim()
            if (trimmed.startsWith("Interface ") || trimmed.startsWith("phy#")) {
                break
            }
            sb.append(line).append('\n')
        }
        return if (sb.isNotEmpty()) sb.toString() else null
    }

    fun modeFromTypeText(text: String): String {
        return when {
            text.contains("type monitor", ignoreCase = true) -> MODE_MONITOR
            text.contains("type managed", ignoreCase = true) -> MODE_MANAGED
            text.contains("type IBSS", ignoreCase = true) -> "ibss"
            text.contains("type AP", ignoreCase = true) -> "ap"
            else -> MODE_UNKNOWN
        }
    }

    fun parseAllInterfaceModes(output: String): Map<String, String> {
        val modes = mutableMapOf<String, String>()
        var currentName: String? = null

        for (line in output.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Interface ") -> {
                    currentName?.let { name ->
                        if (name !in modes) modes[name] = MODE_UNKNOWN
                    }
                    currentName = trimmed.substring(10).trim()
                }

                trimmed.startsWith("type ") && currentName != null -> {
                    val type = trimmed.substring(5).trim()
                    modes[currentName!!] = when {
                        type.contains("monitor", ignoreCase = true) -> MODE_MONITOR
                        type.contains("managed", ignoreCase = true) -> MODE_MANAGED
                        type.contains("IBSS", ignoreCase = true) -> "ibss"
                        type.contains("AP", ignoreCase = true) -> "ap"
                        else -> MODE_UNKNOWN
                    }
                }
            }
        }
        currentName?.let { if (it !in modes) modes[it] = MODE_UNKNOWN }
        return modes.toMap()
    }

    fun parseInterfacesList(output: String): List<IwInterface> {
        val interfaces = mutableListOf<IwInterface>()

        var currentInterface: String? = null
        var currentType = ""
        var currentAddr = ""

        output.lines().forEach { line ->
            val trimmed = line.trim()

            when {
                trimmed.startsWith("Interface ") -> {
                    currentInterface?.let {
                        interfaces.add(IwInterface(it, currentType, currentAddr))
                    }
                    currentInterface = trimmed.substring(10).trim()
                    currentType = ""
                    currentAddr = ""
                }

                trimmed.startsWith("type ") -> {
                    currentType = trimmed.substring(5).trim()
                }

                trimmed.startsWith("addr ") -> {
                    currentAddr = trimmed.substring(5).trim()
                }
            }
        }

        currentInterface?.let {
            interfaces.add(IwInterface(it, currentType, currentAddr))
        }

        return if (interfaces.isEmpty()) {
            listOf(IwInterface("wlan0"))
        } else {
            interfaces
        }
    }
}
