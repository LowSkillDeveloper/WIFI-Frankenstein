package com.lsd.wififrankenstein.ui.bruteforce

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.BottomSheetSourcePickerBinding
import com.lsd.wififrankenstein.util.ArchiveExtractor
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.WpaSecDictManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class SourceOption(val iconRes: Int, val title: String)

class PskWordlistSourcePicker : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSourcePickerBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val TAG = "PskWordlistSourcePicker"
    }

    var onWordlistSelected: ((Uri, String) -> Unit)? = null

    private val wordlistPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            pickWordlist(it, it.lastPathSegment ?: "wordlist.txt")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSourcePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.pickerTitle.text = getString(R.string.brute_wordlist_source_title)
        binding.btnPickerClose.setOnClickListener { dismiss() }

        val options = listOf(
            SourceOption(R.drawable.ic_folder_open, getString(R.string.brute_source_from_file)),
            SourceOption(R.drawable.ic_file_download, getString(R.string.brute_source_from_url)),
            SourceOption(R.drawable.ic_content_copy, getString(R.string.brute_source_paste)),
            SourceOption(R.drawable.cloud_download_24px, getString(R.string.brute_source_wpa_sec)),
            SourceOption(R.drawable.ic_key, getString(R.string.brute_source_single))
        )

        val density = resources.displayMetrics.density
        val dp4 = (4 * density).toInt()
        val dp16 = (16 * density).toInt()
        val dp14 = (14 * density).toInt()
        val dp24 = (24 * density).toInt()

        for ((index, option) in options.withIndex()) {
            val card = MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { if (index > 0) it.topMargin = dp4 }
                radius =
                    resources.getDimension(com.google.android.material.R.dimen.mtrl_card_corner_radius)
                cardElevation = 0f
                setStrokeColor(
                    android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), R.color.divider_color)
                    )
                )
                strokeWidth = resources.getDimensionPixelSize(R.dimen.stroke_default)
                isClickable = true
                isFocusable = true
                val attr = android.util.TypedValue()
                requireContext().theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, attr, true
                )
                if (attr.resourceId != 0) {
                    foreground = ContextCompat.getDrawable(requireContext(), attr.resourceId)
                }
                setOnClickListener { handleOption(index) }
            }

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp16, dp14, dp16, dp14)
            }

            val icon = ImageView(requireContext()).apply {
                setImageResource(option.iconRes)
                layoutParams = LinearLayout.LayoutParams(dp24, dp24)
                val tintValue = android.util.TypedValue()
                requireContext().theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnSurfaceVariant, tintValue, true
                )
                imageTintList = android.content.res.ColorStateList.valueOf(tintValue.data)
            }

            val text = TextView(requireContext()).apply {
                text = option.title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).also { it.marginStart = dp16 }
            }

            row.addView(icon)
            row.addView(text)
            card.addView(row)
            binding.optionsContainer.addView(card)
        }
    }

    private fun handleOption(which: Int) {
        when (which) {
            0 -> wordlistPicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
            1 -> showWordlistUrlDialog()
            2 -> showWordlistPasteDialog()
            3 -> useWpaSecDict()
            4 -> showSinglePasswordDialog()
        }
    }

    private fun showWordlistUrlDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.wordlist_url_hint)
            setPadding(48, 32, 48, 32)
        }
        val megaCheck = CheckBox(requireContext()).apply {
            text = getString(R.string.brute_mega_link)
            setPadding(48, 8, 48, 16)
        }
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(megaCheck)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.brute_download_url_title)
            .setView(layout)
            .setPositiveButton(R.string.download) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                downloadFromUrl(url, megaCheck.isChecked)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun downloadFromUrl(url: String, isMega: Boolean) {
        Toast.makeText(requireContext(), getString(R.string.brute_downloading_wordlist), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = downloadWordlist(url, isMega)
            if (result != null) {
                pickWordlist(Uri.fromFile(result.first), getString(R.string.brute_url_label, result.second))
            } else {
                Toast.makeText(requireContext(), getString(R.string.brute_download_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun downloadWordlist(
        url: String,
        isMega: Boolean
    ): Pair<File, String>? = withContext(Dispatchers.IO) {
        try {
            val app = requireContext().applicationContext
            val tempDir = File(app.cacheDir, "wordlist_dl_${System.nanoTime()}")
            tempDir.mkdirs()
            val fileName =
                url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() }
                    ?: "download_${System.nanoTime()}"
            val tempFile = File(tempDir, fileName)

            if (isMega) {
                val megaClient = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val megaDownloader =
                    com.lsd.wififrankenstein.network.MegaPublicDownloader(megaClient)
                val resolvedName = megaDownloader.resolveFileName(url) ?: fileName
                val resolvedFile = File(tempDir, resolvedName)
                val result = megaDownloader.download(url, resolvedFile)
                result.getOrNull()?.let { return@withContext it to resolvedName }
                return@withContext null
            }

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url(url)
                .addHeader("User-Agent", "WIFI-Frankenstein/1.1").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            response.body?.bytes()?.let { tempFile.writeBytes(it) }

            val ext = tempFile.extension.lowercase()
            if (ext in listOf("zip", "7z", "gz", "tgz")) {
                val extractDir = File(tempDir, "extracted")
                extractDir.mkdirs()
                val extracted = ArchiveExtractor.extract(tempFile, extractDir)
                val txtFile = extracted.firstOrNull {
                    it.extension.lowercase() in listOf("txt", "cap", "pcap", "pcapng", "22000")
                }
                if (txtFile != null) return@withContext txtFile to txtFile.name
                if (extracted.isNotEmpty()) {
                    return@withContext extracted.first() to extracted.first().name
                }
            }

            tempFile to fileName
        } catch (e: Exception) {
            Log.e(TAG, "downloadWordlist failed", e)
            null
        }
    }

    private fun showWordlistPasteDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.brute_paste_hint)
            setPadding(48, 32, 48, 32)
            minLines = 8
            gravity = Gravity.TOP
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.brute_paste_title)
            .setMessage(R.string.brute_one_per_line)
            .setView(input)
            .setPositiveButton(R.string.brute_load) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) return@setPositiveButton
                val passwords = text.lines().map { it.trim() }.filter { it.isNotBlank() }
                if (passwords.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.brute_no_passwords), Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }
                writePastedWordlist(passwords)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun writePastedWordlist(passwords: List<String>) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val f = File(
                    requireContext().cacheDir,
                    "pasted_wordlist_${System.nanoTime()}.txt"
                )
                f.writeText(passwords.joinToString("\n"))
                f
            }
            pickWordlist(Uri.fromFile(file), getString(R.string.brute_pasted, passwords.size))
        }
    }

    private fun showSinglePasswordDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.brute_single_hint)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.brute_source_single)
            .setMessage(R.string.brute_single_message)
            .setView(input)
            .setPositiveButton(R.string.brute_test) { _, _ ->
                val password = input.text.toString().trim()
                if (password.isBlank()) return@setPositiveButton
                writeSinglePassword(password)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun writeSinglePassword(password: String) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val f = File(
                    requireContext().cacheDir,
                    "single_password_${System.nanoTime()}.txt"
                )
                f.writeText(password)
                f
            }
            pickWordlist(Uri.fromFile(file), getString(R.string.handshake_crack_mode_single))
        }
    }

    private fun useWpaSecDict() {
        Toast.makeText(requireContext(), getString(R.string.brute_checking_wpa_sec), Toast.LENGTH_SHORT)
            .show()
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                val manager = WpaSecDictManager(requireContext().applicationContext)
                manager.downloadIfNeeded() ?: manager.getDictPath()
            }
            if (path != null) {
                val file = File(path)
                val mb = if (file.length() > 0) getString(R.string.brute_mb_suffix, file.length() / (1024 * 1024)) else ""
                pickWordlist(Uri.fromFile(file), getString(R.string.brute_wpa_sec_dict_label, mb))
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.brute_wpa_sec_unavailable),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun pickWordlist(uri: Uri, label: String) {
        dismiss()
        onWordlistSelected?.invoke(uri, label)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
