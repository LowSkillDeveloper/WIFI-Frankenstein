package com.lsd.wififrankenstein.ui.geomac

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentGeoMacBinding
import com.lsd.wififrankenstein.util.ChrootManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class GeoMacFragment : Fragment() {

    private var _binding: FragmentGeoMacBinding? = null
    private val binding get() = _binding!!
    private lateinit var chrootManager: ChrootManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGeoMacBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chrootManager = ChrootManager(requireContext())
        setupViews()
        checkRootAndChroot()
    }

    private fun setupViews() {
        binding.buttonSearch.setOnClickListener {
            val macAddress = binding.editTextMacAddress.text.toString().trim()
            if (isValidMacAddress(macAddress)) {
                searchMacLocation(macAddress)
            } else {
                Toast.makeText(requireContext(), R.string.invalid_mac_address, Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonClear.setOnClickListener {
            binding.editTextMacAddress.text?.clear()
            binding.textViewResult.text = ""
            binding.textViewResult.visibility = View.GONE
        }
    }

    private fun checkRootAndChroot() {
        if (Shell.isAppGrantedRoot() != true) {
            showError(getString(R.string.root_required_geomac))
            return
        }

        if (!chrootManager.isChrootInstalled()) {
            showError(getString(R.string.chroot_required_geomac))
            return
        }
    }

    private fun searchMacLocation(macAddress: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.buttonSearch.isEnabled = false
        binding.textViewResult.visibility = View.GONE

        lifecycleScope.launch {
            try {
                if (!chrootManager.mountChroot()) {
                    showError(getString(R.string.failed_mount_chroot))
                    return@launch
                }

                val result = chrootManager.executeInChroot("./home/GeoMac/geomac $macAddress")

                chrootManager.unmountChroot()

                activity?.runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.buttonSearch.isEnabled = true

                    if (result.isSuccess) {
                        val coordinates = extractCoordinates(result.out.joinToString("\n"))
                        if (coordinates != null) {
                            showResult(coordinates)
                        } else {
                            showError(getString(R.string.no_location_found))
                        }
                    } else {
                        showError(getString(R.string.search_failed))
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.buttonSearch.isEnabled = true
                    showError(getString(R.string.search_error, e.message))
                }
            }
        }
    }

    private fun extractCoordinates(output: String): String? {
        val patterns = listOf(
            Pattern.compile("[0-9]*\\.[0-9]+,\\s[0-9]*\\.[0-9]+"),
            Pattern.compile("-[0-9]*\\.[0-9]+,\\s[0-9]*\\.[0-9]+"),
            Pattern.compile("-[0-9]*\\.[0-9]+,\\s-[0-9]*\\.[0-9]+"),
            Pattern.compile("[0-9]*\\.[0-9]+,\\s-[0-9]*\\.[0-9]+")
        )

        patterns.forEach { pattern ->
            val matcher = pattern.matcher(output)
            if (matcher.find()) {
                return matcher.group()
            }
        }

        return null
    }

    private fun showResult(coordinates: String) {
        binding.textViewResult.text = getString(R.string.location_found, coordinates)
        binding.textViewResult.visibility = View.VISIBLE
        binding.textViewResult.setTextColor(resources.getColor(R.color.success_green, null))
    }

    private fun showError(message: String) {
        binding.textViewResult.text = message
        binding.textViewResult.visibility = View.VISIBLE
        binding.textViewResult.setTextColor(resources.getColor(R.color.error_red, null))
    }

    private fun isValidMacAddress(mac: String): Boolean {
        val pattern = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
        return pattern.matcher(mac).matches()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}