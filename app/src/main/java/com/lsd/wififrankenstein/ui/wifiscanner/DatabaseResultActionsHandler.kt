package com.lsd.wififrankenstein.ui.wifiscanner

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.BottomSheetMenu
import com.lsd.wififrankenstein.util.BottomSheetMenuItem
import com.lsd.wififrankenstein.util.DbFieldFormatter
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.QrNavigationHelper
import com.lsd.wififrankenstein.util.SystemWpsConnector
import com.lsd.wififrankenstein.util.WpsMethodSelector
import com.lsd.wififrankenstein.util.WpsRootConnectHelper

object DatabaseResultActionsHandler {

    private const val TAG = "DatabaseResultActionsHandler"

    private val ALPHANUMERIC_REGEX = Regex("[^a-zA-Z0-9]")

    fun extractDatabaseName(path: String): String {
        return try {
            when {
                path.startsWith("content://") -> {
                    val decodedPath = Uri.decode(path)
                    decodedPath.substringAfterLast('/').substringBefore('?')
                }

                path.startsWith("file://") -> {
                    path.substringAfterLast('/')
                }

                else -> {
                    path.substringAfterLast('/')
                }
            }
        } catch (e: Exception) {
            path
        }
    }

    fun hasValidCoordinates(result: NetworkDatabaseResult): Boolean {
        val latitude = result.databaseInfo["lat"] as? Double
            ?: result.databaseInfo["latitude"] as? Double
        val longitude = result.databaseInfo["lon"] as? Double
            ?: result.databaseInfo["longitude"] as? Double
        return latitude != null && longitude != null && (latitude != 0.0 || longitude != 0.0)
    }

    fun wifiKey(result: NetworkDatabaseResult): String? {
        return result.databaseInfo["WiFiKey"]?.toString()
            ?: result.databaseInfo["wifi_pass"]?.toString()
            ?: result.databaseInfo["key"]?.toString()
    }

    fun wpsPin(result: NetworkDatabaseResult): String? {
        return result.databaseInfo["WPSPIN"]?.toString()
            ?: result.databaseInfo["wps_pin"]?.toString()
            ?: result.databaseInfo["wps"]?.toString()
    }

    fun essid(result: NetworkDatabaseResult): String {
        return (result.databaseInfo["ESSID"] ?: result.databaseInfo["essid"])?.toString()
            ?: result.network.SSID ?: "?"
    }

    fun bssid(result: NetworkDatabaseResult): String {
        return (result.databaseInfo["BSSID"]
            ?: result.databaseInfo["bssid"]
            ?: result.databaseInfo["mac"])?.toString()
            ?: result.network.BSSID ?: ""
    }

    fun isWpsPinInvalid(pin: String?): Boolean {
        return pin != null && (pin.length != 8 || !pin.all { it.isDigit() })
    }

    fun isWifiKeyInvalid(key: String?): Boolean {
        return key != null && key.length < 8
    }

    fun credentialColor(context: Context, invalid: Boolean): Int {
        return if (invalid) {
            ContextCompat.getColor(context, R.color.error_red)
        } else {
            onSurfaceColor(context)
        }
    }

    private fun onSurfaceColor(context: Context): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorOnSurface,
            typedValue,
            true
        )
        return typedValue.data
    }

    fun openMap(view: View, result: NetworkDatabaseResult): Boolean {
        val latitude = result.databaseInfo["lat"] as? Double
            ?: result.databaseInfo["latitude"] as? Double
        val longitude = result.databaseInfo["lon"] as? Double
            ?: result.databaseInfo["longitude"] as? Double

        if (latitude == null || longitude == null || (latitude == 0.0 && longitude == 0.0)) {
            return false
        }

        openMapWithCoordinates(view.context, result.network.SSID, latitude, longitude)
        return true
    }

    fun showAdditionalInfo(view: View, result: NetworkDatabaseResult) {
        val context = view.context
        val message = buildString {
            append("SSID: ${result.network.SSID}\n")
            append("BSSID: ${result.network.BSSID}\n")
            append("Signal: ${result.network.level} dBm\n\n")

            val info = result.databaseInfo
            for ((key, value) in info) {
                if (value == null || value == "N/A") {
                    continue
                }

                val lowerKey = key.lowercase()
                if (lowerKey == "essid" || lowerKey == "ssid" || lowerKey == "bssid" || lowerKey == "mac") {
                    continue
                }
                val displayKey = when (lowerKey) {
                    "wifipassword", "password", "wifikey", "wifi_pass", "key" -> "Password"
                    "wpspin", "wps_pin", "wps" -> "WPS PIN"
                    "sec", "security", "security_type" -> "Security"
                    "lat", "latitude" -> "Latitude"
                    "lon", "longitude" -> "Longitude"
                    "time", "timestamp" -> "Time"
                    "ip" -> "IP"
                    "lanip" -> "LAN IP"
                    "wanip" -> "WAN IP"
                    "lanmask" -> "LAN Mask"
                    "wanmask" -> "WAN Mask"
                    "wangateway" -> "WAN Gateway"
                    "dns1", "dns2", "dns3" -> "DNS ${key.substringAfterLast('_')}"
                    "authorization" -> "Authorization"
                    "name" -> "Router Name"
                    "port" -> "Port"
                    "iprange" -> "IP Range"
                    "radiooff" -> "Radio Off"
                    "hidden" -> "Hidden"
                    "nowifikey" -> "No WiFi Key"
                    "nobssid" -> "No BSSID"
                    "nowps" -> "No WPS"
                    "source" -> "Source"
                    "cmtid" -> "Comment ID"
                    else -> capitalizeFirst(key)
                }
                val displayValue = when (lowerKey) {
                    "time", "timestamp" -> DbFieldFormatter.formatTime(value)
                    "ip", "lanip", "wanip", "lanmask", "wanmask", "wangateway", "dns1", "dns2", "dns3" -> {
                        if (value is Long) DbFieldFormatter.longToIp(value) else value.toString()
                    }

                    "source" -> DbFieldFormatter.sourceLabel(context, value as? Int)
                    "hidden" -> DbFieldFormatter.hiddenLabel(value)
                    "radiooff" -> DbFieldFormatter.radioOffLabel(value)
                    "nowifikey" -> DbFieldFormatter.noWifiKeyLabel(context, value as? Int)
                    "nobssid" -> DbFieldFormatter.noBssidLabel(context, value as? Int)
                    "nowps" -> DbFieldFormatter.noWpsLabel(context, value as? Int)
                    "iprange" -> DbFieldFormatter.iprangeLabel(context, value as? Int)
                    "authorization" -> DbFieldFormatter.authorizationLabel(value as? String)
                    else -> value.toString()
                }
                if (displayValue != null && displayValue != "N/A" && displayValue != "") {
                    append("$displayKey: $displayValue\n")
                }
            }
            append("\nDatabase: ${extractDatabaseName(result.databaseName)}\n")
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Additional Information")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    fun showActionsMenu(view: View, result: NetworkDatabaseResult) {
        val context = view.context
        val wifiKey = result.databaseInfo["WiFiKey"] as? String
            ?: result.databaseInfo["key"] as? String ?: ""

        val wpsPin = result.databaseInfo["WPSPIN"]?.toString()
            ?: result.databaseInfo["wps"]?.toString() ?: ""

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val isRootEnabled = prefs.getBoolean("enable_root", false)
        val hasValidCredentials = QrNavigationHelper.hasValidCredentials(wifiKey, wpsPin)
        val canConnectWpsRoot = isRootEnabled && wpsPin.isNotEmpty()

        val items = listOf(
            BottomSheetMenuItem(
                R.id.action_copy_wifi_key,
                context.getString(R.string.copy_wifi_key),
                R.drawable.ic_content_copy,
                wifiKey.isNotEmpty()
            ),
            BottomSheetMenuItem(
                R.id.action_copy_wps_pin,
                context.getString(R.string.copy_wps_pin),
                R.drawable.ic_content_copy,
                wpsPin.isNotEmpty()
            ),
            BottomSheetMenuItem(
                R.id.action_connect_wps,
                context.getString(R.string.connect_wps),
                R.drawable.wifi_protected_setup_24px
            ),
            BottomSheetMenuItem(
                R.id.action_connect_wps_root,
                context.getString(R.string.connect_wps_root),
                R.drawable.wifi_protected_setup_24px,
                enabled = canConnectWpsRoot,
                visible = canConnectWpsRoot
            ),
            BottomSheetMenuItem(
                R.id.action_generate_qr,
                context.getString(R.string.action_generate_qr),
                R.drawable.ic_qr_code,
                visible = hasValidCredentials
            ),
            BottomSheetMenuItem(
                R.id.action_save_profile,
                context.getString(R.string.save_profile),
                R.drawable.ic_save
            )
        )

        BottomSheetMenu.show(context, items = items) { item ->
            when (item.id) {
                R.id.action_copy_wifi_key -> copyToClipboard(context, "WiFi Key", wifiKey)
                R.id.action_copy_wps_pin -> copyToClipboard(context, "WPS PIN", wpsPin)
                R.id.action_generate_qr -> showQrCode(context, result)
                R.id.action_connect_wps_root -> connectUsingWpsRoot(context, result, wpsPin)
                R.id.action_connect_wps -> connectWps(context, result, wpsPin)
                R.id.action_save_profile -> saveProfileAction(context, result)
            }
        }
    }

    private fun openMapWithCoordinates(context: Context, ssid: String, lat: Double, lon: Double) {
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(ssid)})")
        val mapIntent = Intent(Intent.ACTION_VIEW, uri)

        if (mapIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(
                Intent.createChooser(
                    mapIntent,
                    context.getString(R.string.map_choose_app)
                )
            )
        } else {
            val browserUri = Uri.parse("https://maps.google.com/maps?q=$lat,$lon")
            val browserIntent = Intent(Intent.ACTION_VIEW, browserUri)
            context.startActivity(browserIntent)
        }
    }

    private fun connectUsingWpsRoot(
        context: Context,
        result: NetworkDatabaseResult,
        wpsPin: String
    ) {
        val methodSelector = WpsMethodSelector(
            context,
            object : WpsRootConnectHelper.WpsConnectCallbacks {
                override fun onConnectionProgress(message: String) {
                    (context as? Activity)?.runOnUiThread {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onConnectionSuccess(ssid: String) {
                    (context as? Activity)?.runOnUiThread {
                        Toast.makeText(
                            context,
                            context.getString(R.string.wps_root_connection_successful, ssid),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onConnectionFailed(error: String) {
                    (context as? Activity)?.runOnUiThread {
                        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onLogEntry(message: String) {
                    Log.d("WpsRootConnect", message)
                }

                override fun onWpsResult(pin: String?, psk: String?) {
                    (context as? Activity)?.runOnUiThread {
                        showWpsResultDialog(context, result, pin, psk)
                    }
                }
            }
        )

        methodSelector.showMethodSelection(result.network, wpsPin)
    }

    private fun showWpsResultDialog(
        context: Context,
        result: NetworkDatabaseResult,
        pin: String?,
        psk: String?
    ) {
        val pinText = pin?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.wps_result_none)
        val pskText = psk?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.wps_result_none)
        val ssidText = result.network.SSID?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.wps_result_unknown)

        val content = context.getString(R.string.wps_result_format, ssidText, pinText, pskText)
        val copyText = context.getString(
            R.string.wps_result_copy_format,
            ssidText,
            pinText,
            pskText
        )

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.wps_result_title)
            .setMessage(content)
            .setPositiveButton(R.string.wps_result_copy) { _, _ ->
                copyToClipboard(context, context.getString(R.string.wps_result_title), copyText)
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun connectWps(context: Context, result: NetworkDatabaseResult, wpsPin: String) {
        Log.d(
            TAG,
            "connectWps: entry ssid='${result.network.SSID}' bssid='${result.network.BSSID}' " +
                    "wpsPin='$wpsPin' sdk=${Build.VERSION.SDK_INT}"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Log.w(TAG, "connectWps: WPS not supported on Android 9+, aborting")
            Toast.makeText(
                context,
                context.getString(R.string.wps_not_supported),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CHANGE_WIFI_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "connectWps: CHANGE_WIFI_STATE permission missing, aborting")
            Toast.makeText(
                context,
                context.getString(R.string.change_wifi_state_permission_required),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        SystemWpsConnector.showModeSelection(context, databasePin = wpsPin) { mode ->
            val modeName = when {
                mode == null -> "PBC"
                mode == "" -> "EMPTY_PIN"
                mode == WpsMethodSelector.NULL_PIN_IDENTIFIER -> "NULL_PIN"
                else -> "REAL_PIN"
            }
            Log.d(
                TAG,
                "connectWps: mode selected mode=$modeName wpsPin='${mode.orEmpty()}' " +
                        "bssid='${result.network.BSSID}'"
            )
            val connector = SystemWpsConnector(context)
            connector.connect(
                result.network.BSSID,
                mode,
                object : SystemWpsConnector.WpsCallbacks {
                    override fun onStarted(pin: String?) {
                        val message = when {
                            mode == null -> context.getString(R.string.wps_started_pbc)
                            mode == "" -> context.getString(R.string.wps_started_empty_pin)
                            mode == WpsMethodSelector.NULL_PIN_IDENTIFIER ->
                                context.getString(R.string.wps_started_null_pin)

                            else -> context.getString(R.string.wps_started_with_pin, mode)
                        }
                        Log.d(
                            TAG,
                            "connectWps: onStarted pin='${pin.orEmpty()}' message='$message'"
                        )
                        (context as? Activity)?.runOnUiThread {
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onSucceeded() {
                        Log.d(TAG, "connectWps: WPS succeeded for '${result.network.SSID}'")
                        (context as? Activity)?.runOnUiThread {
                            Toast.makeText(
                                context,
                                context.getString(R.string.wps_succeeded),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onFailed(reason: Int) {
                        Log.w(
                            TAG,
                            "connectWps: WPS failed reason=$reason for '${result.network.SSID}'"
                        )
                        (context as? Activity)?.runOnUiThread {
                            Toast.makeText(
                                context,
                                context.getString(R.string.wps_failed, reason),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onError(message: String) {
                        Log.e(TAG, "connectWps: WPS error: $message")
                        (context as? Activity)?.runOnUiThread {
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }

    private fun saveProfileAction(context: Context, result: NetworkDatabaseResult) {
        val capabilities = result.network.capabilities
        val ssid = result.network.SSID
        val password = result.databaseInfo["WiFiKey"] as? String
            ?: result.databaseInfo["key"] as? String

        if (password.isNullOrEmpty()) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_no_data_to_save),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        saveWifiProfile(context, result.network, capabilities, ssid, password)
    }

    private fun saveWifiProfile(
        context: Context,
        network: ScanResult,
        capabilities: String,
        ssid: String,
        password: String
    ) {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        var saved = false

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val wifiConfig = WifiConfiguration().apply {
                    BSSID = network.BSSID
                    SSID = "\"$ssid\""
                    hiddenSSID = false
                    priority = 1000

                    when {
                        capabilities.contains("WEP") -> {
                            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                            allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                            allowedProtocols.set(WifiConfiguration.Protocol.WPA)
                            allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                            allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
                            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
                            wepKeys[0] = "\"$password\""
                            wepTxKeyIndex = 0
                        }

                        capabilities.contains("WPA") || capabilities.contains("PSK") -> {
                            preSharedKey = "\"$password\""
                        }
                    }
                }

                val netId = wifiManager.addNetwork(wifiConfig)
                saved = netId > -1

                if (saved) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.wifi_network_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("WifiAdapter", "Error saving network with old method", e)
            }
        }

        if (!saved && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val suggestionBuilder = WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)

                when {
                    capabilities.contains("WEP") -> {
                        Toast.makeText(
                            context,
                            context.getString(R.string.wifi_network_save_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    capabilities.contains("WPA") || capabilities.contains("PSK") -> {
                        suggestionBuilder.setWpa2Passphrase(password)
                    }

                    capabilities.contains("WPA3") -> {
                        suggestionBuilder.setWpa3Passphrase(password)
                    }

                    else -> {
                    }
                }

                val suggestion = suggestionBuilder.build()
                val suggestions = listOf(suggestion)

                val status = wifiManager.addNetworkSuggestions(suggestions)

                if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.wifi_suggestion_added),
                        Toast.LENGTH_SHORT
                    ).show()
                    saved = true
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.wifi_suggestion_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Throwable) {
                Log.e("WifiAdapter", "Error saving network with new method", e)
            }
        }

        showWifiInfoDialog(context, ssid, password)
    }

    private fun showWifiInfoDialog(context: Context, ssid: String, password: String) {
        val dialogView =
            android.view.LayoutInflater.from(context).inflate(R.layout.dialog_wifi_info, null)
        val ssidTextView = dialogView.findViewById<android.widget.TextView>(R.id.ssidTextView)
        val passwordTextView =
            dialogView.findViewById<android.widget.TextView>(R.id.passwordTextView)
        val copySsidButton = dialogView.findViewById<android.widget.Button>(R.id.copySsidButton)
        val copyPasswordButton =
            dialogView.findViewById<android.widget.Button>(R.id.copyPasswordButton)
        val openWifiSettingsButton =
            dialogView.findViewById<android.widget.Button>(R.id.openWifiSettingsButton)

        ssidTextView.text = context.getString(R.string.wifi_profile_ssid, ssid)
        passwordTextView.text = context.getString(R.string.wifi_profile_password, password)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.wifi_profile_info))
            .setView(dialogView)
            .setCancelable(true)
            .setPositiveButton(context.getString(R.string.close), null)
            .create()

        copySsidButton.setOnClickListener {
            copyToClipboard(context, context.getString(R.string.ssid), ssid)
        }

        copyPasswordButton.setOnClickListener {
            copyToClipboard(context, context.getString(R.string.password), password)
        }

        openWifiSettingsButton.setOnClickListener {
            val wifiSettingsIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
            context.startActivity(wifiSettingsIntent)
        }

        dialog.show()
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun generateQrCode(content: String): android.graphics.Bitmap? {
        return try {
            val writer = com.google.zxing.qrcode.QRCodeWriter()
            val hints = hashMapOf<com.google.zxing.EncodeHintType, Any>()
            hints[com.google.zxing.EncodeHintType.MARGIN] = 1

            val bitMatrix =
                writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = android.graphics.Bitmap.createBitmap(
                width,
                height,
                android.graphics.Bitmap.Config.RGB_565
            )

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(
                        x,
                        y,
                        if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    )
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun showQrDialog(
        context: Context,
        ssid: String,
        qrBitmap: android.graphics.Bitmap
    ) {
        val builder = MaterialAlertDialogBuilder(context)
        val imageView = android.widget.ImageView(context)
        imageView.setImageBitmap(qrBitmap)
        imageView.setPadding(32, 32, 32, 32)

        builder.setTitle(context.getString(R.string.qr_code_generated_for, ssid))
            .setView(imageView)
            .setPositiveButton(context.getString(R.string.ok)) { dialog, _ ->
                dialog.dismiss()
            }
            .setNegativeButton(context.getString(R.string.save_to_gallery)) { _, _ ->
                saveQrToGallery(context, qrBitmap, ssid)
            }
            .show()
            .setOnDismissListener {
                qrBitmap.recycle()
            }
    }

    private fun saveQrToGallery(
        context: Context,
        bitmap: android.graphics.Bitmap,
        ssid: String
    ) {
        try {
            val filename = "wifi_qr_${
                ssid.replace(
                    ALPHANUMERIC_REGEX,
                    "_"
                )
            }_${System.currentTimeMillis()}.png"

            val saved = android.provider.MediaStore.Images.Media.insertImage(
                context.contentResolver,
                bitmap,
                filename,
                context.getString(R.string.qr_code_for_wifi, ssid)
            )

            if (saved != null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.qr_saved_successfully),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.qr_save_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.qr_save_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showQrCode(context: Context, result: NetworkDatabaseResult) {
        try {
            val wifiKey = result.databaseInfo["WiFiKey"]?.toString()
                ?: result.databaseInfo["wifi_pass"]?.toString()
                ?: result.databaseInfo["key"]?.toString()
                ?: ""

            val qrContent = if (wifiKey.isEmpty()) {
                "WIFI:S:${result.network.SSID};T:nopass;;"
            } else {
                val securityType = when {
                    result.network.capabilities.contains("WEP") -> "WEP"
                    result.network.capabilities.contains("WPA3") -> "WPA3"
                    else -> "WPA"
                }
                "WIFI:S:${result.network.SSID};T:$securityType;P:$wifiKey;;"
            }

            val qrBitmap = generateQrCode(qrContent)
            if (qrBitmap != null) {
                showQrDialog(context, result.network.SSID, qrBitmap)
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.qr_code_generation_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.qr_code_generation_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun capitalizeFirst(s: String) = s.replaceFirstChar { it.uppercase() }
}
