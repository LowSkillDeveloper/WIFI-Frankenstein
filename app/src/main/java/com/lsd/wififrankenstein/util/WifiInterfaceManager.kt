package com.lsd.wififrankenstein.util

import android.content.Context
import android.os.Build
import com.lsd.wififrankenstein.ui.iwscanner.IwInterface
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WifiInterfaceManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiInterfaceManager"
    }

    private val binaryDir = context.filesDir.absolutePath
    private val iwBinary = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "iw" else "iw-32"

    suspend fun copyIwBinariesFromAssets(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting iw binaries copy process")

            val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
            Log.d(TAG, "Detected architecture suffix: '$arch'")

            val binaries = listOf("iw$arch")
            Log.d(TAG, "Binaries to copy: $binaries")

            val libraries = if (arch.isEmpty()) {
                listOf(
                    "libnl-3.so",
                    "libnl-genl-3.so",
                    "libnl-route-3.so"
                )
            } else {
                listOf(
                    "libnl-3.so-32",
                    "libnl-genl-3.so-32",
                    "libnl-route-3.so"
                )
            }
            Log.d(TAG, "Libraries to copy: $libraries")

            binaries.forEach { fileName ->
                Log.d(TAG, "Copying binary: $fileName")
                if (copyAssetToInternalStorage(fileName, fileName)) {
                    val chmodResult = Shell.cmd("chmod 755 $binaryDir/$fileName").exec()
                    Log.d(TAG, "chmod 755 for $fileName: ${chmodResult.isSuccess}")
                } else {
                    Log.e(TAG, "Failed to copy binary: $fileName")
                    return@withContext false
                }
            }

            libraries.forEach { libName ->
                Log.d(TAG, "Copying library: $libName")
                if (copyAssetToInternalStorage(libName, libName)) {
                    val chmodResult = Shell.cmd("chmod 755 $binaryDir/$libName").exec()
                    Log.d(TAG, "chmod 755 for $libName: ${chmodResult.isSuccess}")
                } else {
                    Log.w(TAG, "Failed to copy library: $libName (may not exist)")
                }
            }

            if (arch.isNotEmpty()) {
                Log.d(TAG, "Creating library symlinks for 32-bit")
                createLibrarySymlinks()
            }

            Log.d(TAG, "iw binaries copy process completed successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error copying iw binaries", e)
            false
        }
    }

    private fun createLibrarySymlinks() {
        val symlinkConfigs = listOf(
            Pair("libnl-3.so-32", "libnl-3.so"),
            Pair("libnl-genl-3.so-32", "libnl-genl-3.so")
        )

        symlinkConfigs.forEach { (sourceFile, linkName) ->
            createSafeSymlink(sourceFile, linkName)
        }
    }

    private fun createSafeSymlink(sourceFile: String, linkName: String) {
        try {
            val sourcePath = "$binaryDir/$sourceFile"
            val linkPath = "$binaryDir/$linkName"

            val sourceExists = Shell.cmd("test -f $sourcePath && echo 'EXISTS' || echo 'MISSING'").exec()
            if (sourceExists.out.contains("MISSING")) {
                Log.w(TAG, "Source file missing for symlink: $sourceFile")
                return
            }

            Shell.cmd("rm -f $linkPath").exec()

            val createResult = Shell.cmd("cd $binaryDir && ln -sf $sourceFile $linkName").exec()

            if (createResult.isSuccess) {
                val verifyResult = Shell.cmd("test -L $linkPath && test -e $linkPath && echo 'VALID' || echo 'INVALID'").exec()

                if (verifyResult.out.contains("VALID")) {
                    Log.d(TAG, "✓ Created valid symlink: $linkName -> $sourceFile")
                } else {
                    Log.e(TAG, "✗ Created invalid symlink: $linkName")
                    Shell.cmd("rm -f $linkPath").exec()
                }
            } else {
                Log.e(TAG, "✗ Failed to create symlink: $linkName -> $sourceFile")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error creating symlink $linkName: ${e.message}", e)
        }
    }

    private fun copyAssetToInternalStorage(assetName: String, fileName: String): Boolean {
        return try {
            Log.d(TAG, "Attempting to copy asset: $assetName -> $fileName")
            context.assets.open(assetName).use { input ->
                context.openFileOutput(fileName, Context.MODE_PRIVATE).use { output ->
                    val bytes = input.copyTo(output)
                    Log.d(TAG, "Successfully copied $bytes bytes: $assetName -> $fileName")
                }
            }

            val file = java.io.File(context.filesDir, fileName)
            val fileSize = if (file.exists()) file.length() else 0
            Log.d(TAG, "File verification: $fileName exists=${file.exists()}, size=$fileSize bytes")

            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy $assetName -> $fileName: ${e.message}", e)
            false
        }
    }

    suspend fun getAvailableInterfaces(): List<IwInterface> = withContext(Dispatchers.IO) {
        try {
            val command = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./$iwBinary dev"
            Log.d(TAG, "Executing command: $command")

            val result = Shell.cmd(command).exec()
            Log.d(TAG, "Command exit code: ${result.code}")
            Log.d(TAG, "Command success: ${result.isSuccess}")
            Log.d(TAG, "Command output lines: ${result.out.size}")

            result.out.forEachIndexed { index, line ->
                Log.d(TAG, "OUT[$index]: $line")
            }

            if (result.err.isNotEmpty()) {
                Log.w(TAG, "Command stderr lines: ${result.err.size}")
                result.err.forEachIndexed { index, line ->
                    Log.w(TAG, "ERR[$index]: $line")
                }
            }

            if (result.isSuccess && result.out.isNotEmpty()) {
                val interfaces = parseInterfacesList(result.out.joinToString("\n"))
                Log.d(TAG, "Parsed ${interfaces.size} interfaces: ${interfaces.map { it.name }}")
                interfaces
            } else {
                Log.w(TAG, "Command failed or no output, using default wlan0")
                listOf(IwInterface("wlan0"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting interfaces", e)
            listOf(IwInterface("wlan0"))
        }
    }

    private fun parseInterfacesList(output: String): List<IwInterface> {
        Log.d(TAG, "Parsing interfaces list, input length: ${output.length} chars")

        val interfaces = mutableListOf<IwInterface>()
        val lines = output.lines()

        var currentInterface: String? = null
        var currentType = ""
        var currentAddr = ""

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            Log.v(TAG, "Interface parse line $index: $trimmed")

            when {
                trimmed.startsWith("Interface ") -> {
                    currentInterface?.let {
                        interfaces.add(IwInterface(it, currentType, currentAddr))
                        Log.d(TAG, "Added interface: name=$it, type=$currentType, addr=$currentAddr")
                    }
                    currentInterface = trimmed.substring(10).trim()
                    currentType = ""
                    currentAddr = ""
                    Log.d(TAG, "Started parsing interface: $currentInterface")
                }
                trimmed.startsWith("type ") -> {
                    currentType = trimmed.substring(5).trim()
                    Log.v(TAG, "Found type: $currentType")
                }
                trimmed.startsWith("addr ") -> {
                    currentAddr = trimmed.substring(5).trim()
                    Log.v(TAG, "Found addr: $currentAddr")
                }
            }
        }

        currentInterface?.let {
            interfaces.add(IwInterface(it, currentType, currentAddr))
            Log.d(TAG, "Added final interface: name=$it, type=$currentType, addr=$currentAddr")
        }

        val result = if (interfaces.isEmpty()) {
            Log.w(TAG, "No interfaces found, using default wlan0")
            listOf(IwInterface("wlan0"))
        } else {
            Log.d(TAG, "Successfully parsed ${interfaces.size} interfaces")
            interfaces
        }

        return result
    }
}