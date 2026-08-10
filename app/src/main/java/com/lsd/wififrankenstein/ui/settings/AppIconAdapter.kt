package com.lsd.wififrankenstein.ui.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import com.lsd.wififrankenstein.R

class AppIconAdapter(context: Context, icons: List<Pair<String, Int>>) :
    ArrayAdapter<Pair<String, Int>>(context, 0, icons) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createItemView(position, convertView, parent)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createItemView(position, convertView, parent)
    }

    private fun createItemView(position: Int, recycledView: View?, parent: ViewGroup): View {
        val view = recycledView ?: LayoutInflater.from(context).inflate(
            R.layout.spinner_item_app_icon,
            parent,
            false
        )

        val iconImage = view.findViewById<ImageView>(R.id.icon_image)
        val iconText = view.findViewById<TextView>(R.id.icon_text)

        getItem(position)?.let { (text, iconResId) ->
            iconImage.setImageResource(iconResId)
            iconText.text = text
        }

        return view
    }
}