package com.lsd.wififrankenstein.util

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ArchiveExtractor {

    private const val MAX_EXTRACTED_SIZE = 100L * 1024 * 1024

    private val handshakeExtensions =
        setOf("cap", "pcap", "pcapng", "hccapx", "hccap", "22000", "txt")
    private val sqliteExtensions = setOf("db", "sqlite", "sqlite3")

    fun extract(file: File, destDir: File): List<File> {
        val allFiles = extractAll(file, destDir)
        return allFiles.filter { it.extension.lowercase() in handshakeExtensions }
    }

    fun extractAll(file: File, destDir: File): List<File> {
        destDir.mkdirs()
        val results = mutableListOf<File>()

        when (file.extension.lowercase()) {
            "zip" -> extractZip(file, destDir, results)
            "7z" -> extract7z(file, destDir, results)
            "gz", "tgz" -> extractGzip(file, destDir, results)
            else -> throw IllegalArgumentException("Unsupported archive format: ${file.extension}")
        }

        return results
    }

    fun isSqliteFile(file: File): Boolean {
        if (file.extension.lowercase() in sqliteExtensions) return true
        return try {
            val magic = file.inputStream().use { input ->
                val bytes = ByteArray(16)
                if (input.read(bytes) == 16) String(bytes, charset("UTF-8")) else ""
            }
            magic.startsWith("SQLite format 3")
        } catch (e: Exception) {
            false
        }
    }

    private fun safeOutFile(destDir: File, entryName: String): File {
        val outFile = File(destDir, entryName)
        if (!outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
            throw java.io.IOException("Archive entry escapes destination directory")
        }
        return outFile
    }

    private fun extractZip(file: File, destDir: File, results: MutableList<File>) {
        var totalSize = 0L
        ZipInputStream(FileInputStream(file)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = safeOutFile(destDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (zis.read(buffer).also { read = it } > 0) {
                            totalSize += read
                            if (totalSize > MAX_EXTRACTED_SIZE) {
                                throw java.io.IOException("Archive exceeds maximum extraction size")
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                    results.add(outFile)
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun extract7z(file: File, destDir: File, results: MutableList<File>) {
        var totalSize = 0L
        SevenZFile(file).use { sevenZ ->
            var entry = sevenZ.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = safeOutFile(destDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    val buffer = ByteArray(8192)
                    outFile.outputStream().use { out ->
                        var read: Int
                        while (sevenZ.read(buffer).also { read = it } > 0) {
                            totalSize += read
                            if (totalSize > MAX_EXTRACTED_SIZE) {
                                throw java.io.IOException("Archive exceeds maximum extraction size")
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                    results.add(outFile)
                }
                entry = sevenZ.nextEntry
            }
        }
    }

    private fun extractGzip(file: File, destDir: File, results: MutableList<File>) {
        val outName = file.nameWithoutExtension.removeSuffix(".tar")
        val outFile = safeOutFile(destDir, outName)
        var totalSize = 0L
        GZIPInputStream(FileInputStream(file)).use { gz ->
            outFile.outputStream().use { out ->
                val buffer = ByteArray(8192)
                var read: Int
                while (gz.read(buffer).also { read = it } > 0) {
                    totalSize += read
                    if (totalSize > MAX_EXTRACTED_SIZE) {
                        out.close()
                        outFile.delete()
                        throw java.io.IOException("Archive exceeds maximum extraction size")
                    }
                    out.write(buffer, 0, read)
                }
            }
        }
        results.add(outFile)
    }
}
