package com.lsd.wififrankenstein.ui.wifimap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.lsd.wififrankenstein.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class NetworkRecordsAdapter(
    private val records: List<NetworkRecord>,
    private val context: Context,
    private val bssid: String,
    private val onSaveToLocalDb: ((NetworkRecord) -> Unit)? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) : RecyclerView.Adapter<NetworkRecordsAdapter.RecordViewHolder>() {

    private val wpsPinRegex = Regex("^\\d{8}$")
    private val dp8 = (8 * context.resources.displayMetrics.density).toInt()

    class RecordViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.itemCard)
        val databaseColorIndicator: View = view.findViewById(R.id.databaseColorIndicator)
        val buttonShowMore: com.google.android.material.button.MaterialButton =
            view.findViewById(R.id.buttonShowMore)
        val detailsContainer: LinearLayout = view.findViewById(R.id.detailsContainer)
        val textViewRecordTitle: TextView = view.findViewById(R.id.textViewRecordTitle)
        val textViewPasswordInfo: TextView = view.findViewById(R.id.textViewPasswordInfo)
        val textViewWpsInfo: TextView = view.findViewById(R.id.textViewWpsInfo)
        val textViewTimeInfo: TextView = view.findViewById(R.id.textViewTimeInfo)

        val textViewDataEssid: TextView = view.findViewById(R.id.textViewDataEssid)
        val textViewDataBssid: TextView = view.findViewById(R.id.textViewDataBssid)
        val textViewDataPassword: TextView = view.findViewById(R.id.textViewDataPassword)
        val textViewDataWps: TextView = view.findViewById(R.id.textViewDataWps)
        val textViewDataTime: TextView = view.findViewById(R.id.textViewDataTime)

        val layoutDataPassword: LinearLayout = view.findViewById(R.id.layoutDataPassword)
        val layoutDataWps: LinearLayout = view.findViewById(R.id.layoutDataWps)
        val layoutDataTime: LinearLayout = view.findViewById(R.id.layoutDataTime)

        val textViewDataSecurity: TextView = view.findViewById(R.id.textViewDataSecurity)
        val layoutDataSecurity: LinearLayout = view.findViewById(R.id.layoutDataSecurity)

        val textViewDataIp: TextView = view.findViewById(R.id.textViewDataIp)
        val layoutDataIp: LinearLayout = view.findViewById(R.id.layoutDataIp)

        val textViewDataLanIp: TextView = view.findViewById(R.id.textViewDataLanIp)
        val layoutDataLanIp: LinearLayout = view.findViewById(R.id.layoutDataLanIp)

        val textViewDataWanIp: TextView = view.findViewById(R.id.textViewDataWanIp)
        val layoutDataWanIp: LinearLayout = view.findViewById(R.id.layoutDataWanIp)

        val textViewDataIpRange: TextView = view.findViewById(R.id.textViewDataIpRange)
        val layoutDataIpRange: LinearLayout = view.findViewById(R.id.layoutDataIpRange)

        val textViewDataPort: TextView = view.findViewById(R.id.textViewDataPort)
        val layoutDataPort: LinearLayout = view.findViewById(R.id.layoutDataPort)

        val textViewDataLanMask: TextView = view.findViewById(R.id.textViewDataLanMask)
        val layoutDataLanMask: LinearLayout = view.findViewById(R.id.layoutDataLanMask)

        val textViewDataWanMask: TextView = view.findViewById(R.id.textViewDataWanMask)
        val layoutDataWanMask: LinearLayout = view.findViewById(R.id.layoutDataWanMask)

        val textViewDataWanGateway: TextView = view.findViewById(R.id.textViewDataWanGateway)
        val layoutDataWanGateway: LinearLayout = view.findViewById(R.id.layoutDataWanGateway)

        val textViewDataDns: TextView = view.findViewById(R.id.textViewDataDns)
        val layoutDataDns: LinearLayout = view.findViewById(R.id.layoutDataDns)

        val textViewDataNoWifiKey: TextView = view.findViewById(R.id.textViewDataNoWifiKey)
        val layoutDataNoWifiKey: LinearLayout = view.findViewById(R.id.layoutDataNoWifiKey)

        val textViewDataNoBssid: TextView = view.findViewById(R.id.textViewDataNoBssid)
        val layoutDataNoBssid: LinearLayout = view.findViewById(R.id.layoutDataNoBssid)

        val textViewDataNoWps: TextView = view.findViewById(R.id.textViewDataNoWps)
        val layoutDataNoWps: LinearLayout = view.findViewById(R.id.layoutDataNoWps)

        val textViewDataCmtid: TextView = view.findViewById(R.id.textViewDataCmtid)
        val layoutDataCmtid: LinearLayout = view.findViewById(R.id.layoutDataCmtid)

        val textViewDataSource: TextView = view.findViewById(R.id.textViewDataSource)
        val layoutDataSource: LinearLayout = view.findViewById(R.id.layoutDataSource)

        val textViewDataComment: TextView = view.findViewById(R.id.textViewDataComment)
        val layoutDataComment: LinearLayout = view.findViewById(R.id.layoutDataComment)

        val textViewDataTimeLong: TextView = view.findViewById(R.id.textViewDataTimeLong)
        val layoutDataTimeLong: LinearLayout = view.findViewById(R.id.layoutDataTimeLong)

        val layoutDataRawHeader: LinearLayout = view.findViewById(R.id.layoutDataRawHeader)
        val scrollViewRawData: android.widget.ScrollView = view.findViewById(R.id.scrollViewRawData)
        val layoutDataRawContent: LinearLayout = view.findViewById(R.id.layoutDataRawContent)

        val buttonCopyDataEssid: ImageView = view.findViewById(R.id.buttonCopyDataEssid)
        val buttonCopyDataBssid: ImageView = view.findViewById(R.id.buttonCopyDataBssid)
        val buttonCopyDataPassword: ImageView = view.findViewById(R.id.buttonCopyDataPassword)
        val buttonCopyDataWps: ImageView = view.findViewById(R.id.buttonCopyDataWps)

        val buttonSaveToLocalDb: com.google.android.material.button.MaterialButton =
            view.findViewById(R.id.buttonSaveToLocalDb)

        val layoutRouterModel: LinearLayout = view.findViewById(R.id.layoutRouterModel)
        val textViewRouterModel: TextView = view.findViewById(R.id.textViewRouterModel)
        val layoutAdminCredentials: LinearLayout = view.findViewById(R.id.layoutAdminCredentials)
        val recyclerViewAdminCredentials: RecyclerView =
            view.findViewById(R.id.recyclerViewAdminCredentials)
        val textViewHiddenStatus: TextView = view.findViewById(R.id.textViewHiddenStatus)
        val textViewWifiStatus: TextView = view.findViewById(R.id.textViewWifiStatus)
        val layoutNetworkConfig: LinearLayout = view.findViewById(R.id.layoutNetworkConfig)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_record, parent, false)
        return RecordViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
        val record = records[position]

        val dbColor = record.databaseColor
        if (dbColor != 0) {
            holder.databaseColorIndicator.setBackgroundColor(dbColor)
            val strokeDrawable =
                holder.card.background as? GradientDrawable ?: GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp8.toFloat()
                }
            strokeDrawable.apply {
                setColor(Color.TRANSPARENT)
                setStroke(2, dbColor)
            }
            holder.card.background = strokeDrawable
        } else {
            holder.databaseColorIndicator.setBackgroundColor(Color.GRAY)
            holder.card.background = null
        }

        holder.textViewRecordTitle.text = record.essid ?: context.getString(R.string.unknown_ssid)

        holder.buttonShowMore.setOnClickListener {
            if (holder.detailsContainer.visibility == View.VISIBLE) {
                holder.detailsContainer.visibility = View.GONE
                holder.buttonShowMore.text = context.getString(R.string.show_more)
            } else {
                holder.detailsContainer.visibility = View.VISIBLE
                holder.buttonShowMore.text = context.getString(R.string.show_less)
            }
        }

        holder.textViewPasswordInfo.visibility = View.VISIBLE
        if (!record.password.isNullOrBlank()) {
            holder.textViewPasswordInfo.text =
                context.getString(R.string.password_format, record.password)
        } else {
            holder.textViewPasswordInfo.text = context.getString(R.string.password_not_available)
        }

        if (!record.wpsPin.isNullOrBlank()) {
            holder.textViewWpsInfo.visibility = View.VISIBLE
            holder.textViewWpsInfo.text = context.getString(R.string.wps_pin_format, record.wpsPin)
            if (!isWpsPinValid(record.wpsPin)) {
                holder.textViewWpsInfo.setTextColor(Color.RED)
            }
        } else {
            holder.textViewWpsInfo.visibility = View.GONE
        }

        if (!record.timeAdded.isNullOrBlank()) {
            holder.textViewTimeInfo.visibility = View.VISIBLE
            holder.textViewTimeInfo.text =
                context.getString(R.string.time_added_format, record.timeAdded)
        } else {
            holder.textViewTimeInfo.visibility = View.GONE
        }

        setupNetworkData(holder, record)
        holder.buttonSaveToLocalDb.setOnClickListener {
            onSaveToLocalDb?.invoke(record)
        }

        setupRouterModel(holder, record)
        setupAdminCredentials(holder, record)
        setupNetworkStatus(holder, record)
        setupRawData(holder, record)
    }

    private fun setupNetworkData(holder: RecordViewHolder, record: NetworkRecord) {
        holder.textViewDataEssid.text = record.essid ?: context.getString(R.string.unknown_ssid)
        holder.buttonCopyDataEssid.setOnClickListener {
            copyToClipboard(context.getString(R.string.ssid), record.essid ?: "")
        }

        holder.textViewDataBssid.text = bssid
        holder.buttonCopyDataBssid.setOnClickListener {
            copyToClipboard(context.getString(R.string.bssid), bssid)
        }

        holder.layoutDataPassword.visibility = View.VISIBLE
        if (!record.password.isNullOrBlank()) {
            holder.textViewDataPassword.text = record.password
            holder.buttonCopyDataPassword.setOnClickListener {
                copyToClipboard(context.getString(R.string.password), record.password)
            }
        } else {
            holder.textViewDataPassword.text = context.getString(R.string.password_not_available)
            holder.buttonCopyDataPassword.setOnClickListener {
                Toast.makeText(
                    context,
                    context.getString(R.string.password_not_available),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        if (!record.wpsPin.isNullOrBlank()) {
            holder.layoutDataWps.visibility = View.VISIBLE
            holder.textViewDataWps.text = record.wpsPin
            if (!isWpsPinValid(record.wpsPin)) {
                holder.textViewDataWps.setTextColor(Color.RED)
            }
            holder.buttonCopyDataWps.setOnClickListener {
                copyToClipboard(context.getString(R.string.wps_pin), record.wpsPin)
            }
        } else {
            holder.layoutDataWps.visibility = View.GONE
        }

        if (!record.timeAdded.isNullOrBlank()) {
            holder.layoutDataTime.visibility = View.VISIBLE
            holder.textViewDataTime.text = record.timeAdded
        } else {
            holder.layoutDataTime.visibility = View.GONE
        }

        if (!record.security.isNullOrBlank()) {
            holder.layoutDataSecurity.visibility = View.VISIBLE
            holder.textViewDataSecurity.text = record.security
        } else {
            holder.layoutDataSecurity.visibility = View.GONE
        }

        val hasIpData = !record.ip.isNullOrBlank() || record.ipRaw != null ||
                !record.lanIp.isNullOrBlank() || record.lanIpRaw != null ||
                !record.wanIp.isNullOrBlank() || record.wanIpRaw != null ||
                record.port != null ||
                !record.lanMask.isNullOrBlank() || record.lanMaskRaw != null ||
                !record.wanMask.isNullOrBlank() || record.wanMaskRaw != null ||
                !record.wanGateway.isNullOrBlank() || record.wanGatewayRaw != null ||
                !record.dns1.isNullOrBlank() || record.dns1Raw != null ||
                !record.dns2.isNullOrBlank() || record.dns2Raw != null ||
                !record.dns3.isNullOrBlank() || record.dns3Raw != null ||
                record.iprange != null

        if (hasIpData) {
            holder.layoutNetworkConfig.visibility = View.VISIBLE
        } else {
            holder.layoutNetworkConfig.visibility = View.GONE
        }

        val ipDisplay = com.lsd.wififrankenstein.util.DbFieldFormatter.longToIpWithCidr(
            record.ipRaw,
            record.iprange
        )
        if (!ipDisplay.isNullOrBlank()) {
            holder.layoutDataIp.visibility = View.VISIBLE
            holder.textViewDataIp.text = ipDisplay
        } else if (!record.ip.isNullOrBlank()) {
            holder.layoutDataIp.visibility = View.VISIBLE
            holder.textViewDataIp.text = record.ip
        } else {
            holder.layoutDataIp.visibility = View.GONE
        }

        if (record.iprange != null) {
            holder.layoutDataIpRange.visibility = View.VISIBLE
            holder.textViewDataIpRange.text =
                com.lsd.wififrankenstein.util.DbFieldFormatter.iprangeLabel(context, record.iprange)
        } else {
            holder.layoutDataIpRange.visibility = View.GONE
        }

        if (record.port != null) {
            holder.layoutDataPort.visibility = View.VISIBLE
            holder.textViewDataPort.text = record.port.toString()
        } else {
            holder.layoutDataPort.visibility = View.GONE
        }

        val lanIpDisplay = com.lsd.wififrankenstein.util.DbFieldFormatter.longToIp(record.lanIpRaw)
        if (!lanIpDisplay.isNullOrBlank()) {
            holder.layoutDataLanIp.visibility = View.VISIBLE
            holder.textViewDataLanIp.text = lanIpDisplay
        } else if (!record.lanIp.isNullOrBlank()) {
            holder.layoutDataLanIp.visibility = View.VISIBLE
            holder.textViewDataLanIp.text = record.lanIp
        } else {
            holder.layoutDataLanIp.visibility = View.GONE
        }

        val wanIpDisplay = com.lsd.wififrankenstein.util.DbFieldFormatter.longToIp(record.wanIpRaw)
        if (!wanIpDisplay.isNullOrBlank()) {
            holder.layoutDataWanIp.visibility = View.VISIBLE
            holder.textViewDataWanIp.text = wanIpDisplay
        } else if (!record.wanIp.isNullOrBlank()) {
            holder.layoutDataWanIp.visibility = View.VISIBLE
            holder.textViewDataWanIp.text = record.wanIp
        } else {
            holder.layoutDataWanIp.visibility = View.GONE
        }

        val lanMaskDisplay =
            com.lsd.wififrankenstein.util.DbFieldFormatter.longToIp(record.lanMaskRaw)
        if (!lanMaskDisplay.isNullOrBlank()) {
            holder.layoutDataLanMask.visibility = View.VISIBLE
            holder.textViewDataLanMask.text = lanMaskDisplay
        } else if (!record.lanMask.isNullOrBlank()) {
            holder.layoutDataLanMask.visibility = View.VISIBLE
            holder.textViewDataLanMask.text = record.lanMask
        } else {
            holder.layoutDataLanMask.visibility = View.GONE
        }

        val wanMaskDisplay =
            com.lsd.wififrankenstein.util.DbFieldFormatter.longToIp(record.wanMaskRaw)
        if (!wanMaskDisplay.isNullOrBlank()) {
            holder.layoutDataWanMask.visibility = View.VISIBLE
            holder.textViewDataWanMask.text = wanMaskDisplay
        } else if (!record.wanMask.isNullOrBlank()) {
            holder.layoutDataWanMask.visibility = View.VISIBLE
            holder.textViewDataWanMask.text = record.wanMask
        } else {
            holder.layoutDataWanMask.visibility = View.GONE
        }

        val wanGatewayDisplay =
            com.lsd.wififrankenstein.util.DbFieldFormatter.longToIp(record.wanGatewayRaw)
        if (!wanGatewayDisplay.isNullOrBlank()) {
            holder.layoutDataWanGateway.visibility = View.VISIBLE
            holder.textViewDataWanGateway.text = wanGatewayDisplay
        } else if (!record.wanGateway.isNullOrBlank()) {
            holder.layoutDataWanGateway.visibility = View.VISIBLE
            holder.textViewDataWanGateway.text = record.wanGateway
        } else {
            holder.layoutDataWanGateway.visibility = View.GONE
        }

        val dns1Display = com.lsd.wififrankenstein.util.DbFieldFormatter.longToIp(record.dns1Raw)
        val dns2Display = com.lsd.wififrankenstein.util.DbFieldFormatter.longToIp(record.dns2Raw)
        val dns3Display = com.lsd.wififrankenstein.util.DbFieldFormatter.longToIp(record.dns3Raw)
        val dnsValues = listOfNotNull(
            dns1Display?.takeIf { it.isNotBlank() } ?: record.dns1?.takeIf { it.isNotBlank() },
            dns2Display?.takeIf { it.isNotBlank() } ?: record.dns2?.takeIf { it.isNotBlank() },
            dns3Display?.takeIf { it.isNotBlank() } ?: record.dns3?.takeIf { it.isNotBlank() }
        )
        if (dnsValues.isNotEmpty()) {
            holder.layoutDataDns.visibility = View.VISIBLE
            holder.textViewDataDns.text = dnsValues.joinToString(", ")
        } else {
            holder.layoutDataDns.visibility = View.GONE
        }

        if (record.noWifiKey != null) {
            holder.layoutDataNoWifiKey.visibility = View.VISIBLE
            holder.textViewDataNoWifiKey.text =
                com.lsd.wififrankenstein.util.DbFieldFormatter.noWifiKeyLabel(
                    context,
                    record.noWifiKey
                )
        } else {
            holder.layoutDataNoWifiKey.visibility = View.GONE
        }

        if (record.noBssid != null) {
            holder.layoutDataNoBssid.visibility = View.VISIBLE
            holder.textViewDataNoBssid.text =
                com.lsd.wififrankenstein.util.DbFieldFormatter.noBssidLabel(context, record.noBssid)
        } else {
            holder.layoutDataNoBssid.visibility = View.GONE
        }

        if (record.noWps != null) {
            holder.layoutDataNoWps.visibility = View.VISIBLE
            holder.textViewDataNoWps.text =
                com.lsd.wififrankenstein.util.DbFieldFormatter.noWpsLabel(context, record.noWps)
        } else {
            holder.layoutDataNoWps.visibility = View.GONE
        }

        if (record.cmtid != null) {
            holder.layoutDataCmtid.visibility = View.VISIBLE
            holder.textViewDataCmtid.text = record.cmtid.toString()
        } else {
            holder.layoutDataCmtid.visibility = View.GONE
        }

        val sourceLabel =
            com.lsd.wififrankenstein.util.DbFieldFormatter.sourceLabel(context, record.sourceRaw)
        if (!sourceLabel.isNullOrBlank()) {
            holder.layoutDataSource.visibility = View.VISIBLE
            holder.textViewDataSource.text = sourceLabel
        } else if (!record.source.isNullOrBlank()) {
            holder.layoutDataSource.visibility = View.VISIBLE
            holder.textViewDataSource.text = record.source
        } else {
            holder.layoutDataSource.visibility = View.GONE
        }

        if (!record.comment.isNullOrBlank()) {
            holder.layoutDataComment.visibility = View.VISIBLE
            holder.textViewDataComment.text = record.comment
        } else {
            holder.layoutDataComment.visibility = View.GONE
        }

        if (record.time != null) {
            holder.layoutDataTimeLong.visibility = View.VISIBLE
            holder.textViewDataTimeLong.text = record.time.toString()
        } else {
            holder.layoutDataTimeLong.visibility = View.GONE
        }
    }

    private fun setupRouterModel(holder: RecordViewHolder, record: NetworkRecord) {
        if (!record.routerModel.isNullOrBlank()) {
            holder.layoutRouterModel.visibility = View.VISIBLE
            holder.textViewRouterModel.text = record.routerModel
        } else {
            holder.layoutRouterModel.visibility = View.GONE
        }
    }

    private fun setupAdminCredentials(holder: RecordViewHolder, record: NetworkRecord) {
        if (record.adminCredentials.isNotEmpty()) {
            holder.layoutAdminCredentials.visibility = View.VISIBLE
            if (holder.recyclerViewAdminCredentials.layoutManager == null) {
                holder.recyclerViewAdminCredentials.layoutManager = LinearLayoutManager(context)
            }
            val existingAdapter = holder.recyclerViewAdminCredentials.adapter
            if (existingAdapter !is AdminCredentialsAdapter || (existingAdapter as AdminCredentialsAdapter).getItemCount() != record.adminCredentials.size) {
                holder.recyclerViewAdminCredentials.adapter = AdminCredentialsAdapter(
                    record.adminCredentials, context
                )
            }
        } else {
            holder.layoutAdminCredentials.visibility = View.GONE
        }
    }

    private fun setupNetworkStatus(holder: RecordViewHolder, record: NetworkRecord) {
        holder.textViewHiddenStatus.text = if (record.isHidden) {
            context.getString(R.string.yes)
        } else {
            context.getString(R.string.no)
        }

        holder.textViewWifiStatus.text = if (record.isWifiDisabled) {
            context.getString(R.string.yes)
        } else {
            context.getString(R.string.no)
        }
    }

    private fun setupRawData(holder: RecordViewHolder, record: NetworkRecord) {
        val displayedFields = setOf(
            "ESSID", "WiFiKey", "WPSPIN", "name", "Authorization", "Hidden", "RadioOff",
            "Security", "LANMask", "WANMask", "WANGateway", "DNS1", "DNS2", "DNS3",
            "NoWiFiKey", "NoBSSID", "NoWPS", "BSSID", "ip", "LANIP", "WANIP",
            "iprange", "port", "time", "cmtid", "source", "comment", "latitude", "longitude",
            "id", "NoBssid", "quadkey"
        )

        val unmapped = record.rawData.filterKeys { it !in displayedFields }

        if (unmapped.isNotEmpty()) {
            holder.layoutDataRawHeader.visibility = View.VISIBLE
            holder.scrollViewRawData.visibility = View.VISIBLE

            holder.layoutDataRawContent.removeAllViews()

            for ((key, value) in unmapped) {
                if (value != null && value.toString().isNotEmpty()) {
                    val formattedValue = when {
                        key.lowercase(Locale.ROOT) in listOf(
                            "ip",
                            "lanip",
                            "wanip",
                            "lanmask",
                            "wanmask",
                            "wangateway",
                            "dns1",
                            "dns2",
                            "dns3"
                        ) -> {
                            com.lsd.wififrankenstein.util.DbFieldFormatter.longToIp(value as? Long)
                                ?: value.toString()
                        }

                        key.lowercase(Locale.ROOT) == "time" -> {
                            com.lsd.wififrankenstein.util.DbFieldFormatter.formatTime(value)
                                ?: value.toString()
                        }

                        key.lowercase(Locale.ROOT) == "source" -> {
                            com.lsd.wififrankenstein.util.DbFieldFormatter.sourceLabel(
                                context,
                                value as? Int
                            ) ?: value.toString()
                        }

                        else -> value.toString()
                    }

                    val description =
                        com.lsd.wififrankenstein.util.DbFieldFormatter.fieldNameDescription(
                            context,
                            key
                        )

                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 4, 0, 4)
                    }

                    val label = TextView(context).apply {
                        text = if (description == key) {
                            key
                        } else {
                            context.getString(R.string.wm_raw_key_description, key, description)
                        }
                        textSize = 12f
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    }

                    val data = TextView(context).apply {
                        text = formattedValue
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            2f
                        )
                    }

                    row.addView(label)
                    row.addView(data)
                    holder.layoutDataRawContent.addView(row)
                }
            }
        } else {
            holder.layoutDataRawHeader.visibility = View.GONE
            holder.scrollViewRawData.visibility = View.GONE
        }
    }

    private fun isWpsPinValid(wpsPin: String?): Boolean {
        if (wpsPin.isNullOrBlank()) return true
        return wpsPin.matches(wpsPinRegex)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(
            context,
            context.getString(R.string.copied_to_clipboard, label),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun showQrForRecord(record: NetworkRecord) {
        if (record.password.isNullOrBlank()) {
            Toast.makeText(
                context,
                context.getString(R.string.password_not_available),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val securityType = when {
            record.security.isNullOrBlank() -> "WPA"
            record.security.equals("WEP", ignoreCase = true) -> "WEP"
            record.security.equals("WPA2", ignoreCase = true) -> "WPA2"
            record.security.equals("WPA3", ignoreCase = true) -> "WPA3"
            record.security.startsWith("WPA", ignoreCase = true) -> "WPA"
            else -> "WPA"
        }
        val qrContent = "WIFI:S:${record.essid ?: ""};T:$securityType;P:${record.password};;"
        val ssid = record.essid ?: context.getString(R.string.unknown_ssid)
        val failedMessage = context.getString(R.string.qr_code_generation_failed)

        coroutineScope.launch(Dispatchers.Default) {
            val qrBitmap = try {
                generateQrCode(qrContent)
            } catch (e: Exception) {
                null
            }

            withContext(Dispatchers.Main) {
                if (qrBitmap != null) {
                    showQrDialog(ssid, qrBitmap)
                } else {
                    Toast.makeText(context, failedMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun generateQrCode(content: String): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val hints = hashMapOf<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1

            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun showQrDialog(ssid: String, qrBitmap: Bitmap) {
        val builder = MaterialAlertDialogBuilder(context)
        val imageView = ImageView(context)
        imageView.setImageBitmap(qrBitmap)
        imageView.setPadding(32, 32, 32, 32)

        builder.setTitle(context.getString(R.string.qr_code_generated_for, ssid))
            .setView(imageView)
            .setPositiveButton(context.getString(R.string.ok)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
            .setOnDismissListener {
                qrBitmap.recycle()
            }
    }

    override fun getItemCount() = records.size
}
