package com.lsd.wififrankenstein.ui.databasefinder

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WpsGeneratorActivity
import com.lsd.wififrankenstein.databinding.ItemSearchResultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchResultsAdapter : PagingDataAdapter<SearchResult, SearchResultsAdapter.ViewHolder>(SearchResultDiffCallback()) {

    companion object {
        private const val TAG = "SearchResultsAdapter"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        Log.v(TAG, "Creating new ViewHolder")
        val startTime = System.nanoTime()
        val binding = ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val viewHolder = ViewHolder(binding)
        Log.v(TAG, "ViewHolder creation took ${(System.nanoTime() - startTime)/1000}μs")
        return viewHolder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        Log.v(TAG, "Binding ViewHolder at position $position")
        val startTime = System.nanoTime()
        val item = getItem(position)
        item?.let { holder.bind(it) }
        Log.v(TAG, "ViewHolder binding took ${(System.nanoTime() - startTime)/1000}μs")
    }

    class ViewHolder(private val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SearchResult) {
            val startTime = System.nanoTime()
            val context = binding.root.context

            Log.d(TAG, "Binding SearchResult: $item")

            fun getThemeTextColor(context: Context): Int {
                val typedValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.textColor, typedValue, true)
                return typedValue.data
            }

            binding.apply {
                textViewSsid.text = item.ssid
                textViewBssid.text = item.bssid

                val wpsPin = item.wpsPin
                val isValidWpsPin = wpsPin?.let {
                    it.length == 8 && it.all { char -> char.isDigit() }
                } != false

                textViewWpsPin.apply {
                    text = if (!wpsPin.isNullOrBlank()) {
                        context.getString(R.string.wps_pin_format, wpsPin)
                    } else {
                        context.getString(R.string.wps_pin_not_available)
                    }
                    setTextColor(if (!isValidWpsPin)
                        Color.RED
                    else getThemeTextColor(context))
                }

                val isValidPassword = item.password?.length?.let { it >= 8 } != false
                textViewPassword.apply {
                    text = if (!item.password.isNullOrBlank()) {
                        context.getString(R.string.password_format, item.password)
                    } else {
                        context.getString(R.string.password_not_available)
                    }
                    setTextColor(if (!isValidPassword)
                        Color.RED
                    else getThemeTextColor(context))
                }

                textViewSource.text = context.getString(R.string.source_format, formatSourcePath(item.source))

                buttonMore.setOnClickListener {
                    showPopupMenu(it, item)
                }

                buttonInfo.setOnClickListener {
                    showDetailDialog(item)
                }

                buttonMap.setOnClickListener {
                    handleMapButtonClick(item)
                }
            }

            Log.v(TAG, "View binding operations took ${(System.nanoTime() - startTime)/1000}μs")
        }

        private fun showDetailDialog(item: SearchResult) {
            val context = binding.root.context
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_record_details, null)

            val typedValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
            dialogView.setBackgroundColor(typedValue.data)

            val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)
            val scrollView = dialogView.findViewById<NestedScrollView>(R.id.scrollView)
            val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerViewDetails)
            val textViewError = dialogView.findViewById<TextView>(R.id.textViewError)

            val detailAdapter = DetailItemAdapter()
            recyclerView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = detailAdapter
                setHasFixedSize(true)
            }

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.detail_dialog_title)
                .setView(dialogView)
                .setPositiveButton(R.string.detail_close, null)
                .setCancelable(true)
                .show()

            val activity = (context as? FragmentActivity)
            val viewModel = activity?.let {
                ViewModelProvider(it)[DatabaseFinderViewModel::class.java]
            }

            if (viewModel == null) {
                textViewError.text = context.getString(R.string.detail_error)
                textViewError.visibility = View.VISIBLE
                progressBar.visibility = View.GONE
                return
            }

            val lifecycle = activity.lifecycle

            if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                activity.lifecycleScope.launch {
                    progressBar.visibility = View.VISIBLE
                    scrollView.visibility = View.GONE
                    textViewError.visibility = View.GONE

                    viewModel.getDetailData(item).collect { detailData ->
                        withContext(Dispatchers.Main) {
                            if (detailData.containsKey("error")) {
                                progressBar.visibility = View.GONE
                                textViewError.text = detailData["error"]?.toString() ?: context.getString(R.string.detail_error)
                                textViewError.visibility = View.VISIBLE
                            } else if (detailData.containsKey("message")) {
                                textViewError.text = detailData["message"]?.toString()
                                textViewError.visibility = View.VISIBLE
                            } else if (detailData.isEmpty()) {
                                progressBar.visibility = View.GONE
                                textViewError.text = context.getString(R.string.detail_no_data)
                                textViewError.visibility = View.VISIBLE
                            } else {
                                progressBar.visibility = View.GONE
                                textViewError.visibility = View.GONE
                                scrollView.visibility = View.VISIBLE

                                val detailItems = detailData.map { (key, value) ->
                                    val valueStr = when (value) {
                                        null -> "null"
                                        is Number -> value.toString()
                                        is String -> value
                                        else -> value.toString()
                                    }

                                    val sortOrder = when(key.uppercase()) {
                                        "BSSID", "ESSID", "WIFIKEY", "WPSPIN" -> 0
                                        else -> 1
                                    }

                                    DetailItem(key, valueStr, sortOrder)
                                }.sortedWith(compareBy({ it.sortOrder }, { it.key.uppercase() }))

                                detailAdapter.submitList(detailItems)
                            }
                        }
                    }
                }
            }
        }

        private fun handleMapButtonClick(item: SearchResult) {
            val context = binding.root.context

            Log.d("MapDebug", "Map button clicked for BSSID: ${item.bssid}, lat: ${item.latitude}, lon: ${item.longitude}")

            if (item.latitude != null && item.longitude != null &&
                (item.latitude != 0.0 || item.longitude != 0.0)) {
                Log.d("MapDebug", "Using coordinates from SearchResult: lat=${item.latitude}, lon=${item.longitude}")
                openMapWithCoordinates(context, item.ssid, item.latitude, item.longitude)
                return
            }

            val activity = (context as? FragmentActivity) ?: return
            val viewModel = ViewModelProvider(activity)[DatabaseFinderViewModel::class.java]

            val loadingToast = Toast.makeText(context, R.string.loading_coordinates, Toast.LENGTH_SHORT)
            loadingToast.show()

            activity.lifecycleScope.launch {
                try {
                    if (viewModel.dbSetupViewModel.dbList.value.isNullOrEmpty()) {
                        Log.d("MapDebug", "Waiting for database list to load...")
                        viewModel.dbSetupViewModel.loadDbList()

                        var waitAttempts = 0
                        while (viewModel.dbSetupViewModel.dbList.value.isNullOrEmpty() && waitAttempts < 5) {
                            delay(600)
                            waitAttempts++
                            Log.d("MapDebug", "Wait attempt $waitAttempts for database list")
                        }
                    }

                    val dbItem = viewModel.dbSetupViewModel.dbList.value?.find { it.path == item.source }

                    if (dbItem != null) {
                        Log.d("MapDebug", "Found database: ${dbItem.path}, type: ${dbItem.dbType}")
                        val coordinatesHelper = MapCoordinatesHelper(context)
                        val (lat, lon) = coordinatesHelper.getCoordinates(item.bssid, dbItem)

                        withContext(Dispatchers.Main) {

                            loadingToast.cancel()

                            if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                                Log.d("MapDebug", "Found coordinates: lat=$lat, lon=$lon")
                                openMapWithCoordinates(context, item.ssid, lat, lon)
                            } else {
                                Log.d("MapDebug", "Coordinates not found in database")
                                Toast.makeText(context, R.string.map_no_location, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Log.d("MapDebug", "Database not found: ${item.source}")
                            loadingToast.cancel()
                            Toast.makeText(context, R.string.database_not_found, Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MapDebug", "Error fetching coordinates", e)
                    withContext(Dispatchers.Main) {
                        loadingToast.cancel()
                        Toast.makeText(context, R.string.map_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        @SuppressLint("QueryPermissionsNeeded")
        private fun openMapWithCoordinates(context: Context, ssid: String, lat: Double, lon: Double) {
            val uri = "geo:$lat,$lon?q=$lat,$lon(${Uri.encode(ssid)})".toUri()
            val mapIntent = Intent(Intent.ACTION_VIEW, uri)

            if (mapIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(Intent.createChooser(mapIntent,
                    context.getString(R.string.map_choose_app)))
            } else {
                val browserUri = "https://maps.google.com/maps?q=$lat,$lon".toUri()
                val browserIntent = Intent(Intent.ACTION_VIEW, browserUri)
                context.startActivity(browserIntent)
            }
        }

        private fun formatSourcePath(path: String): String {
            return try {
                when {
                    path.startsWith("content://") -> {
                        val uri = path.toUri()
                        uri.lastPathSegment?.let { lastSegment ->
                            val decodedSegment = Uri.decode(lastSegment)
                            decodedSegment.substringAfterLast('/')
                        } ?: path
                    }
                    path.startsWith("file://") -> {
                        val uri = path.toUri()
                        uri.lastPathSegment ?: path
                    }
                    else -> {
                        path.substringAfterLast('/')
                    }
                }.substringAfterLast("%2F")
            } catch (e: Exception) {
                Log.e(TAG, "Error formatting source path: $path", e)
                path
            }
        }

        private fun showPopupMenu(view: View, item: SearchResult) {
            val context = view.context
            val popupMenu = PopupMenu(context, view)
            popupMenu.inflate(R.menu.menu_search_result)

            popupMenu.menu.findItem(R.id.action_copy_password)?.isVisible = !item.password.isNullOrBlank()
            popupMenu.menu.findItem(R.id.action_copy_wps)?.isVisible = !item.wpsPin.isNullOrBlank()
            popupMenu.menu.findItem(R.id.action_connect_wps)?.isVisible = !item.wpsPin.isNullOrBlank()

            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val isRootEnabled = prefs.getBoolean("enable_root", false)
            popupMenu.menu.findItem(R.id.action_connect_wps_root)?.isVisible = isRootEnabled && !item.wpsPin.isNullOrBlank()

            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_copy_ssid -> copyToClipboard("SSID", item.ssid)
                    R.id.action_copy_bssid -> copyToClipboard("BSSID", item.bssid)
                    R.id.action_copy_password -> item.password?.let { copyToClipboard("Password", it) }
                    R.id.action_copy_wps -> item.wpsPin?.let { copyToClipboard("WPS PIN", it) }
                    R.id.action_save_wifi -> saveWifiProfile(item)
                    R.id.action_connect_wps -> connectUsingWPS(item)
                    R.id.action_connect_wps_root -> {
                        Toast.makeText(context, context.getString(R.string.connect_wps_root_menu), Toast.LENGTH_SHORT).show()
                    }
                    R.id.action_open_generator -> openWpsGenerator(item)
                }
                true
            }
            popupMenu.show()
        }

        private fun saveWifiProfile(item: SearchResult) {
            val context = binding.root.context

            if (item.password.isNullOrEmpty()) {
                Toast.makeText(context, R.string.wifi_save_error, Toast.LENGTH_SHORT).show()
                return
            }

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(context, R.string.permission_location_required, Toast.LENGTH_SHORT).show()
                    return
                }

                val suggestion = WifiNetworkSuggestion.Builder()
                    .setSsid(item.ssid)
                    .setWpa2Passphrase(item.password)
                    .build()

                val suggestions = listOf(suggestion)
                val status = wifiManager.addNetworkSuggestions(suggestions)

                if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                    Toast.makeText(context, R.string.wifi_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.wifi_save_error, Toast.LENGTH_SHORT).show()
                }
            } else {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(context, R.string.permission_wifi_required, Toast.LENGTH_SHORT).show()
                    return
                }

                val conf = WifiConfiguration()
                conf.SSID = "\"" + item.ssid + "\""
                conf.preSharedKey = "\"" + item.password + "\""

                try {
                    val netId = wifiManager.addNetwork(conf)
                    if (netId != -1) {
                        wifiManager.enableNetwork(netId, false)
                        Toast.makeText(context, R.string.wifi_saved, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, R.string.wifi_save_error, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving WiFi profile", e)
                    Toast.makeText(context, R.string.wifi_save_error, Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun connectUsingWPS(item: SearchResult) {
            val context = binding.root.context
            val wpsPin = item.wpsPin

            if (wpsPin.isNullOrEmpty()) {
                Toast.makeText(context, R.string.wps_connection_error, Toast.LENGTH_SHORT).show()
                return
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CHANGE_WIFI_STATE)
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, R.string.permission_wifi_required, Toast.LENGTH_SHORT).show()
                return
            }

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            try {
                val wpsInfo = android.net.wifi.WpsInfo()
                wpsInfo.setup = android.net.wifi.WpsInfo.KEYPAD
                wpsInfo.pin = wpsPin

                wifiManager.startWps(wpsInfo, object : WifiManager.WpsCallback() {
                    override fun onStarted(pin: String?) {
                        Toast.makeText(context, R.string.wps_connection_started, Toast.LENGTH_SHORT).show()
                    }

                    override fun onSucceeded() {
                        Toast.makeText(context, "WPS подключение успешно", Toast.LENGTH_SHORT).show()
                    }

                    override fun onFailed(reason: Int) {
                        Toast.makeText(context,
                            context.getString(R.string.wps_connection_error) + ": " + reason,
                            Toast.LENGTH_SHORT).show()
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting with WPS", e)
                Toast.makeText(context, R.string.wps_connection_error, Toast.LENGTH_SHORT).show()
            }
        }

        private fun openWpsGenerator(item: SearchResult) {
            val context = binding.root.context
            val intent = Intent(context, WpsGeneratorActivity::class.java).apply {
                putExtra("BSSID", item.bssid)
            }
            context.startActivity(intent)
        }

        private fun copyToClipboard(label: String, text: String) {
            val context = binding.root.context
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
        }
    }

    class SearchResultDiffCallback : DiffUtil.ItemCallback<SearchResult>() {
        override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean {
            return oldItem.bssid == newItem.bssid && oldItem.source == newItem.source
        }

        override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean {
            return oldItem == newItem
        }
    }
}