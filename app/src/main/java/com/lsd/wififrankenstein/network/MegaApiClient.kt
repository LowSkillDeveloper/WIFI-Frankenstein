package com.lsd.wififrankenstein.network

import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom

data class MegaFileInfo(
    val downloadUrl: String,
    val size: Long,
    val encryptedAttrs: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MegaFileInfo) return false
        return downloadUrl == other.downloadUrl && size == other.size &&
                encryptedAttrs.contentEquals(other.encryptedAttrs)
    }

    override fun hashCode(): Int {
        var result = downloadUrl.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + encryptedAttrs.contentHashCode()
        return result
    }
}

class MegaApiException(message: String, cause: Throwable? = null) : IOException(message, cause)

class MegaApiClient(private val client: OkHttpClient) {

    companion object {
        private const val API_BASE = "https://g.api.mega.co.nz"
        private const val APP_KEY = "BdARkQSQ"
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    suspend fun getFileInfo(handle: String): MegaFileInfo = withContext(Dispatchers.IO) {
        Log.d("MegaApiClient", "Fetching file info for handle: $handle")
        val seqno = ThreadLocalRandom.current().nextInt(1_000_000, Int.MAX_VALUE)
        val url = "$API_BASE/cs?id=$seqno"
        val body = JSONArray().apply {
            put(JSONObject().apply {
                put("a", "g")
                put("g", 1)
                put("ssl", 2)
                put("p", handle)
            })
        }
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .header("User-Agent", APP_KEY)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw MegaApiException("Empty response from MEGA API")

        Log.d("MegaApiClient", "MEGA API response: $responseBody")

        if (!response.isSuccessful) {
            throw MegaApiException("MEGA API returned ${response.code}: $responseBody")
        }

        val jsonArray = JSONArray(responseBody)
        if (jsonArray.length() == 0) {
            throw MegaApiException("MEGA API returned empty array")
        }

        val obj = jsonArray.getJSONObject(0)

        if (obj.has("e")) {
            val code = obj.getInt("e")
            throw MegaApiException("MEGA API error code $code for handle $handle")
        }

        val downloadUrl = obj.optString("g", null)
            ?: throw MegaApiException("No download URL in MEGA API response")
        val size = obj.optLong("s", -1L)
        val atB64 = obj.optString("at", null)
            ?: throw MegaApiException("No attributes (at) in MEGA API response")

        Log.d(
            "MegaApiClient",
            "Download URL: $downloadUrl, Size: $size, Attrs length: ${atB64.length}"
        )
        val fullUrl =
            if (downloadUrl.startsWith("http")) downloadUrl else "https://g.api.mega.co.nz/$downloadUrl"

        MegaFileInfo(
            downloadUrl = fullUrl,
            size = size,
            encryptedAttrs = MegaUrlParser.base64UrlDecode(atB64)
        )
    }
}
