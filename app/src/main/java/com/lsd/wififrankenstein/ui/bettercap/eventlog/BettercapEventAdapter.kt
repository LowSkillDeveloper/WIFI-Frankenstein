package com.lsd.wififrankenstein.ui.bettercap.eventlog

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.network.bettercap.BettercapEvent
import com.lsd.wififrankenstein.network.bettercap.EventTag
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class BettercapEventAdapter(
    private var items: List<BettercapEvent> = emptyList()
) : RecyclerView.Adapter<BettercapEventAdapter.ViewHolder>() {

    private var filterTag: String? = null

    private var filteredItems: List<BettercapEvent> = items

    fun updateData(newItems: List<BettercapEvent>) {
        items = newItems
        applyFilter()
    }

    fun setFilter(tag: String?) {
        filterTag = tag
        applyFilter()
    }

    fun clearFilter() {
        filterTag = null
        applyFilter()
    }

    private fun applyFilter() {
        filteredItems = if (filterTag == null) {
            items
        } else {
            items.filter { event ->
                event.tag.startsWith(filterTag!!, ignoreCase = true) ||
                        event.tag.contains(filterTag!!, ignoreCase = true)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bettercap_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = filteredItems[position]
        holder.bind(event)
    }

    fun getDisplayLines(): List<String> {
        return filteredItems.map { event ->
            val time = formatEventTime(event.time)
            val tag = event.tag.split(".").lastOrNull() ?: event.tag
            "$time [$tag] ${formatEventMessage(event)}"
        }
    }

    override fun getItemCount(): Int = filteredItems.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textEventTime: TextView = itemView.findViewById(R.id.textEventTime)
        private val textEventTag: TextView = itemView.findViewById(R.id.textEventTag)
        private val textEventMessage: TextView = itemView.findViewById(R.id.textEventMessage)

        fun bind(event: BettercapEvent) {
            val tag = EventTag.fromTag(event.tag)
            val tagDisplay = when (tag) {
                EventTag.AP_NEW, EventTag.AP_LOST -> "[AP]"
                EventTag.CLIENT_NEW, EventTag.CLIENT_LOST -> "[CLIENT]"
                EventTag.CLIENT_PROBE -> "[PROBE]"
                EventTag.CLIENT_HANDSHAKE -> "[HANDSHAKE]"
                EventTag.DEAUTH -> "[DEAUTH]"
                EventTag.MOD_STARTED -> "[MODULE]"
                EventTag.MOD_STOPPED -> "[MODULE]"
                null -> "[${event.tag.split(".").firstOrNull() ?: "?"}]"
            }

            val tagColor = when (tag) {
                EventTag.AP_NEW, EventTag.AP_LOST -> Color.rgb(33, 150, 243)
                EventTag.CLIENT_HANDSHAKE -> Color.rgb(76, 175, 80)
                EventTag.DEAUTH -> Color.rgb(244, 67, 54)
                EventTag.CLIENT_PROBE -> Color.rgb(255, 193, 7)
                else -> Color.GRAY
            }

            textEventTime.text = formatEventTime(event.time)
            textEventTag.text = tagDisplay
            textEventTag.setTextColor(tagColor)

            textEventMessage.text = formatEventMessage(event)
        }
    }
}

private fun formatEventTime(time: String): String {

    if (time.length >= 19) return time.substring(11, 19)

    val millis = time.toLongOrNull()
    if (millis != null && millis > 0) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(millis))
    }
    return time.takeLast(8)
}

private fun formatEventMessage(event: BettercapEvent): String {
    val obj = event.data as? JsonObject ?: return event.tag
    return when (event.tag) {
        "wifi.ap.new", "wifi.ap.lost" -> {
            val hostname = obj.str("hostname")
            val mac = obj.str("mac")
            buildString {
                if (hostname.isNotEmpty()) append("SSID: $hostname")
                if (mac.isNotEmpty()) {
                    if (isNotEmpty()) append("  ")
                    append("BSSID: $mac")
                }
            }.ifBlank { event.tag }
        }

        "wifi.client.new", "wifi.client.lost" -> {
            val client = obj["client"] as? JsonObject
            val ap = obj["ap"] as? JsonObject
            val mac = client?.str("mac") ?: ""
            val vendor = client?.str("vendor") ?: ""
            val apName = ap?.str("hostname") ?: ap?.str("mac") ?: ""
            buildString {
                append("Client: $mac")
                if (vendor.isNotEmpty()) append(" ($vendor)")
                if (apName.isNotEmpty()) append(" @ $apName")
            }
        }

        "wifi.client.probe" -> {
            val mac = obj.str("mac")
            val essid = obj.str("essid")
            buildString {
                append("Probe: $mac")
                if (essid.isNotEmpty()) append(" -> $essid")
            }
        }

        "wifi.deauthentication" -> {
            val addr1 = obj.str("address1")
            val addr2 = obj.str("address2")
            val reason = obj.str("reason")
            buildString {
                append("Deauth: $addr1 -> $addr2")
                if (reason.isNotEmpty()) append(" ($reason)")
            }
        }

        "wifi.client.handshake" -> {
            val ap = obj.str("ap")
            val station = obj.str("station")
            val status = when {
                obj.str("full") == "true" -> "FULL"
                obj.str("half") == "true" -> "HALF"
                else -> "PARTIAL"
            }
            "Handshake: AP=$ap station=$station [$status]"
        }

        "sys.log" -> {
            obj.str("Message").ifBlank { obj.str("message") }.ifBlank { event.tag }
        }

        "mod.started", "mod.stopped" -> {
            val name = obj.str("name")
            "Module: ${name.ifBlank { event.tag }}"
        }

        else -> jsonToLines(obj).ifBlank { event.tag }
    }
}

private fun JsonObject.str(key: String): String {
    val el = this[key] ?: return ""
    if (el is JsonNull) return ""
    return (el as? JsonPrimitive)?.contentOrNull ?: ""
}

private fun jsonToLines(obj: JsonObject): String {
    val sb = StringBuilder()
    for ((k, v) in obj) {
        if (v is JsonNull) continue
        val text = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
        if (sb.isNotEmpty()) sb.append("  ")
        sb.append(k).append(": ").append(text)
    }
    return sb.toString()
}
