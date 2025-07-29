package com.lsd.wififrankenstein.ui.wifimap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R

class AdminCredentialsAdapter(
    private val credentials: List<AdminCredential>,
    private val context: Context
) : RecyclerView.Adapter<AdminCredentialsAdapter.CredentialViewHolder>() {

    inner class CredentialViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textViewLogin: TextView = view.findViewById(R.id.textViewLogin)
        val textViewPassword: TextView = view.findViewById(R.id.textViewPassword)
        val buttonCopyLogin: ImageView = view.findViewById(R.id.buttonCopyLogin)
        val buttonCopyPassword: ImageView = view.findViewById(R.id.buttonCopyPassword)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CredentialViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_admin_credential, parent, false)
        return CredentialViewHolder(view)
    }

    override fun onBindViewHolder(holder: CredentialViewHolder, position: Int) {
        val credential = credentials[position]

        holder.textViewLogin.text = credential.login
        holder.textViewPassword.text = credential.password

        if (credential.login == "<empty>") {
            holder.buttonCopyLogin.setColorFilter(Color.RED)
        } else {
            holder.buttonCopyLogin.setColorFilter(ContextCompat.getColor(context, android.R.color.darker_gray))
        }

        holder.buttonCopyLogin.setOnClickListener {
            if (credential.login == "<empty>") {
                Toast.makeText(context, context.getString(R.string.empty_field_message), Toast.LENGTH_LONG).show()
            }
            copyToClipboard(context.getString(R.string.admin_login), credential.login)
        }

        // Setup password copy button
        if (credential.password == "<empty>") {
            holder.buttonCopyPassword.setColorFilter(Color.RED)
        } else {
            holder.buttonCopyPassword.setColorFilter(ContextCompat.getColor(context, android.R.color.darker_gray))
        }

        holder.buttonCopyPassword.setOnClickListener {
            if (credential.password == "<empty>") {
                Toast.makeText(context, context.getString(R.string.empty_field_message), Toast.LENGTH_LONG).show()
            }
            copyToClipboard(context.getString(R.string.admin_password), credential.password)
        }
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

    override fun getItemCount() = credentials.size
}