package com.lsd.wififrankenstein.network

import android.content.Context
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ThreeWifiAppClient private constructor(
    private val context: Context,
    val baseUrl: String
) {

    private val settings = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val http: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { buildHttpClient() }

    suspend fun request(
        procedure: String,
        payload: JSONObject,
        token: String? = null,
        mutation: Boolean = false,
        readTimeoutMs: Long? = null,
        tokenInBody: Boolean = true
    ): Any? = withContext(Dispatchers.IO) {
        val body = JSONObject(payload.toString())
        if (token != null && tokenInBody) {
            body.put(FIELD_TOKEN, token)
        }

        val request = if (mutation) {
            buildPostRequest(procedure, body, token)
        } else {
            buildGetRequest(procedure, body, token)
        }

        val response = execute(request, readTimeoutMs)
        response.use { res ->
            val text = res.body?.string().orEmpty()
            parseEnvelope(text, res.code)
        }
    }

    private fun buildGetRequest(procedure: String, payload: JSONObject, token: String?): Request {
        val envelope = JSONObject().put(KEY_JSON, payload)
        val encoded = URLEncoder.encode(envelope.toString(), "UTF-8")
        val url = "$baseUrl/$PATH_PREFIX/$procedure?$PARAM_INPUT=$encoded"
        return baseRequest(url, token).get().build()
    }

    private fun buildPostRequest(procedure: String, payload: JSONObject, token: String?): Request {
        val envelope = JSONObject().put(KEY_JSON, payload)
        val mediaType = "application/json".toMediaTypeOrNull()
        val body = envelope.toString().toRequestBody(mediaType)
        val url = "$baseUrl/$PATH_PREFIX/$procedure"
        return baseRequest(url, token).post(body).build()
    }

    private fun baseRequest(url: String, token: String?): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", ANDROID_USER_AGENT)
            .header("Accept", "application/json")
            .header(HEADER_APP_VERSION, appVersion)
            .header("Origin", baseUrl)
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    private fun execute(request: Request, readTimeoutMs: Long?): okhttp3.Response {
        val call = if (readTimeoutMs != null) {
            http.newBuilder()
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
        } else {
            http.newCall(request)
        }
        return call.execute()
    }

    private fun parseEnvelope(text: String, httpStatus: Int): Any? {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ThreeWifiAppException(
                trpcCode = CODE_UNKNOWN,
                httpStatus = httpStatus,
                serverMessage = text.take(512).ifBlank { "Empty server response" },
                cause = e
            )
        }

        root.optJSONObject("error")?.let { error ->
            throw error.toException(httpStatus)
        }

        val data = root.optJSONObject("result")?.optJSONObject("data")
        val value = when {
            data != null && data.has(KEY_JSON) -> data.opt(KEY_JSON)
            root.has(KEY_JSON) -> root.opt(KEY_JSON)
            else -> null
        }
        return if (value == null || value === JSONObject.NULL) null else value
    }

    private fun JSONObject.toException(httpStatus: Int): ThreeWifiAppException {
        val detail = optJSONObject("json") ?: this
        val data = detail.optJSONObject("data")
        return ThreeWifiAppException(
            trpcCode = detail.optInt("code", CODE_UNKNOWN),
            httpStatus = data?.optInt("httpStatus", httpStatus) ?: httpStatus,
            serverMessage = detail.optString("message").ifBlank { null },
            missingFields = extractMissingFields(data)
        )
    }

    private fun extractMissingFields(data: JSONObject?): List<String> {
        val fields =
            data?.optJSONObject("zodError")?.optJSONObject("fieldErrors") ?: return emptyList()
        return fields.keys().asSequence().toList()
    }

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(
                settings.getInt(KEY_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT).toLong(),
                TimeUnit.MILLISECONDS
            )
            .readTimeout(
                settings.getInt(KEY_READ_TIMEOUT, DEFAULT_READ_TIMEOUT).toLong(),
                TimeUnit.MILLISECONDS
            )
            .writeTimeout(DEFAULT_WRITE_TIMEOUT, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)

        if (settings.getBoolean(KEY_IGNORE_SSL, false)) {
            runCatching {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<X509Certificate>,
                        authType: String
                    ) {
                    }

                    override fun checkServerTrusted(
                        chain: Array<X509Certificate>,
                        authType: String
                    ) {
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAll, java.security.SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }
            }.onFailure { Log.w(TAG, "Unable to relax TLS checks", it) }
        }
        return builder.build()
    }

    private val appVersion: String
        get() = settings.getString(KEY_APP_VERSION, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_APP_VERSION

    suspend fun getAppVersion(): JSONObject? =
        request(PROC_APP_VERSION, JSONObject()) as? JSONObject

    suspend fun login(usernameOrEmail: String, password: String): ThreeWifiLoginOutcome {
        val payload = JSONObject()
            .put("usernameOrEmail", usernameOrEmail)
            .put("password", password)
        val result = try {
            request(PROC_LOGIN, payload, mutation = true)
        } catch (e: ThreeWifiAppException) {
            return ThreeWifiLoginOutcome.Rejected(e.serverMessage ?: e.message.orEmpty())
        }
        return interpretLogin(result)
    }

    suspend fun refreshSession(expiredToken: String): String? {
        val payload = JSONObject().put(FIELD_TOKEN, expiredToken)
        val result = request(PROC_REFRESH_SESSION, payload, mutation = true) as? JSONObject
        return result?.optString(FIELD_TOKEN)?.takeIf { it.isNotBlank() }
    }

    suspend fun verifyTwoFactor(pendingToken: String, code: String): ThreeWifiLoginOutcome {
        val payload = JSONObject()
            .put("pendingToken", pendingToken)
            .put("code", code)
        val result = try {
            request(PROC_VERIFY_TWO_FACTOR, payload, mutation = true)
        } catch (e: ThreeWifiAppException) {
            return ThreeWifiLoginOutcome.Rejected(e.serverMessage ?: e.message.orEmpty())
        }
        return interpretLogin(result)
    }

    suspend fun sendTwoFactorCode(pendingToken: String, channel: String) {
        val payload = JSONObject()
            .put("pendingToken", pendingToken)
            .put("channel", channel)
        request(PROC_SEND_TWO_FACTOR, payload, mutation = true)
    }

    suspend fun verifyDeviceLogin(pendingToken: String, code: String): ThreeWifiLoginOutcome {
        val payload = JSONObject()
            .put("pendingToken", pendingToken)
            .put("code", code)
        val result = try {
            request(PROC_VERIFY_DEVICE, payload, mutation = true)
        } catch (e: ThreeWifiAppException) {
            return ThreeWifiLoginOutcome.Rejected(e.serverMessage ?: e.message.orEmpty())
        }
        return interpretLogin(result)
    }

    suspend fun resendDeviceCode(pendingToken: String) {
        request(
            PROC_RESEND_DEVICE_CODE,
            JSONObject().put("pendingToken", pendingToken),
            mutation = true
        )
    }

    suspend fun getProfile(token: String): JSONObject? =
        request(PROC_PROFILE, JSONObject(), token) as? JSONObject

    suspend fun getNetworksByBounds(
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        limit: Int,
        token: String? = null
    ): JSONArray {
        val payload = JSONObject()
            .put("north", north)
            .put("south", south)
            .put("east", east)
            .put("west", west)
            .put("limit", limit)
        return toArray(
            request(
                PROC_NETWORKS_BY_BOUNDS,
                payload,
                token,
                tokenInBody = false
            )
        )
    }

    suspend fun getAccessPointDetails(id: Long, token: String): JSONObject? {
        val payload = JSONObject().put("id", id)
        return request(PROC_POINT_DETAILS, payload, token) as? JSONObject
    }

    suspend fun searchNetworks(
        query: String,
        byBssid: Boolean,
        limit: Int,
        token: String
    ): JSONArray {
        val payload = JSONObject()
            .put(if (byBssid) "bssid" else "ssid", query)
            .put("limit", limit)
        val result = request(PROC_SEARCH, payload, token)
        val obj = result as? JSONObject
        return obj?.optJSONArray("networks") ?: toArray(result)
    }

    suspend fun getNetworkPasswords(bssid: String, token: String): JSONArray {
        val payload = JSONObject().put("bssid", bssid)
        return toArray(request(PROC_PASSWORDS, payload, token))
    }

    suspend fun uploadNetworks(networks: JSONArray, token: String): Any? =
        request(
            PROC_UPLOAD_NETWORKS,
            JSONObject().put("networks", networks),
            token,
            mutation = true,
            readTimeoutMs = UPLOAD_READ_TIMEOUT_MS
        )

    suspend fun submitNetwork(payload: JSONObject, token: String): Any? =
        request(PROC_SUBMIT_NETWORK, payload, token, mutation = true)

    suspend fun submitNetworkPassword(bssid: String, password: String, token: String): Any? {
        val payload = JSONObject()
            .put("bssid", bssid)
            .put("password", password)
        return request(PROC_SUBMIT_PASSWORD, payload, token, mutation = true)
    }

    private fun interpretLogin(result: Any?): ThreeWifiLoginOutcome {
        val obj = result as? JSONObject
            ?: return ThreeWifiLoginOutcome.Rejected("Unexpected login response")

        obj.optString(FIELD_TOKEN).takeIf { it.isNotBlank() }?.let {
            return ThreeWifiLoginOutcome.Authenticated(it, obj.optJSONObject("user"))
        }

        val pending = obj.optString("pendingToken").takeIf { it.isNotBlank() }
        if (pending != null) {
            val deviceFlow = obj.optBoolean("needsDeviceVerification", false) ||
                    obj.optBoolean("deviceVerification", false)
            return if (deviceFlow) {
                ThreeWifiLoginOutcome.DeviceVerificationRequired(pending)
            } else {
                ThreeWifiLoginOutcome.TwoFactorRequired(pending)
            }
        }

        val error = obj.optString("error").takeIf { it.isNotBlank() }
        return ThreeWifiLoginOutcome.Rejected(error ?: "Login rejected")
    }

    private fun toArray(value: Any?): JSONArray = when (value) {
        is JSONArray -> value
        is JSONObject -> JSONArray().put(value)
        else -> JSONArray()
    }

    companion object {
        const val TAG = "ThreeWifiAppClient"
        const val DEFAULT_APP_VERSION = "1.9.16"

        private const val PREFS_NAME = "API3WiFiSettings"
        private const val KEY_APP_VERSION = "appVersion"
        private const val KEY_CONNECT_TIMEOUT = "connectTimeout"
        private const val KEY_READ_TIMEOUT = "readTimeout"
        private const val KEY_IGNORE_SSL = "ignoreSSLCertificate"

        private const val DEFAULT_CONNECT_TIMEOUT = 5000
        private const val DEFAULT_READ_TIMEOUT = 10000
        private const val DEFAULT_WRITE_TIMEOUT = 30000L
        private const val UPLOAD_READ_TIMEOUT_MS = 120000L

        private const val ANDROID_USER_AGENT = "okhttp/4.12.0"
        private const val HEADER_APP_VERSION = "X-App-Version"
        private const val PATH_PREFIX = "trpc"
        private const val PARAM_INPUT = "input"
        private const val KEY_JSON = "json"
        private const val FIELD_TOKEN = "token"

        const val CODE_UNKNOWN = 0
        const val CODE_BAD_REQUEST = -32600
        const val CODE_APP_UPDATE_REQUIRED = -32003
        const val CODE_NOT_FOUND = -32004
        const val CODE_METHOD_NOT_SUPPORTED = -32005

        private const val PROC_APP_VERSION = "getAppVersion"
        private const val PROC_LOGIN = "login"
        private const val PROC_REFRESH_SESSION = "refreshSession"
        private const val PROC_VERIFY_TWO_FACTOR = "verifyTwoFactor"
        private const val PROC_SEND_TWO_FACTOR = "sendTwoFactorCode"
        private const val PROC_VERIFY_DEVICE = "verifyDeviceLogin"
        private const val PROC_RESEND_DEVICE_CODE = "resendDeviceCode"
        private const val PROC_PROFILE = "getProfile"
        private const val PROC_NETWORKS_BY_BOUNDS = "getNetworksByBounds"
        private const val PROC_POINT_DETAILS = "getAccessPointDetails"
        private const val PROC_SEARCH = "searchNetworks"
        private const val PROC_PASSWORDS = "getNetworkPasswords"
        private const val PROC_UPLOAD_NETWORKS = "uploadNetworks"
        private const val PROC_SUBMIT_NETWORK = "submitNetwork"
        private const val PROC_SUBMIT_PASSWORD = "submitNetworkPassword"

        private val instances = HashMap<String, ThreeWifiAppClient>()

        fun get(context: Context, serverUrl: String): ThreeWifiAppClient {
            val normalized = normalizeUrl(serverUrl)
            return synchronized(instances) {
                instances.getOrPut(normalized) {
                    ThreeWifiAppClient(context.applicationContext, normalized)
                }
            }
        }

        fun invalidate() {
            synchronized(instances) { instances.clear() }
        }

        fun normalizeUrl(raw: String): String {
            var url = raw.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            return url.trimEnd('/')
        }
    }
}

sealed class ThreeWifiLoginOutcome {
    data class Authenticated(val token: String, val user: JSONObject?) : ThreeWifiLoginOutcome()

    data class TwoFactorRequired(val pendingToken: String) : ThreeWifiLoginOutcome()

    data class DeviceVerificationRequired(val pendingToken: String) : ThreeWifiLoginOutcome()

    data class Rejected(val reason: String) : ThreeWifiLoginOutcome()
}

class ThreeWifiAppException(
    val trpcCode: Int,
    val httpStatus: Int,
    val serverMessage: String?,
    val missingFields: List<String> = emptyList(),
    cause: Throwable? = null
) : Exception(serverMessage ?: "3wifi.app request failed", cause) {

    val requiresAppUpdate: Boolean get() = trpcCode == ThreeWifiAppClient.CODE_APP_UPDATE_REQUIRED
    val procedureNotFound: Boolean get() = trpcCode == ThreeWifiAppClient.CODE_NOT_FOUND
    val wrongMethod: Boolean get() = trpcCode == ThreeWifiAppClient.CODE_METHOD_NOT_SUPPORTED
    val isValidationError: Boolean get() = trpcCode == ThreeWifiAppClient.CODE_BAD_REQUEST
    val isUnauthorized: Boolean get() = httpStatus == 401
}
