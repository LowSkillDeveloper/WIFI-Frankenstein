package com.lsd.wififrankenstein.ui.wifiscanner

import android.net.wifi.ScanResult
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.BottomSheetWifiAttacksBinding
import com.lsd.wififrankenstein.service.ForegroundAttackService
import com.lsd.wififrankenstein.util.BottomSheetMenu
import com.lsd.wififrankenstein.util.BottomSheetMenuItem
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.ChrootType
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.PskBruteForceEngines
import com.lsd.wififrankenstein.util.RootlessManager

class WifiAttackBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetWifiAttacksBinding? = null
    private val binding get() = _binding!!

    private var scanResult: ScanResult? = null
    private var iwSsid: String = ""
    private var iwBssid: String = ""
    private var iwChannel: String = ""
    private var isIwNetwork: Boolean = false
    private var currentInterface: String = "wlan0"

    companion object {
        private const val TAG = "WifiAttackBottomSheet"
        private const val EXTRA_SSID = "extra_ssid"
        private const val EXTRA_BSSID = "extra_bssid"
        private const val EXTRA_CHANNEL = "extra_channel"
        private const val EXTRA_CAPABILITIES = "extra_capabilities"
        private const val EXTRA_IS_IW = "extra_is_iw"
        private const val EXTRA_INTERFACE = "extra_interface"

        fun newInstance(
            ssid: String,
            bssid: String,
            capabilities: String = "",
            isIwNetwork: Boolean = false,
            interfaceName: String = "wlan0",
            channel: String = ""
        ): WifiAttackBottomSheet {
            val fragment = WifiAttackBottomSheet()
            fragment.arguments = Bundle().apply {
                putString(EXTRA_SSID, ssid)
                putString(EXTRA_BSSID, bssid)
                putString(EXTRA_CHANNEL, channel)
                putString(EXTRA_CAPABILITIES, capabilities)
                putBoolean(EXTRA_IS_IW, isIwNetwork)
                putString(EXTRA_INTERFACE, interfaceName)
            }
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetWifiAttacksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        parseArguments()
        setupViews()
    }

    private fun parseArguments() {
        arguments?.let { args ->
            iwSsid = args.getString(EXTRA_SSID, "")
            iwBssid = args.getString(EXTRA_BSSID, "")
            iwChannel = args.getString(EXTRA_CHANNEL, "")
            isIwNetwork = args.getBoolean(EXTRA_IS_IW, false)
            currentInterface = args.getString(EXTRA_INTERFACE, "wlan0")

            if (!isIwNetwork) {
                val caps = args.getString(EXTRA_CAPABILITIES, "")
                scanResult = try {
                    val unsafeClass = Class.forName("sun.misc.Unsafe")
                    val field = unsafeClass.getDeclaredField("theUnsafe")
                    field.isAccessible = true
                    val unsafe = field.get(null)
                    val allocateInstance =
                        unsafeClass.getMethod("allocateInstance", Class::class.java)
                    val result =
                        allocateInstance.invoke(unsafe, ScanResult::class.java) as ScanResult
                    result.SSID = iwSsid
                    result.BSSID = iwBssid
                    result.capabilities = caps
                    result
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create ScanResult", e)
                    null
                }
            }
        }
    }

    private fun setupViews() {
        val displayText = if (iwSsid.isNotEmpty()) "$iwSsid ($iwBssid)" else iwBssid
        binding.textNetworkInfo.text = displayText

        val prefs = requireContext().getSharedPreferences(
            "com.lsd.wififrankenstein",
            android.content.Context.MODE_PRIVATE
        )
        val isRootEnabled = prefs.getBoolean("enable_root", false)

        binding.buttonPixieDust.setOnClickListener {
            navigateToPixieDust()
            dismiss()
        }

        binding.buttonWpsBrute.setOnClickListener {
            if (!isRootEnabled) {
                showRootRequiredDialog()
                return@setOnClickListener
            }
            ForegroundAttackService.startWpsBruteForce(requireContext(), iwBssid, currentInterface)
            dismiss()
        }

        binding.buttonCustomPin.setOnClickListener {
            if (!isRootEnabled) {
                showRootRequiredDialog()
                return@setOnClickListener
            }
            showCustomPinDialog()
        }

        binding.buttonPskBrute.setOnClickListener {
            if (iwSsid.isEmpty() || iwBssid.isEmpty()) {
                Toast.makeText(requireContext(), "SSID/BSSID not available", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            showPskBackendSelection()
        }

        binding.buttonHandshakeCapture.setOnClickListener {
            navigateToHandshakeCapture()
            dismiss()
        }
    }

    private fun showRootRequiredDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wps_root_no_root)
            .setMessage(R.string.wps_root_no_root)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showCustomPinDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_wps_pin, null)
        val pinInput = dialogView.findViewById<TextInputEditText>(R.id.editTextWpsPin)
        pinInput?.hint = getString(R.string.custom_pin_attack_hint)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.custom_pin_attack)
            .setView(dialogView)
            .setPositiveButton(R.string.attack_custom_pin) { _, _ ->
                val pin = pinInput?.text?.toString()?.trim() ?: ""
                if (pin.length == 8 && pin.all { it.isDigit() }) {
                    ForegroundAttackService.startCustomPin(
                        requireContext(),
                        iwBssid,
                        pin,
                        currentInterface
                    )
                    dismiss()
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.wps_pin_invalid)
                        .setMessage(R.string.wps_pin_input_hint)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPskBackendSelection() {
        val nativeSupported = PskBruteForceEngines.isNativeSupported(requireContext())
        val chrootReady = isPskChrootReady()

        fun navigate(engine: String) {
            val bundle = Bundle().apply {
                putString("ssid", iwSsid)
                putString("bssid", iwBssid)
                putString("interface", currentInterface)
                putString("engine", engine)
            }
            try {
                findNavController().navigate(R.id.nav_bruteforce, bundle)
            } catch (e: Exception) {
                Log.e(TAG, "Navigation to PSK brute force failed", e)
            }
            dismiss()
        }

        val items = mutableListOf<BottomSheetMenuItem>()
        if (nativeSupported) {
            items.add(
                BottomSheetMenuItem(
                    R.id.action_psk_backend_native,
                    getString(R.string.psk_engine_native),
                    R.drawable.ic_wifi
                )
            )
        }
        if (chrootReady) {
            items.add(
                BottomSheetMenuItem(
                    R.id.action_psk_backend_chroot,
                    getString(R.string.psk_engine_chroot),
                    R.drawable.ic_wps
                )
            )
        }

        when {
            nativeSupported && !chrootReady -> navigate("NATIVE")
            !nativeSupported && chrootReady -> navigate("CHROOT")
            !nativeSupported && !chrootReady -> {
                Toast.makeText(
                    requireContext(),
                    R.string.psk_engine_chroot_unsupported,
                    Toast.LENGTH_LONG
                ).show()
            }

            else -> {
                BottomSheetMenu.show(
                    requireContext(),
                    title = getString(R.string.psk_engine_label),
                    items = items
                ) { item ->
                    when (item.id) {
                        R.id.action_psk_backend_native -> navigate("NATIVE")
                        R.id.action_psk_backend_chroot -> navigate("CHROOT")
                    }
                }
            }
        }
    }

    private fun isPskChrootReady(): Boolean {
        return try {
            val type = ChrootManager.get(requireContext()).getChrootType()
            type is ChrootType.Root ||
                    (type is ChrootType.Rootless &&
                            RootlessManager(requireContext()).isSetupCompleted())
        } catch (e: Exception) {
            Log.e(TAG, "PSK chroot readiness check failed", e)
            false
        }
    }

    private fun navigateToPixieDust() {
        try {
            val bundle = Bundle().apply {
                putString("bssid", iwBssid)
                putString("ssid", iwSsid)
                putString("interface", currentInterface)
            }
            findNavController().navigate(R.id.nav_pixie_dust, bundle)
        } catch (e: Exception) {
            Log.e(TAG, "Navigation to PixieDust failed", e)
        }
    }

    private fun navigateToHandshakeCapture() {
        try {
            val bundle = Bundle().apply {
                putString("bssid", iwBssid)
                putString("ssid", iwSsid)
                putString("channel", iwChannel)
                putString("interface", currentInterface)
            }
            findNavController().navigate(R.id.nav_handshake_capture_selector, bundle)
        } catch (e: Exception) {
            Log.e(TAG, "Navigation to handshake capture failed", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
