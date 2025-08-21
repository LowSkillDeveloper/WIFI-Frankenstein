package com.lsd.wififrankenstein.ui.api3wifi

import android.content.Context
import com.lsd.wififrankenstein.util.SignatureVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class API3WiFiNetwork(
    private val context: Context,
    private val serverUrl: String,
    private val connectTimeout: Int,
    private val readTimeout: Int,
    private val ignoreSSL: Boolean,
    private val includeAppIdentifier: Boolean,
    private val apiReadKey: String = "000000000000",
    private val apiWriteKey: String? = null
) {
    private val client: OkHttpClient by lazy { createClient() }

    private fun createClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout.toLong(), TimeUnit.MILLISECONDS)

        if (ignoreSSL) {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }

        return builder.build()
    }

    suspend fun executeRequest(
        request: API3WiFiRequest,
        requestType: API3WiFiViewModel.RequestType
    ): String = withContext(Dispatchers.IO) {
        val url = "$serverUrl/api/${request.methodName}".toHttpUrl()

        when (requestType) {
            API3WiFiViewModel.RequestType.GET -> executeGetRequest(url, request)
            API3WiFiViewModel.RequestType.POST_FORM -> executePostFormRequest(url, request)
            API3WiFiViewModel.RequestType.POST_JSON -> executePostJsonRequest(url, request)
        }
    }

    private fun executeGetRequest(baseUrl: HttpUrl, request: API3WiFiRequest): String {
        val urlBuilder = baseUrl.newBuilder()
        addParamsToUrl(urlBuilder, request)

        val httpRequest = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()

        return executeHttpRequest(httpRequest)
    }

    private fun executePostFormRequest(url: HttpUrl, request: API3WiFiRequest): String {
        val formBuilder = FormBody.Builder()
        addParamsToForm(formBuilder, request)

        val httpRequest = Request.Builder()
            .url(url)
            .post(formBuilder.build())
            .build()

        return executeHttpRequest(httpRequest)
    }

    private fun executePostJsonRequest(url: HttpUrl, request: API3WiFiRequest): String {
        val jsonBody = createJsonBody(request)

        val httpRequest = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        return executeHttpRequest(httpRequest)
    }

    private fun executeHttpRequest(request: Request): String {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP error ${response.code}")
        }
        return response.body.string()
    }

    private fun addParamsToUrl(builder: HttpUrl.Builder, request: API3WiFiRequest) {
        when (request) {
            is API3WiFiRequest.ApiKeys -> {
                builder.addQueryParameter("login", request.login)
                    .addQueryParameter("password", request.password)
                    .addQueryParameter("genread", request.genRead.toString())
                    .addQueryParameter("genwrite", request.genWrite.toString())
            }
            is API3WiFiRequest.ApiQuery -> {
                builder.addQueryParameter("key", request.key)
                request.bssidList?.let { list ->
                    when {
                        list.size == 1 && list.first() == "*" -> builder.addQueryParameter("bssid", "*")
                        list.size == 1 -> builder.addQueryParameter("bssid", list.first())
                        else -> builder.addQueryParameter("bssid", JSONArray(list).toString())
                    }
                }
                request.essidList?.let { list ->
                    when {
                        list.size == 1 -> builder.addQueryParameter("essid", list.first())
                        else -> builder.addQueryParameter("essid", JSONArray(list).toString())
                    }
                }
                builder.addQueryParameter("sens", request.sens.toString())
            }
            is API3WiFiRequest.ApiWps -> {
                builder.addQueryParameter("key", request.key)
                if (request.bssidList.size == 1) {
                    builder.addQueryParameter("bssid", request.bssidList.first())
                } else {
                    builder.addQueryParameter("bssid", JSONArray(request.bssidList).toString())
                }
            }
            is API3WiFiRequest.ApiDev -> {
                builder.addQueryParameter("key", request.key)
                if (request.bssidList.size == 1) {
                    builder.addQueryParameter("bssid", request.bssidList.first())
                } else {
                    builder.addQueryParameter("bssid", JSONArray(request.bssidList).toString())
                }
                builder.addQueryParameter("nocli", request.nocli.toString())
            }
            is API3WiFiRequest.ApiRanges -> {
                builder.addQueryParameter("key", request.key)
                    .addQueryParameter("lat", request.lat.toString())
                    .addQueryParameter("lon", request.lon.toString())
                    .addQueryParameter("rad", request.rad.toString())
            }
        }
        if (includeAppIdentifier) {
            builder.addQueryParameter("appinfo", SignatureVerifier.getAppIdentifier(context))
        }
    }

    private fun addParamsToForm(builder: FormBody.Builder, request: API3WiFiRequest) {
        when (request) {
            is API3WiFiRequest.ApiKeys -> {
                builder.add("login", request.login)
                    .add("password", request.password)
                    .add("genread", request.genRead.toString())
                    .add("genwrite", request.genWrite.toString())
            }
            is API3WiFiRequest.ApiQuery -> {
                builder.add("key", request.key)
                request.bssidList?.let { list ->
                    if (list.size == 1 && list.first() == "*") {
                        builder.add("bssid", "*")
                    } else if (list.size == 1) {
                        builder.add("bssid", list.first())
                    } else {
                        builder.add("bssid", JSONArray(list).toString())
                    }
                }
                request.essidList?.let { list ->
                    if (list.size == 1) {
                        builder.add("essid", list.first())
                    } else {
                        builder.add("essid", JSONArray(list).toString())
                    }
                }
                builder.add("sens", request.sens.toString())
            }
            is API3WiFiRequest.ApiWps -> {
                builder.add("key", request.key)
                if (request.bssidList.size == 1) {
                    builder.add("bssid", request.bssidList.first())
                } else {
                    builder.add("bssid", JSONArray(request.bssidList).toString())
                }
            }
            is API3WiFiRequest.ApiDev -> {
                builder.add("key", request.key)
                if (request.bssidList.size == 1) {
                    builder.add("bssid", request.bssidList.first())
                } else {
                    builder.add("bssid", JSONArray(request.bssidList).toString())
                }
                builder.add("nocli", request.nocli.toString())
            }
            is API3WiFiRequest.ApiRanges -> {
                builder.add("key", request.key)
                    .add("lat", request.lat.toString())
                    .add("lon", request.lon.toString())
                    .add("rad", request.rad.toString())
            }
        }
        if (includeAppIdentifier) {
            builder.add("appinfo", SignatureVerifier.getAppIdentifier(context))
        }
    }

    private fun createJsonBody(request: API3WiFiRequest): JSONObject {
        val jsonObject = when (request) {
            is API3WiFiRequest.ApiKeys -> JSONObject().apply {
                put("login", request.login)
                put("password", request.password)
                put("genread", request.genRead)
                put("genwrite", request.genWrite)
            }
            is API3WiFiRequest.ApiQuery -> JSONObject().apply {
                put("key", request.key)
                request.bssidList?.let { list ->
                    if (list.size == 1 && list.first() == "*") {
                        put("bssid", "*")
                    } else {
                        put("bssid", if (list.size == 1) list.first() else JSONArray(list))
                    }
                }
                request.essidList?.let { list ->
                    put("essid", if (list.size == 1) list.first() else JSONArray(list))
                }
                put("sens", request.sens)
            }
            is API3WiFiRequest.ApiWps -> JSONObject().apply {
                put("key", request.key)
                put("bssid", if (request.bssidList.size == 1) request.bssidList.first() else JSONArray(request.bssidList))
            }
            is API3WiFiRequest.ApiDev -> JSONObject().apply {
                put("key", request.key)
                put("bssid", if (request.bssidList.size == 1) request.bssidList.first() else JSONArray(request.bssidList))
                put("nocli", request.nocli)
            }
            is API3WiFiRequest.ApiRanges -> JSONObject().apply {
                put("key", request.key)
                put("lat", request.lat)
                put("lon", request.lon)
                put("rad", request.rad)
            }
        }
        if (includeAppIdentifier) {
            jsonObject.put("appinfo", SignatureVerifier.getAppIdentifier(context))
        }
        return jsonObject

    }

}