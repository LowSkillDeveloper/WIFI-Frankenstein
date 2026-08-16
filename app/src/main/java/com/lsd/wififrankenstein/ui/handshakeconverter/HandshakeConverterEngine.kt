package com.lsd.wififrankenstein.ui.handshakeconverter

import android.content.Context
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.ChrootCapabilities
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.HandshakeHash
import com.lsd.wififrankenstein.util.HandshakeType
import com.lsd.wififrankenstein.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class HandshakeConverterEngine(private val context: Context) {

    private val chrootManager = ChrootManager.get(context)
    private val tag = "HandshakeConverter"

    suspend fun convert(item: ConvertFileItem, target: TargetFormat): ConvertResultItem =
        withContext(Dispatchers.IO) {
            val outDir = File(context.cacheDir, "converter_temp")
            outDir.mkdirs()
            try {
                val lines = item.hash22000Lines
                if (lines.isEmpty()) {
                    return@withContext fail(item, target, context.getString(R.string.hc_no_hashes_to_convert))
                }
                val baseName = item.fileName.substringBeforeLast('.')
                val hashes = lines.mapNotNull { HandshakeHash.parse22000Line(it) }
                val outputFile: File? = when (target) {
                    TargetFormat.HASH_22000 -> {
                        val f = File(outDir, "$baseName.22000")
                        f.writeText(lines.joinToString("\n"))
                        f
                    }

                    TargetFormat.HCCAPX -> {
                        val bytes = concatRecords(hashes) { it.toHccapxBytes() }
                        if (bytes.isEmpty()) null
                        else File(outDir, "$baseName.hccapx").also { it.writeBytes(bytes) }
                    }

                    TargetFormat.HCCAP -> {
                        val bytes = concatRecords(hashes) { it.toHccapBytes() }
                        if (bytes.isEmpty()) null
                        else File(outDir, "$baseName.hccap").also { it.writeBytes(bytes) }
                    }

                    TargetFormat.PMKID -> {
                        val pmkid = hashes.filter {
                            it.type == HandshakeType.PMKID ||
                                    it.type == HandshakeType.PMKID_EAPOL
                        }.map { it.toPmkidLine() }
                        if (pmkid.isEmpty()) null
                        else File(outDir, "${baseName}_pmkid.txt").also {
                            it.writeText(pmkid.joinToString("\n"))
                        }
                    }

                    TargetFormat.HASH_16800 -> {
                        val lines16800 = hashes.mapNotNull {
                            if ((it.type == HandshakeType.PMKID ||
                                        it.type == HandshakeType.PMKID_EAPOL) &&
                                it.pmkidOrMic.length == 32
                            ) it.to16800Line() else null
                        }.distinct()
                        if (lines16800.isEmpty()) null
                        else File(outDir, "${baseName}_16800.txt").also {
                            it.writeText(lines16800.joinToString("\n"))
                        }
                    }

                    TargetFormat.CAP -> convertToCap(outDir, baseName, lines)
                }

                if (outputFile == null || !outputFile.exists() || outputFile.length() == 0L) {
                    fail(item, target, context.getString(R.string.hc_empty_output))
                } else {
                    ConvertResultItem(item.fileName, outputFile.absolutePath, target, true)
                }
            } catch (e: Exception) {
                Log.w(tag, "convert failed for ${item.fileName}", e)
                fail(item, target, e.message ?: context.getString(R.string.hc_conversion_error))
            }
        }

    private fun fail(item: ConvertFileItem, target: TargetFormat, msg: String) =
        ConvertResultItem(item.fileName, "", target, false, msg)

    private fun concatRecords(
        hashes: List<HandshakeHash>,
        toBytes: (HandshakeHash) -> ByteArray
    ): ByteArray {
        val records = hashes.filter { it.type == HandshakeType.EAPOL }.map { toBytes(it) }
        if (records.isEmpty()) return ByteArray(0)
        val out = ByteArray(records.sumOf { it.size })
        var offset = 0
        for (record in records) {
            record.copyInto(out, offset)
            offset += record.size
        }
        return out
    }

    private suspend fun convertToCap(
        outDir: File,
        baseName: String,
        lines: List<String>
    ): File? {
        if (!ChrootCapabilities.hasChrootTools(context)) return null
        return withContext(Dispatchers.IO) {
            val tempDir = "/sdcard/WIFI-Frankenstein/temp"
            val safeBase = baseName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val chrootHash = "$tempDir/${safeBase}_hash.txt"
            val capOut = "$tempDir/$safeBase.cap"
            try {
                val jvmHash = File(outDir, "${baseName}_hash.txt")
                jvmHash.writeText(lines.joinToString("\n"))

                chrootManager.executeInChroot("mkdir -p '$tempDir'")

                val cpIn = Shell.cmd(
                    "cp '${jvmHash.absolutePath}' '$chrootHash' && echo CP_OK"
                ).exec()
                if (!cpIn.isSuccess || cpIn.out.none { it.trim() == "CP_OK" }) {
                    return@withContext null
                }

                val conv = chrootManager.executeInChroot(
                    "hcxhash2cap -o '$capOut' '$chrootHash' 2>&1"
                )
                if (!conv.isSuccess) return@withContext null
                val check = chrootManager.executeInChroot("test -s '$capOut'")
                if (!check.isSuccess) return@withContext null

                val jvmDest = File(outDir, "$baseName.cap")
                val cpBack = Shell.cmd(
                    "cp '$capOut' '${jvmDest.absolutePath}' && echo CP_OK"
                ).exec()
                if (cpBack.isSuccess &&
                    cpBack.out.any { it.trim() == "CP_OK" } &&
                    jvmDest.exists()
                ) jvmDest else null
            } catch (e: Exception) {
                Log.w(tag, "convertToCap failed", e)
                null
            } finally {
                Shell.cmd("rm -f '$chrootHash' '$capOut'").exec()
            }
        }
    }
}
