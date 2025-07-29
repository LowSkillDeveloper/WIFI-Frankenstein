package com.lsd.wififrankenstein.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class WiFiNetwork(
    val ssid: String,
    val password: String = "",
    val security: SecurityType = SecurityType.WPA,
    val hidden: Boolean = false
)

enum class SecurityType(val value: String) {
    NONE("nopass"),
    WEP("WEP"),
    WPA("WPA"),
    WPA3("WPA3")
}

object QrCodeHelper {

    fun generateWiFiQrString(network: WiFiNetwork): String {
        val ssidEscaped = escapeSpecialChars(network.ssid)
        val passwordEscaped = escapeSpecialChars(network.password)

        return "WIFI:T:${network.security.value};S:$ssidEscaped;P:$passwordEscaped;H:${if (network.hidden) "true" else "false"};;"
    }

    fun generateQrCodeBitmap(data: String, size: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1
            )

            val bitMatrix: BitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }

            bitmap
        } catch (e: WriterException) {
            e.printStackTrace()
            null
        }
    }

    private fun escapeSpecialChars(input: String): String {
        return input.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
    }

    suspend fun saveQrCodeToGallery(context: Context, bitmap: Bitmap, networkName: String): Boolean {
        return try {
            val filename = "WiFi_QR_${networkName.replace("[^a-zA-Z0-9]".toRegex(), "_")}_${System.currentTimeMillis()}.png"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WiFi QR Codes")
                }

                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                    true
                } ?: false
            } else {
                val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "WiFi QR Codes")
                if (!picturesDir.exists()) {
                    picturesDir.mkdirs()
                }

                val file = File(picturesDir, filename)
                FileOutputStream(file).use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                }
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                true
            }
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    fun shareQrCode(context: Context, bitmap: Bitmap, networkName: String) {
        try {
            val cachePath = File(context.cacheDir, "qr_codes")
            cachePath.mkdirs()

            val filename = "wifi_qr_${networkName.replace("[^a-zA-Z0-9]".toRegex(), "_")}.png"
            val file = File(cachePath, filename)

            FileOutputStream(file).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }

            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "WiFi QR Code")
                putExtra(Intent.EXTRA_TEXT, "WiFi: $networkName")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(shareIntent, "Share WiFi QR Code"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}