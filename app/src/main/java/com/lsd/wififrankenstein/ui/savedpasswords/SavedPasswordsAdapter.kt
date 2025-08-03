package com.lsd.wififrankenstein.ui.savedpasswords

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.data.SavedWifiPassword

class SavedPasswordsAdapter(
    private val onPasswordClick: (SavedWifiPassword) -> Unit,
    private val onCopyPassword: (SavedWifiPassword) -> Unit,
    private val onShowQrCode: (SavedWifiPassword) -> Unit
) : ListAdapter<SavedWifiPassword, SavedPasswordsAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_password, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardView: MaterialCardView = itemView.findViewById(R.id.cardView)
        private val ssidText: TextView = itemView.findViewById(R.id.textSsid)
        private val passwordText: TextView = itemView.findViewById(R.id.textPassword)
        private val securityText: TextView = itemView.findViewById(R.id.textSecurity)
        private val bssidText: TextView = itemView.findViewById(R.id.textBssid)
        private val iconSecurity: ImageView = itemView.findViewById(R.id.iconSecurity)
        private val buttonCopyPassword: View = itemView.findViewById(R.id.buttonCopyPassword)
        private val buttonQrCode: View = itemView.findViewById(R.id.buttonQrCode)

        fun bind(password: SavedWifiPassword) {
            ssidText.text = password.displayName

            if (password.isOpenNetwork) {
                passwordText.text = itemView.context.getString(R.string.security_type_open)
                buttonCopyPassword.visibility = View.GONE
            } else {
                passwordText.text = if (password.password.isNotEmpty()) {
                    password.password
                } else {
                    itemView.context.getString(R.string.not_available)
                }
                buttonCopyPassword.visibility = if (password.password.isNotEmpty()) View.VISIBLE else View.GONE
            }

            securityText.text = itemView.context.getString(R.string.security_type_format, password.securityType)

            if (password.bssid != null) {
                bssidText.text = password.bssid
                bssidText.visibility = View.VISIBLE
            } else {
                bssidText.visibility = View.GONE
            }

            iconSecurity.setImageResource(getSecurityIcon(password.securityType))

            cardView.setOnClickListener { onPasswordClick(password) }
            buttonCopyPassword.setOnClickListener { onCopyPassword(password) }
            buttonQrCode.setOnClickListener { onShowQrCode(password) }
        }

        private fun getSecurityIcon(securityType: String): Int {
            return when (securityType) {
                SavedWifiPassword.SECURITY_OPEN -> R.drawable.ic_lock_open
                SavedWifiPassword.SECURITY_WEP -> R.drawable.ic_lock_outline
                SavedWifiPassword.SECURITY_WPA,
                SavedWifiPassword.SECURITY_WPA2,
                SavedWifiPassword.SECURITY_WPA3 -> R.drawable.ic_lock_outline
                else -> R.drawable.ic_wifi
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<SavedWifiPassword>() {
        override fun areItemsTheSame(oldItem: SavedWifiPassword, newItem: SavedWifiPassword): Boolean {
            return oldItem.ssid == newItem.ssid && oldItem.bssid == newItem.bssid
        }

        override fun areContentsTheSame(oldItem: SavedWifiPassword, newItem: SavedWifiPassword): Boolean {
            return oldItem == newItem
        }
    }
}