package com.lsd.wififrankenstein.ui.handshakecapture

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.BottomSheetMenu
import com.lsd.wififrankenstein.util.BottomSheetMenuItem

class HandshakeStorageAdapter(
    private val onVerify: (HandshakeItem) -> Unit,
    private val onUploadTo3WiFi: ((HandshakeItem) -> Unit)? = null,
    private val onCrack: (HandshakeItem) -> Unit,
    private val onExportHccapx: (HandshakeItem) -> Unit,
    private val onExport22000: (HandshakeItem) -> Unit,
    private val onShare: (HandshakeItem) -> Unit,
    private val onDelete: (HandshakeItem) -> Unit,
    private val onItemClick: (HandshakeItem) -> Unit,
    private val onSelectionChanged: ((Set<String>) -> Unit)? = null,
    private val onMultiSelectModeChanged: ((Boolean) -> Unit)? = null,
    private val onCopyHash22000: ((HandshakeItem) -> Unit)? = null,
    private val onCopyHashPmkid: ((HandshakeItem) -> Unit)? = null,
    private val onCopyHash16800: ((HandshakeItem) -> Unit)? = null,
    private val onCopyPassword: ((HandshakeItem) -> Unit)? = null,
    private val onCopySsid: ((HandshakeItem) -> Unit)? = null,
    private val onCopyBssid: ((HandshakeItem) -> Unit)? = null,
    private val onUploadWpaSec: ((HandshakeItem) -> Unit)? = null,
    private val onUploadOhc: ((HandshakeItem) -> Unit)? = null,
    private val onCheckWpaSec: ((HandshakeItem) -> Unit)? = null,
    private val hasChroot: Boolean = true
) : ListAdapter<HandshakeItem, HandshakeStorageAdapter.ViewHolder>(DIFF_CALLBACK) {

    var isMultiSelectMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) selectedFilePaths.clear()
                notifyDataSetChanged()
                onSelectionChanged?.invoke(selectedFilePaths)
                onMultiSelectModeChanged?.invoke(value)
            }
        }

    val selectedFilePaths: MutableSet<String> = mutableSetOf()

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<HandshakeItem>() {
            override fun areItemsTheSame(oldItem: HandshakeItem, newItem: HandshakeItem): Boolean =
                oldItem.filePath == newItem.filePath

            override fun areContentsTheSame(
                oldItem: HandshakeItem,
                newItem: HandshakeItem
            ): Boolean =
                oldItem == newItem
        }
    }

    fun toggleSelection(filePath: String) {
        if (selectedFilePaths.contains(filePath)) {
            selectedFilePaths.remove(filePath)
        } else {
            selectedFilePaths.add(filePath)
        }
        onSelectionChanged?.invoke(selectedFilePaths)
        notifyItemChanged(currentList.indexOfFirst { it.filePath == filePath })
    }

    fun selectAll() {
        selectedFilePaths.clear()
        selectedFilePaths.addAll(currentList.map { it.filePath })
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedFilePaths)
    }

    fun clearSelection() {
        selectedFilePaths.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedFilePaths)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_handshake_storage, parent, false)
        val context = view.context
        TooltipCompat.setTooltipText(
            view.findViewById(R.id.btn_hs_verify),
            context.getString(R.string.handshake_verify)
        )
        TooltipCompat.setTooltipText(
            view.findViewById(R.id.btn_hs_crack),
            context.getString(R.string.handshake_crack)
        )
        TooltipCompat.setTooltipText(
            view.findViewById(R.id.btn_hs_share),
            context.getString(R.string.handshake_share)
        )
        TooltipCompat.setTooltipText(
            view.findViewById(R.id.btn_hs_delete),
            context.getString(R.string.delete)
        )
        TooltipCompat.setTooltipText(
            view.findViewById(R.id.btn_hs_more),
            context.getString(R.string.handshake_more)
        )
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: View = itemView.findViewById(R.id.card_hs_root)
        private val checkSelect: CheckBox = itemView.findViewById(R.id.check_hs_select)
        private val textDisplayName: TextView = itemView.findViewById(R.id.text_hs_display_name)
        private val textFileInfo: TextView = itemView.findViewById(R.id.text_hs_file_info)
        private val textFileNotFound: TextView = itemView.findViewById(R.id.text_hs_file_not_found)
        private val textCrackedPassword: TextView =
            itemView.findViewById(R.id.text_hs_cracked_password)
        private val textStatus: TextView = itemView.findViewById(R.id.text_hs_status)
        private val iconPmkid: ImageView = itemView.findViewById(R.id.icon_hs_pmkid)
        private val iconCracked: ImageView = itemView.findViewById(R.id.icon_hs_cracked)
        private val btnVerify: MaterialButton = itemView.findViewById(R.id.btn_hs_verify)
        private val btnCrack: MaterialButton = itemView.findViewById(R.id.btn_hs_crack)
        private val btnShare: MaterialButton = itemView.findViewById(R.id.btn_hs_share)
        private val btnDelete: MaterialButton = itemView.findViewById(R.id.btn_hs_delete)
        private val layoutHash22000: View = itemView.findViewById(R.id.layout_hs_hash_22000)
        private val textHash22000: TextView = itemView.findViewById(R.id.text_hs_hash_22000)
        private val iconCopy22000: ImageView = itemView.findViewById(R.id.icon_hs_copy_22000)
        private val layoutCrackedPassword: View =
            itemView.findViewById(R.id.layout_hs_cracked_password)
        private val iconCopyPassword: ImageView = itemView.findViewById(R.id.icon_hs_copy_password)
        private val layoutHashPmkid: View = itemView.findViewById(R.id.layout_hs_hash_pmkid)
        private val textHashPmkid: TextView = itemView.findViewById(R.id.text_hs_hash_pmkid)
        private val iconCopyPmkid: ImageView = itemView.findViewById(R.id.icon_hs_copy_pmkid)
        private val textWpaSecStatus: TextView = itemView.findViewById(R.id.text_hs_wpasec_status)
        private val btnMore: MaterialButton = itemView.findViewById(R.id.btn_hs_more)

        fun bind(item: HandshakeItem) {
            checkSelect.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
            checkSelect.isChecked = item.filePath in selectedFilePaths
            checkSelect.setOnClickListener { toggleSelection(item.filePath) }

            textDisplayName.text = item.displayName
            textFileInfo.text = "${item.dateFormatted}  |  ${item.formattedSize}"

            cardRoot.alpha = 1.0f
            if (!item.fileExists) {
                textFileNotFound.visibility = View.VISIBLE
                textFileNotFound.setTextColor(
                    ContextCompat.getColor(itemView.context, R.color.error_red)
                )
                textFileNotFound.text =
                    itemView.context.getString(R.string.handshake_file_not_found)
            } else if (item.isInvalidMacFilename) {
                textFileNotFound.visibility = View.VISIBLE
                textFileNotFound.setTextColor(
                    ContextCompat.getColor(itemView.context, R.color.warning_orange)
                )
                textFileNotFound.text =
                    itemView.context.getString(R.string.handshake_invalid_mac_filename)
            } else {
                textFileNotFound.visibility = View.GONE
            }

            if (item.hasPmkid) iconPmkid.visibility = View.VISIBLE else iconPmkid.visibility =
                View.GONE

            if (item.crackedPassword != null) {
                iconCracked.visibility = View.VISIBLE
                layoutCrackedPassword.visibility = View.VISIBLE
                textCrackedPassword.text =
                    itemView.context.getString(R.string.handshake_key_found, item.crackedPassword)
                iconCopyPassword.setOnClickListener { onCopyPassword?.invoke(item) }
            } else {
                iconCracked.visibility = View.GONE
                layoutCrackedPassword.visibility = View.GONE
            }

            when (item.isValid) {
                true -> {
                    textStatus.visibility = View.VISIBLE
                    textStatus.text = itemView.context.getString(R.string.handshake_valid)
                    textStatus.setTextColor(
                        ContextCompat.getColor(
                            itemView.context,
                            R.color.success_green
                        )
                    )
                }

                false -> {
                    textStatus.visibility = View.VISIBLE
                    textStatus.text = itemView.context.getString(R.string.handshake_invalid)
                    textStatus.setTextColor(
                        ContextCompat.getColor(
                            itemView.context,
                            R.color.error_red
                        )
                    )
                }

                null -> textStatus.visibility = View.GONE
            }

            if (item.hash22000 != null && item.hash22000.isNotBlank()) {
                layoutHash22000.visibility = View.VISIBLE
                textHash22000.text = item.hash22000.take(80)
                iconCopy22000.setOnClickListener { onCopyHash22000?.invoke(item) }
            } else {
                layoutHash22000.visibility = View.GONE
            }

            if (item.hashPmkid != null && item.hashPmkid.isNotBlank()) {
                layoutHashPmkid.visibility = View.VISIBLE
                textHashPmkid.text = item.hashPmkid.take(80)
                iconCopyPmkid.setOnClickListener { onCopyHashPmkid?.invoke(item) }
            } else {
                layoutHashPmkid.visibility = View.GONE
            }


            textWpaSecStatus.visibility = View.GONE
            if (item.wpasecPasswordFound) {
                textWpaSecStatus.visibility = View.VISIBLE
                textWpaSecStatus.text = itemView.context.getString(R.string.wpasec_password_found)
                textWpaSecStatus.setTextColor(
                    ContextCompat.getColor(
                        itemView.context,
                        R.color.success_green
                    )
                )
            } else if (item.uploadedToWpaSec && item.wpasecChecked) {
                textWpaSecStatus.visibility = View.VISIBLE
                textWpaSecStatus.text = itemView.context.getString(R.string.wpasec_not_found)
                textWpaSecStatus.setTextColor(
                    ContextCompat.getColor(
                        itemView.context,
                        R.color.text_secondary
                    )
                )
            } else if (item.uploadedToWpaSec) {
                textWpaSecStatus.visibility = View.VISIBLE
                textWpaSecStatus.text = itemView.context.getString(R.string.wpasec_uploaded)
                textWpaSecStatus.setTextColor(
                    ContextCompat.getColor(
                        itemView.context,
                        R.color.success_green
                    )
                )
            }

            val actionsVisible = !isMultiSelectMode
            btnVerify.visibility = if (actionsVisible && hasChroot) View.VISIBLE else View.GONE
            btnVerify.isEnabled = item.fileExists
            btnVerify.alpha = if (item.fileExists) 1f else 0.4f
            val hasHashes = item.hash22000 != null || item.hashPmkid != null
            btnCrack.visibility = if (actionsVisible) View.VISIBLE else View.GONE
            btnCrack.isEnabled = hasHashes
            btnCrack.alpha = if (hasHashes) 1f else 0.4f
            btnShare.visibility = if (actionsVisible) View.VISIBLE else View.GONE
            val shareable = item.fileExists || hasHashes || item.hash16800 != null
            btnShare.isEnabled = shareable
            btnShare.alpha = if (shareable) 1f else 0.4f
            btnDelete.visibility = if (actionsVisible) View.VISIBLE else View.GONE


            if (actionsVisible) {
                btnMore.visibility = View.VISIBLE
                btnMore.setOnClickListener { v ->
                    val context = v.context
                    val wpasecTitle = when {
                        item.hash22000 == null -> context.getString(R.string.wpasec_upload)
                        item.uploadedToWpaSec && !item.wpasecChecked -> context.getString(R.string.wpasec_check)
                        else -> context.getString(R.string.wpasec_upload)
                    }
                    val wpasecEnabled = item.hash22000 != null

                    val hasCrackedPassword = item.crackedPassword != null
                    val items = mutableListOf(
                        BottomSheetMenuItem(
                            R.id.action_upload_wpasec,
                            wpasecTitle,
                            R.drawable.ic_cloud_upload,
                            enabled = wpasecEnabled
                        ),
                        BottomSheetMenuItem(
                            R.id.action_check_wpasec,
                            context.getString(R.string.wpasec_check),
                            R.drawable.cloud_24px,
                            enabled = !item.bssid.isNullOrBlank() && !item.essid.isNullOrBlank()
                        ),
                        BottomSheetMenuItem(
                            R.id.action_upload_ohc,
                            context.getString(R.string.handshake_upload_onlinehashcrack),
                            R.drawable.ic_cloud_upload,
                            enabled = wpasecEnabled
                        ),
                        BottomSheetMenuItem(
                            R.id.action_upload_3wifi,
                            context.getString(R.string.upload_cracked_to_3wifi),
                            R.drawable.ic_file_upload,
                            enabled = hasCrackedPassword,
                            visible = hasCrackedPassword && onUploadTo3WiFi != null
                        ),
                        BottomSheetMenuItem(
                            R.id.action_copy_ssid,
                            context.getString(R.string.handshake_copy_ssid),
                            R.drawable.ic_content_copy,
                            enabled = !item.essid.isNullOrBlank()
                        ),
                        BottomSheetMenuItem(
                            R.id.action_copy_bssid,
                            context.getString(R.string.handshake_copy_bssid),
                            R.drawable.ic_content_copy,
                            enabled = !item.bssid.isNullOrBlank()
                        ),
                        BottomSheetMenuItem(
                            R.id.action_copy_hash_22000,
                            context.getString(R.string.handshake_copy_hash_22000),
                            R.drawable.ic_content_copy,
                            enabled = item.hash22000 != null || item.fileExists
                        ),
                        BottomSheetMenuItem(
                            R.id.action_copy_hash_pmkid,
                            context.getString(R.string.handshake_copy_hash_pmkid),
                            R.drawable.ic_content_copy,
                            enabled = item.hashPmkid != null || item.fileExists
                        ),
                        BottomSheetMenuItem(
                            R.id.action_copy_hash_16800,
                            context.getString(R.string.handshake_copy_hash_16800),
                            R.drawable.ic_content_copy,
                            enabled = item.hash16800 != null || item.fileExists
                        )
                    )

                    BottomSheetMenu.show(context, items = items) { menuItem ->
                        when (menuItem.id) {
                            R.id.action_upload_wpasec -> {
                                if (item.hash22000 != null && !item.uploadedToWpaSec) onUploadWpaSec?.invoke(
                                    item
                                )
                                else if (item.uploadedToWpaSec && !item.wpasecChecked) onCheckWpaSec?.invoke(
                                    item
                                )
                            }

                            R.id.action_check_wpasec -> onCheckWpaSec?.invoke(item)
                            R.id.action_upload_ohc -> onUploadOhc?.invoke(item)
                            R.id.action_upload_3wifi -> onUploadTo3WiFi?.invoke(item)
                            R.id.action_copy_ssid -> onCopySsid?.invoke(item)
                            R.id.action_copy_bssid -> onCopyBssid?.invoke(item)
                            R.id.action_copy_hash_22000 -> onCopyHash22000?.invoke(item)
                            R.id.action_copy_hash_pmkid -> onCopyHashPmkid?.invoke(item)
                            R.id.action_copy_hash_16800 -> onCopyHash16800?.invoke(item)
                        }
                    }
                }
            } else {
                btnMore.visibility = View.GONE
            }

            btnVerify.setOnClickListener { if (actionsVisible) onVerify(item) }
            btnCrack.setOnClickListener { if (actionsVisible) onCrack(item) }
            btnShare.setOnClickListener { if (actionsVisible) onShare(item) }
            btnDelete.setOnClickListener { if (actionsVisible) onDelete(item) }
            itemView.setOnClickListener {
                if (isMultiSelectMode) toggleSelection(item.filePath)
                else onItemClick(item)
            }
            itemView.setOnLongClickListener {
                if (!isMultiSelectMode) isMultiSelectMode = true
                toggleSelection(item.filePath)
                true
            }
        }
    }
}
