package com.lsd.wififrankenstein.ui.bettercap.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.network.bettercap.BettercapClientStation

class BettercapClientAdapter(
    private var items: List<BettercapClientStation> = emptyList()
) : RecyclerView.Adapter<BettercapClientAdapter.ViewHolder>() {

    private val checkedMacs = mutableSetOf<String>()
    private val missingPolls = mutableMapOf<String, Int>()

    init {
        setHasStableIds(true)
    }

    fun updateData(newItems: List<BettercapClientStation>) {
        items = newItems.sortedWith(compareBy { it.mac })
        val presentMacs = items.map { it.mac }.toSet()
        val iterator = checkedMacs.iterator()
        while (iterator.hasNext()) {
            val mac = iterator.next()
            if (mac in presentMacs) {
                missingPolls.remove(mac)
            } else {
                val count = (missingPolls[mac] ?: 0) + 1
                missingPolls[mac] = count
                if (count >= 2) {
                    iterator.remove()
                    missingPolls.remove(mac)
                }
            }
        }
        notifyDataSetChanged()
    }

    fun getCheckedClients(): List<String> {
        return checkedMacs.toList()
    }

    fun setAllChecked(checked: Boolean) {
        checkedMacs.clear()
        if (checked) {
            checkedMacs.addAll(items.map { it.mac })
        }
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].mac.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bettercap_client, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val client = items[position]
        holder.bind(client, checkedMacs.contains(client.mac)) { isChecked ->
            if (isChecked) checkedMacs.add(client.mac)
            else checkedMacs.remove(client.mac)
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val checkClient: CheckBox = itemView.findViewById(R.id.checkClient)
        private val textClientMac: TextView = itemView.findViewById(R.id.textClientMac)
        private val textClientInfo: TextView = itemView.findViewById(R.id.textClientInfo)

        fun bind(client: BettercapClientStation, isChecked: Boolean, onToggle: (Boolean) -> Unit) {
            textClientMac.text = client.mac
            textClientInfo.text =
                itemView.context.getString(R.string.bc_client_info, client.rssi, client.vendor)
            checkClient.setOnCheckedChangeListener(null)
            checkClient.isChecked = isChecked
            checkClient.setOnCheckedChangeListener { _, checked -> onToggle(checked) }
            itemView.setOnClickListener { checkClient.isChecked = !checkClient.isChecked }
        }
    }
}
