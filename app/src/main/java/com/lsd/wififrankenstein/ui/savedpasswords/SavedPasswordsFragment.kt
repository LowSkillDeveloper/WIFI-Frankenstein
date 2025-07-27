package com.lsd.wififrankenstein.ui.savedpasswords

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.data.SavedWifiPassword
import com.lsd.wififrankenstein.databinding.DialogPasswordDetailsBinding
import com.lsd.wififrankenstein.databinding.FragmentSavedPasswordsBinding
import com.topjohnwu.superuser.Shell

class SavedPasswordsFragment : Fragment() {

    private var _binding: FragmentSavedPasswordsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SavedPasswordsViewModel by viewModels()
    private lateinit var adapter: SavedPasswordsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSavedPasswordsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Shell.getShell { shell ->
        }

        setupRecyclerView()
        setupButtons()
        observeViewModel()

        viewModel.loadPasswords()
    }

    private fun setupRecyclerView() {
        adapter = SavedPasswordsAdapter(
            onPasswordClick = { password -> showPasswordDetails(password) },
            onCopyPassword = { password ->
                viewModel.copyToClipboard(password.password, "password")
            },
            onShowQrCode = { password -> showQrCode(password) }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadPasswords()
        }
    }

    private fun setupButtons() {
        binding.buttonRefresh.setOnClickListener {
            viewModel.loadPasswords()
        }

        binding.buttonExport.setOnClickListener {
            showExportDialog()
        }
    }

    private fun observeViewModel() {
        viewModel.passwords.observe(viewLifecycleOwner) { passwords ->
            adapter.submitList(passwords)
            updateEmptyState(passwords.isEmpty())
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.swipeRefresh.isRefreshing = isLoading
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                binding.textEmpty.text = error
                updateEmptyState(true)
            }
        }

        viewModel.toastMessage.observe(viewLifecycleOwner) { message ->
            if (message.isNotEmpty()) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                viewModel.clearToastMessage()
            }
        }
    }

    private fun updateRootStatus(status: SavedPasswordsViewModel.RootStatus) {
        when (status) {
            SavedPasswordsViewModel.RootStatus.CHECKING -> {
                binding.textEmpty.text = getString(R.string.checking_root_access)
                binding.buttonRetryRoot?.visibility = View.GONE
            }
            SavedPasswordsViewModel.RootStatus.NOT_AVAILABLE -> {
                if (viewModel.passwords.value?.isEmpty() == true) {
                    binding.textEmpty.text = getString(R.string.root_required_passwords)
                    binding.buttonRetryRoot?.visibility = View.VISIBLE
                    showRootRequiredDialog()
                }
            }
            SavedPasswordsViewModel.RootStatus.ERROR -> {
                if (viewModel.passwords.value?.isEmpty() == true) {
                    binding.textEmpty.text = getString(R.string.error_checking_root_access)
                    binding.buttonRetryRoot?.visibility = View.VISIBLE
                }
            }
            SavedPasswordsViewModel.RootStatus.AVAILABLE -> {
                binding.buttonRetryRoot?.visibility = View.GONE
            }
        }
    }

    private fun showRootRequiredDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.root_access_required))
            .setMessage(getString(R.string.root_access_required_message))
            .setPositiveButton(getString(R.string.retry)) { _, _ ->
                viewModel.retryWithRoot()
            }
            .setNegativeButton(getString(R.string.ok), null)
            .show()
    }

    private fun updateButtonStates(hasPasswords: Boolean) {
        binding.buttonExport.isEnabled = hasPasswords
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateEmptyStateWithError(error: String) {
        binding.textEmpty.text = error
        updateEmptyState(true)
    }

    private fun showPasswordDetails(password: SavedWifiPassword) {
        val dialogBinding = DialogPasswordDetailsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(requireContext())

        dialogBinding.apply {
            textSsid.text = password.displayName
            textPassword.text = if (password.isOpenNetwork) {
                getString(R.string.security_type_open)
            } else {
                password.password.ifEmpty { getString(R.string.not_available) }
            }
            textSecurity.text = password.securityType
            textBssid.text = password.bssid ?: getString(R.string.not_available)

            buttonCopyPassword.visibility = if (password.isOpenNetwork || password.password.isEmpty()) {
                View.GONE
            } else {
                View.VISIBLE
            }

            buttonCopyPassword.setOnClickListener {
                viewModel.copyToClipboard(password.password, "password")
                dialog.dismiss()
            }

            buttonQrCode.setOnClickListener {
                showQrCode(password)
                dialog.dismiss()
            }

            buttonClose.setOnClickListener {
                dialog.dismiss()
            }
        }

        dialog.setContentView(dialogBinding.root)
        dialog.show()
    }

    private fun showQrCode(password: SavedWifiPassword) {
        try {
            val qrContent = if (password.isOpenNetwork) {
                "WIFI:S:${password.ssid};T:nopass;;"
            } else {
                val securityType = when (password.securityType) {
                    SavedWifiPassword.SECURITY_WEP -> "WEP"
                    SavedWifiPassword.SECURITY_WPA3 -> "WPA3"
                    else -> "WPA"
                }
                "WIFI:S:${password.ssid};T:$securityType;P:${password.password};;"
            }

            val qrBitmap = generateQrCode(qrContent)
            if (qrBitmap != null) {
                showQrDialog(password.ssid, qrBitmap)
            } else {
                Toast.makeText(requireContext(), getString(R.string.qr_code_generation_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.qr_code_generation_failed), Toast.LENGTH_SHORT).show()
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
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun showQrDialog(ssid: String, qrBitmap: Bitmap) {
        val builder = MaterialAlertDialogBuilder(requireContext())
        val imageView = android.widget.ImageView(requireContext())
        imageView.setImageBitmap(qrBitmap)
        imageView.setPadding(32, 32, 32, 32)

        builder.setTitle(getString(R.string.qr_code_generated_for, ssid))
            .setView(imageView)
            .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.save_to_gallery)) { _, _ ->
                saveQrToGallery(qrBitmap, ssid)
            }
            .show()
    }

    private fun saveQrToGallery(bitmap: Bitmap, ssid: String) {
        try {
            val filename = "wifi_qr_${ssid.replace("[^a-zA-Z0-9]".toRegex(), "_")}_${System.currentTimeMillis()}.png"

            val saved = android.provider.MediaStore.Images.Media.insertImage(
                requireContext().contentResolver,
                bitmap,
                filename,
                getString(R.string.qr_code_for_wifi, ssid)
            )

            if (saved != null) {
                Toast.makeText(requireContext(), getString(R.string.qr_saved_successfully), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), getString(R.string.qr_save_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.qr_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExportDialog() {
        val options = arrayOf(
            getString(R.string.export_as_json),
            getString(R.string.export_as_csv),
            getString(R.string.export_as_txt)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.select_export_method))
            .setItems(options) { _, which ->
                val format = when (which) {
                    0 -> SavedPasswordsViewModel.ExportFormat.JSON
                    1 -> SavedPasswordsViewModel.ExportFormat.CSV
                    else -> SavedPasswordsViewModel.ExportFormat.TXT
                }
                viewModel.exportPasswords(format)
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}