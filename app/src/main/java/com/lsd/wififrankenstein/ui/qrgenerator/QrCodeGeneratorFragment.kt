package com.lsd.wififrankenstein.ui.qrgenerator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentQrGeneratorBinding
import com.lsd.wififrankenstein.utils.SecurityType

class QrCodeGeneratorFragment : Fragment() {

    private var _binding: FragmentQrGeneratorBinding? = null
    private val binding get() = _binding!!

    private val viewModel: QrCodeGeneratorViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.saveQrCodeToGallery()
        } else {
            Toast.makeText(requireContext(), R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQrGeneratorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews()
        setupObservers()
        handleIncomingData()
    }

    private fun setupViews() {
        binding.btnGenerate.setOnClickListener {
            generateQrCode()
        }

        binding.btnSave.setOnClickListener {
            checkPermissionAndSave()
        }

        binding.btnShare.setOnClickListener {
            viewModel.shareQrCode()
        }

        binding.chipGroupSecurity.setOnCheckedStateChangeListener { _, checkedIds ->
            updatePasswordFieldVisibility(checkedIds.firstOrNull())
        }
    }

    private fun setupObservers() {
        viewModel.qrCodeBitmap.observe(viewLifecycleOwner) { bitmap ->
            if (bitmap != null) {
                binding.ivQrCode.setImageBitmap(bitmap)
                binding.cardQrCode.visibility = View.VISIBLE
                updateQrInfo()
                Toast.makeText(requireContext(), R.string.qr_code_generated, Toast.LENGTH_SHORT).show()
            } else {
                binding.cardQrCode.visibility = View.GONE
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.btnGenerate.isEnabled = !isLoading
            if (isLoading) {
                binding.btnGenerate.text = getString(R.string.generating_keys)
            } else {
                binding.btnGenerate.text = getString(R.string.generate_qr)
            }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        viewModel.saveResult.observe(viewLifecycleOwner) { success ->
            val message = if (success) {
                getString(R.string.qr_saved_successfully)
            } else {
                getString(R.string.qr_save_failed)
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleIncomingData() {
        arguments?.let { args ->
            val ssid = args.getString("ssid")
            val password = args.getString("password", "")
            val securityType = args.getString("security", "WPA")

            ssid?.let {
                binding.etNetworkName.setText(it)
                binding.etPassword.setText(password)

                when (securityType.uppercase()) {
                    "NONE", "OPEN" -> binding.chipNone.isChecked = true
                    "WEP" -> binding.chipWep.isChecked = true
                    "WPA3" -> binding.chipWpa3.isChecked = true
                    else -> binding.chipWpa.isChecked = true
                }
            }
        }
    }

    private fun generateQrCode() {
        val ssid = binding.etNetworkName.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString() ?: ""
        val hidden = binding.switchHidden.isChecked

        if (ssid.isEmpty()) {
            binding.etNetworkName.error = getString(R.string.invalid_network_data)
            return
        }

        val security = when (binding.chipGroupSecurity.checkedChipId) {
            R.id.chipNone -> SecurityType.NONE
            R.id.chipWep -> SecurityType.WEP
            R.id.chipWpa3 -> SecurityType.WPA3
            else -> SecurityType.WPA
        }

        if (security != SecurityType.NONE && password.isEmpty()) {
            binding.etPassword.error = getString(R.string.password_required)
            return
        }

        viewModel.generateQrCode(ssid, password, security, hidden)
    }

    private fun updatePasswordFieldVisibility(checkedChipId: Int?) {
        val isPasswordRequired = checkedChipId != R.id.chipNone
        binding.etPassword.isEnabled = isPasswordRequired

        if (!isPasswordRequired) {
            binding.etPassword.setText("")
        }
    }

    private fun updateQrInfo() {
        if (_binding == null) return
        val network = viewModel.getCurrentNetwork()
        network?.let {
            val securityText = when (it.security) {
                SecurityType.NONE -> getString(R.string.security_none)
                SecurityType.WEP -> getString(R.string.security_wep)
                SecurityType.WPA -> getString(R.string.security_wpa)
                SecurityType.WPA3 -> getString(R.string.security_wpa3)
            }

            val infoText = buildString {
                append("${getString(R.string.network_name)}: ${it.ssid}\n")
                append("${getString(R.string.security_type)}: $securityText")
                if (it.hidden) {
                    append("\n${getString(R.string.hidden_network)}: ${getString(R.string.yes)}")
                }
            }

            binding.tvQrInfo.text = infoText
        }
    }

    private fun checkPermissionAndSave() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                viewModel.saveQrCodeToGallery()
            }
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED -> {
                viewModel.saveQrCodeToGallery()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(ssid: String, password: String = "", security: String = "WPA"): QrCodeGeneratorFragment {
            return QrCodeGeneratorFragment().apply {
                arguments = Bundle().apply {
                    putString("ssid", ssid)
                    putString("password", password)
                    putString("security", security)
                }
            }
        }
    }
}