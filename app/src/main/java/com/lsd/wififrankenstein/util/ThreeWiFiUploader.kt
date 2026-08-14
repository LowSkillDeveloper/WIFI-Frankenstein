package com.lsd.wififrankenstein.util

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ThreeWiFiCsvRow(
    val ip: String = "",
    val port: String = "80",
    val auth: String = "",
    val title: String = "",
    val bssid: String = "",
    val essid: String = "",
    val sec: String = "",
    val key: String = "",
    val wps: String = "",
)

object ThreeWiFiUploader {

    data class UploadResult(
        val success: Boolean,
        val message: String
    )

    fun convertToCsv(rows: List<ThreeWiFiCsvRow>): String {
        return buildString {
            appendLine("IP Address;Port;;;Authorization;Server name / Realm name / Device type;Radio Off;Hidden;BSSID;ESSID;Security;Key;WPS PIN;LAN IP Address;LAN Subnet Mask;WAN IP Address;WAN Subnet Mask;WAN Gateway;Domain Name Servers")
            rows.forEach { r ->
                appendLine(
                    "${escCsv(r.ip)};${escCsv(r.port)};;;${escCsv(r.auth)};${escCsv(r.title)};0;0;${
                        escCsv(
                            r.bssid
                        )
                    };${escCsv(r.essid)};${escCsv(r.sec)};${escCsv(r.key)};${escCsv(r.wps)};${
                        escCsv(
                            r.ip
                        )
                    };;;;;"
                )
            }
        }
    }

    private fun escCsv(value: String): String {
        return if (value.contains(';') || value.contains('"') || value.contains('\n') || value.contains(
                '\r'
            )
        ) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }

    suspend fun uploadCsv(
        context: Context,
        server: DbItem,
        csvData: String,
        comment: String = "WiFi-Frankenstein"
    ): UploadResult = withContext(Dispatchers.IO) {
        val serverUrl = server.path.trimEnd('/')
        val uploadUrl = "$serverUrl/3wifi.php?a=upload"

        val url = buildString {
            append(uploadUrl)
            append(
                "&comment=${
                    URLEncoder.encode(
                        comment.ifBlank { "WiFi-Frankenstein" },
                        "UTF-8"
                    )
                }"
            )
            append("&checkexist=1")
            append("&done=1")
            if (!server.apiWriteKey.isNullOrBlank()) {
                append("&key=${URLEncoder.encode(server.apiWriteKey, "UTF-8")}")
            }
        }

        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            SslHelper.configure(connection)
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/csv")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            connection.outputStream.use { it.write(csvData.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                if (json.optBoolean("result", false)) {
                    val upload = json.optJSONObject("upload")
                    if (upload != null && upload.optBoolean("state", false)) {
                        UploadResult(true, context.getString(R.string.upl_uploaded, rowsCount(csvData)))
                    } else {
                        val errors = upload?.optJSONArray("error")
                        val errorMsg = if (errors != null && errors.length() > 0) {
                            context.getString(R.string.upl_server_error_code, errors.getInt(0))
                        } else {
                            context.getString(R.string.upl_upload_rejected)
                        }
                        UploadResult(false, errorMsg)
                    }
                } else {
                    UploadResult(false, json.optString("error", context.getString(R.string.upl_unknown_server_error)))
                }
            } else {
                UploadResult(false, context.getString(R.string.upl_http_code, responseCode))
            }
        } catch (e: Exception) {
            UploadResult(false, e.message ?: context.getString(R.string.upl_connection_failed))
        } finally {
            connection.disconnect()
        }
    }

    private fun rowsCount(csv: String): Int =
        csv.lines().count { it.isNotBlank() } - 1

    fun showServerPicker(
        context: Context,
        servers: List<DbItem>,
        title: CharSequence = context.getString(R.string.router_scan_upload_select_server),
        onSelected: (DbItem) -> Unit
    ) {
        if (servers.isEmpty()) return
        if (servers.size == 1) {
            onSelected(servers[0])
            return
        }
        val names = servers.map { s ->
            when {
                s.userNick != null -> "${s.userNick} (${s.path})"
                !s.apiWriteKey.isNullOrBlank() -> "${s.path} (API key)"
                else -> s.path
            }
        }.toTypedArray()
        var selected = 0
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(names, 0) { _, which -> selected = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSelected(servers[selected])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
