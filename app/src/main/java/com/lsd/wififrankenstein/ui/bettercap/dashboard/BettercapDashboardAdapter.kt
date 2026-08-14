package com.lsd.wififrankenstein.ui.bettercap.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.network.bettercap.BettercapAP

enum class SortMode(@StringRes val labelRes: Int) {
    NAME_ASC(R.string.bc_sort_name_asc),
    NAME_DESC(R.string.bc_sort_name_desc),
    CHANNEL(R.string.bc_sort_channel),
    CLIENTS_DESC(R.string.bc_sort_clients_desc),
    CLIENTS_ASC(R.string.bc_sort_clients_asc),
    RSSI_DESC(R.string.bc_sort_rssi_desc),
    RSSI_ASC(R.string.bc_sort_rssi_asc)
}

class BettercapDashboardAdapter(
    private var items: List<BettercapAP> = emptyList(),
    private val onItemClick: (BettercapAP) -> Unit = {},
    private val onDeauthAll: (BettercapAP) -> Unit = {},
    private val onAssoc: (BettercapAP) -> Unit = {}
) : RecyclerView.Adapter<BettercapDashboardAdapter.ViewHolder>() {

    private var filterClientsOnly = false
    private var sortMode = SortMode.NAME_ASC

    private var filteredItems: List<BettercapAP> = items

    fun updateData(newItems: List<BettercapAP>) {
        items = newItems
        applyFilters()
    }

    fun setSortMode(mode: SortMode) {
        sortMode = mode
        applyFilters()
    }

    fun setFilterClientsOnly(enabled: Boolean) {
        filterClientsOnly = enabled
        applyFilters()
    }

    private fun applyFilters() {
        filteredItems = items
            .filter { ap ->
                !filterClientsOnly || ap.clients.isNotEmpty()
            }
            .sortedWith(
                when (sortMode) {
                    SortMode.NAME_ASC -> compareBy { it.hostname.lowercase() }
                    SortMode.NAME_DESC -> compareByDescending { it.hostname.lowercase() }
                    SortMode.CHANNEL -> compareBy { it.channel }
                    SortMode.CLIENTS_DESC -> compareByDescending { it.clients.size }
                    SortMode.CLIENTS_ASC -> compareBy { it.clients.size }
                    SortMode.RSSI_DESC -> compareByDescending<BettercapAP> { it.rssi }
                    SortMode.RSSI_ASC -> compareBy<BettercapAP> { it.rssi }
                })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bettercap_ap, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ap = filteredItems[position]
        holder.bind(ap, onItemClick, onDeauthAll, onAssoc)
    }

    override fun getItemCount(): Int = filteredItems.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textRssi: TextView = itemView.findViewById(R.id.textRssi)
        private val textBssid: TextView = itemView.findViewById(R.id.textBssid)
        private val textSsid: TextView = itemView.findViewById(R.id.textSsid)
        private val textEncryption: TextView = itemView.findViewById(R.id.textEncryption)
        private val textChannel: TextView = itemView.findViewById(R.id.textChannel)
        private val textClients: TextView = itemView.findViewById(R.id.textClients)
        private val textWps: TextView = itemView.findViewById(R.id.textWps)
        private val textHandshakeBadge: TextView = itemView.findViewById(R.id.textHandshakeBadge)

        fun bind(
            ap: BettercapAP,
            onClick: (BettercapAP) -> Unit,
            onDeauthAll: (BettercapAP) -> Unit,
            onAssoc: (BettercapAP) -> Unit
        ) {
            val ctx = itemView.context
            textRssi.text = ap.rssi.toString()
            textRssi.setTextColor(
                when {
                    ap.rssi >= -60 -> ContextCompat.getColor(ctx, R.color.success_green)
                    ap.rssi >= -75 -> ContextCompat.getColor(ctx, R.color.blue_500)
                    else -> ContextCompat.getColor(ctx, R.color.warning_orange)
                }
            )

            textBssid.text = ap.mac
            textSsid.text = ap.hostname.ifEmpty { ctx.getString(R.string.bc_hidden_ssid) }

            val encText = buildString {
                append(ap.encryption)
                if (ap.cipher.isNotEmpty() && ap.authentication.isNotEmpty()) {
                    append(" (${ap.cipher}, ${ap.authentication})")
                }
            }
            textEncryption.text = encText.ifEmpty { ctx.getString(R.string.bc_open) }

            textChannel.text = ap.channel.toString()
            textClients.text = ap.clients.size.toString()

            textWps.visibility = if (ap.wps.isNotEmpty()) View.VISIBLE else View.GONE
            textHandshakeBadge.visibility = if (ap.handshake) View.VISIBLE else View.GONE


            val card = itemView as? MaterialCardView
            if (card != null) {
                if (ap.handshake) {
                    card.setStrokeColor(
                        android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(ctx, R.color.success_green)
                        )
                    )
                    card.strokeWidth = 3
                } else {
                    card.setStrokeColor(
                        android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(ctx, R.color.divider_color)
                        )
                    )
                    card.strokeWidth = 1
                }
            }

            itemView.setOnClickListener { onClick(ap) }
            itemView.setOnLongClickListener {
                showContextMenu(ctx, ap, onClick, onDeauthAll, onAssoc)
                true
            }
        }

        private fun showContextMenu(
            ctx: Context,
            ap: BettercapAP,
            onClick: (BettercapAP) -> Unit,
            onDeauthAll: (BettercapAP) -> Unit,
            onAssoc: (BettercapAP) -> Unit
        ) {
            val items = arrayOf(
                ctx.getString(R.string.copy_bssid),
                ctx.getString(R.string.copy_ssid),
                ctx.getString(R.string.bc_menu_open_details),
                ctx.getString(R.string.bc_menu_deauth_all),
                ctx.getString(R.string.bc_menu_assoc)
            )
            MaterialAlertDialogBuilder(ctx)
                .setTitle(ap.hostname.ifEmpty { ap.mac })
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> copyToClipboard(ctx, "BSSID", ap.mac)
                        1 -> copyToClipboard(ctx, "SSID", ap.hostname)
                        2 -> onClick(ap)
                        3 -> onDeauthAll(ap)
                        4 -> onAssoc(ap)
                    }
                }
                .show()
        }

        private fun copyToClipboard(ctx: Context, label: String, text: String) {
            val clip = ClipData.newPlainText(label, text)
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                clip
            )
            Toast.makeText(ctx, ctx.getString(R.string.bc_copied, label), Toast.LENGTH_SHORT).show()
        }
    }
}
