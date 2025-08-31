package com.lsd.wififrankenstein.util

import android.content.Context
import com.lsd.wififrankenstein.data.ChrootInfo
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.util.concurrent.TimeUnit

class ChrootManager(private val context: Context) {

    companion object {
        private const val CHROOT_PATH = "/data/data/com.lsd.wififrankenstein/chroot"
        private const val CHROOT_INFO_URL = "https://github.com/LowSkillDeveloper/WIFI-Frankenstein/raw/refs/heads/service/Chroot.json"
        private const val VERSION_FILE = "chroot_version.txt"
    }

    private val chrootDir = File(CHROOT_PATH)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isArm64(): Boolean {
        return android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
    }

    suspend fun getChrootInfo(): ChrootInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(CHROOT_INFO_URL)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonString = response.body?.string() ?: return@withContext null
                Json.decodeFromString<ChrootInfo>(jsonString)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("ChrootManager", "Failed to fetch chroot info", e)
            null
        }
    }

    suspend fun downloadAndInstall(
        onProgress: (Int) -> Unit,
        onStatusUpdate: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onStatusUpdate("Fetching chroot information...")
            onProgress(5)

            val chrootInfo = getChrootInfo()
            if (chrootInfo == null) {
                onStatusUpdate("Failed to fetch chroot information")
                return@withContext false
            }

            val archive = if (isArm64()) chrootInfo.arm64 else chrootInfo.armhf
            val architecture = if (isArm64()) "ARM64" else "ARM"

            onStatusUpdate("Preparing chroot environment for $architecture...")
            onProgress(10)

            if (chrootDir.exists()) {
                onStatusUpdate("Removing old chroot...")
                Shell.cmd("rm -rf $CHROOT_PATH").exec()
            }

            chrootDir.mkdirs()
            onProgress(15)

            val tempFile = File(context.cacheDir, archive.filename)

            onStatusUpdate("Downloading ${archive.filename}...")
            if (!downloadFile(archive.download_url, tempFile, onProgress)) {
                onStatusUpdate("Download failed")
                return@withContext false
            }

            onStatusUpdate("Extracting archive...")
            extractTarGz(tempFile, chrootDir, onProgress)

            onStatusUpdate("Setting up environment...")
            setupChrootEnvironment()
            onProgress(90)

            saveVersion(chrootInfo.version)
            tempFile.delete()

            onStatusUpdate("Installation completed!")
            onProgress(100)

            true
        } catch (e: Exception) {
            Log.e("ChrootManager", "Installation failed", e)
            false
        }
    }

    private suspend fun downloadFile(
        url: String,
        destination: File,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) return@withContext false

            val body = response.body ?: return@withContext false
            val contentLength = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(8192)
                    var totalBytesRead = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (contentLength > 0) {
                            val progress = 15 + (totalBytesRead * 25 / contentLength).toInt()
                            onProgress(progress.coerceAtMost(40))
                        }
                    }
                }
            }

            true
        } catch (e: Exception) {
            Log.e("ChrootManager", "Download failed", e)
            false
        }
    }

    private fun extractTarGz(archive: File, destination: File, onProgress: (Int) -> Unit) {
        val gzipStream = GZIPInputStream(FileInputStream(archive))
        val tarStream = TarArchiveInputStream(gzipStream)

        var entry = tarStream.nextTarEntry
        var processed = 0

        while (entry != null) {
            val outputFile = File(destination, entry.name)

            if (entry.isDirectory) {
                outputFile.mkdirs()
            } else {
                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { output ->
                    tarStream.copyTo(output)
                }

                if (entry.mode and 0x49 != 0) {
                    outputFile.setExecutable(true)
                }
            }

            processed++
            if (processed % 50 == 0) {
                val progress = 40 + (processed * 40 / 1000).coerceAtMost(40)
                onProgress(progress)
            }

            entry = tarStream.nextTarEntry
        }

        tarStream.close()
    }

    private fun setupChrootEnvironment() {
        val commands = listOf(
            "chmod 755 $CHROOT_PATH",
            "chmod +x $CHROOT_PATH/usr/bin/*",
            "chmod +x $CHROOT_PATH/home/PixieWps/pixie.py",
            "chmod +x $CHROOT_PATH/home/GeoMac/geomac",
            "chmod +x $CHROOT_PATH/home/disable_internet.sh"
        )

        commands.forEach { command ->
            Shell.cmd(command).exec()
        }
    }

    private fun saveVersion(version: String) {
        try {
            val versionFile = File(chrootDir, VERSION_FILE)
            versionFile.writeText(version)
        } catch (e: Exception) {
            Log.e("ChrootManager", "Failed to save version", e)
        }
    }

    fun getCurrentVersion(): String? {
        return try {
            val versionFile = File(chrootDir, VERSION_FILE)
            if (versionFile.exists()) {
                versionFile.readText().trim()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun checkForUpdates(): Boolean {
        val chrootInfo = getChrootInfo() ?: return false
        val currentVersion = getCurrentVersion() ?: return true
        return chrootInfo.version != currentVersion
    }

    fun isChrootInstalled(): Boolean {
        return chrootDir.exists() &&
                File(chrootDir, "usr/bin").exists() &&
                File(chrootDir, "home").exists() &&
                getCurrentVersion() != null
    }

    fun mountChroot(): Boolean {
        if (!isChrootInstalled()) return false

        val mountCommands = listOf(
            "mount -t proc proc $CHROOT_PATH/proc",
            "mount -t sysfs sysfs $CHROOT_PATH/sys",
            "mount --bind /dev $CHROOT_PATH/dev",
            "mount -t devpts devpts $CHROOT_PATH/dev/pts"
        )

        return mountCommands.all { command ->
            Shell.cmd(command).exec().isSuccess
        }
    }

    fun unmountChroot(): Boolean {
        val umountCommands = listOf(
            "umount $CHROOT_PATH/dev/pts",
            "umount $CHROOT_PATH/dev",
            "umount $CHROOT_PATH/sys",
            "umount $CHROOT_PATH/proc"
        )

        return umountCommands.all { command ->
            Shell.cmd(command).exec().isSuccess
        }
    }

    fun executeInChroot(command: String): Shell.Result {
        Log.d("ChrootManager", "Checking root access before chroot execution")
        val rootCheck = Shell.cmd("whoami").exec()
        Log.d("ChrootManager", "Root check result: ${rootCheck.out.joinToString(" ")}")

        if (!rootCheck.isSuccess || !rootCheck.out.any { it.contains("root") }) {
            Log.w("ChrootManager", "Warning: Not running as root user")
        }

        return ChrootCommandExecutor.executeWithDetailedLogging(CHROOT_PATH, command)
    }

    fun getChrootPath(): String = CHROOT_PATH
}