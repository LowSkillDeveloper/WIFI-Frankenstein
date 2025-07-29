package com.lsd.wififrankenstein.ui.wifimap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.lsd.wififrankenstein.R

class NetworkRecordsAdapter(
    private val records: List<NetworkRecord>,
    private val context: Context,
    private val bssid: String
) : RecyclerView.Adapter<NetworkRecordsAdapter.RecordViewHolder>() {

    private val expandedPositions = mutableSetOf<Int>()

    inner class RecordViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textViewRecordTitle: TextView = view.findViewById(R.id.textViewRecordTitle)
        val textViewPasswordInfo: TextView = view.findViewById(R.id.textViewPasswordInfo)
        val textViewWpsInfo: TextView = view.findViewById(R.id.textViewWpsInfo)
        val textViewTimeInfo: TextView = view.findViewById(R.id.textViewTimeInfo)
        val imageViewExpand: ImageView = view.findViewById(R.id.imageViewExpand)
        val layoutRecordHeader: LinearLayout = view.findViewById(R.id.layoutRecordHeader)
        val layoutRecordDetails: LinearLayout = view.findViewById(R.id.layoutRecordDetails)

        val textViewDataEssid: TextView = view.findViewById(R.id.textViewDataEssid)
        val textViewDataBssid: TextView = view.findViewById(R.id.textViewDataBssid)
        val textViewDataPassword: TextView = view.findViewById(R.id.textViewDataPassword)
        val textViewDataWps: TextView = view.findViewById(R.id.textViewDataWps)
        val textViewDataTime: TextView = view.findViewById(R.id.textViewDataTime)

        val layoutDataPassword: LinearLayout = view.findViewById(R.id.layoutDataPassword)
        val layoutDataWps: LinearLayout = view.findViewById(R.id.layoutDataWps)
        val layoutDataTime: LinearLayout = view.findViewById(R.id.layoutDataTime)

        val buttonCopyDataEssid: ImageView = view.findViewById(R.id.buttonCopyDataEssid)
        val buttonCopyDataBssid: ImageView = view.findViewById(R.id.buttonCopyDataBssid)
        val buttonCopyDataPassword: ImageView = view.findViewById(R.id.buttonCopyDataPassword)
        val buttonCopyDataWps: ImageView = view.findViewById(R.id.buttonCopyDataWps)

        val layoutRouterModel: LinearLayout = view.findViewById(R.id.layoutRouterModel)
        val textViewRouterModel: TextView = view.findViewById(R.id.textViewRouterModel)
        val layoutAdminCredentials: LinearLayout = view.findViewById(R.id.layoutAdminCredentials)
        val recyclerViewAdminCredentials: RecyclerView = view.findViewById(R.id.recyclerViewAdminCredentials)
        val textViewHiddenStatus: TextView = view.findViewById(R.id.textViewHiddenStatus)
        val textViewWifiStatus: TextView = view.findViewById(R.id.textViewWifiStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_record, parent, false)
        return RecordViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
        val record = records[position]
        val isExpanded = expandedPositions.contains(position)

        holder.textViewRecordTitle.text = record.essid ?: context.getString(R.string.unknown_ssid)

        if (!record.password.isNullOrBlank()) {
            holder.textViewPasswordInfo.visibility = View.VISIBLE
            holder.textViewPasswordInfo.text = context.getString(R.string.password_format, record.password)
        } else {
            holder.textViewPasswordInfo.visibility = View.GONE
        }

        if (!record.wpsPin.isNullOrBlank()) {
            holder.textViewWpsInfo.visibility = View.VISIBLE
            holder.textViewWpsInfo.text = context.getString(R.string.wps_pin_format, record.wpsPin)
        } else {
            holder.textViewWpsInfo.visibility = View.GONE
        }

        if (!record.timeAdded.isNullOrBlank()) {
            holder.textViewTimeInfo.visibility = View.VISIBLE
            holder.textViewTimeInfo.text = context.getString(R.string.time_added_format, record.timeAdded)
        } else {
            holder.textViewTimeInfo.visibility = View.GONE
        }

        holder.layoutRecordDetails.visibility = if (isExpanded) View.VISIBLE else View.GONE
        holder.imageViewExpand.setImageResource(
            if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )

        holder.layoutRecordHeader.setOnClickListener {
            if (isExpanded) {
                expandedPositions.remove(position)
            } else {
                expandedPositions.add(position)
            }
            notifyItemChanged(position)
        }

        setupNetworkData(holder, record)
        setupRouterModel(holder, record)
        setupAdminCredentials(holder, record)
        setupNetworkStatus(holder, record)
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

        if (!record.password.isNullOrBlank()) {
            holder.layoutDataPassword.visibility = View.VISIBLE
            holder.textViewDataPassword.text = record.password
            holder.buttonCopyDataPassword.setOnClickListener {
                copyToClipboard(context.getString(R.string.password), record.password)
            }
        } else {
            holder.layoutDataPassword.visibility = View.GONE
        }

        if (!record.wpsPin.isNullOrBlank()) {
            holder.layoutDataWps.visibility = View.VISIBLE
            holder.textViewDataWps.text = record.wpsPin
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
            holder.recyclerViewAdminCredentials.layoutManager = LinearLayoutManager(context)
            holder.recyclerViewAdminCredentials.adapter = AdminCredentialsAdapter(
                record.adminCredentials, context
            )
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

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.copied_to_clipboard, label), Toast.LENGTH_SHORT).show()
    }

    fun showQrForRecord(record: NetworkRecord) {
        if (record.password.isNullOrBlank()) {
            Toast.makeText(context, context.getString(R.string.password_not_available), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val qrContent = "WIFI:S:${record.essid ?: ""};T:WPA;P:${record.password};;"
            val qrBitmap = generateQrCode(qrContent)

            if (qrBitmap != null) {
                showQrDialog(record.essid ?: context.getString(R.string.unknown_ssid), qrBitmap)
            } else {
                Toast.makeText(context, context.getString(R.string.qr_code_generation_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.qr_code_generation_failed), Toast.LENGTH_SHORT).show()
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
        val imageView = android.widget.ImageView(context)
        imageView.setImageBitmap(qrBitmap)
        imageView.setPadding(32, 32, 32, 32)

        builder.setTitle(context.getString(R.string.qr_code_generated_for, ssid))
            .setView(imageView)
            .setPositiveButton(context.getString(R.string.ok)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    override fun getItemCount() = records.size
}