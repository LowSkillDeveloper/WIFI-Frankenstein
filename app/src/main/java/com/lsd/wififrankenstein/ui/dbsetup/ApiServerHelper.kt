package com.lsd.wififrankenstein.ui.dbsetup

import com.lsd.wififrankenstein.util.SslHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

object ApiServerHelper {

    suspend fun getApiKeysFromLogin(
        serverUrl: String,
        login: String,
        password: String,
        getUserErrorDesc: (String) -> String
    ): Triple<String?, String?, Pair<String, Int>?> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$serverUrl/api/apikeys")
            val connection = url.openConnection() as HttpURLConnection
            SslHelper.configure(connection)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val postData = "login=${URLEncoder.encode(login, "UTF-8")}&" +
                    "password=${URLEncoder.encode(password, "UTF-8")}&" +
                    "genread=1"

            connection.outputStream.use { it.write(postData.toByteArray()) }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)

            if (json.getBoolean("result")) {
                val profile = json.getJSONObject("profile")
                val keys = json.getJSONArray("data")

                var readKey: String? = null
                var writeKey: String? = null

                for (i in 0 until keys.length()) {
                    val keyData = keys.getJSONObject(i)
                    val access = keyData.getString("access")
                    when (access) {
                        "read" -> readKey = keyData.getString("key")
                        "write" -> writeKey = keyData.getString("key")
                    }
                }

                val userInfo = Pair(
                    profile.getString("nick"),
                    profile.getInt("level")
                )

                Triple(readKey, writeKey, userInfo)
            } else {
                val error = json.getString("error")
                val errorDesc = getUserErrorDesc(error)
                throw Exception(errorDesc)
            }
        } catch (e: Exception) {
            throw e
        }
    }

    fun createDbItemWithKeys(
        serverUrl: String,
        readKey: String,
        writeKey: String,
        authMethod: AuthMethod,
        typeString: String
    ): DbItem {
        var url = serverUrl
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        url = url.trimEnd('/')

        return DbItem(
            id = UUID.randomUUID().toString(),
            path = url,
            directPath = null,
            type = typeString,
            dbType = DbType.WIFI_API,
            apiReadKey = readKey,
            apiWriteKey = writeKey,
            authMethod = authMethod,
            originalSizeInMB = 0f,
            cachedSizeInMB = 0f
        )
    }

    fun createDbItemWithLogin(
        serverUrl: String,
        readKey: String,
        writeKey: String,
        login: String,
        password: String,
        authMethod: AuthMethod,
        userInfo: Pair<String, Int>?,
        typeString: String
    ): DbItem {
        var url = serverUrl
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        url = url.trimEnd('/')

        return DbItem(
            id = UUID.randomUUID().toString(),
            path = url,
            directPath = null,
            type = typeString,
            dbType = DbType.WIFI_API,
            apiReadKey = readKey,
            apiWriteKey = writeKey,
            login = login,
            password = password,
            authMethod = authMethod,
            userNick = userInfo?.first,
            userLevel = userInfo?.second,
            originalSizeInMB = 0f,
            cachedSizeInMB = 0f
        )
    }
}
