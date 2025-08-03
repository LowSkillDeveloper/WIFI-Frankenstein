package com.lsd.wififrankenstein.ui.iwscanner

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.lsd.wififrankenstein.R

class InterfaceSpinnerAdapter(
    private val context: Context,
    private var interfaces: List<IwInterface>
) : BaseAdapter() {

    fun updateInterfaces(newInterfaces: List<IwInterface>) {
        interfaces = newInterfaces
        notifyDataSetChanged()
    }

    override fun getCount(): Int = interfaces.size

    override fun getItem(position: Int): IwInterface = interfaces[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_spinner_item, parent, false)
        val textView = view.findViewById<TextView>(android.R.id.text1)
        val interfaceItem = interfaces[position]

        textView.text = if (interfaceItem.type.isNotBlank()) {
            "${interfaceItem.name} (${interfaceItem.type})"
        } else {
            interfaceItem.name
        }

        return view
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_interface_dropdown, parent, false)
        val textName = view.findViewById<TextView>(R.id.textInterfaceName)
        val textDetails = view.findViewById<TextView>(R.id.textInterfaceDetails)

        val interfaceItem = interfaces[position]
        textName.text = interfaceItem.name

        val details = mutableListOf<String>()
        if (interfaceItem.type.isNotBlank()) details.add("Type: ${interfaceItem.type}")
        if (interfaceItem.addr.isNotBlank()) details.add("Addr: ${interfaceItem.addr}")

        textDetails.text = if (details.isNotEmpty()) {
            details.joinToString(" • ")
        } else {
            context.getString(R.string.iw_interface_no_details)
        }

        textDetails.visibility = if (details.isNotEmpty()) View.VISIBLE else View.GONE

        return view
    }
}