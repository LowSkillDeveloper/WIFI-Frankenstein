package com.lsd.wififrankenstein

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsd.wififrankenstein.databinding.ActivityWpsGeneratorBinding
import com.lsd.wififrankenstein.databinding.ContentWpsGeneratorBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import com.lsd.wififrankenstein.util.WpsPinGenerator

data class WPSPin(
    var mode: Int,
    var name: String,
    var pin: String = "",
    var sugg: Boolean = false,
    var score: Double = 0.0,
    var additionalData: Map<String, Any?> = emptyMap(),
    var isFrom3WiFi: Boolean = false,
    var isExperimental: Boolean = false
)

data class Algo(val mode: Int, val name: String)

class WpsGeneratorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWpsGeneratorBinding
    private lateinit var contentBinding: ContentWpsGeneratorBinding
    private lateinit var bssid: String
    private val pinListAdapter = PinListAdapter()
    private val algos = mutableListOf<Algo>()
    private lateinit var dbSetupViewModel: DbSetupViewModel
    private lateinit var settingsViewModel: SettingsViewModel

    private var currentServerIndex = 0
    private var serverResults = mutableMapOf<String, List<WPSPin>>()

    private lateinit var wpsPinGenerator: WpsPinGenerator

    private fun applyCurrentTheme() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val colorTheme = prefs.getString("color_theme", "purple") ?: "purple"
        val nightMode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        AppCompatDelegate.setDefaultNightMode(nightMode)

        val isDarkTheme = when (nightMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }

        val themeResId = when (colorTheme) {
            "purple" -> if (isDarkTheme) R.style.Theme_WIFIFrankenstein_Purple_Night else R.style.Theme_WIFIFrankenstein_Purple
            "green" -> if (isDarkTheme) R.style.Theme_WIFIFrankenstein_Green_Night else R.style.Theme_WIFIFrankenstein_Green
            "blue" -> if (isDarkTheme) R.style.Theme_WIFIFrankenstein_Blue_Night else R.style.Theme_WIFIFrankenstein_Blue
            else -> if (isDarkTheme) R.style.Theme_WIFIFrankenstein_Purple_Night else R.style.Theme_WIFIFrankenstein_Purple
        }
        setTheme(themeResId)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        applyCurrentTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityWpsGeneratorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contentBinding = ContentWpsGeneratorBinding.bind(binding.root.findViewById(R.id.content_wps_generator))

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.wps_generator_title)

        dbSetupViewModel = ViewModelProvider(this)[DbSetupViewModel::class.java]
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        bssid = intent.getStringExtra("BSSID") ?: ""
        Log.d("WpsGeneratorActivity", "BSSID: $bssid")

        setupRecyclerView()
        setupServerNavigation()
        setupButtons()
        setupWebView()
        wpsPinGenerator = WpsPinGenerator()
    }

    private fun setupRecyclerView() {
        contentBinding.recyclerViewPins.apply {
            layoutManager = LinearLayoutManager(this@WpsGeneratorActivity)
            adapter = pinListAdapter
        }
    }

    private fun setupServerNavigation() {
        contentBinding.buttonPreviousServer.setOnClickListener {
            if (currentServerIndex > 0) {
                currentServerIndex--
                updateServerDisplay()
            }
        }

        contentBinding.buttonNextServer.setOnClickListener {
            if (currentServerIndex < serverResults.size - 1) {
                currentServerIndex++
                updateServerDisplay()
            }
        }
    }

    private fun setupButtons() {
        contentBinding.buttonMethod1.setOnClickListener {
            contentBinding.offlineNoticeCard.visibility = View.GONE
            lifecycleScope.launch {
                val usePostMethod = settingsViewModel.getUsePostMethod()
                clearPreviousList()
                getPinsFromAllServers(bssid, usePostMethod)
            }
        }

        contentBinding.buttonNewGenerator.setOnClickListener {
            Log.d("WpsGeneratorActivity", "New generator button clicked")
            clearPreviousList()
            generatePinsUsingNewGenerator()
        }

        contentBinding.buttonOfflineAlgorithms.setOnClickListener {
            Log.d("WpsGeneratorActivity", "Offline algorithm button clicked")
            clearPreviousList()
            generatePinsUsingOfflineAlgorithms()
        }

        contentBinding.buttonMethod3.setOnClickListener {
            Log.d("WpsGeneratorActivity", "Local database button clicked")
            clearPreviousList()
            btnLocalClick()
        }
    }

    private fun clearPreviousList() {
        pinListAdapter.submitList(emptyList())
        contentBinding.textViewMessage.text = ""
        contentBinding.serverSwitcherLayout.visibility = View.GONE
        contentBinding.offlineNoticeCard.visibility = View.GONE
    }

    private fun updateServerDisplay() {
        val servers = serverResults.keys.toList()
        if (servers.size > 1) {
            contentBinding.serverSwitcherLayout.visibility = View.VISIBLE
            val currentServer = servers[currentServerIndex]
            contentBinding.textViewCurrentServer.text = currentServer
            pinListAdapter.submitList(serverResults[currentServer])

            contentBinding.buttonPreviousServer.isEnabled = currentServerIndex > 0
            contentBinding.buttonNextServer.isEnabled = currentServerIndex < servers.size - 1
        } else {
            contentBinding.serverSwitcherLayout.visibility = View.GONE
            if (servers.isNotEmpty()) {
                pinListAdapter.submitList(serverResults[servers[0]])
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun updateMessage(message: String) {
        Log.d("WpsGeneratorActivity", "Update message: $message")
        contentBinding.textViewMessage.text = message
    }

    private fun btnLocalClick() {
        contentBinding.offlineNoticeCard.visibility = View.GONE
        lifecycleScope.launch {
            val pins = withContext(Dispatchers.IO) { getPinsFromDatabase(bssid) }
            if (pins.isEmpty()) {
                updateMessage(getString(R.string.no_pins_found))
                Log.d("WpsGeneratorActivity", "No pins found in local database")
            } else {
                updateMessage("")
                Log.d("WpsGeneratorActivity", "Found pins in local database: ${pins.joinToString(", ")}")
                pinListAdapter.submitList(pins)
            }
        }
    }

    private suspend fun getPinsFromDatabase(bssid: String): List<WPSPin> {
        return withContext(Dispatchers.IO) {
            val pinList = mutableListOf<WPSPin>()
            try {
                val dbFile = getFileFromInternalStorageOrAssets("wps_pin.db")

                val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
                val macPrefix = bssid.substring(0, 8).uppercase()
                Log.d("WpsGeneratorActivity", "Formatted MAC Prefix: $macPrefix")
                val cursor = db.rawQuery("SELECT pin FROM pins WHERE mac=?", arrayOf(macPrefix))
                cursor.use {
                    while (it.moveToNext()) {
                        val pin = it.getString(it.getColumnIndexOrThrow("pin"))
                        Log.d("WpsGeneratorActivity", "Found pin in database: $pin")
                        pinList.add(WPSPin(0, "Database", pin, isFrom3WiFi = false))
                    }
                }
                db.close()
            } catch (e: Exception) {
                Log.e("WpsGeneratorActivity", "Error accessing database", e)
            }
            pinList
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val htmlFilePath = getFileFromInternalStorageOrAssets("wpspin.html").absolutePath

        val htmlContent = File(htmlFilePath).readText()
        val modifiedHtmlContent = htmlContent.replace(
            Regex("<!--Google Analytics-->[\\s\\S]*?<!--/Google Analytics-->"),
            "<!-- Google Analytics blocked -->"
        )

        contentBinding.webView.apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            addJavascriptInterface(MyJavascriptInterface(), "JavaHandler")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    Log.d("WpsGeneratorActivity", "WebView page finished loading: $url")
                }
            }
        }
        Log.d("WpsGeneratorActivity", "Loading modified WebView content")
        contentBinding.webView.loadDataWithBaseURL(null, modifiedHtmlContent, "text/html", "UTF-8", null)
    }

    private fun getFileFromInternalStorageOrAssets(fileName: String): File {
        val file = File(filesDir, fileName)
        if (!file.exists()) {
            Log.d("WpsGeneratorActivity", "Copying $fileName from assets to internal storage")
            assets.open(fileName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            createVersionFile(fileName, "1.0")
        }
        return file
    }

    private fun createVersionFile(fileName: String, version: String) {
        val versionFileName = "${fileName.substringBeforeLast(".")}_version.json"
        try {
            val versionJson = JSONObject().put("version", version)
            openFileOutput(versionFileName, MODE_PRIVATE).use { output ->
                output.write(versionJson.toString().toByteArray())
            }
            Log.d("WpsGeneratorActivity", "Created version file for $fileName: version $version")
        } catch (e: Exception) {
            Log.e("WpsGeneratorActivity", "Error creating version file: $versionFileName", e)
        }
    }

    private fun generatePinsUsingOfflineAlgorithms() {

        contentBinding.offlineNoticeCard.visibility = View.VISIBLE

        Log.d("WpsGeneratorActivity", "Generating pins using offline algorithms")
        contentBinding.webView.loadUrl("javascript:initAlgos();window.JavaHandler.initAlgos(JSON.stringify(algos), '$bssid');")
    }

    private fun generatePinsUsingNewGenerator() {
        contentBinding.offlineNoticeCard.visibility = View.GONE

        Log.d("WpsGeneratorActivity", "Generating pins using new generator for BSSID: $bssid")

        val suggestedPins = wpsPinGenerator.generateSuggestedPins(bssid, includeExperimental = true)
        val allPins = wpsPinGenerator.generateAllPins(bssid, includeExperimental = true)

        val wpsPins = mutableListOf<WPSPin>()

        suggestedPins.forEach { pinResult ->
            wpsPins.add(WPSPin(
                mode = 0,
                name = pinResult.algorithm,
                pin = pinResult.pin,
                sugg = true,
                score = if (pinResult.isSuggested) 1.0 else 0.0,
                additionalData = mapOf("mode" to pinResult.mode),
                isFrom3WiFi = false,
                isExperimental = pinResult.isExperimental
            ))
        }

        val nonSuggestedPins = allPins.filter { allPin ->
            suggestedPins.none { suggestedPin ->
                suggestedPin.pin == allPin.pin && suggestedPin.algorithm == allPin.algorithm
            }
        }

        nonSuggestedPins.forEach { pinResult ->
            wpsPins.add(WPSPin(
                mode = 0,
                name = pinResult.algorithm,
                pin = pinResult.pin,
                sugg = false,
                score = 0.0,
                additionalData = mapOf("mode" to pinResult.mode),
                isFrom3WiFi = false,
                isExperimental = pinResult.isExperimental
            ))
        }

        val sortedPins = wpsPins.sortedWith(compareByDescending<WPSPin> { it.sugg }.thenBy { it.isExperimental })

        pinListAdapter.submitList(sortedPins)
        contentBinding.recyclerViewPins.isEnabled = sortedPins.isNotEmpty()

        if (sortedPins.isEmpty()) {
            updateMessage(getString(R.string.no_pins_found))
        } else {
            val suggestedCount = suggestedPins.size
            if (suggestedCount > 0) {
                updateMessage(getString(R.string.pins_generated_with_suggested, suggestedCount))
            } else {
                updateMessage(getString(R.string.pins_generated))
            }
        }
    }

    private suspend fun getPinsFromAllServers(bssid: String, usePostMethod: Boolean) {
        val uppercaseBssid = bssid.uppercase(Locale.ROOT)

        val servers = dbSetupViewModel.getWifiApiDatabases()
        if (servers.isEmpty()) {
            withContext(Dispatchers.Main) {
                updateMessage(getString(R.string.no_servers_configured))
            }
            return
        }

        serverResults.clear()
        currentServerIndex = 0

        withContext(Dispatchers.Main) {
            contentBinding.serverSwitcherLayout.visibility = View.GONE
        }

        withContext(Dispatchers.IO) {
            servers.forEach { server ->
                try {
                    val url = if (usePostMethod) {
                        URL("${server.path}/api/apiwps")
                    } else {
                        URL("${server.path}/api/apiwps?key=${server.apiKey}&bssid=$uppercaseBssid")
                    }

                    val connection = url.openConnection() as HttpURLConnection
                    connection.apply {
                        if (usePostMethod) {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json")
                            doOutput = true

                            val jsonInputString = """
                            {
                                "key": "${server.apiKey}",
                                "bssid": ["$uppercaseBssid"]
                            }
                            """.trimIndent()

                            outputStream.use { os ->
                                val input = jsonInputString.toByteArray(charset("utf-8"))
                                os.write(input, 0, input.size)
                            }
                        } else {
                            requestMethod = "GET"
                        }
                    }

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonResponse = JSONObject(response)

                        if (jsonResponse.getBoolean("result")) {
                            val data = jsonResponse.optJSONObject("data")
                            if (data != null && data.has(uppercaseBssid)) {
                                val bssidData = data.getJSONObject(uppercaseBssid)
                                val scores = bssidData.optJSONArray("scores")

                                val serverPins = mutableListOf<WPSPin>()

                                scores?.let {
                                    for (i in 0 until it.length()) {
                                        val score = it.getJSONObject(i)
                                        val name = score.optString("name", "Unknown")
                                        val value = score.optString("value", "")
                                        val scoreValue = score.optDouble("score", 0.0)

                                        val additionalData = mutableMapOf<String, Any?>()
                                        score.keys().forEach { key ->
                                            if (key !in listOf("name", "value", "score")) {
                                                additionalData[key] = score.opt(key)
                                            }
                                        }

                                        serverPins.add(WPSPin(0, name, value, scoreValue > 0.5, scoreValue, additionalData, isFrom3WiFi = true))
                                    }
                                }

                                if (serverPins.isNotEmpty()) {
                                    serverResults[server.path] = serverPins
                                } else {
                                    Log.w("WpsGeneratorActivity", "No pins found for BSSID: $uppercaseBssid on server: ${server.path}")
                                }
                            } else {
                                Log.w("WpsGeneratorActivity", "No data found for BSSID: $uppercaseBssid on server: ${server.path}")
                            }
                        } else {
                            val errorMessage = jsonResponse.optString("error", "Unknown error")
                            Log.e("WpsGeneratorActivity", "Server error: $errorMessage")
                        }
                    } else {
                        Log.e("WpsGeneratorActivity", "HTTP Error: ${connection.responseCode}")
                    }
                } catch (e: Exception) {
                    Log.e("WpsGeneratorActivity", "Error getting pins from server ${server.path}", e)
                }
            }
        }

        withContext(Dispatchers.Main) {
            if (serverResults.isNotEmpty()) {
                updateMessage("")
                updateServerDisplay()
            } else {
                updateMessage(getString(R.string.no_pins_found))
            }
        }
    }

    inner class MyJavascriptInterface {

        // DO NOT DELETE initAlgos() AND getPins()!!

        @JavascriptInterface
        fun initAlgos(json: String?, bssid: String) {
            Log.d("MyJavascriptInterface", "initAlgos called with JSON: $json")
            algos.clear()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val mode = obj.optInt("mode", 0)
                    val name = obj.optString("name", "Unknown")
                    algos.add(Algo(mode, name))
                    Log.d("MyJavascriptInterface", "Added algorithm: $name, mode: $mode")
                }
                contentBinding.webView.post {
                    contentBinding.webView.loadUrl("javascript:window.JavaHandler.getPins(1,JSON.stringify(pinSuggestAPI(true,'$bssid',null)), '$bssid');")
                }
            } catch (e: JSONException) {
                Log.e("MyJavascriptInterface", "Error parsing JSON in initAlgos", e)
            }
        }

        @JavascriptInterface
        fun getPins(all: Int, json: String?, bssid: String) {
            try {
                val arr = JSONArray(json)
                val newPins = mutableListOf<WPSPin>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val pin = obj.optString("pin", "")
                    val algoIndex = obj.optInt("algo", -1)
                    val name = if (algoIndex != -1 && algoIndex < algos.size) algos[algoIndex].name else "Unknown"
                    val mode = if (algoIndex != -1 && algoIndex < algos.size) algos[algoIndex].mode else 0

                    val isSuggested = all <= 0

                    newPins.add(WPSPin(mode, name, pin, isSuggested))
                }

                runOnUiThread {
                    val sortedPins = newPins.sortedByDescending { it.sugg }
                    pinListAdapter.submitList(sortedPins)
                    contentBinding.recyclerViewPins.isEnabled = newPins.isNotEmpty()
                }
            } catch (e: JSONException) {
                Log.e("MyJavascriptInterface", "Error parsing JSON in getPins", e)
            }
        }
    }
}