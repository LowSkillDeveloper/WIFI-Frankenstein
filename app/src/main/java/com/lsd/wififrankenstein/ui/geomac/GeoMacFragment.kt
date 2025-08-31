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
import com.lsd.wififrankenstein.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class GeoMacFragment : Fragment() {

    private var _binding: FragmentGeoMacBinding? = null
    private val binding get() = _binding!!
    private lateinit var chrootManager: ChrootManager

    companion object {
        private const val TAG = "GeoMacFragment"
    }

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

        Log.d(TAG, "Fragment created")
        chrootManager = ChrootManager(requireContext())
        setupViews()
        checkRootAndChroot()
    }

    private fun setupViews() {
        Log.d(TAG, "Setting up views")

        binding.buttonSearch.setOnClickListener {
            val macAddress = binding.editTextMacAddress.text.toString().trim()
            Log.d(TAG, "Search button clicked with MAC: $macAddress")

            if (isValidMacAddress(macAddress)) {
                Log.d(TAG, "MAC address validation passed")
                searchMacLocation(macAddress)
            } else {
                Log.w(TAG, "Invalid MAC address format: $macAddress")
                Toast.makeText(requireContext(), R.string.invalid_mac_address, Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonClear.setOnClickListener {
            Log.d(TAG, "Clear button clicked")
            binding.editTextMacAddress.text?.clear()
            binding.textViewResult.text = ""
            binding.textViewResult.visibility = View.GONE
        }
    }

    private fun checkRootAndChroot() {
        Log.d(TAG, "Checking root and chroot requirements")

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Testing actual root access with command execution")
                val testResult = Shell.cmd("id").exec()
                Log.d(TAG, "Root test command result: ${testResult.isSuccess}")
                Log.d(TAG, "Root test output: ${testResult.out.joinToString(" ")}")

                val hasRoot = testResult.isSuccess && testResult.out.any { it.contains("uid=0") }
                Log.d(TAG, "Actual root access: $hasRoot")

                if (!hasRoot) {
                    Log.e(TAG, "Root access not available - test command failed or not running as root")
                    activity?.runOnUiThread {
                        showError(getString(R.string.root_required_geomac))
                    }
                    return@launch
                }

                val chrootInstalled = chrootManager.isChrootInstalled()
                Log.d(TAG, "Chroot installed: $chrootInstalled")

                if (!chrootInstalled) {
                    Log.e(TAG, "Chroot environment not installed")
                    activity?.runOnUiThread {
                        showError(getString(R.string.chroot_required_geomac))
                    }
                    return@launch
                }

                Log.i(TAG, "All requirements satisfied - ready for MAC geolocation")

            } catch (e: Exception) {
                Log.e(TAG, "Error checking requirements", e)
                activity?.runOnUiThread {
                    showError("Error checking requirements: ${e.message}")
                }
            }
        }
    }

    private fun searchMacLocation(macAddress: String) {
        Log.i(TAG, "Starting MAC location search for: $macAddress")

        binding.progressBar.visibility = View.VISIBLE
        binding.buttonSearch.isEnabled = false
        binding.textViewResult.visibility = View.GONE

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Attempting to mount chroot environment")
                val mountResult = chrootManager.mountChroot()
                Log.d(TAG, "Mount chroot result: $mountResult")

                if (!mountResult) {
                    Log.e(TAG, "Failed to mount chroot environment")
                    showError(getString(R.string.failed_mount_chroot))
                    return@launch
                }

                Log.d(TAG, "Chroot mounted successfully, executing geomac binary")
                val command = "./home/GeoMac/geomac $macAddress"
                Log.d(TAG, "Executing command: $command")

                val result = chrootManager.executeInChroot(command)

                Log.d(TAG, "Command execution completed")
                Log.d(TAG, "Command success: ${result.isSuccess}")
                Log.d(TAG, "Exit code: ${result.code}")

                if (result.out.isNotEmpty()) {
                    Log.d(TAG, "Command stdout (${result.out.size} lines):")
                    result.out.forEachIndexed { index, line ->
                        Log.d(TAG, "stdout[$index]: $line")
                    }
                } else {
                    Log.d(TAG, "Command stdout: <empty>")
                }

                if (result.err.isNotEmpty()) {
                    Log.w(TAG, "Command stderr (${result.err.size} lines):")
                    result.err.forEachIndexed { index, line ->
                        Log.w(TAG, "stderr[$index]: $line")
                    }
                } else {
                    Log.d(TAG, "Command stderr: <empty>")
                }

                Log.d(TAG, "Unmounting chroot environment")
                val unmountResult = chrootManager.unmountChroot()
                Log.d(TAG, "Unmount chroot result: $unmountResult")

                if (!unmountResult) {
                    Log.w(TAG, "Warning: Failed to cleanly unmount chroot environment")
                }

                activity?.runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.buttonSearch.isEnabled = true

                    if (result.isSuccess) {
                        Log.d(TAG, "Processing successful command output")
                        val outputText = result.out.joinToString("\n")
                        Log.d(TAG, "Full output text: '$outputText'")

                        val coordinates = extractCoordinates(outputText)
                        Log.d(TAG, "Extracted coordinates: $coordinates")

                        if (coordinates != null) {
                            Log.i(TAG, "Location found successfully: $coordinates")
                            showResult(coordinates)
                        } else {
                            Log.w(TAG, "No coordinates found in output")

                            if (outputText.isNotEmpty()) {
                                Log.d(TAG, "Showing raw output as fallback")
                                showResult("Raw output: $outputText")
                            } else {
                                Log.w(TAG, "No output received from geomac binary")
                                showError(getString(R.string.no_location_found))
                            }
                        }
                    } else {
                        Log.e(TAG, "Command execution failed with exit code: ${result.code}")
                        val errorText = if (result.err.isNotEmpty()) {
                            result.err.joinToString("\n")
                        } else {
                            "Unknown error"
                        }
                        Log.e(TAG, "Error details: $errorText")
                        showError(getString(R.string.search_failed) + "\nError: $errorText")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during MAC location search", e)
                activity?.runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.buttonSearch.isEnabled = true
                    showError(getString(R.string.search_error, e.message ?: "Unknown exception"))
                }
            }
        }
    }

    private fun extractCoordinates(output: String): String? {
        Log.d(TAG, "Extracting coordinates from output: '$output'")

        val patterns = listOf(
            Pattern.compile("[0-9]*\\.[0-9]+,\\s*[0-9]*\\.[0-9]+"),
            Pattern.compile("-[0-9]*\\.[0-9]+,\\s*[0-9]*\\.[0-9]+"),
            Pattern.compile("-[0-9]*\\.[0-9]+,\\s*-[0-9]*\\.[0-9]+"),
            Pattern.compile("[0-9]*\\.[0-9]+,\\s*-[0-9]*\\.[0-9]+"),
            Pattern.compile("([0-9]*\\.?[0-9]+),\\s*([0-9]*\\.?[0-9]+)"),
            Pattern.compile("lat[^0-9-]*(-?[0-9]*\\.?[0-9]+)[^0-9-]*lon[^0-9-]*(-?[0-9]*\\.?[0-9]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("latitude[^0-9-]*(-?[0-9]*\\.?[0-9]+)[^0-9-]*longitude[^0-9-]*(-?[0-9]*\\.?[0-9]+)", Pattern.CASE_INSENSITIVE)
        )

        patterns.forEachIndexed { index, pattern ->
            Log.d(TAG, "Trying pattern $index: ${pattern.pattern()}")
            val matcher = pattern.matcher(output)
            if (matcher.find()) {
                val result = matcher.group()
                Log.d(TAG, "Pattern $index matched: '$result'")
                return result
            }
        }

        Log.d(TAG, "No coordinate patterns matched")

        val lines = output.split("\n")
        Log.d(TAG, "Checking individual lines (${lines.size} lines)")
        lines.forEachIndexed { index, line ->
            Log.d(TAG, "Line $index: '$line'")
            if (line.contains("location", ignoreCase = true) ||
                line.contains("coordinates", ignoreCase = true) ||
                line.contains("lat", ignoreCase = true) ||
                line.contains("lon", ignoreCase = true)) {
                Log.d(TAG, "Found potential location line: '$line'")
            }
        }

        return null
    }

    private fun showResult(coordinates: String) {
        Log.i(TAG, "Showing successful result: $coordinates")
        binding.textViewResult.text = getString(R.string.location_found, coordinates)
        binding.textViewResult.visibility = View.VISIBLE
        binding.textViewResult.setTextColor(resources.getColor(R.color.success_green, null))
    }

    private fun showError(message: String) {
        Log.e(TAG, "Showing error: $message")
        binding.textViewResult.text = message
        binding.textViewResult.visibility = View.VISIBLE
        binding.textViewResult.setTextColor(resources.getColor(R.color.error_red, null))
    }

    private fun isValidMacAddress(mac: String): Boolean {
        val pattern = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
        val isValid = pattern.matcher(mac).matches()
        Log.d(TAG, "MAC address validation for '$mac': $isValid")
        return isValid
    }

    override fun onDestroyView() {
        Log.d(TAG, "Fragment destroyed")
        super.onDestroyView()
        _binding = null
    }
}