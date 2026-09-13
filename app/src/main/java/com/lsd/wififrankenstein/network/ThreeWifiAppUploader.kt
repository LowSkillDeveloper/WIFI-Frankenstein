package com.lsd.wififrankenstein.network

import android.content.Context
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.PersonalWifiNetwork
import com.lsd.wififrankenstein.util.Log
import org.json.JSONArray
import org.json.JSONObject

object ThreeWifiAppUploader {

    const val TAG = "ThreeWifiAppUploader"
    const val BATCH_SIZE = 100

    data class UploadReport(
        val uploaded: Int,
        val failed: Int,
        val error: String? = null
    ) {
        val isSuccess: Boolean get() = failed == 0 && uploaded > 0
    }

    data class SubmitRecord(
        val bssid: String,
        val ssid: String,
        val security: String? = null,
        val password: String? = null,
        val wpsPin: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    suspend fun uploadWardriving(
        context: Context,
        server: DbItem,
        token: String,
        networks: List<PersonalWifiNetwork>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): UploadReport {
        val client = ThreeWifiAppClient.get(context, server.path)
        val valid = networks.filter(::isUploadable)
        if (valid.isEmpty()) return UploadReport(0, 0)

        var activeToken = token
        var uploaded = 0
        var failed = 0
        var firstError: String? = null

        val batches = valid.chunked(BATCH_SIZE)
        batches.forEachIndexed { index, chunk ->
            var error = attemptUpload(client, chunk, activeToken)

            if (error is ThreeWifiAppException && error.isUnauthorized) {
                val renewed = ThreeWifiAppSession.refresh(context, server)
                if (renewed != null) {
                    activeToken = renewed
                    error = attemptUpload(client, chunk, activeToken)
                }
            }

            if (error == null) {
                uploaded += chunk.size
            } else {
                failed += chunk.size
                if (firstError == null) firstError = error.message
            }
            onProgress(index + 1, batches.size)
        }
        return UploadReport(uploaded, failed, firstError)
    }

    private suspend fun attemptUpload(
        client: ThreeWifiAppClient,
        chunk: List<PersonalWifiNetwork>,
        token: String
    ): Throwable? {
        return try {
            client.uploadNetworks(buildNetworks(chunk, includeOptional = true), token)
            null
        } catch (e: ThreeWifiAppException) {
            if (e.isValidationError && e.missingFields.isNotEmpty()) {
                try {
                    client.uploadNetworks(buildNetworks(chunk, includeOptional = false), token)
                    null
                } catch (e2: ThreeWifiAppException) {
                    e2
                }
            } else {
                e
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wardriving upload failed", e)
            e
        }
    }

    suspend fun submitAll(
        context: Context,
        server: DbItem,
        token: String,
        records: List<SubmitRecord>
    ): UploadReport {
        var activeToken = token
        var uploaded = 0
        var failed = 0
        var firstError: String? = null

        records.forEachIndexed { index, record ->
            var error = attemptSubmit(context, server, activeToken, record)
            if (error is ThreeWifiAppException && error.isUnauthorized) {
                val renewed = ThreeWifiAppSession.refresh(context, server)
                if (renewed != null) {
                    activeToken = renewed
                    error = attemptSubmit(context, server, activeToken, record)
                }
            }

            if (error == null) {
                uploaded++
            } else {
                failed++
                if (firstError == null) firstError = error.message
            }
        }
        return UploadReport(uploaded, failed, firstError)
    }

    private suspend fun attemptSubmit(
        context: Context,
        server: DbItem,
        token: String,
        record: SubmitRecord
    ): Throwable? {
        return try {
            submitNetwork(
                context = context,
                server = server,
                token = token,
                bssid = record.bssid,
                ssid = record.ssid,
                securityType = record.security,
                password = record.password,
                wpsPin = record.wpsPin,
                latitude = record.latitude,
                longitude = record.longitude
            )
            null
        } catch (e: Exception) {
            Log.w(TAG, "submitNetwork failed for ${record.bssid}: ${e.message}")
            e
        }
    }

    private suspend fun submitNetwork(
        context: Context,
        server: DbItem,
        token: String,
        bssid: String,
        ssid: String,
        securityType: String?,
        password: String?,
        wpsPin: String?,
        latitude: Double?,
        longitude: Double?
    ): String {
        val client = ThreeWifiAppClient.get(context, server.path)
        val payload = JSONObject().apply {
            put("bssid", bssid.uppercase())
            put("ssid", ssid)
            put("securityType", securityLabel(securityType))
            if (!password.isNullOrBlank()) put("password", password)
            if (!wpsPin.isNullOrBlank()) put("wpsPin", wpsPin)
            if (latitude != null && longitude != null && (latitude != 0.0 || longitude != 0.0)) {
                put("latitude", latitude)
                put("longitude", longitude)
            }
        }
        val result = client.submitNetwork(payload, token)
        return (result as? JSONObject)?.optString("status")?.takeIf { it.isNotBlank() } ?: "added"
    }

    private fun buildNetworks(
        networks: List<PersonalWifiNetwork>,
        includeOptional: Boolean
    ): JSONArray {
        val array = JSONArray()
        networks.forEach { array.put(toNetworkJson(it, includeOptional)) }
        return array
    }

    private fun toNetworkJson(network: PersonalWifiNetwork, includeOptional: Boolean): JSONObject =
        JSONObject().apply {
            put("bssid", network.macAddress.uppercase())
            put("ssid", network.wifiName)
            put("securityType", securityLabel(network.securityType ?: network.security))
            put("latitude", network.latitude)
            put("longitude", network.longitude)
            if (includeOptional) {
                put("signal", network.level)
                if (network.channel > 0) put("channel", network.channel)
                if (network.frequency > 0) put("frequency", network.frequency)
                if (network.accuracy > 0f) put("accuracy", network.accuracy.toDouble())
                val timestamp = if (network.timestamp > 0) network.timestamp else network.firstSeen
                if (timestamp > 0) put("scanned_at", isoUtc(timestamp))
            }
        }

    private fun isoUtc(timestamp: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(timestamp))

    private fun securityLabel(raw: String?): String {
        val value = raw?.trim().orEmpty()
        val upper = value.uppercase()
        return when {
            upper.contains("WPA3") -> "WPA3"
            upper.contains("WPA2") -> "WPA2"
            upper.contains("WPA") -> "WPA"
            upper.contains("WEP") -> "WEP"
            upper.isEmpty() || upper == "OPEN" || upper == "NONE" -> "Open"
            else -> value
        }
    }

    private fun isUploadable(network: PersonalWifiNetwork): Boolean {
        val mac = network.macAddress.replace(":", "").replace("-", "")
        if (mac.length != 12 || mac.toLongOrNull(16) == null) return false
        return network.latitude != 0.0 || network.longitude != 0.0
    }
}
